from __future__ import annotations

import argparse
import json
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[3]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from cloud.app.models.api import RoutePrior
from cloud.app.services.relocalization import BaselineRelocalizer, LocalizationQuery
from cloud.app.services.venue_package import KeyframeRecord, VenueBundle


def load_fixture(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def build_bundle(temp_root: Path, fixture: dict[str, Any]) -> VenueBundle:
    image_root = temp_root / "localization" / "images"
    image_root.mkdir(parents=True, exist_ok=True)
    keyframes: list[KeyframeRecord] = []
    for item in fixture["keyframes"]:
        image_name = f"{item['keyframe_id']}.jpg"
        (image_root / image_name).write_bytes(item["payload_token"].encode("utf-8"))
        keyframes.append(
            KeyframeRecord(
                keyframe_id=item["keyframe_id"],
                floor_id=item["floor_id"],
                venue_xy=item["venue_xy"],
                intrinsics_id=None,
                heading=item.get("heading"),
                route_edge_id=item.get("route_edge_id"),
                image_ref=f"images/{image_name}",
                feature_ref=None,
            )
        )
    return VenueBundle(
        root=temp_root,
        manifest={"package_version": "0.1.0", "venue_id": fixture["venue_id"]},
        venue={"venue_id": fixture["venue_id"], "venue_name": fixture["venue_id"]},
        floors=[{"floor_id": item["floor_id"]} for item in fixture["floors"]],
        pois=[],
        entrances=[],
        connectors=[],
        route_graph={"nodes": [], "edges": []},
        cameras=[],
        keyframes=keyframes,
    )


def compute_position_error(result: Any, query: dict[str, Any]) -> float | None:
    if result.position is None or query.get("expected_position") is None:
        return None
    dx = result.position.x - query["expected_position"]["x"]
    dy = result.position.y - query["expected_position"]["y"]
    return round((dx * dx + dy * dy) ** 0.5, 4)


def evaluate_fixture(path: Path) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    fixture = load_fixture(path)
    query_rows: list[dict[str, Any]] = []

    with tempfile.TemporaryDirectory(prefix="aiglasses_eval_") as temp_root_str:
        bundle = build_bundle(Path(temp_root_str), fixture)
        relocalizer = BaselineRelocalizer(bundle)

        total = 0
        top1_correct = 0
        floor_correct = 0
        localization_errors: list[float] = []
        status_counts: dict[str, int] = {}

        for query in fixture["queries"]:
            route_prior = RoutePrior(
                route_id=query.get("route_id"),
                edge_ids=query.get("route_edge_ids", []),
                corridor_window_m=query.get("corridor_window_m"),
            )
            result = relocalizer.localize(
                LocalizationQuery(
                    request_id=query["query_id"],
                    venue_id=fixture["venue_id"],
                    capture_mode=query["capture_mode"],
                    image_bytes=query["payload_token"].encode("utf-8"),
                    candidate_floor_id=query.get("candidate_floor_id"),
                    route_prior=route_prior,
                )
            )
            total += 1
            status_counts[result.status] = status_counts.get(result.status, 0) + 1
            expected_keyframe_id = query.get("expected_keyframe_id")
            expected_status = query.get("expected_status")
            top1_match = bool(expected_keyframe_id and result.matched_keyframe_id == expected_keyframe_id)
            if top1_match:
                top1_correct += 1
            floor_match = bool(query.get("expected_floor_id") and result.floor_id == query["expected_floor_id"])
            if floor_match:
                floor_correct += 1
            position_error_m = compute_position_error(result, query)
            if position_error_m is not None:
                localization_errors.append(position_error_m)
            status_match = result.status == expected_status if expected_status is not None else result.status == "ok"
            is_failure = not status_match or (
                expected_keyframe_id is not None and not top1_match
            ) or (
                query.get("expected_floor_id") is not None and not floor_match
            )
            query_rows.append(
                {
                    "fixture_name": path.stem,
                    "fixture_path": str(path),
                    "query_id": query["query_id"],
                    "capture_mode": query["capture_mode"],
                    "candidate_floor_id": query.get("candidate_floor_id"),
                    "query_image_ref": query.get("query_image_ref"),
                    "ground_truth_source": query.get("ground_truth_source"),
                    "expected_keyframe_id": expected_keyframe_id,
                    "expected_status": expected_status,
                    "expected_floor_id": query.get("expected_floor_id"),
                    "expected_position": query.get("expected_position"),
                    "expected_route_node_id": query.get("expected_route_node_id"),
                    "expected_poi_id": query.get("expected_poi_id"),
                    "route_edge_ids": query.get("route_edge_ids", []),
                    "corridor_window_m": query.get("corridor_window_m"),
                    "matched_keyframe_id": result.matched_keyframe_id,
                    "matched_keyframes": [item.model_dump() for item in result.matched_keyframes],
                    "status": result.status,
                    "status_match": status_match,
                    "confidence": result.confidence,
                    "failure_stage": result.failure_stage,
                    "suggested_action": result.suggested_action,
                    "latency_ms": result.latency_ms,
                    "floor_id": result.floor_id,
                    "position": result.position.model_dump() if result.position else None,
                    "position_error_m": position_error_m,
                    "top1_match": top1_match,
                    "floor_match": floor_match,
                    "is_failure": is_failure,
                    "query_payload_token": query["payload_token"],
                }
            )

    summary = {
        "fixture_name": path.stem,
        "fixture_path": str(path),
        "venue_id": fixture["venue_id"],
        "total_queries": total,
        "top1_accuracy": round(top1_correct / total, 4) if total else 0.0,
        "floor_accuracy": round(floor_correct / total, 4) if total else 0.0,
        "avg_position_error_m": round(sum(localization_errors) / len(localization_errors), 4)
        if localization_errors
        else None,
        "status_counts": status_counts,
        "failure_count": sum(1 for row in query_rows if row["is_failure"]),
    }
    return summary, query_rows


def discover_fixture_paths(inputs: list[str]) -> list[Path]:
    raw_paths = inputs or [str(Path(__file__).resolve().parent / "fixtures")]
    fixture_paths: list[Path] = []
    for raw in raw_paths:
        path = Path(raw).resolve()
        if path.is_file():
            fixture_paths.append(path)
            continue
        if path.is_dir():
            fixture_paths.extend(sorted(item for item in path.glob("*.json") if item.is_file()))
            continue
        raise ValueError(f"fixture input not found: {raw}")
    unique_paths = sorted(dict.fromkeys(fixture_paths))
    if not unique_paths:
        raise ValueError("no fixture files found")
    return unique_paths


def build_aggregate_report(fixture_summaries: list[dict[str, Any]], query_rows: list[dict[str, Any]]) -> dict[str, Any]:
    total_queries = len(query_rows)
    status_counts: dict[str, int] = {}
    position_errors = [row["position_error_m"] for row in query_rows if row["position_error_m"] is not None]
    top1_matches = sum(1 for row in query_rows if row["top1_match"])
    floor_matches = sum(1 for row in query_rows if row["floor_match"])
    failure_count = sum(1 for row in query_rows if row["is_failure"])
    for row in query_rows:
        status_counts[row["status"]] = status_counts.get(row["status"], 0) + 1

    return {
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "fixture_count": len(fixture_summaries),
        "total_queries": total_queries,
        "top1_accuracy": round(top1_matches / total_queries, 4) if total_queries else 0.0,
        "floor_accuracy": round(floor_matches / total_queries, 4) if total_queries else 0.0,
        "avg_position_error_m": round(sum(position_errors) / len(position_errors), 4) if position_errors else None,
        "status_counts": status_counts,
        "failure_count": failure_count,
        "fixtures": fixture_summaries,
    }


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=True), encoding="utf-8")


