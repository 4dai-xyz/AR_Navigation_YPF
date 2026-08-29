from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from cloud.app.models.api import RoutePrior
from cloud.app.services.relocalization import BaselineRelocalizer, LocalizationQuery
from cloud.app.services.venue_package import VenueBundle


@dataclass(frozen=True)
class RelocalizationEvalQuery:
    query_id: str
    image_path: Path
    venue_id: str
    expected_keyframe_id: str | None = None
    expected_floor_id: str | None = None
    capture_mode: str = "offline_eval"
    candidate_floor_id: str | None = None
    route_prior: RoutePrior | None = None


def _route_prior_from_payload(payload: dict[str, Any] | None) -> RoutePrior | None:
    if not payload:
        return None
    edge_ids = payload.get("edge_ids", [])
    if not isinstance(edge_ids, list):
        edge_ids = []
    corridor_window_m = payload.get("corridor_window_m")
    return RoutePrior(
        route_id=payload.get("route_id") if isinstance(payload.get("route_id"), str) else None,
        edge_ids=[edge_id for edge_id in edge_ids if isinstance(edge_id, str)],
        corridor_window_m=corridor_window_m if isinstance(corridor_window_m, (int, float)) else None,
    )


def _query_from_payload(payload: dict[str, Any], line_no: int) -> RelocalizationEvalQuery:
    query_id = payload.get("query_id") or payload.get("request_id")
    image_path = payload.get("image_path")
    venue_id = payload.get("venue_id")
    if not isinstance(query_id, str) or not query_id:
        raise ValueError(f"line {line_no}: query_id is required")
    if not isinstance(image_path, str) or not image_path:
        raise ValueError(f"line {line_no}: image_path is required")
    if not isinstance(venue_id, str) or not venue_id:
        raise ValueError(f"line {line_no}: venue_id is required")
    route_prior = payload.get("route_prior")
    return RelocalizationEvalQuery(
        query_id=query_id,
        image_path=Path(image_path),
        venue_id=venue_id,
        expected_keyframe_id=payload.get("expected_keyframe_id")
        if isinstance(payload.get("expected_keyframe_id"), str)
        else None,
        expected_floor_id=payload.get("expected_floor_id") if isinstance(payload.get("expected_floor_id"), str) else None,
        capture_mode=payload.get("capture_mode") if isinstance(payload.get("capture_mode"), str) else "offline_eval",
        candidate_floor_id=payload.get("candidate_floor_id")
        if isinstance(payload.get("candidate_floor_id"), str)
        else None,
        route_prior=_route_prior_from_payload(route_prior) if isinstance(route_prior, dict) else None,
    )


def load_eval_queries(jsonl_path: str | Path) -> list[RelocalizationEvalQuery]:
    path = Path(jsonl_path)
    queries: list[RelocalizationEvalQuery] = []
    for line_no, line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), start=1):
        if not line.strip():
            continue
        payload = json.loads(line)
        if not isinstance(payload, dict):
            raise ValueError(f"line {line_no}: must be a JSON object")
        queries.append(_query_from_payload(payload, line_no))
    return queries


def _resolve_image_path(query: RelocalizationEvalQuery, query_root: Path | None) -> Path:
    if query.image_path.is_absolute():
        return query.image_path
    return (query_root or Path.cwd()) / query.image_path


def _ratio(correct: int, total: int) -> float:
    if total == 0:
        return 0.0
    return round(correct / total, 4)


def _new_stats() -> dict[str, Any]:
    return {
        "query_count": 0,
        "localized_count": 0,
        "top1_total": 0,
        "top1_correct": 0,
        "top3_total": 0,
        "top3_correct": 0,
        "floor_total": 0,
        "floor_correct": 0,
        "total_latency_ms": 0,
        "status_counts": {},
        "failure_stage_counts": {},
    }


def _increment(counter: dict[str, int], key: str) -> None:
    counter[key] = counter.get(key, 0) + 1


def _finalize_stats(stats: dict[str, Any]) -> dict[str, Any]:
    localized_count = stats["localized_count"]
    return {
        "query_count": stats["query_count"],
        "localized_count": localized_count,
        "top1_accuracy": _ratio(stats["top1_correct"], stats["top1_total"]),
        "top1_evaluable_count": stats["top1_total"],
        "top3_accuracy": _ratio(stats["top3_correct"], stats["top3_total"]),
        "top3_evaluable_count": stats["top3_total"],
        "floor_accuracy": _ratio(stats["floor_correct"], stats["floor_total"]),
        "floor_evaluable_count": stats["floor_total"],
        "average_latency_ms": round(stats["total_latency_ms"] / localized_count, 2) if localized_count else 0.0,
        "status_counts": stats["status_counts"],
        "failure_stage_counts": stats["failure_stage_counts"],
    }


def _apply_query_result(
    stats: dict[str, Any],
    *,
    result_status: str,
    failure_stage: str | None,
    latency_ms: int,
    top1_hit: bool | None,
    top3_hit: bool | None,
    floor_hit: bool | None,
) -> None:
    stats["query_count"] += 1
    stats["localized_count"] += 1
    stats["total_latency_ms"] += latency_ms
    _increment(stats["status_counts"], result_status)
    if failure_stage:
        _increment(stats["failure_stage_counts"], failure_stage)
    if top1_hit is not None:
        stats["top1_total"] += 1
        stats["top1_correct"] += 1 if top1_hit else 0
    if top3_hit is not None:
        stats["top3_total"] += 1
        stats["top3_correct"] += 1 if top3_hit else 0
    if floor_hit is not None:
        stats["floor_total"] += 1
        stats["floor_correct"] += 1 if floor_hit else 0


