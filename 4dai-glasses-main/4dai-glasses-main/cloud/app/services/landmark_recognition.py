from __future__ import annotations

import hashlib
import json
import re
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np

from cloud.app.models.api import HeadingHint, LocalizationData, MatchedLandmark, RouteSnap
from cloud.app.services.real_ocr_adapter import (
    RealOcrCandidate,
    RealOcrResult,
    get_real_ocr_debug,
    real_ocr_backend_status,
    remember_real_ocr_debug,
    run_real_ocr_adapter,
)
from cloud.app.services.venue_package import VenueBundle


SUPPORTED_RECOGNITION_MODES = {
    "baseline",
    "mock",
    "template",
    "real_ocr_adapter",
    "scene_retrieval",
    "scene_classifier",
}
DEBUG_TARGET_ALIASES = {
    "b10": "lm_booth_b10_card",
    "boothb10": "lm_booth_b10_card",
    "展台b10": "lm_booth_b10_card",
    "b10展台": "lm_booth_b10_card",
    "b17": "lm_booth_b17_card",
    "boothb17": "lm_booth_b17_card",
    "展台b17": "lm_booth_b17_card",
    "b17展台": "lm_booth_b17_card",
    "toilet": "lm_toilet_f1_sign",
    "restroom": "lm_toilet_f1_sign",
    "wc": "lm_toilet_f1_sign",
    "厕所": "lm_toilet_f1_sign",
    "洗手间": "lm_toilet_f1_sign",
    "hall": "lm_hall_main_sign",
    "mainhall": "lm_hall_main_sign",
    "报告厅": "lm_hall_main_sign",
    "主报告厅": "lm_hall_main_sign",
}
COLOR_TARGETS = {
    "lm_booth_b10_card": {"ranges": [((95, 50, 40), (130, 255, 255))], "label": "blue"},
    "lm_booth_b17_card": {"ranges": [((35, 50, 40), (85, 255, 255))], "label": "green"},
    "lm_toilet_f1_sign": {"ranges": [((10, 50, 60), (34, 255, 255))], "label": "orange"},
    "lm_hall_main_sign": {"ranges": [((135, 40, 40), (169, 255, 255))], "label": "purple"},
}


@dataclass(frozen=True)
class LandmarkHit:
    landmark: dict[str, Any]
    confidence: float
    match_source: str
    candidate_count: int
    ambiguous: bool = False
    failure_stage: str | None = None


def normalize_token(value: str | None) -> str:
    if not value:
        return ""
    value = value.strip().lower()
    value = re.sub(r"[\s_\-:/\\.,，。()（）]+", "", value)
    return value


def load_landmarks(bundle: VenueBundle) -> list[dict[str, Any]]:
    path = bundle.root / "landmarks.json"
    if not path.exists():
        return []
    payload = json.loads(path.read_text(encoding="utf-8"))
    landmarks = payload.get("landmarks", [])
    if not isinstance(landmarks, list):
        return []
    return [item for item in landmarks if isinstance(item, dict)]


def landmark_backend_status(bundle: VenueBundle, recognition_mode: str) -> dict[str, Any]:
    landmarks = load_landmarks(bundle)
    real_ocr_status = real_ocr_backend_status(Path(__file__).resolve().parents[3])
    return {
        "recognition_mode": recognition_mode,
        "supported_modes": sorted(SUPPORTED_RECOGNITION_MODES),
        "landmark_count": len(landmarks),
        "template_color_cards": sorted(COLOR_TARGETS),
        "cv2_available": _cv2_available(),
        "real_ocr_adapter_available": real_ocr_status["available"],
        "real_ocr_adapter": real_ocr_status,
    }


def _cv2_available() -> bool:
    try:
        import cv2  # noqa: F401
    except Exception:
        return False
    return True