def write_query_jsonl(path: Path, query_rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        for row in query_rows:
            handle.write(json.dumps(row, ensure_ascii=True) + "\n")


def export_failure_samples(failure_dir: Path, query_rows: list[dict[str, Any]]) -> int:
    failure_dir.mkdir(parents=True, exist_ok=True)
    count = 0
    for row in query_rows:
        if not row["is_failure"]:
            continue
        file_name = f"{row['fixture_name']}__{row['query_id']}.json"
        payload = {
            "fixture_name": row["fixture_name"],
            "query_id": row["query_id"],
            "status": row["status"],
            "expected_status": row["expected_status"],
            "status_match": row["status_match"],
            "confidence": row["confidence"],
            "failure_stage": row["failure_stage"],
            "expected_keyframe_id": row["expected_keyframe_id"],
            "expected_floor_id": row["expected_floor_id"],
            "expected_route_node_id": row["expected_route_node_id"],
            "expected_poi_id": row["expected_poi_id"],
            "matched_keyframe_id": row["matched_keyframe_id"],
            "position_error_m": row["position_error_m"],
            "capture_mode": row["capture_mode"],
            "candidate_floor_id": row["candidate_floor_id"],
            "query_image_ref": row["query_image_ref"],
            "ground_truth_source": row["ground_truth_source"],
            "route_edge_ids": row["route_edge_ids"],
            "corridor_window_m": row["corridor_window_m"],
            "query_payload_token": row["query_payload_token"],
            "matched_keyframes": row["matched_keyframes"],
        }
        write_json(failure_dir / file_name, payload)
        count += 1
    return count


def main() -> int:
    parser = argparse.ArgumentParser(description="Run offline relocalization evaluation.")
    parser.add_argument(
        "inputs",
        nargs="*",
        help="Fixture file or directory. Default: mapping/algorithms/relocalization/fixtures",
    )
    parser.add_argument(
        "--report-json",
        default="",
        help="Optional path to aggregated JSON report output.",
    )
    parser.add_argument(
        "--query-jsonl",
        default="",
        help="Optional path to per-query JSONL output.",
    )
    parser.add_argument(
        "--failure-dir",
        default="",
        help="Optional directory used to export failed sample records.",
    )
    args = parser.parse_args()

    try:
        fixture_paths = discover_fixture_paths(args.inputs)
    except ValueError as exc:
        print(json.dumps({"status": "error", "message": str(exc)}, ensure_ascii=True))
        return 1

    fixture_summaries: list[dict[str, Any]] = []
    query_rows: list[dict[str, Any]] = []
    for path in fixture_paths:
        summary, rows = evaluate_fixture(path)
        fixture_summaries.append(summary)
        query_rows.extend(rows)

    report = build_aggregate_report(fixture_summaries, query_rows)
    if args.query_jsonl:
        write_query_jsonl(Path(args.query_jsonl).resolve(), query_rows)
    if args.failure_dir:
        report["exported_failure_samples"] = export_failure_samples(Path(args.failure_dir).resolve(), query_rows)
    if args.report_json:
        write_json(Path(args.report_json).resolve(), report)

    print(json.dumps(report, indent=2, ensure_ascii=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