def _apply_missing_image(stats: dict[str, Any]) -> None:
    stats["query_count"] += 1
    _increment(stats["status_counts"], "not_found")
    _increment(stats["failure_stage_counts"], "image_file_missing")


def evaluate_relocalization(
    bundle: VenueBundle,
    queries: list[RelocalizationEvalQuery],
    *,
    query_root: str | Path | None = None,
    max_failure_samples: int = 20,
) -> dict[str, Any]:
    relocalizer = BaselineRelocalizer(bundle)
    root = Path(query_root) if query_root is not None else None
    status_counts: dict[str, int] = {}
    failure_stage_counts: dict[str, int] = {}
    failure_samples: list[dict[str, Any]] = []
    by_floor: dict[str, dict[str, Any]] = {}
    by_venue: dict[str, dict[str, Any]] = {}
    total_latency_ms = 0
    top1_total = 0
    top1_correct = 0
    top3_total = 0
    top3_correct = 0
    floor_total = 0
    floor_correct = 0
    localized_count = 0

    for query in queries:
        floor_key = query.expected_floor_id or query.candidate_floor_id or "unknown"
        venue_key = query.venue_id
        floor_stats = by_floor.setdefault(floor_key, _new_stats())
        venue_stats = by_venue.setdefault(venue_key, _new_stats())
        image_path = _resolve_image_path(query, root)
        if not image_path.exists():
            _increment(failure_stage_counts, "image_file_missing")
            _increment(status_counts, "not_found")
            _apply_missing_image(floor_stats)
            _apply_missing_image(venue_stats)
            if len(failure_samples) < max_failure_samples:
                failure_samples.append(
                    {
                        "query_id": query.query_id,
                        "image_path": str(image_path),
                        "failure_stage": "image_file_missing",
                    }
                )
            continue

        result = relocalizer.localize(
            LocalizationQuery(
                request_id=query.query_id,
                venue_id=query.venue_id,
                capture_mode=query.capture_mode,
                image_bytes=image_path.read_bytes(),
                candidate_floor_id=query.candidate_floor_id,
                route_prior=query.route_prior,
            )
        )
        localized_count += 1
        total_latency_ms += result.latency_ms
        status_counts[result.status] = status_counts.get(result.status, 0) + 1
        if result.failure_stage:
            failure_stage_counts[result.failure_stage] = failure_stage_counts.get(result.failure_stage, 0) + 1

        top1_hit = True
        top3_hit = True
        floor_hit = True
        top1_eval: bool | None = None
        top3_eval: bool | None = None
        floor_eval: bool | None = None
        if query.expected_keyframe_id:
            top1_total += 1
            top3_total += 1
            top1_hit = result.matched_keyframe_id == query.expected_keyframe_id
            top3_hit = any(item.keyframe_id == query.expected_keyframe_id for item in result.matched_keyframes)
            top1_eval = top1_hit
            top3_eval = top3_hit
            top1_correct += 1 if top1_hit else 0
            top3_correct += 1 if top3_hit else 0
        if query.expected_floor_id:
            floor_total += 1
            floor_hit = result.floor_id == query.expected_floor_id
            floor_eval = floor_hit
            floor_correct += 1 if floor_hit else 0
        for stats in (floor_stats, venue_stats):
            _apply_query_result(
                stats,
                result_status=result.status,
                failure_stage=result.failure_stage,
                latency_ms=result.latency_ms,
                top1_hit=top1_eval,
                top3_hit=top3_eval,
                floor_hit=floor_eval,
            )

        if (result.status == "not_found" or not top1_hit or not floor_hit) and len(failure_samples) < max_failure_samples:
            failure_samples.append(
                {
                    "query_id": query.query_id,
                    "status": result.status,
                    "failure_stage": result.failure_stage,
                    "matched_keyframe_id": result.matched_keyframe_id,
                    "expected_keyframe_id": query.expected_keyframe_id,
                    "floor_id": result.floor_id,
                    "expected_floor_id": query.expected_floor_id,
                }
            )

    query_count = len(queries)
    return {
        "report_schema_version": "cloud_relocalization_eval_v1",
        "query_count": query_count,
        "localized_count": localized_count,
        "top1_accuracy": _ratio(top1_correct, top1_total),
        "top1_evaluable_count": top1_total,
        "top3_accuracy": _ratio(top3_correct, top3_total),
        "top3_evaluable_count": top3_total,
        "floor_accuracy": _ratio(floor_correct, floor_total),
        "floor_evaluable_count": floor_total,
        "average_latency_ms": round(total_latency_ms / localized_count, 2) if localized_count else 0.0,
        "status_counts": status_counts,
        "failure_stage_counts": failure_stage_counts,
        "by_floor": {key: _finalize_stats(value) for key, value in sorted(by_floor.items())},
        "by_venue": {key: _finalize_stats(value) for key, value in sorted(by_venue.items())},
        "failure_samples": failure_samples,
    }
