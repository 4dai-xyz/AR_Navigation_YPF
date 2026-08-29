from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

import cv2
import numpy as np


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as input_file:
        for line_no, line in enumerate(input_file, start=1):
            stripped = line.strip()
            if not stripped:
                continue
            try:
                rows.append(json.loads(stripped))
            except json.JSONDecodeError as exc:
                raise ValueError(f"{path}:{line_no}: invalid JSONL row") from exc
    return rows


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as output:
        for row in rows:
            output.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")


def l2_normalize(vector: np.ndarray) -> np.ndarray:
    norm = float(np.linalg.norm(vector))
    if norm <= 1e-8:
        return vector.astype(np.float32)
    return (vector / norm).astype(np.float32)


def normalized_histogram(image: np.ndarray, bins: tuple[int, int, int]) -> np.ndarray:
    hist = cv2.calcHist([image], [0, 1, 2], None, bins, [0, 180, 0, 256, 0, 256])
    hist = cv2.normalize(hist, hist).flatten()
    return hist.astype(np.float32)


def extract_hybrid_v1(image_path: Path) -> np.ndarray:
    image = cv2.imread(str(image_path), cv2.IMREAD_COLOR)
    if image is None:
        raise ValueError(f"{image_path}: failed to read image")

    resized = cv2.resize(image, (320, 568))
    hsv = cv2.cvtColor(resized, cv2.COLOR_BGR2HSV)
    lab = cv2.cvtColor(resized, cv2.COLOR_BGR2LAB)
    gray = cv2.cvtColor(resized, cv2.COLOR_BGR2GRAY)

    color_layout = cv2.resize(lab, (24, 32)).astype(np.float32).flatten() / 255.0
    gray_layout = cv2.resize(cv2.equalizeHist(gray), (24, 32)).astype(np.float32).flatten() / 255.0
    edge = cv2.Canny(gray, 80, 160)
    edge_layout = cv2.resize(edge, (24, 32)).astype(np.float32).flatten() / 255.0
    hsv_hist = normalized_histogram(hsv, (12, 6, 6))

    feature = np.concatenate(
        [
            color_layout * 0.50,
            gray_layout * 0.35,
            edge_layout * 0.25,
            hsv_hist * 0.75,
        ]
    )
    return l2_normalize(feature)


def booth_sort_key(booth_id: str) -> tuple[str, int]:
    return booth_id[0], int(booth_id[1:])


def load_expected_booths(path: Path | None) -> set[str]:
    if path is None:
        return set()
    payload = json.loads(path.read_text(encoding="utf-8"))
    return {
        str(booth["booth_id"]).upper()
        for booth in payload.get("booths", [])
        if booth.get("booth_id")
    }


def build_index(
    samples_path: Path,
    output_root: Path,
    booth_coordinates_path: Path | None,
) -> tuple[np.ndarray, list[dict[str, Any]], dict[str, Any]]:
    samples_root = samples_path.parent
    rows = read_jsonl(samples_path)
    features: list[np.ndarray] = []
    metadata: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []

    for row in rows:
        image_ref = row.get("sample_image_ref")
        booth_id = row.get("booth_id")
        if not image_ref or not booth_id:
            skipped.append({"keyframe_id": row.get("keyframe_id"), "reason": "missing image_ref or booth_id"})
            continue
        image_path = samples_root / str(image_ref)
        if not image_path.exists():
            skipped.append({"keyframe_id": row.get("keyframe_id"), "reason": f"missing image {image_ref}"})
            continue
        feature = extract_hybrid_v1(image_path)
        features.append(feature)
        metadata.append(
            {
                "index": len(metadata),
                "keyframe_id": row.get("keyframe_id"),
                "booth_id": str(booth_id).upper(),
                "poi_id": row.get("poi_id"),
                "position": row.get("position"),
                "timestamp_ms": row.get("timestamp_ms"),
                "source_frame_index": row.get("source_frame_index"),
                "sample_image_ref": image_ref,
                "quality": row.get("quality"),
                "label_confidence": row.get("label_confidence"),
            }
        )

    if not features:
        raise ValueError(f"{samples_path}: no valid sample images")

    feature_matrix = np.stack(features).astype(np.float32)
    output_root.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(output_root / "scene_retrieval_hybrid_v1_index.npz", features=feature_matrix)
    write_jsonl(output_root / "scene_retrieval_hybrid_v1_metadata.jsonl", metadata)

    counts: dict[str, int] = {}
    for item in metadata:
        counts[item["booth_id"]] = counts.get(item["booth_id"], 0) + 1
    expected_booths = load_expected_booths(booth_coordinates_path)
    indexed_booths = set(counts)
    summary = {
        "schema_version": "scene_retrieval_index_summary_v0.1",
        "feature_extractor": "hybrid_v1",
        "samples_path": str(samples_path).replace("\\", "/"),
        "index_path": str(output_root / "scene_retrieval_hybrid_v1_index.npz").replace("\\", "/"),
        "metadata_path": str(output_root / "scene_retrieval_hybrid_v1_metadata.jsonl").replace("\\", "/"),
        "sample_count": len(metadata),
        "feature_dim": int(feature_matrix.shape[1]),
        "booth_count": len(counts),
        "expected_booth_count": len(expected_booths) if expected_booths else None,
        "missing_expected_booths": sorted(expected_booths - indexed_booths) if expected_booths else [],
        "sample_counts": dict(sorted(counts.items(), key=lambda item: booth_sort_key(item[0]))),
        "skipped": skipped,
    }
    write_json(output_root / "scene_retrieval_hybrid_v1_index_summary.json", summary)
    return feature_matrix, metadata, summary


