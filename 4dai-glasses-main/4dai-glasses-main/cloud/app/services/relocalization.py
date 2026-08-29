from __future__ import annotations

import hashlib
import time
from dataclasses import dataclass

import numpy as np

from cloud.app.core.confidence import VisualRelocalizationEvidence, assess_relocalization, assess_visual_relocalization
from cloud.app.models.api import LocalizationData, MatchedKeyframe, RoutePrior, RouteSnap
from cloud.app.services.image_quality import ImageQualityResult, evaluate_image_quality
from cloud.app.services.venue_package import KeyframeRecord, VenueBundle
from cloud.app.services.visual_features import KeyframeFeatureIndex
from cloud.app.services.visual_matching import MatchCandidate, MatchingResult, match_keyframe_candidates


def describe_bytes(payload: bytes, bins: int = 16, ngram_bins: int = 16, positional_width: int = 8) -> np.ndarray:
    if not payload:
        return np.zeros(bins + ngram_bins + positional_width * 2 + 1, dtype=np.float64)
    array = np.frombuffer(payload, dtype=np.uint8)
    histogram, _ = np.histogram(array, bins=bins, range=(0, 256))
    ngram_histogram = np.zeros(ngram_bins, dtype=np.float64)
    if len(array) >= 3:
        for index in range(len(array) - 2):
            value = int(array[index]) * 31 + int(array[index + 1]) * 17 + int(array[index + 2])
            ngram_histogram[value % ngram_bins] += 1.0
    prefix = np.zeros(positional_width, dtype=np.float64)
    suffix = np.zeros(positional_width, dtype=np.float64)
    prefix_slice = array[:positional_width]
    suffix_slice = array[-positional_width:]
    prefix[: len(prefix_slice)] = prefix_slice / 255.0
    suffix[-len(suffix_slice) :] = suffix_slice / 255.0
    length_feature = np.array([min(len(array) / 128.0, 1.0)], dtype=np.float64)
    descriptor = np.concatenate([histogram.astype(np.float64), ngram_histogram, prefix, suffix, length_feature])
    norm = np.linalg.norm(descriptor)
    if norm == 0.0:
        return descriptor
    return descriptor / norm


def cosine_similarity(left: np.ndarray, right: np.ndarray) -> float:
    left_norm = np.linalg.norm(left)
    right_norm = np.linalg.norm(right)
    if left_norm == 0.0 or right_norm == 0.0:
        return 0.0
    return float(np.dot(left, right) / (left_norm * right_norm))


@dataclass(frozen=True)
class LocalizationQuery:
    request_id: str
    venue_id: str
    capture_mode: str
    image_bytes: bytes
    candidate_floor_id: str | None = None
    route_prior: RoutePrior | None = None


@dataclass(frozen=True)
class Candidate:
    keyframe: KeyframeRecord
    score: float
    floor_prior_hit: bool
    route_prior_hit: bool


