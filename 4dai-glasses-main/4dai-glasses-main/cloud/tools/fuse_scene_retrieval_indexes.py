from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np

from build_scene_retrieval_index import evaluate_index, l2_normalize, read_jsonl, write_json, write_jsonl


def load_npz_features(path: Path) -> np.ndarray:
    with np.load(path) as payload:
        return payload["features"].astype(np.float32)


def load_metadata(path: Path) -> list[dict]:
    return read_jsonl(path)


def validate_metadata(reference: list[dict], candidate: list[dict], candidate_path: Path) -> None:
    if len(reference) != len(candidate):
        raise ValueError(f"{candidate_path}: metadata row count differs")
    for index, (left, right) in enumerate(zip(reference, candidate)):
        if left.get("sample_image_ref") != right.get("sample_image_ref") or left.get("booth_id") != right.get("booth_id"):
            raise ValueError(f"{candidate_path}:{index}: metadata order differs")


def weighted_normalized(features: np.ndarray, weight: float) -> np.ndarray:
    normalized = np.zeros_like(features, dtype=np.float32)
    for index, feature in enumerate(features):
        normalized[index] = l2_normalize(feature)
    return normalized * weight


def main() -> None:
    parser = argparse.ArgumentParser(description="Fuse multiple scene retrieval indexes by feature concatenation.")
    parser.add_argument("--index", type=Path, action="append", required=True)
    parser.add_argument("--metadata", type=Path, action="append", required=True)
    parser.add_argument("--weight", type=float, action="append")
    parser.add_argument("--name", default="fusion")
    parser.add_argument("--output-root", type=Path, required=True)
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--exclude-near-ms", type=int, default=2000)
    args = parser.parse_args()

    if len(args.index) != len(args.metadata):
        raise ValueError("--index and --metadata counts must match")
    weights = args.weight or [1.0] * len(args.index)
    if len(weights) != len(args.index):
        raise ValueError("--weight count must match --index count")

    metadata = load_metadata(args.metadata[0])
    feature_parts: list[np.ndarray] = []
    part_summaries: list[dict] = []
    for index_path, metadata_path, weight in zip(args.index, args.metadata, weights):
        current_metadata = load_metadata(metadata_path)
        validate_metadata(metadata, current_metadata, metadata_path)
        features = load_npz_features(index_path)
        feature_parts.append(weighted_normalized(features, weight))
        part_summaries.append(
            {
                "index_path": str(index_path).replace("\\", "/"),
                "metadata_path": str(metadata_path).replace("\\", "/"),
                "feature_dim": int(features.shape[1]),
                "weight": weight,
            }
        )

    fused = np.concatenate(feature_parts, axis=1).astype(np.float32)
    for index, feature in enumerate(fused):
        fused[index] = l2_normalize(feature)

    args.output_root.mkdir(parents=True, exist_ok=True)
    feature_extractor = args.name
    np.savez_compressed(args.output_root / f"scene_retrieval_{feature_extractor}_index.npz", features=fused)
    write_jsonl(args.output_root / f"scene_retrieval_{feature_extractor}_metadata.jsonl", metadata)

    summary = {
        "schema_version": "scene_retrieval_fusion_summary_v0.1",
        "feature_extractor": feature_extractor,
        "sample_count": len(metadata),
        "feature_dim": int(fused.shape[1]),
        "parts": part_summaries,
    }
    write_json(args.output_root / f"scene_retrieval_{feature_extractor}_index_summary.json", summary)
    report = evaluate_index(
        features=fused,
        metadata=metadata,
        output_root=args.output_root,
        top_k=args.top_k,
        exclude_near_ms=args.exclude_near_ms,
        feature_extractor=feature_extractor,
    )
    print(
        json.dumps(
            {
                "index_summary": summary,
                "eval": {
                    "top1_accuracy": report["top1_accuracy"],
                    "top3_accuracy": report["top3_accuracy"],
                    "top5_accuracy": report["top5_accuracy"],
                    "low_accuracy_booths_lt_0_8": report["low_accuracy_booths_lt_0_8"],
                },
            },
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
