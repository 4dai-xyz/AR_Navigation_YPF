from __future__ import annotations

import hashlib
import json
import os
import sys
import threading
import time
from collections import deque
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np

from cloud.app.models.api import LocalizationData, MatchedKeyframe, MatchedLandmark
from cloud.app.services.venue_package import VenueBundle


@dataclass(frozen=True)
class SceneRetrievalConfig:
    index_path: Path | None
    metadata_path: Path | None
    booth_coordinates_path: Path | None
    feature_extractor: str
    device: str = "auto"
    top_k: int = 5
    min_score: float = 0.82
    ok_score: float = 0.9


@dataclass(frozen=True)
class SceneFeatureModel:
    name: str
    model: Any
    transform: Any
    device: str


@dataclass(frozen=True)
class SceneTemporalVote:
    booth_id: str
    score: float
    match: dict[str, Any]
    timestamp_ms: int


class SceneRetrievalUnavailable(RuntimeError):
    pass


_ADAPTER_CACHE: dict[tuple[str, str, str, str], "SceneRetrievalAdapter"] = {}
_INFERENCE_LOCK = threading.Lock()


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as input_file:
        for line_no, line in enumerate(input_file, start=1):
            stripped = line.strip()
            if not stripped:
                continue
            try:
                rows.append(json.loads(stripped))
            except json.JSONDecodeError as exc:
                raise SceneRetrievalUnavailable(f"{path}:{line_no}: invalid JSONL row") from exc
    return rows


def _l2_normalize(vector: np.ndarray) -> np.ndarray:
    norm = float(np.linalg.norm(vector))
    if norm <= 1e-8:
        return vector.astype(np.float32)
    return (vector / norm).astype(np.float32)


def _normalized_histogram(image: np.ndarray, bins: tuple[int, int, int]) -> np.ndarray:
    import cv2

    hist = cv2.calcHist([image], [0, 1, 2], None, bins, [0, 180, 0, 256, 0, 256])
    hist = cv2.normalize(hist, hist).flatten()
    return hist.astype(np.float32)


def _extract_hybrid_v1_from_bytes(image_bytes: bytes) -> np.ndarray:
    import cv2

    array = np.frombuffer(image_bytes, dtype=np.uint8)
    image = cv2.imdecode(array, cv2.IMREAD_COLOR)
    if image is None:
        raise SceneRetrievalUnavailable("scene retrieval failed to decode image")

    resized = cv2.resize(image, (320, 568))
    hsv = cv2.cvtColor(resized, cv2.COLOR_BGR2HSV)
    lab = cv2.cvtColor(resized, cv2.COLOR_BGR2LAB)
    gray = cv2.cvtColor(resized, cv2.COLOR_BGR2GRAY)

    color_layout = cv2.resize(lab, (24, 32)).astype(np.float32).flatten() / 255.0
    gray_layout = cv2.resize(cv2.equalizeHist(gray), (24, 32)).astype(np.float32).flatten() / 255.0
    edge = cv2.Canny(gray, 80, 160)
    edge_layout = cv2.resize(edge, (24, 32)).astype(np.float32).flatten() / 255.0
    hsv_hist = _normalized_histogram(hsv, (12, 6, 6))

    feature = np.concatenate(
        [
            color_layout * 0.50,
            gray_layout * 0.35,
            edge_layout * 0.25,
            hsv_hist * 0.75,
        ]
    )
    return _l2_normalize(feature)


def _trace_id(request_id: str, key: str, elapsed_ms: int) -> str:
    return hashlib.sha1(f"{request_id}:{key}:{elapsed_ms}".encode("utf-8")).hexdigest()[:12]


def _torch_device_status(configured_device: str, *, import_if_needed: bool = False) -> dict[str, Any]:
    status: dict[str, Any] = {
        "configured_device": configured_device,
        "resolved_device": None,
        "torch_loaded": False,
        "cuda_available": False,
        "cuda_device_name": None,
        "torch_error": None,
    }
    torch = sys.modules.get("torch")
    if torch is None and import_if_needed:
        try:
            import torch as torch_module
        except Exception as exc:
            status["torch_error"] = str(exc)
            return status
        torch = torch_module
    if torch is None:
        status["resolved_device"] = "pending_model_load"
        return status

    status["torch_loaded"] = True
    cuda_available = bool(torch.cuda.is_available())
    status["cuda_available"] = cuda_available
    if cuda_available:
        status["cuda_device_name"] = torch.cuda.get_device_name(0)
    requested = (configured_device or "auto").strip().lower()
    status["resolved_device"] = "cuda" if requested == "auto" and cuda_available else "cpu" if requested == "auto" else requested
    return status