class BaselineRelocalizer:
    def __init__(self, bundle: VenueBundle) -> None:
        self.bundle = bundle
        self._keyframes_by_id = {keyframe.keyframe_id: keyframe for keyframe in bundle.keyframes}
        self._feature_index = KeyframeFeatureIndex.from_bundle(bundle)
        self._descriptor_index = {
            keyframe.keyframe_id: self._load_keyframe_descriptor(keyframe) for keyframe in bundle.keyframes
        }

    def _load_keyframe_descriptor(self, keyframe: KeyframeRecord) -> np.ndarray:
        if keyframe.image_ref:
            image_path = self.bundle.root / "localization" / keyframe.image_ref
            if image_path.exists():
                return describe_bytes(image_path.read_bytes())
        return describe_bytes(keyframe.keyframe_id.encode("utf-8"))

    def _route_prior_hit(self, route_prior: RoutePrior | None, keyframe: KeyframeRecord) -> bool:
        if route_prior is None or keyframe.route_edge_id is None:
            return False
        return keyframe.route_edge_id in route_prior.edge_ids

    def _route_snap(self, keyframe: KeyframeRecord) -> RouteSnap | None:
        if keyframe.route_edge_id is None:
            return None
        return RouteSnap(edge_id=keyframe.route_edge_id, distance_m=0.0)

    def _matched_keyframes(self, candidates: list[MatchCandidate]) -> list[MatchedKeyframe]:
        if not candidates:
            return []
        best_score = max(candidates[0].score, 1.0)
        return [
            MatchedKeyframe(
                keyframe_id=item.keyframe_id,
                score=round(min(item.score / best_score, 0.9999), 4),
            )
            for item in candidates[:3]
        ]

    def _not_found(
        self,
        *,
        query: LocalizationQuery,
        started_at: float,
        trace_id: str,
        confidence: float,
        failure_stage: str,
        matched_keyframes: list[MatchedKeyframe] | None = None,
    ) -> LocalizationData:
        return LocalizationData(
            status="not_found",
            venue_id=query.venue_id,
            confidence=round(confidence, 4),
            failure_stage=failure_stage,
            suggested_action="retry_after_move",
            matched_keyframes=matched_keyframes or [],
            trace_id=trace_id,
            latency_ms=int((time.perf_counter() - started_at) * 1000),
        )

    def _quality_blocks_matching(self, quality: ImageQualityResult) -> bool:
        if quality.failure_stage is None:
            return False
        return quality.decoded or quality.payload_bytes == 0

    def _trace_id(self, request_id: str, keyframe_id: str, started_at: float) -> str:
        elapsed_ms = int((time.perf_counter() - started_at) * 1000)
        return hashlib.sha1(f"{request_id}:{keyframe_id}:{elapsed_ms}".encode("utf-8")).hexdigest()[:12]

    def _localize_with_visual_pipeline(self, query: LocalizationQuery) -> LocalizationData:
        started_at = time.perf_counter()
        quality = evaluate_image_quality(query.image_bytes)
        if self._quality_blocks_matching(quality):
            return self._not_found(
                query=query,
                started_at=started_at,
                trace_id=self._trace_id(query.request_id, "image_quality", started_at),
                confidence=0.0,
                failure_stage="image_quality_failed",
            )

        match_result = match_keyframe_candidates(
            query.image_bytes,
            self._feature_index,
            candidate_floor_id=None,
            route_prior=query.route_prior,
            top_k=3,
        )
        matched_keyframes = self._matched_keyframes(match_result.matched_keyframes)
        if match_result.matched_keyframe_id is None:
            failure_stage = "image_quality_failed" if quality.failure_stage else match_result.failure_stage
            return self._not_found(
                query=query,
                started_at=started_at,
                trace_id=self._trace_id(query.request_id, failure_stage or "not_found", started_at),
                confidence=0.0,
                failure_stage=failure_stage or "match_insufficient",
                matched_keyframes=matched_keyframes,
            )

        best_keyframe = self._keyframes_by_id[match_result.matched_keyframe_id]
        second_score = match_result.matched_keyframes[1].score if len(match_result.matched_keyframes) > 1 else 0.0
        floor_prior_hit = query.candidate_floor_id == best_keyframe.floor_id if query.candidate_floor_id else False
        floor_prior_mismatch = query.candidate_floor_id is not None and query.candidate_floor_id != best_keyframe.floor_id
        decision = assess_visual_relocalization(
            VisualRelocalizationEvidence(
                match_score=match_result.score,
                second_match_score=second_score,
                good_match_count=match_result.good_match_count,
                inlier_count=match_result.inlier_count,
                inlier_ratio=match_result.inlier_ratio,
                payload_bytes=len(query.image_bytes),
                image_decoded=quality.decoded,
                brightness_score=quality.brightness_score,
                blur_score=quality.blur_score,
                floor_prior_hit=floor_prior_hit,
                floor_prior_mismatch=floor_prior_mismatch,
                route_prior_hit=self._route_prior_hit(query.route_prior, best_keyframe),
            )
        )
        elapsed_ms = int((time.perf_counter() - started_at) * 1000)
        trace_id = hashlib.sha1(
            f"{query.request_id}:{best_keyframe.keyframe_id}:{elapsed_ms}".encode("utf-8")
        ).hexdigest()[:12]

        if decision.status == "not_found":
            return LocalizationData(
                status=decision.status,
                venue_id=query.venue_id,
                confidence=round(decision.confidence, 4),
                failure_stage=match_result.failure_stage or decision.failure_stage,
                suggested_action=decision.suggested_action,
                matched_keyframes=matched_keyframes,
                trace_id=trace_id,
                latency_ms=elapsed_ms,
            )

        position = best_keyframe.venue_xy
        uncertainty = max(0.5, round((1.0 - decision.confidence) * 8.0, 2))
        return LocalizationData(
            status=decision.status,
            venue_id=query.venue_id,
            floor_id=best_keyframe.floor_id,
            position={"x": position["x"], "y": position["y"]},
            confidence=round(decision.confidence, 4),
            uncertainty_m=uncertainty,
            matched_keyframe_id=best_keyframe.keyframe_id,
            matched_keyframes=matched_keyframes,
            inlier_count=match_result.inlier_count,
            failure_stage=match_result.failure_stage or decision.failure_stage,
            route_snap=self._route_snap(best_keyframe),
            next_capture_hint="normal" if decision.status == "ok" else "face_forward_and_retry",
            suggested_action=decision.suggested_action,
            trace_id=trace_id,
            latency_ms=elapsed_ms,
        )

    def localize(self, query: LocalizationQuery) -> LocalizationData:
        return self._localize_with_visual_pipeline(query)

    def localize_by_byte_fallback(self, query: LocalizationQuery) -> LocalizationData:
        started_at = time.perf_counter()
        query_descriptor = describe_bytes(query.image_bytes)
        candidates: list[Candidate] = []
        for keyframe in self.bundle.keyframes:
            score = cosine_similarity(query_descriptor, self._descriptor_index[keyframe.keyframe_id])
            floor_prior_hit = query.candidate_floor_id == keyframe.floor_id if query.candidate_floor_id else False
            route_prior_hit = self._route_prior_hit(query.route_prior, keyframe)
            if floor_prior_hit:
                score += 0.04
            if route_prior_hit:
                score += 0.03
            candidates.append(
                Candidate(
                    keyframe=keyframe,
                    score=score,
                    floor_prior_hit=floor_prior_hit,
                    route_prior_hit=route_prior_hit,
                )
            )

        candidates.sort(key=lambda item: item.score, reverse=True)
        best = candidates[0]
        second = candidates[1] if len(candidates) > 1 else Candidate(best.keyframe, 0.0, False, False)
        decision = assess_relocalization(
            best_score=best.score,
            second_score=second.score,
            payload_bytes=len(query.image_bytes),
            floor_prior_hit=best.floor_prior_hit,
            route_prior_hit=best.route_prior_hit,
        )
        elapsed_ms = int((time.perf_counter() - started_at) * 1000)
        matched = [
            MatchedKeyframe(keyframe_id=item.keyframe.keyframe_id, score=round(min(item.score, 0.9999), 4))
            for item in candidates[:3]
        ]
        trace_id = hashlib.sha1(
            f"{query.request_id}:{best.keyframe.keyframe_id}:{elapsed_ms}".encode("utf-8")
        ).hexdigest()[:12]

        if decision.status == "not_found":
            return LocalizationData(
                status=decision.status,
                venue_id=query.venue_id,
                confidence=round(decision.confidence, 4),
                failure_stage=decision.failure_stage,
                suggested_action=decision.suggested_action,
                matched_keyframes=matched,
                trace_id=trace_id,
                latency_ms=elapsed_ms,
            )

        position = best.keyframe.venue_xy
        uncertainty = max(0.5, round((1.0 - decision.confidence) * 8.0, 2))
        return LocalizationData(
            status=decision.status,
            venue_id=query.venue_id,
            floor_id=best.keyframe.floor_id,
            position={"x": position["x"], "y": position["y"]},
            confidence=round(decision.confidence, 4),
            uncertainty_m=uncertainty,
            matched_keyframe_id=best.keyframe.keyframe_id,
            matched_keyframes=matched,
            inlier_count=max(0, int(best.score * 100)),
            failure_stage=decision.failure_stage,
            route_snap=self._route_snap(best.keyframe),
            next_capture_hint="normal" if decision.status == "ok" else "face_forward_and_retry",
            suggested_action=decision.suggested_action,
            trace_id=trace_id,
            latency_ms=elapsed_ms,
        )
