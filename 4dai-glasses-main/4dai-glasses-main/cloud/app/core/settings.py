from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    service_name: str
    api_version: str
    service_mode: str
    repo_root: Path
    venue_package_root: Path
    venue_package_version: str | None
    max_upload_bytes: int
    relocalization_timeout_ms: int
    route_timeout_ms: int
    pc_backend_host: str
    pc_backend_port: int
    recognition_mode: str
    scene_retrieval_index_path: Path | None
    scene_retrieval_metadata_path: Path | None
    scene_retrieval_booth_coordinates_path: Path | None
    scene_retrieval_feature_extractor: str
    scene_retrieval_device: str
    scene_retrieval_top_k: int
    scene_retrieval_min_score: float
    scene_retrieval_ok_score: float
    scene_retrieval_timeout_ms: int
    scene_classifier_checkpoint_path: Path | None
    scene_classifier_device: str
    scene_classifier_min_confidence: float
    scene_classifier_ok_confidence: float
    auth_enabled: bool
    api_token: str | None
    rate_limit_per_minute: int


def env_bool(name: str, default: bool = False) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def build_settings() -> Settings:
    repo_root = Path(__file__).resolve().parents[3]
    venue_package_root = Path(
        os.getenv("AI_GLASSES_VENUE_PACKAGE_ROOT")
        or repo_root / "mapping" / "examples" / "venue-package-example"
    ).resolve()
    default_scene_index_root = (
        repo_root
        / "cloud"
        / "tmp_scene_recognition_probe"
        / "yjdd_hd_scene_retrieval_fusion_resnet50_clip_dinov2_exclude2s"
    )
    scene_index_path = os.getenv("AI_GLASSES_SCENE_RETRIEVAL_INDEX_PATH")
    scene_metadata_path = os.getenv("AI_GLASSES_SCENE_RETRIEVAL_METADATA_PATH")
    scene_booth_coordinates_path = os.getenv("AI_GLASSES_SCENE_RETRIEVAL_BOOTH_COORDINATES_PATH")
    scene_classifier_checkpoint_path = os.getenv("AI_GLASSES_SCENE_CLASSIFIER_CHECKPOINT_PATH")
    default_scene_classifier_v3_checkpoint = (
        repo_root
        / "cloud"
        / "tmp_scene_recognition_probe"
        / "yjdd_hd_booth_classifier_mobilenet_v3_small_v3_gpu_hard_partial"
        / "booth_classifier_mobilenet_v3_small_v3_gpu_hard_partial.pt"
    )
    default_scene_classifier_v2_checkpoint = (
        repo_root
        / "cloud"
        / "tmp_scene_recognition_probe"
        / "yjdd_hd_booth_classifier_mobilenet_v3_small_v2_aug"
        / "booth_classifier_mobilenet_v3_small_v2_aug.pt"
    )
    default_scene_classifier_v1_checkpoint = (
        repo_root
        / "cloud"
        / "tmp_scene_recognition_probe"
        / "yjdd_hd_booth_classifier_mobilenet_v3_small"
        / "booth_classifier_mobilenet_v3_small.pt"
    )
    default_scene_classifier_checkpoint = (
        default_scene_classifier_v3_checkpoint
        if default_scene_classifier_v3_checkpoint.exists()
        else (
            default_scene_classifier_v2_checkpoint
            if default_scene_classifier_v2_checkpoint.exists()
            else default_scene_classifier_v1_checkpoint
        )
    )
    max_upload_bytes = int(os.getenv("AI_GLASSES_MAX_UPLOAD_BYTES", "8388608"))
    return Settings(
        service_name=os.getenv("AI_GLASSES_SERVICE_NAME", "indoor-navigation-api"),
        api_version=os.getenv("AI_GLASSES_API_VERSION", "0.1.0"),
        service_mode=os.getenv("AI_GLASSES_SERVICE_MODE", "cloud"),
        repo_root=repo_root,
        venue_package_root=venue_package_root,
        venue_package_version=os.getenv("AI_GLASSES_VENUE_PACKAGE_VERSION") or None,
        max_upload_bytes=max_upload_bytes,
        relocalization_timeout_ms=int(os.getenv("AI_GLASSES_RELOCALIZATION_TIMEOUT_MS", "3000")),
        route_timeout_ms=int(os.getenv("AI_GLASSES_ROUTE_TIMEOUT_MS", "1500")),
        pc_backend_host=os.getenv("AI_GLASSES_PC_BACKEND_HOST", "0.0.0.0"),
        pc_backend_port=int(os.getenv("AI_GLASSES_PC_BACKEND_PORT", "8000")),
        recognition_mode=os.getenv("AI_GLASSES_RECOGNITION_MODE", "baseline").strip().lower(),
        scene_retrieval_index_path=Path(
            scene_index_path
            or default_scene_index_root / "scene_retrieval_fusion_resnet50_clip_vitb32_dinov2_index.npz"
        ).resolve()
        if scene_index_path or default_scene_index_root.exists()
        else None,
        scene_retrieval_metadata_path=Path(
            scene_metadata_path
            or default_scene_index_root / "scene_retrieval_fusion_resnet50_clip_vitb32_dinov2_metadata.jsonl"
        ).resolve()
        if scene_metadata_path or default_scene_index_root.exists()
        else None,
        scene_retrieval_booth_coordinates_path=Path(scene_booth_coordinates_path).resolve()
        if scene_booth_coordinates_path
        else None,
        scene_retrieval_feature_extractor=os.getenv(
            "AI_GLASSES_SCENE_RETRIEVAL_FEATURE_EXTRACTOR",
            "fusion_resnet50_clip_vitb32_dinov2",
        ).strip(),
        scene_retrieval_device=os.getenv("AI_GLASSES_SCENE_RETRIEVAL_DEVICE", "auto").strip().lower(),
        scene_retrieval_top_k=int(os.getenv("AI_GLASSES_SCENE_RETRIEVAL_TOP_K", "5")),
        scene_retrieval_min_score=float(os.getenv("AI_GLASSES_SCENE_RETRIEVAL_MIN_SCORE", "0.82")),
        scene_retrieval_ok_score=float(os.getenv("AI_GLASSES_SCENE_RETRIEVAL_OK_SCORE", "0.9")),
        scene_retrieval_timeout_ms=int(os.getenv("AI_GLASSES_SCENE_RETRIEVAL_TIMEOUT_MS", "60000")),
        scene_classifier_checkpoint_path=Path(
            scene_classifier_checkpoint_path or default_scene_classifier_checkpoint
        ).resolve()
        if scene_classifier_checkpoint_path or default_scene_classifier_checkpoint.exists()
        else None,
        scene_classifier_device=os.getenv(
            "AI_GLASSES_SCENE_CLASSIFIER_DEVICE",
            os.getenv("AI_GLASSES_SCENE_RETRIEVAL_DEVICE", "auto"),
        )
        .strip()
        .lower(),
        scene_classifier_min_confidence=float(os.getenv("AI_GLASSES_SCENE_CLASSIFIER_MIN_CONFIDENCE", "0.20")),
        scene_classifier_ok_confidence=float(os.getenv("AI_GLASSES_SCENE_CLASSIFIER_OK_CONFIDENCE", "0.50")),
        auth_enabled=env_bool("AI_GLASSES_AUTH_ENABLED", False),
        api_token=os.getenv("AI_GLASSES_API_TOKEN") or None,
        rate_limit_per_minute=int(os.getenv("AI_GLASSES_RATE_LIMIT_PER_MINUTE", "0")),
    )


settings = build_settings()