def _alias_index(landmarks: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    index: dict[str, dict[str, Any]] = {}
    for landmark in landmarks:
        values = [
            landmark.get("landmark_id"),
            landmark.get("poi_id"),
            landmark.get("display_name"),
            *(landmark.get("aliases") or []),
        ]
        for value in values:
            token = normalize_token(str(value) if value is not None else "")
            if token:
                index[token] = landmark
    return index


def _landmark_by_id(landmarks: list[dict[str, Any]], landmark_id: str) -> dict[str, Any] | None:
    return next((item for item in landmarks if item.get("landmark_id") == landmark_id), None)


def _poi_by_id(bundle: VenueBundle, poi_id: str | None) -> dict[str, Any] | None:
    if not poi_id:
        return None
    return next((item for item in bundle.pois if item.get("poi_id") == poi_id), None)


def _route_node_by_id(bundle: VenueBundle, node_id: str | None) -> dict[str, Any] | None:
    if not node_id:
        return None
    return next((item for item in bundle.route_graph.get("nodes", []) if item.get("node_id") == node_id), None)


def _position_for_landmark(bundle: VenueBundle, landmark: dict[str, Any]) -> dict[str, float] | None:
    poi = _poi_by_id(bundle, landmark.get("poi_id"))
    if poi and isinstance(poi.get("position"), dict):
        return {"x": float(poi["position"]["x"]), "y": float(poi["position"]["y"])}
    node = _route_node_by_id(bundle, landmark.get("route_node_id"))
    if node:
        return {"x": float(node["x"]), "y": float(node["y"])}
    return None


def _floor_for_landmark(bundle: VenueBundle, landmark: dict[str, Any]) -> str | None:
    if isinstance(landmark.get("floor_id"), str):
        return landmark["floor_id"]
    poi = _poi_by_id(bundle, landmark.get("poi_id"))
    if poi:
        return poi.get("floor_id")
    node = _route_node_by_id(bundle, landmark.get("route_node_id"))
    if node:
        return node.get("floor_id")
    return None


def _match_debug_or_filename(
    *,
    landmarks: list[dict[str, Any]],
    debug_target: str | None,
    image_filename: str | None,
) -> LandmarkHit | None:
    alias_index = _alias_index(landmarks)
    probes = [debug_target, Path(image_filename or "").stem]
    for raw in probes:
        token = normalize_token(raw)
        if not token:
            continue
        if token in DEBUG_TARGET_ALIASES:
            landmark = _landmark_by_id(landmarks, DEBUG_TARGET_ALIASES[token])
            if landmark:
                return LandmarkHit(landmark, 0.94, "debug_target" if raw == debug_target else "filename", 1)
        if token in alias_index:
            return LandmarkHit(alias_index[token], 0.92, "debug_target" if raw == debug_target else "filename", 1)
        for alias, landmark in alias_index.items():
            if alias and (alias in token or token in alias):
                return LandmarkHit(landmark, 0.88, "debug_target" if raw == debug_target else "filename", 1)
    return None


def _decode_hsv(image_bytes: bytes):
    try:
        import cv2
    except Exception:
        return None
    array = np.frombuffer(image_bytes, dtype=np.uint8)
    if array.size == 0:
        return None
    decoded = cv2.imdecode(array, cv2.IMREAD_COLOR)
    if decoded is None:
        return None
    return cv2.cvtColor(decoded, cv2.COLOR_BGR2HSV)


def _match_color_template(landmarks: list[dict[str, Any]], image_bytes: bytes) -> LandmarkHit | None:
    hsv = _decode_hsv(image_bytes)
    if hsv is None:
        return None
    try:
        import cv2
    except Exception:
        return None
    total_pixels = max(int(hsv.shape[0] * hsv.shape[1]), 1)
    scores: list[tuple[str, float]] = []
    for landmark_id, config in COLOR_TARGETS.items():
        mask = None
        for lower, upper in config["ranges"]:
            current = cv2.inRange(hsv, np.array(lower, dtype=np.uint8), np.array(upper, dtype=np.uint8))
            mask = current if mask is None else cv2.bitwise_or(mask, current)
        ratio = float(cv2.countNonZero(mask)) / total_pixels if mask is not None else 0.0
        scores.append((landmark_id, ratio))
    scores.sort(key=lambda item: item[1], reverse=True)
    best_id, best_ratio = scores[0]
    second_ratio = scores[1][1] if len(scores) > 1 else 0.0
    if best_ratio < 0.035:
        return None
    landmark = _landmark_by_id(landmarks, best_id)
    if not landmark:
        return None
    ambiguous = second_ratio > 0.0 and (best_ratio - second_ratio) < 0.025
    confidence = min(0.9, 0.62 + best_ratio * 1.8)
    if ambiguous:
        confidence = min(confidence, 0.68)
    return LandmarkHit(
        landmark=landmark,
        confidence=round(confidence, 4),
        match_source="color_template",
        candidate_count=sum(1 for _, score in scores if score >= 0.035),
        ambiguous=ambiguous,
        failure_stage="landmark_ambiguous" if ambiguous else None,
    )


def _candidate_match_tokens(candidate: RealOcrCandidate) -> list[str]:
    label = candidate.label
    stripped = re.sub(r"^logo[_\-\s]*", "", label, flags=re.IGNORECASE)
    return [
        label,
        stripped,
        label.replace("_", " "),
        stripped.replace("_", " "),
        label.replace("_", ""),
        stripped.replace("_", ""),
        f"class_{candidate.class_index:03d}",
    ]


def _match_real_ocr_candidates(
    *,
    landmarks: list[dict[str, Any]],
    ocr_result: RealOcrResult,
) -> LandmarkHit | None:
    if not ocr_result.candidates:
        return None
    alias_index = _alias_index(landmarks)
    for candidate in ocr_result.candidates:
        for raw_token in _candidate_match_tokens(candidate):
            token = normalize_token(raw_token)
            if not token:
                continue
            if token in alias_index:
                return LandmarkHit(
                    alias_index[token],
                    candidate.score,
                    "real_ocr_adapter",
                    len(ocr_result.candidates),
                    ambiguous=_real_ocr_is_ambiguous(ocr_result.candidates),
                )
            for alias, landmark in alias_index.items():
                if alias and (alias in token or token in alias):
                    return LandmarkHit(
                        landmark,
                        candidate.score,
                        "real_ocr_adapter",
                        len(ocr_result.candidates),
                        ambiguous=_real_ocr_is_ambiguous(ocr_result.candidates),
                    )
    return None


def _real_ocr_is_ambiguous(candidates: list[RealOcrCandidate]) -> bool:
    if len(candidates) < 2:
        return False
    return (candidates[0].score - candidates[1].score) < 0.08


def _real_ocr_candidate_details(ocr_result: RealOcrResult) -> dict[str, Any]:
    return {
        **ocr_result.details,
        "candidate_count": len(ocr_result.candidates),
        "candidates": [
            {
                "label": item.label,
                "class_index": item.class_index,
                "score": item.score,
                "detection_confidence": item.detection_confidence,
                "classification_confidence": item.classification_confidence,
                "bbox": list(item.bbox),
            }
            for item in ocr_result.candidates
        ],
    }


def _color_template_score_summary(image_bytes: bytes) -> dict[str, Any]:
    hsv = _decode_hsv(image_bytes)
    if hsv is None:
        return {"cv2_available": _cv2_available(), "decoded": False, "scores": []}
    try:
        import cv2
    except Exception:
        return {"cv2_available": False, "decoded": True, "scores": []}
    total_pixels = max(int(hsv.shape[0] * hsv.shape[1]), 1)
    scores = []
    for landmark_id, config in COLOR_TARGETS.items():
        mask = None
        for lower, upper in config["ranges"]:
            current = cv2.inRange(hsv, np.array(lower, dtype=np.uint8), np.array(upper, dtype=np.uint8))
            mask = current if mask is None else cv2.bitwise_or(mask, current)
        ratio = float(cv2.countNonZero(mask)) / total_pixels if mask is not None else 0.0
        scores.append(
            {
                "landmark_id": landmark_id,
                "color_label": config["label"],
                "ratio": round(ratio, 6),
            }
        )
    scores.sort(key=lambda item: item["ratio"], reverse=True)
    return {"cv2_available": True, "decoded": True, "scores": scores}


def explain_landmark_recognition(
    *,
    bundle: VenueBundle,
    recognition_mode: str,
    image_bytes: bytes,
    image_filename: str | None,
    debug_target: str | None,
    candidate_floor_id: str | None,
    target_poi_id: str | None,
    result: LocalizationData,
) -> list[dict[str, Any]]:
    landmarks = load_landmarks(bundle)
    mode = recognition_mode if recognition_mode in SUPPORTED_RECOGNITION_MODES else "mock"
    stages: list[dict[str, Any]] = [
        {
            "stage": "landmark_catalog",
            "status": "ok" if landmarks else "error",
            "details": {
                "landmark_count": len(landmarks),
                "mode": mode,
            },
        }
    ]

    filename_probe = None if mode == "real_ocr_adapter" else image_filename
    probes = [
        {"source": "debug_target", "raw": debug_target, "normalized": normalize_token(debug_target)},
        {"source": "filename", "raw": filename_probe, "normalized": normalize_token(Path(filename_probe or "").stem)},
    ]
    debug_hit = _match_debug_or_filename(
        landmarks=landmarks,
        debug_target=debug_target,
        image_filename=filename_probe,
    )
    stages.append(
        {
            "stage": "debug_or_filename_match",
            "status": "ok" if debug_hit else "not_found",
            "details": {
                "probes": probes,
                "matched_landmark_id": debug_hit.landmark.get("landmark_id") if debug_hit else None,
                "match_source": debug_hit.match_source if debug_hit else None,
                "confidence": debug_hit.confidence if debug_hit else None,
            },
        }
    )

    if mode == "template":
        if debug_hit:
            stages.append(
                {
                    "stage": "template_color_match",
                    "status": "skipped",
                    "details": {"reason": "debug_or_filename_match already returned a landmark"},
                }
            )
        else:
            summary = _color_template_score_summary(image_bytes)
            top_score = summary["scores"][0] if summary["scores"] else None
            template_matched = (
                result.matched_landmark is not None
                and result.matched_landmark.match_source == "color_template"
            )
            stages.append(
                {
                    "stage": "template_color_match",
                    "status": result.status if template_matched else "not_found",
                    "details": {
                        "cv2_available": summary["cv2_available"],
                        "decoded": summary["decoded"],
                        "top_score": top_score,
                        "scores": summary["scores"],
                        "matched_landmark_id": (
                            result.matched_landmark.landmark_id if template_matched else None
                        ),
                    },
                }
            )
    elif mode == "real_ocr_adapter":
        ocr_result = get_real_ocr_debug(result.request_id)
        stages.append(
            {
                "stage": "real_ocr_adapter",
                "status": ocr_result.status if ocr_result else "missing_debug",
                "details": _real_ocr_candidate_details(ocr_result)
                if ocr_result
                else {"message": "real OCR adapter debug result is missing"},
            }
        )
    else:
        stages.append(
            {
                "stage": "template_color_match",
                "status": "skipped",
                "details": {"reason": f"recognition_mode is {mode}"},
            }
        )

    if candidate_floor_id or target_poi_id:
        stages.append(
            {
                "stage": "prior_check",
                "status": "ok" if not result.failure_stage else result.status,
                "details": {
                    "candidate_floor_id": candidate_floor_id,
                    "target_poi_id": target_poi_id,
                    "resolved_floor_id": result.floor_id,
                    "matched_poi_id": result.matched_landmark.poi_id if result.matched_landmark else None,
                    "failure_stage": result.failure_stage,
                },
            }
        )

    stages.append(
        {
            "stage": "final_result",
            "status": result.status,
            "details": {
                "floor_id": result.floor_id,
                "position": result.position,
                "confidence": result.confidence,
                "matched_landmark": result.matched_landmark.model_dump() if result.matched_landmark else None,
                "failure_stage": result.failure_stage,
                "message": result.message,
                "latency_ms": result.latency_ms,
            },
        }
    )
    return stages


def _trace_id(request_id: str, landmark_id: str, elapsed_ms: int) -> str:
    return hashlib.sha1(f"{request_id}:{landmark_id}:{elapsed_ms}".encode("utf-8")).hexdigest()[:12]


def _not_found(
    *,
    request_id: str,
    venue_id: str,
    started_at: float,
    recognition_mode: str,
    failure_stage: str,
    message: str,
) -> LocalizationData:
    latency_ms = int((time.perf_counter() - started_at) * 1000)
    return LocalizationData(
        request_id=request_id,
        status="not_found",
        venue_id=venue_id,
        confidence=0.0,
        failure_stage=failure_stage,
        suggested_action="retry_after_move",
        recognition_mode=recognition_mode,
        message=message,
        trace_id=_trace_id(request_id, failure_stage, latency_ms),
        latency_ms=latency_ms,
    )


def localize_landmark(
    *,
    bundle: VenueBundle,
    request_id: str,
    venue_id: str,
    recognition_mode: str,
    image_bytes: bytes,
    image_filename: str | None,
    debug_target: str | None,
    candidate_floor_id: str | None,
    target_poi_id: str | None,
) -> LocalizationData:
    started_at = time.perf_counter()
    landmarks = load_landmarks(bundle)
    if not landmarks:
        return _not_found(
            request_id=request_id,
            venue_id=venue_id,
            started_at=started_at,
            recognition_mode=recognition_mode,
            failure_stage="landmark_catalog_missing",
            message="landmark catalog is missing",
        )

    mode = recognition_mode if recognition_mode in SUPPORTED_RECOGNITION_MODES else "mock"
    filename_probe = None if mode == "real_ocr_adapter" else image_filename
    hit = _match_debug_or_filename(landmarks=landmarks, debug_target=debug_target, image_filename=filename_probe)
    if hit is None and mode == "template":
        hit = _match_color_template(landmarks, image_bytes)
    if hit is None and mode == "real_ocr_adapter":
        ocr_result = run_real_ocr_adapter(repo_root=Path(__file__).resolve().parents[3], image_bytes=image_bytes)
        remember_real_ocr_debug(request_id, ocr_result)
        hit = _match_real_ocr_candidates(landmarks=landmarks, ocr_result=ocr_result)
        if hit is None:
            failure_stage = ocr_result.failure_stage or "real_ocr_no_landmark_binding"
            message = (
                ocr_result.message
                if ocr_result.failure_stage
                else "real OCR candidate has no landmark alias binding"
            )
            return _not_found(
                request_id=request_id,
                venue_id=venue_id,
                started_at=started_at,
                recognition_mode=mode,
                failure_stage=failure_stage,
                message=message,
            )
    if hit is None:
        return _not_found(
            request_id=request_id,
            venue_id=venue_id,
            started_at=started_at,
            recognition_mode=mode,
            failure_stage="landmark_no_hit",
            message="no landmark matched by debug target, filename, or template color",
        )

    floor_id = _floor_for_landmark(bundle, hit.landmark)
    position = _position_for_landmark(bundle, hit.landmark)
    if not floor_id or position is None:
        return _not_found(
            request_id=request_id,
            venue_id=venue_id,
            started_at=started_at,
            recognition_mode=mode,
            failure_stage="landmark_map_binding_missing",
            message="matched landmark has no floor or map position binding",
        )

    failure_stage = hit.failure_stage
    status = "low_confidence" if hit.ambiguous or hit.confidence < 0.72 else "ok"
    message = "landmark localized" if status == "ok" else "landmark matched with low confidence"
    if candidate_floor_id and candidate_floor_id != floor_id:
        status = "low_confidence"
        failure_stage = "landmark_floor_conflict"
        message = "matched landmark floor conflicts with candidate_floor_id"
    if target_poi_id and target_poi_id != hit.landmark.get("poi_id"):
        failure_stage = failure_stage or "target_prior_mismatch"

    latency_ms = int((time.perf_counter() - started_at) * 1000)
    matched = MatchedLandmark(
        landmark_id=hit.landmark["landmark_id"],
        poi_id=hit.landmark.get("poi_id"),
        match_source=hit.match_source,
        display_name=hit.landmark.get("display_name", hit.landmark["landmark_id"]),
        score=hit.confidence,
        candidate_count=hit.candidate_count,
        ambiguous=hit.ambiguous,
    )
    heading_hint = None
    if hit.landmark.get("map_heading_deg") is not None:
        heading_hint = HeadingHint(
            map_heading_deg=float(hit.landmark["map_heading_deg"]),
            source="landmark_facing",
            confidence=0.7 if status == "ok" else 0.45,
        )
    route_edge_ids = hit.landmark.get("visibility_area", {}).get("route_edge_ids", [])
    route_snap = None
    if route_edge_ids:
        route_snap = RouteSnap(edge_id=route_edge_ids[0], distance_m=0.0)
    return LocalizationData(
        request_id=request_id,
        status=status,
        venue_id=venue_id,
        floor_id=floor_id,
        position=position,
        confidence=hit.confidence,
        matched_landmark=matched,
        heading_hint=heading_hint,
        uncertainty_m=1.5 if status == "ok" else 4.0,
        failure_stage=failure_stage,
        route_snap=route_snap,
        next_capture_hint="normal" if status == "ok" else "face_forward_and_retry",
        suggested_action="continue_navigation" if status == "ok" else "request_more_images",
        trace_id=_trace_id(request_id, hit.landmark["landmark_id"], latency_ms),
        recognition_mode=mode,
        message=message,
        latency_ms=latency_ms,
    )
