from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

import numpy as np

try:
    import cv2
except ModuleNotFoundError:
    cv2 = None

MIN_WIDTH = 160
MIN_HEIGHT = 160
MIN_BRIGHTNESS = 15.0
MIN_BLUR_SCORE = 20.0


@dataclass(frozen=True)
class ImageQualityResult:
    decoded: bool
    width: int
    height: int
    payload_bytes: int
    brightness_score: float
    blur_score: float
    failure_stage: str | None
    suggested_action: str | None
    details: dict[str, Any] = field(default_factory=dict)


def _decode_grayscale(image_bytes: bytes) -> np.ndarray | None:
    if cv2 is None or not image_bytes:
        return None
    payload = np.frombuffer(image_bytes, dtype=np.uint8)
    if payload.size == 0:
        return None
    return cv2.imdecode(payload, cv2.IMREAD_GRAYSCALE)


def evaluate_image_quality(image_bytes: bytes) -> ImageQualityResult:
    payload_bytes = len(image_bytes)
    if cv2 is None:
        return ImageQualityResult(
            decoded=False,
            width=0,
            height=0,
            payload_bytes=payload_bytes,
            brightness_score=0.0,
            blur_score=0.0,
            failure_stage="image_quality_failed",
            suggested_action="install_cv2",
            details={"reason": "cv2_not_installed"},
        )

    decoded = _decode_grayscale(image_bytes)
    if decoded is None:
        return ImageQualityResult(
            decoded=False,
            width=0,
            height=0,
            payload_bytes=payload_bytes,
            brightness_score=0.0,
            blur_score=0.0,
            failure_stage="image_quality_failed",
            suggested_action="retake_image",
            details={"reason": "decode_failed" if payload_bytes > 0 else "empty_payload"},
        )

    height, width = decoded.shape
    brightness_score = float(np.mean(decoded))
    blur_score = float(cv2.Laplacian(decoded, cv2.CV_64F).var())

    details: dict[str, Any] = {
        "min_width": MIN_WIDTH,
        "min_height": MIN_HEIGHT,
        "min_brightness": MIN_BRIGHTNESS,
        "min_blur_score": MIN_BLUR_SCORE,
    }

    too_small = width < MIN_WIDTH or height < MIN_HEIGHT
    very_dark = brightness_score < MIN_BRIGHTNESS
    very_blurry = blur_score < MIN_BLUR_SCORE

    if too_small or very_dark or very_blurry:
        reasons: list[str] = []
        if too_small:
            reasons.append("image_too_small")
        if very_dark:
            reasons.append("image_too_dark")
        if very_blurry:
            reasons.append("image_too_blurry")
        details["reasons"] = reasons
        return ImageQualityResult(
            decoded=True,
            width=width,
            height=height,
            payload_bytes=payload_bytes,
            brightness_score=brightness_score,
            blur_score=blur_score,
            failure_stage="image_quality_failed",
            suggested_action="retake_image",
            details=details,
        )

    details["reasons"] = []
    return ImageQualityResult(
        decoded=True,
        width=width,
        height=height,
        payload_bytes=payload_bytes,
        brightness_score=brightness_score,
        blur_score=blur_score,
        failure_stage=None,
        suggested_action="proceed",
        details=details,
    )