def _resolve_torch_device(configured_device: str) -> str:
    status = _torch_device_status(configured_device, import_if_needed=True)
    if status["torch_error"]:
        raise SceneRetrievalUnavailable(f"torch unavailable for scene retrieval: {status['torch_error']}")
    resolved = str(status["resolved_device"] or "cpu")
    if resolved.startswith("cuda") and not status["cuda_available"]:
        raise SceneRetrievalUnavailable("scene retrieval device cuda requested but CUDA is unavailable")
    return resolved


def _load_booth_coordinates(path: Path) -> dict[str, dict[str, Any]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    booths: dict[str, dict[str, Any]] = {}
    for item in payload.get("booths", []):
        booth_id = str(item.get("booth_id", "")).strip().upper()
        if booth_id:
            booths[booth_id] = item
    return booths


def _default_booth_coordinates_path(bundle: VenueBundle) -> Path:
    return bundle.root / "localization" / "booth_coordinates.json"


def scene_retrieval_backend_status(bundle: VenueBundle, config: SceneRetrievalConfig) -> dict[str, Any]:
    booth_path = config.booth_coordinates_path or _default_booth_coordinates_path(bundle)
    index_exists = bool(config.index_path and config.index_path.exists())
    metadata_exists = bool(config.metadata_path and config.metadata_path.exists())
    booth_exists = booth_path.exists()
    sample_count = None
    if metadata_exists and config.metadata_path:
        try:
            sample_count = sum(1 for line in config.metadata_path.read_text(encoding="utf-8").splitlines() if line.strip())
        except Exception:
            sample_count = None
    return {
        "available": index_exists and metadata_exists and booth_exists,
        "recognition_mode": "scene_retrieval",
        "feature_extractor": config.feature_extractor,
        "device": _torch_device_status(config.device),
        "index_path": str(config.index_path) if config.index_path else None,
        "index_exists": index_exists,
        "metadata_path": str(config.metadata_path) if config.metadata_path else None,
        "metadata_exists": metadata_exists,
        "booth_coordinates_path": str(booth_path),
        "booth_coordinates_exists": booth_exists,
        "sample_count": sample_count,
        "top_k": config.top_k,
        "min_score": config.min_score,
        "ok_score": config.ok_score,
        "temporal_filter_enabled": config.feature_extractor == "hybrid_v1",
        "temporal_min_votes": 3 if config.feature_extractor == "hybrid_v1" else None,
    }


def get_scene_retrieval_adapter(bundle: VenueBundle, config: SceneRetrievalConfig) -> "SceneRetrievalAdapter":
    booth_path = config.booth_coordinates_path or _default_booth_coordinates_path(bundle)
    key = (
        str(config.index_path),
        str(config.metadata_path),
        str(booth_path),
        config.feature_extractor,
        config.device,
    )
    adapter = _ADAPTER_CACHE.get(key)
    if adapter is None:
        adapter = SceneRetrievalAdapter(bundle=bundle, config=config, booth_coordinates_path=booth_path)
        _ADAPTER_CACHE[key] = adapter
    return adapter


class SceneRetrievalAdapter:
    def __init__(self, *, bundle: VenueBundle, config: SceneRetrievalConfig, booth_coordinates_path: Path) -> None:
        if config.index_path is None or not config.index_path.exists():
            raise SceneRetrievalUnavailable(f"scene retrieval index is missing: {config.index_path}")
        if config.metadata_path is None or not config.metadata_path.exists():
            raise SceneRetrievalUnavailable(f"scene retrieval metadata is missing: {config.metadata_path}")
        if not booth_coordinates_path.exists():
            raise SceneRetrievalUnavailable(f"booth coordinates are missing: {booth_coordinates_path}")
        self.bundle = bundle
        self.config = config
        self.booths = _load_booth_coordinates(booth_coordinates_path)
        with np.load(config.index_path) as payload:
            self.features = payload["features"].astype(np.float32)
        self.metadata = _read_jsonl(config.metadata_path)
        if self.features.shape[0] != len(self.metadata):
            raise SceneRetrievalUnavailable(
                f"scene retrieval index rows ({self.features.shape[0]}) do not match metadata rows ({len(self.metadata)})"
            )
        self.models: dict[str, SceneFeatureModel] = {}
        self.device: str | None = None
        self.temporal_votes: deque[SceneTemporalVote] = deque(maxlen=8)
        self.last_stable_vote: SceneTemporalVote | None = None

    def _model_device(self) -> str:
        if self.device is None:
            self.device = _resolve_torch_device(self.config.device)
        return self.device

    def _load_resnet50(self) -> SceneFeatureModel:
        import torch
        from torchvision import models

        device = self._model_device()
        weights = models.ResNet50_Weights.DEFAULT
        model = models.resnet50(weights=weights)
        extractor = torch.nn.Sequential(*(list(model.children())[:-1]))
        extractor.to(device).eval()
        return SceneFeatureModel("torchvision_resnet50", extractor, weights.transforms(), device)

    def _load_mobilenet_v3_small(self) -> SceneFeatureModel:
        import torch
        from torchvision import models

        device = self._model_device()
        weights = models.MobileNet_V3_Small_Weights.DEFAULT
        model = models.mobilenet_v3_small(weights=weights)
        extractor = torch.nn.Sequential(model.features, model.avgpool)
        extractor.to(device).eval()
        return SceneFeatureModel("torchvision_mobilenet_v3_small", extractor, weights.transforms(), device)

    def _load_dinov2(self) -> SceneFeatureModel:
        import torch
        from torchvision import transforms

        device = self._model_device()
        model = torch.hub.load("facebookresearch/dinov2", "dinov2_vits14")
        model.to(device).eval()
        transform = transforms.Compose(
            [
                transforms.Resize(256, interpolation=transforms.InterpolationMode.BICUBIC),
                transforms.CenterCrop(224),
                transforms.ToTensor(),
                transforms.Normalize(mean=(0.485, 0.456, 0.406), std=(0.229, 0.224, 0.225)),
            ]
        )
        return SceneFeatureModel("dinov2_vits14", model, transform, device)

    def _load_clip(self) -> SceneFeatureModel:
        import open_clip

        device = self._model_device()
        model, _, transform = open_clip.create_model_and_transforms(
            "ViT-B-32",
            pretrained="openai",
            cache_dir=os.environ.get("OPENCLIP_CACHE_DIR"),
        )
        model.to(device).eval()
        return SceneFeatureModel("open_clip_vit_b32_openai", model, transform, device)

    def _get_model(self, name: str) -> SceneFeatureModel:
        if name not in self.models:
            if name == "torchvision_resnet50":
                self.models[name] = self._load_resnet50()
            elif name == "torchvision_mobilenet_v3_small":
                self.models[name] = self._load_mobilenet_v3_small()
            elif name == "dinov2_vits14":
                self.models[name] = self._load_dinov2()
            elif name == "open_clip_vit_b32_openai":
                self.models[name] = self._load_clip()
            else:
                raise SceneRetrievalUnavailable(f"unsupported scene retrieval feature model: {name}")
        return self.models[name]

    def _extract_model_feature(self, image: Any, model_name: str) -> np.ndarray:
        import torch

        feature_model = self._get_model(model_name)
        tensor = feature_model.transform(image).unsqueeze(0).to(feature_model.device)
        with torch.no_grad():
            if feature_model.name == "open_clip_vit_b32_openai":
                output = feature_model.model.encode_image(tensor).flatten(1)
            else:
                output = feature_model.model(tensor).flatten(1)
        return _l2_normalize(output.cpu().numpy()[0].astype(np.float32))

    def extract_query_feature(self, image_bytes: bytes) -> np.ndarray:
        from io import BytesIO

        from PIL import Image

        try:
            image = Image.open(BytesIO(image_bytes)).convert("RGB")
        except Exception as exc:
            raise SceneRetrievalUnavailable("scene retrieval failed to decode image") from exc

        extractor = self.config.feature_extractor
        if extractor == "hybrid_v1":
            return _extract_hybrid_v1_from_bytes(image_bytes)
        if extractor == "torchvision_resnet50":
            return self._extract_model_feature(image, "torchvision_resnet50")
        if extractor == "torchvision_mobilenet_v3_small":
            return self._extract_model_feature(image, "torchvision_mobilenet_v3_small")
        if extractor == "dinov2_vits14":
            return self._extract_model_feature(image, "dinov2_vits14")
        if extractor == "open_clip_vit_b32_openai":
            return self._extract_model_feature(image, "open_clip_vit_b32_openai")

        parts: list[np.ndarray] = []
        if extractor in {"fusion_resnet50_clip_vitb32", "fusion_resnet50_clip_vitb32_dinov2"}:
            parts.append(self._extract_model_feature(image, "torchvision_resnet50"))
            parts.append(self._extract_model_feature(image, "open_clip_vit_b32_openai"))
            if extractor == "fusion_resnet50_clip_vitb32_dinov2":
                parts.append(self._extract_model_feature(image, "dinov2_vits14"))
            return _l2_normalize(np.concatenate(parts).astype(np.float32))
        raise SceneRetrievalUnavailable(f"unsupported scene retrieval feature_extractor: {extractor}")

    def retrieve(self, image_bytes: bytes) -> list[dict[str, Any]]:
        query_feature = self.extract_query_feature(image_bytes)
        if query_feature.shape[0] != self.features.shape[1]:
            raise SceneRetrievalUnavailable(
                f"query feature dim {query_feature.shape[0]} does not match index dim {self.features.shape[1]}"
            )
        scores = self.features @ query_feature
        top_k = max(1, min(self.config.top_k, scores.shape[0]))
        indices = np.argpartition(-scores, np.arange(top_k))[:top_k]
        indices = indices[np.argsort(-scores[indices])]
        matches = []
        for index in indices:
            metadata = self.metadata[int(index)]
            matches.append(
                {
                    "score": round(float(scores[int(index)]), 6),
                    "keyframe_id": metadata.get("keyframe_id"),
                    "booth_id": str(metadata.get("booth_id", "")).upper(),
                    "poi_id": metadata.get("poi_id"),
                    "position": metadata.get("position"),
                    "sample_image_ref": metadata.get("sample_image_ref"),
                    "timestamp_ms": metadata.get("timestamp_ms"),
                }
            )
        return matches

    def _matched_keyframes(self, matches: list[dict[str, Any]]) -> list[MatchedKeyframe]:
        return [MatchedKeyframe(keyframe_id=str(item["keyframe_id"]), score=float(item["score"])) for item in matches]

    def _build_landmark(self, match: dict[str, Any], *, candidate_count: int, ambiguous: bool) -> MatchedLandmark:
        booth_id = str(match["booth_id"]).upper()
        booth = self.booths.get(booth_id)
        return MatchedLandmark(
            landmark_id=f"scene_booth_{booth_id.lower()}",
            poi_id=match.get("poi_id") or f"poi_booth_{booth_id.lower()}",
            match_source="scene_retrieval",
            display_name=booth.get("display_name", f"{booth_id} booth") if booth else f"{booth_id} booth",
            score=float(match["score"]),
            candidate_count=candidate_count,
            ambiguous=ambiguous,
        )

    def _floor_and_position(self, match: dict[str, Any]) -> tuple[str | None, dict[str, float] | None]:
        booth_id = str(match["booth_id"]).upper()
        booth = self.booths.get(booth_id)
        position = booth.get("position") if booth else match.get("position")
        floor_id = booth.get("floor_id") if booth else self.bundle.venue.get("default_floor_id", "F1")
        return floor_id, position

    def _stable_vote(self, now_ms: int) -> tuple[SceneTemporalVote | None, dict[str, Any]]:
        window_ms = 2500
        recent_votes = [item for item in self.temporal_votes if now_ms - item.timestamp_ms <= window_ms]
        if not recent_votes:
            return None, {"reason": "no_recent_votes", "vote_count": 0}

        counts: dict[str, int] = {}
        score_sums: dict[str, float] = {}
        best_by_booth: dict[str, SceneTemporalVote] = {}
        for vote in recent_votes:
            counts[vote.booth_id] = counts.get(vote.booth_id, 0) + 1
            score_sums[vote.booth_id] = score_sums.get(vote.booth_id, 0.0) + vote.score
            if vote.booth_id not in best_by_booth or vote.score > best_by_booth[vote.booth_id].score:
                best_by_booth[vote.booth_id] = vote

        ranked = sorted(
            counts,
            key=lambda booth_id: (counts[booth_id], score_sums[booth_id]),
            reverse=True,
        )
        best_booth = ranked[0]
        best_count = counts[best_booth]
        second_count = counts[ranked[1]] if len(ranked) > 1 else 0
        details = {
            "window_ms": window_ms,
            "vote_count": len(recent_votes),
            "counts": counts,
            "score_sums": {booth_id: round(score, 4) for booth_id, score in score_sums.items()},
            "best_booth_id": best_booth,
            "best_count": best_count,
            "second_count": second_count,
        }
        if best_count < 3:
            return None, {**details, "reason": "not_enough_consistent_votes"}
        if second_count > 0 and best_count - second_count < 2:
            return None, {**details, "reason": "vote_margin_too_small"}
        return best_by_booth[best_booth], details

    def _localize_hybrid_with_temporal_filter(
        self,
        *,
        request_id: str,
        venue_id: str,
        matches: list[dict[str, Any]],
        candidate_floor_id: str | None,
        target_poi_id: str | None,
        started_at: float,
    ) -> LocalizationData:
        now_ms = int(time.time() * 1000)
        best = matches[0]
        score = float(best["score"])
        if score >= self.config.min_score and best.get("booth_id"):
            self.temporal_votes.append(
                SceneTemporalVote(
                    booth_id=str(best["booth_id"]).upper(),
                    score=score,
                    match=best,
                    timestamp_ms=now_ms,
                )
            )

        stable_vote, vote_details = self._stable_vote(now_ms)
        matched_keyframes = self._matched_keyframes(matches)
        latency_ms = int((time.perf_counter() - started_at) * 1000)
        if stable_vote is None:
            held_vote = self.last_stable_vote
            if held_vote and now_ms - held_vote.timestamp_ms <= 3500:
                floor_id, position = self._floor_and_position(held_vote.match)
                return LocalizationData(
                    request_id=request_id,
                    status="low_confidence",
                    venue_id=venue_id,
                    floor_id=floor_id,
                    position=position,
                    confidence=0.45,
                    matched_landmark=self._build_landmark(
                        held_vote.match,
                        candidate_count=len(vote_details.get("counts", {})),
                        ambiguous=True,
                    ),
                    uncertainty_m=6.0,
                    matched_keyframe_id=str(held_vote.match["keyframe_id"]),
                    matched_keyframes=matched_keyframes,
                    failure_stage="scene_retrieval_temporal_hold",
                    next_capture_hint="move_or_capture_more_frames",
                    suggested_action="request_more_images",
                    trace_id=_trace_id(request_id, "scene_retrieval_temporal_hold", latency_ms),
                    recognition_mode="scene_retrieval",
                    message=f"holding last stable booth while waiting for consistent frames: {vote_details}",
                    latency_ms=latency_ms,
                )
            return LocalizationData(
                request_id=request_id,
                status="low_confidence",
                venue_id=venue_id,
                confidence=0.0,
                matched_keyframes=matched_keyframes,
                failure_stage="scene_retrieval_temporal_unstable",
                next_capture_hint="move_or_capture_more_frames",
                suggested_action="request_more_images",
                trace_id=_trace_id(request_id, "scene_retrieval_temporal_unstable", latency_ms),
                recognition_mode="scene_retrieval",
                message=f"waiting for consistent multi-frame scene match: {vote_details}",
                latency_ms=latency_ms,
            )

        floor_id, position = self._floor_and_position(stable_vote.match)
        if candidate_floor_id and floor_id and candidate_floor_id != floor_id:
            return LocalizationData(
                request_id=request_id,
                status="low_confidence",
                venue_id=venue_id,
                floor_id=floor_id,
                position=position,
                confidence=0.45,
                matched_landmark=self._build_landmark(stable_vote.match, candidate_count=1, ambiguous=True),
                uncertainty_m=6.0,
                matched_keyframe_id=str(stable_vote.match["keyframe_id"]),
                matched_keyframes=matched_keyframes,
                failure_stage="scene_retrieval_floor_conflict",
                next_capture_hint="move_or_capture_more_frames",
                suggested_action="request_more_images",
                trace_id=_trace_id(request_id, "scene_retrieval_floor_conflict", latency_ms),
                recognition_mode="scene_retrieval",
                message="stable scene match conflicts with candidate_floor_id",
                latency_ms=latency_ms,
            )
        if target_poi_id and target_poi_id != stable_vote.match.get("poi_id"):
            failure_stage = "target_prior_mismatch"
            status = "low_confidence"
            confidence = 0.55
        else:
            failure_stage = None
            status = "ok"
            confidence = min(0.92, 0.55 + 0.1 * min(int(vote_details.get("best_count", 3)), 4))

        stable_vote = SceneTemporalVote(
            booth_id=stable_vote.booth_id,
            score=stable_vote.score,
            match=stable_vote.match,
            timestamp_ms=now_ms,
        )
        self.last_stable_vote = stable_vote
        return LocalizationData(
            request_id=request_id,
            status=status,
            venue_id=venue_id,
            floor_id=floor_id,
            position=position,
            confidence=round(confidence, 4),
            matched_landmark=self._build_landmark(
                stable_vote.match,
                candidate_count=len(vote_details.get("counts", {})),
                ambiguous=False,
            ),
            uncertainty_m=3.0 if status == "ok" else 6.0,
            matched_keyframe_id=str(stable_vote.match["keyframe_id"]),
            matched_keyframes=matched_keyframes,
            failure_stage=failure_stage,
            next_capture_hint="normal" if status == "ok" else "move_or_capture_more_frames",
            suggested_action="continue_navigation" if status == "ok" else "request_more_images",
            trace_id=_trace_id(request_id, str(stable_vote.match["keyframe_id"]), latency_ms),
            recognition_mode="scene_retrieval",
            message=f"scene retrieval localized by temporal vote: {vote_details}",
            latency_ms=latency_ms,
        )

    def localize(
        self,
        *,
        request_id: str,
        venue_id: str,
        image_bytes: bytes,
        candidate_floor_id: str | None,
        target_poi_id: str | None,
    ) -> LocalizationData:
        started_at = time.perf_counter()
        if not _INFERENCE_LOCK.acquire(blocking=False):
            latency_ms = int((time.perf_counter() - started_at) * 1000)
            return LocalizationData(
                request_id=request_id,
                status="low_confidence",
                venue_id=venue_id,
                confidence=0.0,
                failure_stage="scene_retrieval_busy",
                suggested_action="retry_next_frame",
                trace_id=_trace_id(request_id, "scene_retrieval_busy", latency_ms),
                recognition_mode="scene_retrieval",
                message="scene retrieval is busy; dropped this frame for realtime processing",
                latency_ms=latency_ms,
            )
        try:
            matches = self.retrieve(image_bytes)
        except SceneRetrievalUnavailable as exc:
            latency_ms = int((time.perf_counter() - started_at) * 1000)
            return LocalizationData(
                request_id=request_id,
                status="error",
                venue_id=venue_id,
                confidence=0.0,
                failure_stage="scene_retrieval_unavailable",
                suggested_action="check_scene_retrieval_assets",
                trace_id=_trace_id(request_id, "scene_retrieval_unavailable", latency_ms),
                recognition_mode="scene_retrieval",
                message=str(exc),
                latency_ms=latency_ms,
            )
        finally:
            _INFERENCE_LOCK.release()

        if not matches:
            latency_ms = int((time.perf_counter() - started_at) * 1000)
            return LocalizationData(
                request_id=request_id,
                status="not_found",
                venue_id=venue_id,
                confidence=0.0,
                failure_stage="scene_retrieval_no_match",
                suggested_action="retry_after_move",
                trace_id=_trace_id(request_id, "scene_retrieval_no_match", latency_ms),
                recognition_mode="scene_retrieval",
                message="scene retrieval returned no match",
                latency_ms=latency_ms,
            )

        if self.config.feature_extractor == "hybrid_v1":
            return self._localize_hybrid_with_temporal_filter(
                request_id=request_id,
                venue_id=venue_id,
                matches=matches,
                candidate_floor_id=candidate_floor_id,
                target_poi_id=target_poi_id,
                started_at=started_at,
            )

        best = matches[0]
        booth_id = best["booth_id"]
        booth = self.booths.get(booth_id)
        position = booth.get("position") if booth else best.get("position")
        floor_id = booth.get("floor_id") if booth else self.bundle.venue.get("default_floor_id", "F1")
        score = float(best["score"])
        unique_booths = {item["booth_id"] for item in matches if item.get("booth_id")}
        top_same_booth_count = sum(1 for item in matches if item.get("booth_id") == booth_id)
        status = "ok" if score >= self.config.ok_score and top_same_booth_count >= 2 else "low_confidence"
        failure_stage = None
        if score < self.config.min_score:
            status = "not_found"
            failure_stage = "scene_retrieval_low_score"
        elif candidate_floor_id and floor_id and candidate_floor_id != floor_id:
            status = "low_confidence"
            failure_stage = "scene_retrieval_floor_conflict"
        elif target_poi_id and target_poi_id != best.get("poi_id"):
            failure_stage = "target_prior_mismatch"

        latency_ms = int((time.perf_counter() - started_at) * 1000)
        matched_keyframes = self._matched_keyframes(matches)
        matched_landmark = None
        if status != "not_found":
            matched_landmark = self._build_landmark(
                best,
                candidate_count=len(unique_booths),
                ambiguous=top_same_booth_count < 2 or len(unique_booths) > 2,
            )
        confidence = max(0.0, min(0.99, 0.25 + (score - self.config.min_score) / 0.2 * 0.7))
        return LocalizationData(
            request_id=request_id,
            status=status,
            venue_id=venue_id,
            floor_id=floor_id if status != "not_found" else None,
            position=position if status != "not_found" else None,
            confidence=round(confidence, 4) if status != "not_found" else 0.0,
            matched_landmark=matched_landmark,
            uncertainty_m=2.0 if status == "ok" else 5.0,
            matched_keyframe_id=str(best["keyframe_id"]) if status != "not_found" else None,
            matched_keyframes=matched_keyframes,
            failure_stage=failure_stage,
            next_capture_hint="normal" if status == "ok" else "move_or_capture_more_frames",
            suggested_action="continue_navigation" if status == "ok" else "request_more_images",
            trace_id=_trace_id(request_id, str(best["keyframe_id"]), latency_ms),
            recognition_mode="scene_retrieval",
            message="scene retrieval localized" if status == "ok" else "scene retrieval needs more frames",
            latency_ms=latency_ms,
        )


def localize_scene_retrieval(
    *,
    bundle: VenueBundle,
    request_id: str,
    venue_id: str,
    image_bytes: bytes,
    candidate_floor_id: str | None,
    target_poi_id: str | None,
    config: SceneRetrievalConfig,
) -> LocalizationData:
    adapter = get_scene_retrieval_adapter(bundle, config)
    return adapter.localize(
        request_id=request_id,
        venue_id=venue_id,
        image_bytes=image_bytes,
        candidate_floor_id=candidate_floor_id,
        target_poi_id=target_poi_id,
    )


def explain_scene_retrieval(result: LocalizationData) -> list[dict[str, Any]]:
    return [
        {
            "stage": "scene_retrieval",
            "status": result.status,
            "details": {
                "matched_keyframe_id": result.matched_keyframe_id,
                "matched_keyframes": [item.model_dump() for item in result.matched_keyframes],
                "matched_landmark": result.matched_landmark.model_dump() if result.matched_landmark else None,
                "confidence": result.confidence,
                "failure_stage": result.failure_stage,
                "message": result.message,
                "latency_ms": result.latency_ms,
            },
        },
        {
            "stage": "final_result",
            "status": result.status,
            "details": {
                "floor_id": result.floor_id,
                "position": result.position,
                "confidence": result.confidence,
                "failure_stage": result.failure_stage,
            },
        },
    ]
