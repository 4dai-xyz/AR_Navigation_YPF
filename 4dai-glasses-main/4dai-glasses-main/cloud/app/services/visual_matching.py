from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import cv2
import numpy as np

from cloud.app.models.api import RoutePrior
from cloud.app.services.visual_features import KeyframeFeature, KeyframeFeatureIndex, decode_grayscale_image, extract_orb_features


@dataclass(frozen=True)
class MatchCandidate:
    keyframe_id: str
    floor_id: str
    good_match_count: int
    inlier_count: int
    inlier_ratio: float
    score: float
    failure_stage: str | None = None


@dataclass(frozen=True)
class MatchingResult:
    matched_keyframes: list[MatchCandidate]
    matched_keyframe_id: str | None
    good_match_count: int
    inlier_count: int
    inlier_ratio: float
    score: float
    failure_stage: str | None


def _good_matches(
    query_descriptors: np.ndarray | None,
    candidate_descriptors: np.ndarray | None,
    ratio: float = 0.75,
) -> list[cv2.DMatch]:
    if query_descriptors is None or candidate_descriptors is None:
        return []
    matcher = cv2.BFMatcher(cv2.NORM_HAMMING, crossCheck=False)
    pairs = matcher.knnMatch(query_descriptors, candidate_descriptors, k=2)
    good: list[cv2.DMatch] = []
    for pair in pairs:
        if len(pair) < 2:
            continue
        first, second = pair
        if first.distance < ratio * second.distance:
            good.append(first)
    return good


def _geometry_inliers(
    query_keypoints: tuple[cv2.KeyPoint, ...],
    candidate_feature: KeyframeFeature,
    matches: list[cv2.DMatch],
    ransac_threshold: float = 5.0,
) -> int:
    if len(matches) < 4:
        return 0
    source_points = np.float32([query_keypoints[match.queryIdx].pt for match in matches]).reshape(-1, 1, 2)
    target_points = np.float32([candidate_feature.keypoints[match.trainIdx].pt for match in matches]).reshape(-1, 1, 2)
    _, mask = cv2.findHomography(source_points, target_points, cv2.RANSAC, ransac_threshold)
    if mask is None:
        return 0
    return int(mask.ravel().sum())


def _build_candidate_result(
    candidate_feature: KeyframeFeature,
    query_keypoints: tuple[cv2.KeyPoint, ...],
    query_descriptors: np.ndarray | None,
) -> MatchCandidate:
    good = _good_matches(query_descriptors, candidate_feature.descriptors)
    if len(good) < 4:
        return MatchCandidate(
            keyframe_id=candidate_feature.keyframe.keyframe_id,
            floor_id=candidate_feature.keyframe.floor_id,
            good_match_count=len(good),
            inlier_count=0,
            inlier_ratio=0.0,
            score=float(len(good)),
            failure_stage="match_insufficient",
        )

    inliers = _geometry_inliers(query_keypoints, candidate_feature, good)
    inlier_ratio = inliers / max(len(good), 1)
    if inliers <= 0:
        return MatchCandidate(
            keyframe_id=candidate_feature.keyframe.keyframe_id,
            floor_id=candidate_feature.keyframe.floor_id,
            good_match_count=len(good),
            inlier_count=0,
            inlier_ratio=0.0,
            score=float(len(good)),
            failure_stage="geometry_failed",
        )

    score = float(len(good) + inliers * 2)
    return MatchCandidate(
        keyframe_id=candidate_feature.keyframe.keyframe_id,
        floor_id=candidate_feature.keyframe.floor_id,
        good_match_count=len(good),
        inlier_count=inliers,
        inlier_ratio=inlier_ratio,
        score=score,
        failure_stage=None,
    )


def match_keyframe_candidates(
    query_image_bytes: bytes,
    index: KeyframeFeatureIndex,
    candidate_floor_id: str | None = None,
    route_prior: RoutePrior | dict[str, Any] | None = None,
    top_k: int = 3,
) -> MatchingResult:
    orb = cv2.ORB_create(nfeatures=500)
    query_image = decode_grayscale_image(query_image_bytes)
    query_keypoints, query_descriptors = extract_orb_features(query_image, orb)
    if query_descriptors is None or len(query_keypoints) < 4:
        return MatchingResult(
            matched_keyframes=[],
            matched_keyframe_id=None,
            good_match_count=0,
            inlier_count=0,
            inlier_ratio=0.0,
            score=0.0,
            failure_stage="match_insufficient",
        )

    candidates = index.candidates(candidate_floor_id=candidate_floor_id, route_prior=route_prior)
    if not candidates:
        return MatchingResult(
            matched_keyframes=[],
            matched_keyframe_id=None,
            good_match_count=0,
            inlier_count=0,
            inlier_ratio=0.0,
            score=0.0,
            failure_stage="match_insufficient",
        )

    results = [_build_candidate_result(feature, query_keypoints, query_descriptors) for feature in candidates]
    ordered = sorted(results, key=lambda item: item.score, reverse=True)
    top_results = ordered[: max(1, top_k)]
    best = top_results[0]
    return MatchingResult(
        matched_keyframes=top_results,
        matched_keyframe_id=best.keyframe_id if best.failure_stage is None else None,
        good_match_count=best.good_match_count,
        inlier_count=best.inlier_count,
        inlier_ratio=best.inlier_ratio,
        score=best.score,
        failure_stage=best.failure_stage,
    )
