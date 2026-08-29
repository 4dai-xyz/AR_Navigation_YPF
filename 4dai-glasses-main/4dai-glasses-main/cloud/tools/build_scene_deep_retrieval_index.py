from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
from typing import Any

import numpy as np
import torch
from PIL import Image
from torch.utils.data import DataLoader, Dataset
from torchvision import models
from torchvision import transforms

from build_scene_retrieval_index import (
    booth_sort_key,
    evaluate_index,
    load_expected_booths,
    l2_normalize,
    read_jsonl,
    write_json,
    write_jsonl,
)


class SceneSampleDataset(Dataset):
    def __init__(self, samples_root: Path, metadata: list[dict[str, Any]], transform: Any) -> None:
        self.samples_root = samples_root
        self.metadata = metadata
        self.transform = transform

    def __len__(self) -> int:
        return len(self.metadata)

    def __getitem__(self, index: int) -> torch.Tensor:
        image_path = self.samples_root / self.metadata[index]["sample_image_ref"]
        image = Image.open(image_path).convert("RGB")
        return self.transform(image)


def load_resnet50() -> tuple[torch.nn.Module, Any, str]:
    weights = models.ResNet50_Weights.DEFAULT
    model = models.resnet50(weights=weights)
    extractor = torch.nn.Sequential(*(list(model.children())[:-1]))
    extractor.eval()
    return extractor, weights.transforms(), "torchvision_resnet50"


def load_mobilenet_v3_small() -> tuple[torch.nn.Module, Any, str]:
    weights = models.MobileNet_V3_Small_Weights.DEFAULT
    model = models.mobilenet_v3_small(weights=weights)
    extractor = torch.nn.Sequential(model.features, model.avgpool)
    extractor.eval()
    return extractor, weights.transforms(), "torchvision_mobilenet_v3_small"


def load_dinov2_vits14() -> tuple[torch.nn.Module, Any, str]:
    model = torch.hub.load("facebookresearch/dinov2", "dinov2_vits14")
    model.eval()
    transform = transforms.Compose(
        [
            transforms.Resize(256, interpolation=transforms.InterpolationMode.BICUBIC),
            transforms.CenterCrop(224),
            transforms.ToTensor(),
            transforms.Normalize(mean=(0.485, 0.456, 0.406), std=(0.229, 0.224, 0.225)),
        ]
    )
    return model, transform, "dinov2_vits14"


def load_open_clip_vit_b32_openai() -> tuple[torch.nn.Module, Any, str]:
    import open_clip

    model, _, transform = open_clip.create_model_and_transforms(
        "ViT-B-32",
        pretrained="openai",
        cache_dir=os.environ.get("OPENCLIP_CACHE_DIR"),
    )
    model.eval()
    return model, transform, "open_clip_vit_b32_openai"


def load_model(model_name: str) -> tuple[torch.nn.Module, Any, str]:
    if model_name == "resnet50":
        return load_resnet50()
    if model_name == "mobilenet_v3_small":
        return load_mobilenet_v3_small()
    if model_name == "dinov2_vits14":
        return load_dinov2_vits14()
    if model_name == "clip_vit_b32_openai":
        return load_open_clip_vit_b32_openai()
    raise ValueError(f"unsupported model {model_name!r}")


def normalize_features(features: np.ndarray) -> np.ndarray:
    normalized = np.zeros_like(features, dtype=np.float32)
    for index, feature in enumerate(features):
        normalized[index] = l2_normalize(feature.astype(np.float32))
    return normalized


def load_metadata(samples_path: Path) -> list[dict[str, Any]]:
    samples_root = samples_path.parent
    rows = read_jsonl(samples_path)
    metadata: list[dict[str, Any]] = []
    for row in rows:
        image_ref = row.get("sample_image_ref")
        booth_id = row.get("booth_id")
        if not image_ref or not booth_id:
            continue
        if not (samples_root / str(image_ref)).exists():
            continue
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
    if not metadata:
        raise ValueError(f"{samples_path}: no valid sample rows")
    return metadata


def extract_features(
    samples_path: Path,
    metadata: list[dict[str, Any]],
    model_name: str,
    batch_size: int,
    device: str,
) -> tuple[np.ndarray, str]:
    model, transform, feature_extractor = load_model(model_name)
    model.to(device)
    dataset = SceneSampleDataset(samples_path.parent, metadata, transform)
    dataloader = DataLoader(dataset, batch_size=batch_size, shuffle=False, num_workers=0)

    batches: list[np.ndarray] = []
    with torch.no_grad():
        for batch in dataloader:
            batch = batch.to(device)
            if feature_extractor.startswith("open_clip_"):
                output = model.encode_image(batch).flatten(1)
            else:
                output = model(batch).flatten(1)
            batches.append(output.cpu().numpy().astype(np.float32))
    return normalize_features(np.concatenate(batches, axis=0)), feature_extractor


def build_deep_index(
    samples_path: Path,
    output_root: Path,
    booth_coordinates_path: Path | None,
    model_name: str,
    batch_size: int,
    device: str,
) -> tuple[np.ndarray, list[dict[str, Any]], dict[str, Any], str]:
    metadata = load_metadata(samples_path)
    features, feature_extractor = extract_features(samples_path, metadata, model_name, batch_size, device)

    output_root.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(output_root / f"scene_retrieval_{feature_extractor}_index.npz", features=features)
    write_jsonl(output_root / f"scene_retrieval_{feature_extractor}_metadata.jsonl", metadata)

    counts: dict[str, int] = {}
    for item in metadata:
        counts[item["booth_id"]] = counts.get(item["booth_id"], 0) + 1
    expected_booths = load_expected_booths(booth_coordinates_path)
    summary = {
        "schema_version": "scene_retrieval_index_summary_v0.1",
        "feature_extractor": feature_extractor,
        "samples_path": str(samples_path).replace("\\", "/"),
        "index_path": str(output_root / f"scene_retrieval_{feature_extractor}_index.npz").replace("\\", "/"),
        "metadata_path": str(output_root / f"scene_retrieval_{feature_extractor}_metadata.jsonl").replace("\\", "/"),
        "sample_count": len(metadata),
        "feature_dim": int(features.shape[1]),
        "booth_count": len(counts),
        "expected_booth_count": len(expected_booths) if expected_booths else None,
        "missing_expected_booths": sorted(expected_booths - set(counts)) if expected_booths else [],
        "sample_counts": dict(sorted(counts.items(), key=lambda item: booth_sort_key(item[0]))),
        "device": device,
        "torch_home": os.environ.get("TORCH_HOME"),
    }
    write_json(output_root / f"scene_retrieval_{feature_extractor}_index_summary.json", summary)
    return features, metadata, summary, feature_extractor


def main() -> None:
    parser = argparse.ArgumentParser(description="Build and evaluate a torchvision deep scene retrieval index.")
    parser.add_argument("--samples", type=Path, required=True)
    parser.add_argument("--output-root", type=Path, required=True)
    parser.add_argument("--booth-coordinates", type=Path)
    parser.add_argument(
        "--model",
        choices=["resnet50", "mobilenet_v3_small", "dinov2_vits14", "clip_vit_b32_openai"],
        default="resnet50",
    )
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--device", default="cpu")
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--exclude-near-ms", type=int, default=2000)
    args = parser.parse_args()

    features, metadata, summary, feature_extractor = build_deep_index(
        samples_path=args.samples,
        output_root=args.output_root,
        booth_coordinates_path=args.booth_coordinates,
        model_name=args.model,
        batch_size=args.batch_size,
        device=args.device,
    )
    report = evaluate_index(
        features=features,
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