def topk_indices(scores: np.ndarray, k: int) -> np.ndarray:
    k = min(k, scores.shape[0])
    if k <= 0:
        return np.array([], dtype=np.int64)
    indices = np.argpartition(-scores, np.arange(k))[:k]
    return indices[np.argsort(-scores[indices])]


def evaluate_index(
    features: np.ndarray,
    metadata: list[dict[str, Any]],
    output_root: Path,
    top_k: int,
    exclude_near_ms: int,
    feature_extractor: str = "hybrid_v1",
) -> dict[str, Any]:
    similarities = features @ features.T
    timestamps = np.array([int(item.get("timestamp_ms") or 0) for item in metadata], dtype=np.int64)

    top1_correct = 0
    top3_correct = 0
    top5_correct = 0
    per_booth_total: dict[str, int] = {}
    per_booth_top1: dict[str, int] = {}
    confusion: dict[str, dict[str, int]] = {}
    examples: list[dict[str, Any]] = []

    for query_index, query in enumerate(metadata):
        scores = similarities[query_index].copy()
        scores[query_index] = -np.inf
        if exclude_near_ms > 0:
            near_mask = np.abs(timestamps - timestamps[query_index]) <= exclude_near_ms
            scores[near_mask] = -np.inf
        query_booth = query["booth_id"]
        per_booth_total[query_booth] = per_booth_total.get(query_booth, 0) + 1
        ranked = topk_indices(scores, max(top_k, 5))
        top_booths = [metadata[int(index)]["booth_id"] for index in ranked]
        top_items = [
            {
                "booth_id": metadata[int(index)]["booth_id"],
                "keyframe_id": metadata[int(index)]["keyframe_id"],
                "score": round(float(scores[int(index)]), 6),
                "sample_image_ref": metadata[int(index)]["sample_image_ref"],
            }
            for index in ranked[:top_k]
        ]

        top1 = top_booths[0] if top_booths else None
        if top1 == query_booth:
            top1_correct += 1
            per_booth_top1[query_booth] = per_booth_top1.get(query_booth, 0) + 1
        else:
            confusion.setdefault(query_booth, {})
            if top1:
                confusion[query_booth][top1] = confusion[query_booth].get(top1, 0) + 1
            if len(examples) < 25:
                examples.append(
                    {
                        "query_booth_id": query_booth,
                        "query_keyframe_id": query["keyframe_id"],
                        "query_image_ref": query["sample_image_ref"],
                        "top_matches": top_items,
                    }
                )
        if query_booth in top_booths[:3]:
            top3_correct += 1
        if query_booth in top_booths[:5]:
            top5_correct += 1

    total = len(metadata)
    per_booth_accuracy = {
        booth_id: round(per_booth_top1.get(booth_id, 0) / count, 4)
        for booth_id, count in sorted(per_booth_total.items(), key=lambda item: booth_sort_key(item[0]))
    }
    report = {
        "schema_version": "scene_retrieval_eval_report_v0.1",
        "feature_extractor": feature_extractor,
        "sample_count": total,
        "exclude_near_ms": exclude_near_ms,
        "top1_accuracy": round(top1_correct / total, 4),
        "top3_accuracy": round(top3_correct / total, 4),
        "top5_accuracy": round(top5_correct / total, 4),
        "per_booth_top1_accuracy": per_booth_accuracy,
        "low_accuracy_booths_lt_0_8": {
            booth_id: accuracy for booth_id, accuracy in per_booth_accuracy.items() if accuracy < 0.8
        },
        "confusion": confusion,
        "failure_examples": examples,
    }
    write_json(output_root / f"scene_retrieval_{feature_extractor}_eval_report.json", report)
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description="Build and evaluate a local scene retrieval index.")
    parser.add_argument("--samples", type=Path, required=True)
    parser.add_argument("--output-root", type=Path, required=True)
    parser.add_argument("--booth-coordinates", type=Path)
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--exclude-near-ms", type=int, default=0)
    args = parser.parse_args()

    features, metadata, summary = build_index(args.samples, args.output_root, args.booth_coordinates)
    report = evaluate_index(features, metadata, args.output_root, args.top_k, args.exclude_near_ms, "hybrid_v1")
    result = {
        "index_summary": summary,
        "eval": {
            "top1_accuracy": report["top1_accuracy"],
            "top3_accuracy": report["top3_accuracy"],
            "top5_accuracy": report["top5_accuracy"],
            "low_accuracy_booths_lt_0_8": report["low_accuracy_booths_lt_0_8"],
        },
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
