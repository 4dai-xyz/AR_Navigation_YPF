from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class ConfidenceThresholds:
    accepted: float = 0.75
    low: float = 0.40
    retrieval_min: float = 0.20
    margin_good: float = 0.12
    visual_margin_good: float = 0.18
    min_good_matches: int = 4
    min_inliers: int = 4
    min_inlier_ratio: float = 0.12
    min_payload_bytes: int = 16


@dataclass(frozen=True)
class ConfidenceDecision:
    status: str
    confidence: float
    failure_stage: str | None
    suggested_action: str
    debug: dict[str, float | bool | str]


@dataclass(frozen=True)
class VisualRelocalizationEvidence:
    match_score: float
    second_match_score: float
    good_match_count: int
    inlier_count: int
    inlier_ratio: float
    payload_bytes: int
    image_decoded: bool
    brightness_score: float = 0.0
    blur_score: float = 0.0
    floor_prior_hit: bool = False
    floor_prior_mismatch: bool = False
    route_prior_hit: bool = False


THRESHOLDS = ConfidenceThresholds()


def clamp(value: float, lower: float = 0.0, upper: float = 0.99) -> float:
    return max(lower, min(upper, value))


def assess_relocalization(
    *,
    best_score: float,
    second_score: float,
    payload_bytes: int,
    floor_prior_hit: bool,
    route_prior_hit: bool,
) -> ConfidenceDecision:
    if payload_bytes < THRESHOLDS.min_payload_bytes:
        return ConfidenceDecision(
            status="not_found",
            confidence=0.0,
            failure_stage="image_quality_failed",
            suggested_action="retry_after_move",
            debug={"payload_bytes": float(payload_bytes)},
        )

    margin = max(best_score - second_score, 0.0)
    margin_score = clamp(margin / THRESHOLDS.margin_good, 0.0, 1.0)
    prior_score = (0.1 if floor_prior_hit else 0.0) + (0.05 if route_prior_hit else 0.0)
    payload_score = clamp(payload_bytes / 2048.0, 0.0, 1.0) * 0.05
    confidence = clamp(best_score * 0.70 + margin_score * 0.10 + prior_score + payload_score)

    debug = {
        "best_score": round(best_score, 4),
        "second_score": round(second_score, 4),
        "margin": round(margin, 4),
        "margin_score": round(margin_score, 4),
        "prior_score": round(prior_score, 4),
        "payload_bytes": float(payload_bytes),
        "payload_score": round(payload_score, 4),
        "confidence": round(confidence, 4),
        "floor_prior_hit": floor_prior_hit,
        "route_prior_hit": route_prior_hit,
    }

    if best_score < THRESHOLDS.retrieval_min:
        return ConfidenceDecision(
            status="not_found",
            confidence=confidence,
            failure_stage="retrieval_no_hit",
            suggested_action="retry_after_move",
            debug=debug,
        )

    if confidence >= THRESHOLDS.accepted:
        return ConfidenceDecision(
            status="ok",
            confidence=confidence,
            failure_stage=None,
            suggested_action="continue_navigation",
            debug=debug,
        )

    if confidence >= THRESHOLDS.low:
        return ConfidenceDecision(
            status="low_confidence",
            confidence=confidence,
            failure_stage="low_confidence",
            suggested_action="request_more_images",
            debug=debug,
        )

    return ConfidenceDecision(
        status="not_found",
        confidence=confidence,
        failure_stage="retrieval_no_hit",
        suggested_action="retry_after_move",
        debug=debug,
    )


def _visual_quality_score(evidence: VisualRelocalizationEvidence) -> float:
    if not evidence.image_decoded:
        return 0.5 if evidence.payload_bytes >= THRESHOLDS.min_payload_bytes else 0.0
    brightness_score = clamp(evidence.brightness_score / 80.0, 0.0, 1.0)
    blur_score = clamp(evidence.blur_score / 120.0, 0.0, 1.0)
    return (brightness_score + blur_score) / 2.0


def assess_visual_relocalization(evidence: VisualRelocalizationEvidence) -> ConfidenceDecision:
    if evidence.payload_bytes < THRESHOLDS.min_payload_bytes:
        return ConfidenceDecision(
            status="not_found",
            confidence=0.0,
            failure_stage="image_quality_failed",
            suggested_action="retry_after_move",
            debug={"payload_bytes": float(evidence.payload_bytes)},
        )

    match_count_score = clamp(evidence.good_match_count / 120.0, 0.0, 1.0)
    inlier_count_score = clamp(evidence.inlier_count / 80.0, 0.0, 1.0)
    inlier_ratio_score = clamp(evidence.inlier_ratio, 0.0, 1.0)
    margin = max(evidence.match_score - evidence.second_match_score, 0.0)
    relative_margin = margin / max(evidence.match_score, 1.0)
    margin_score = clamp(relative_margin / THRESHOLDS.visual_margin_good, 0.0, 1.0)
    quality_score = _visual_quality_score(evidence)
    prior_score = (0.12 if evidence.floor_prior_hit else 0.0) + (0.05 if evidence.route_prior_hit else 0.0)
    prior_penalty = 0.08 if evidence.floor_prior_mismatch else 0.0

    confidence = clamp(
        match_count_score * 0.20
        + inlier_count_score * 0.20
        + inlier_ratio_score * 0.20
        + margin_score * 0.25
        + quality_score * 0.05
        + prior_score
        - prior_penalty
    )

    debug = {
        "match_score": round(evidence.match_score, 4),
        "second_match_score": round(evidence.second_match_score, 4),
        "good_match_count": float(evidence.good_match_count),
        "inlier_count": float(evidence.inlier_count),
        "inlier_ratio": round(evidence.inlier_ratio, 4),
        "match_count_score": round(match_count_score, 4),
        "inlier_count_score": round(inlier_count_score, 4),
        "inlier_ratio_score": round(inlier_ratio_score, 4),
        "margin": round(margin, 4),
        "relative_margin": round(relative_margin, 4),
        "margin_score": round(margin_score, 4),
        "quality_score": round(quality_score, 4),
        "prior_score": round(prior_score, 4),
        "prior_penalty": round(prior_penalty, 4),
        "confidence": round(confidence, 4),
        "image_decoded": evidence.image_decoded,
        "floor_prior_hit": evidence.floor_prior_hit,
        "floor_prior_mismatch": evidence.floor_prior_mismatch,
        "route_prior_hit": evidence.route_prior_hit,
    }

    if evidence.good_match_count < THRESHOLDS.min_good_matches:
        return ConfidenceDecision(
            status="not_found",
            confidence=confidence,
            failure_stage="match_insufficient",
            suggested_action="retry_after_move",
            debug=debug,
        )

    if evidence.inlier_count < THRESHOLDS.min_inliers or evidence.inlier_ratio < THRESHOLDS.min_inlier_ratio:
        return ConfidenceDecision(
            status="not_found",
            confidence=confidence,
            failure_stage="geometry_failed",
            suggested_action="retry_after_move",
            debug=debug,
        )

    if confidence >= THRESHOLDS.accepted:
        return ConfidenceDecision(
            status="ok",
            confidence=confidence,
            failure_stage=None,
            suggested_action="continue_navigation",
            debug=debug,
        )

    if confidence >= THRESHOLDS.low:
        return ConfidenceDecision(
            status="low_confidence",
            confidence=confidence,
            failure_stage="low_confidence",
            suggested_action="request_more_images",
            debug=debug,
        )

    return ConfidenceDecision(
        status="not_found",
        confidence=confidence,
        failure_stage="retrieval_no_hit",
        suggested_action="retry_after_move",
        debug=debug,
    )
