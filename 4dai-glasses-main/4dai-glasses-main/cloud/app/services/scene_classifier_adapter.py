from __future__ import annotations

import hashlib
import importlib.util
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from cloud.app.models.api import LocalizationData, MatchedKeyframe, MatchedLandmark
from cloud.app.services.scene_retrieval_adapter import _load_booth_coordinates, _resolve_torch_device
from cloud.app.services.venue_package import VenueBundle


@dataclass(frozen=True)
class SceneClassifierConfig:
    checkpoint_path: Path | None
    booth_coordinates_path: Path | None
    device: str = "auto"
    min_confidence: float = 0.55
    ok_confidence: float = 0.8


class SceneClassifierUnavailable(RuntimeError):
    pass


_CLASSIFIER_CACHE: dict[tuple[str, str, str], "SceneClassifierAdapter"] = {}


def _trace_id(request_id: str, key: str, elapsed_ms: int) -> str:
    return hashlib.sha1(f"{request_id}:{key}:{elapsed_ms}".encode("utf-8")).hexdigest()[:12]


def _default_booth_coordinates_path(bundle: VenueBundle) -> Path:
    return bundle.root / "localization" / "booth_coordinates.json"


def scene_classifier_backend_status(bundle: VenueBundle, config: SceneClassifierConfig) -> dict[str, Any]:
    booth_path = config.booth_coordinates_path or _default_booth_coordinates_path(bundle)
    checkpoint_exists = bool(config.checkpoint_path and config.checkpoint_path.exists())
    return {
        "available": checkpoint_exists and booth_path.exists() and importlib.util.find_spec("torch") is not None,
        "recognition_mode": "scene_classifier",
        "model": "mobilenet_v3_small_or_large",
        "checkpoint_path": str(config.checkpoint_path) if config.checkpoint_path else None,
        "checkpoint_exists": checkpoint_exists,
        "booth_coordinates_path": str(booth_path),
        "booth_coordinates_exists": booth_path.exists(),
        "device": config.device,
        "min_confidence": config.min_confidence,
        "ok_confidence": config.ok_confidence,
    }


def get_scene_classifier_adapter(bundle: VenueBundle, config: SceneClassifierConfig) -> "SceneClassifierAdapter":
    booth_path = config.booth_coordinates_path or _default_booth_coordinates_path(bundle)
    key = (str(config.checkpoint_path), str(booth_path), config.device)
    adapter = _CLASSIFIER_CACHE.get(key)
    if adapter is None:
        adapter = SceneClassifierAdapter(bundle=bundle, config=config, booth_coordinates_path=booth_path)
        _CLASSIFIER_CACHE[key] = adapter
    return adapter


class SceneClassifierAdapter:
    def __init__(self, *, bundle: VenueBundle, config: SceneClassifierConfig, booth_coordinates_path: Path) -> None:
        if config.checkpoint_path is None or not config.checkpoint_path.exists():
            raise SceneClassifierUnavailable(f"scene classifier checkpoint is missing: {config.checkpoint_path}")
        if not booth_coordinates_path.exists():
            raise SceneClassifierUnavailable(f"booth coordinates are missing: {booth_coordinates_path}")
        self.bundle = bundle
        self.config = config
        self.booths = _load_booth_coordinates(booth_coordinates_path)
        self.device = _resolve_torch_device(config.device)
        self.torch, self.model, self.transform, self.classes = self._load_model(config.checkpoint_path)
        self._mean = None
        self._std = None

    def _load_model(self, checkpoint_path: Path):
        import torch
        from torchvision import models
        from torchvision import transforms

        try:
            checkpoint = torch.load(checkpoint_path, map_location="cpu", weights_only=False)
        except TypeError:
            checkpoint = torch.load(checkpoint_path, map_location="cpu")
        classes = [str(item).upper() for item in checkpoint["classes"]]
        model_arch = checkpoint.get("model", "mobilenet_v3_small")
        if model_arch == "mobilenet_v3_large":
            model = models.mobilenet_v3_large(weights=None)
        elif model_arch == "mobilenet_v3_small":
            model = models.mobilenet_v3_small(weights=None)
        else:
            raise SceneClassifierUnavailable(f"unsupported scene classifier model: {model_arch}")
        in_features = model.classifier[-1].in_features
        model.classifier[-1] = torch.nn.Linear(in_features, len(classes))
        model.load_state_dict(checkpoint["state_dict"])
        model.to(self.device).eval()
        if self.device.startswith("cuda"):
            torch.backends.cudnn.benchmark = True
        transform = transforms.Compose(
            [
                transforms.Resize((224, 224)),
                transforms.ToTensor(),
                transforms.Normalize(mean=(0.485, 0.456, 0.406), std=(0.229, 0.224, 0.225)),
            ]
        )
        return torch, model, transform, classes

    def _tensor_from_bytes(self, image_bytes: bytes):
        try:
            import cv2
            import numpy as np

            array = np.frombuffer(image_bytes, dtype=np.uint8)
            image = cv2.imdecode(array, cv2.IMREAD_COLOR)
            if image is None:
                raise ValueError("image_decode_failed")
            image = cv2.resize(image, (224, 224), interpolation=cv2.INTER_AREA)
            image = cv2.cvtColor(image, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
            if self._mean is None:
                self._mean = np.array((0.485, 0.456, 0.406), dtype=np.float32)
                self._std = np.array((0.229, 0.224, 0.225), dtype=np.float32)
            image = (image - self._mean) / self._std
            image = np.ascontiguousarray(image.transpose(2, 0, 1))
            return self.torch.from_numpy(image).unsqueeze(0).to(self.device)
        except Exception:
            from io import BytesIO

            from PIL import Image

            image = Image.open(BytesIO(image_bytes)).convert("RGB")
            return self.transform(image).unsqueeze(0).to(self.device)

    def _predict(self, image_bytes: bytes) -> list[dict[str, Any]]:
        try:
            tensor = self._tensor_from_bytes(image_bytes)
        except Exception as exc:
            raise SceneClassifierUnavailable("scene classifier failed to decode image") from exc
        with self.torch.inference_mode():
            logits = self.model(tensor)
            probabilities = self.torch.nn.functional.softmax(logits, dim=1)
            values, indices = self.torch.topk(probabilities, k=min(5, probabilities.shape[1]), dim=1)
        predictions = []
        for score, index in zip(values[0].detach().cpu().tolist(), indices[0].detach().cpu().tolist()):
            predictions.append({"booth_id": self.classes[int(index)], "score": round(float(score), 6)})
        return predictions

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
        try:
            predictions = self._predict(image_bytes)
        except SceneClassifierUnavailable as exc:
            latency_ms = int((time.perf_counter() - started_at) * 1000)
            return LocalizationData(
                request_id=request_id,
                status="error",
                venue_id=venue_id,
                confidence=0.0,
                failure_stage="scene_classifier_unavailable",
                suggested_action="check_scene_classifier_assets",
                trace_id=_trace_id(request_id, "scene_classifier_unavailable", latency_ms),
                recognition_mode="scene_classifier",
                message=str(exc),
                latency_ms=latency_ms,
            )
        latency_ms = int((time.perf_counter() - started_at) * 1000)
        if not predictions:
            return LocalizationData(
                request_id=request_id,
                status="not_found",
                venue_id=venue_id,
                confidence=0.0,
                failure_stage="scene_classifier_no_prediction",
                suggested_action="retry_after_move",
                trace_id=_trace_id(request_id, "scene_classifier_no_prediction", latency_ms),
                recognition_mode="scene_classifier",
                message="scene classifier returned no prediction",
                latency_ms=latency_ms,
            )
        best = predictions[0]
        booth_id = best["booth_id"]
        score = float(best["score"])
        booth = self.booths.get(booth_id)
        floor_id = booth.get("floor_id") if booth else self.bundle.venue.get("default_floor_id", "F1")
        position = booth.get("position") if booth else None
        poi_id = booth.get("poi_id") if booth else f"poi_booth_{booth_id.lower()}"
        status = "ok" if score >= self.config.ok_confidence else "low_confidence"
        failure_stage = None
        if score < self.config.min_confidence:
            status = "not_found"
            failure_stage = "scene_classifier_low_confidence"
        elif candidate_floor_id and floor_id and candidate_floor_id != floor_id:
            status = "low_confidence"
            failure_stage = "scene_classifier_floor_conflict"
        elif target_poi_id and target_poi_id != poi_id:
            status = "low_confidence"
            failure_stage = "target_prior_mismatch"
        matched_landmark = None
        if status != "not_found":
            matched_landmark = MatchedLandmark(
                landmark_id=f"scene_classifier_booth_{booth_id.lower()}",
                poi_id=poi_id,
                match_source="scene_classifier",
                display_name=booth.get("display_name", f"{booth_id} booth") if booth else f"{booth_id} booth",
                score=score,
                candidate_count=len(predictions),
                ambiguous=status != "ok",
            )
        matched_keyframes = [
            MatchedKeyframe(keyframe_id=f"scene_classifier_{item['booth_id'].lower()}", score=float(item["score"]))
            for item in predictions
        ]
        return LocalizationData(
            request_id=request_id,
            status=status,
            venue_id=venue_id,
            floor_id=floor_id if status != "not_found" else None,
            position=position if status != "not_found" else None,
            confidence=round(score if status == "ok" else min(score, 0.6), 4),
            matched_landmark=matched_landmark,
            uncertainty_m=2.0 if status == "ok" else 6.0,
            matched_keyframe_id=matched_keyframes[0].keyframe_id if status != "not_found" else None,
            matched_keyframes=matched_keyframes,
            failure_stage=failure_stage,
            next_capture_hint="normal" if status == "ok" else "move_or_capture_more_frames",
            suggested_action="continue_navigation" if status == "ok" else "request_more_images",
            trace_id=_trace_id(request_id, booth_id, latency_ms),
            recognition_mode="scene_classifier",
            message="scene classifier localized" if status == "ok" else "scene classifier needs more frames",
            latency_ms=latency_ms,
        )

    def warm_up(self) -> None:
        dummy = self.torch.zeros((1, 3, 224, 224), device=self.device)
        with self.torch.inference_mode():
            _ = self.model(dummy)
        if self.device.startswith("cuda"):
            self.torch.cuda.synchronize()


def localize_scene_classifier(
    *,
    bundle: VenueBundle,
    request_id: str,
    venue_id: str,
    image_bytes: bytes,
    candidate_floor_id: str | None,
    target_poi_id: str | None,
    config: SceneClassifierConfig,
) -> LocalizationData:
    adapter = get_scene_classifier_adapter(bundle, config)
    return adapter.localize(
        request_id=request_id,
        venue_id=venue_id,
        image_bytes=image_bytes,
        candidate_floor_id=candidate_floor_id,
        target_poi_id=target_poi_id,
    )


def warm_scene_classifier(*, bundle: VenueBundle, config: SceneClassifierConfig) -> None:
    adapter = get_scene_classifier_adapter(bundle, config)
    adapter.warm_up()


def explain_scene_classifier(result: LocalizationData) -> list[dict[str, Any]]:
    return [
        {
            "stage": "scene_classifier",
            "status": result.status,
            "details": {
                "matched_landmark_id": result.matched_landmark.landmark_id if result.matched_landmark else None,
                "matched_keyframes": [item.model_dump() for item in result.matched_keyframes],
                "confidence": result.confidence,
                "failure_stage": result.failure_stage,
                "latency_ms": result.latency_ms,
            },
        }
    ]
