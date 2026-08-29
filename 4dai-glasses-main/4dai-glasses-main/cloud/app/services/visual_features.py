from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import cv2
import numpy as np

from cloud.app.models.api import RoutePrior
from cloud.app.services.venue_package import KeyframeRecord, VenueBundle


@dataclass(frozen=True)
class KeyframeFeature:
    keyframe: KeyframeRecord
    image: np.ndarray
    keypoints: tuple[cv2.KeyPoint, ...]
    descriptors: np.ndarray | None


def _fallback_image(payload: bytes, size: int = 128) -> np.ndarray:
    if not payload:
        payload = b"\x00"
    raw = np.frombuffer(payload, dtype=np.uint8)
    repeated = np.resize(raw, size * size)
    image = repeated.reshape((size, size))
    gradient_x = np.tile(np.arange(size, dtype=np.uint8), (size, 1))
    gradient_y = gradient_x.T
    return cv2.addWeighted(image, 0.6, gradient_x, 0.2, 0.0) ^ gradient_y


def decode_grayscale_image(payload: bytes) -> np.ndarray:
    array = np.frombuffer(payload, dtype=np.uint8)
    if array.size == 0:
        return _fallback_image(payload)
    decoded = cv2.imdecode(array, cv2.IMREAD_GRAYSCALE)
    if decoded is None:
        return _fallback_image(payload)
    return decoded


def extract_orb_features(image: np.ndarray, orb: cv2.ORB) -> tuple[tuple[cv2.KeyPoint, ...], np.ndarray | None]:
    keypoints, descriptors = orb.detectAndCompute(image, None)
    if not keypoints:
        return tuple(), None
    return tuple(keypoints), descriptors


def _edge_ids_from_route_prior(route_prior: RoutePrior | dict[str, Any] | None) -> set[str]:
    if route_prior is None:
        return set()
    if isinstance(route_prior, RoutePrior):
        return {edge_id for edge_id in route_prior.edge_ids if isinstance(edge_id, str)}
    if isinstance(route_prior, dict):
        edge_ids = route_prior.get("edge_ids", [])
        if isinstance(edge_ids, list):
            return {edge_id for edge_id in edge_ids if isinstance(edge_id, str)}
    return set()


class KeyframeFeatureIndex:
    def __init__(self, bundle: VenueBundle, features: list[KeyframeFeature]) -> None:
        self.bundle = bundle
        self.features = features
        self._by_id = {item.keyframe.keyframe_id: item for item in features}

    @classmethod
    def from_bundle(cls, bundle: VenueBundle, nfeatures: int = 500) -> KeyframeFeatureIndex:
        orb = cv2.ORB_create(nfeatures=nfeatures)
        features: list[KeyframeFeature] = []
        for keyframe in bundle.keyframes:
            payload = b""
            if keyframe.image_ref:
                image_path = bundle.root / "localization" / keyframe.image_ref
                if image_path.exists():
                    payload = image_path.read_bytes()
            image = decode_grayscale_image(payload or keyframe.keyframe_id.encode("utf-8"))
            keypoints, descriptors = extract_orb_features(image, orb)
            features.append(
                KeyframeFeature(
                    keyframe=keyframe,
                    image=image,
                    keypoints=keypoints,
                    descriptors=descriptors,
                )
            )
        return cls(bundle=bundle, features=features)

    def get(self, keyframe_id: str) -> KeyframeFeature | None:
        return self._by_id.get(keyframe_id)

    def candidates(
        self,
        candidate_floor_id: str | None = None,
        route_prior: RoutePrior | dict[str, Any] | None = None,
    ) -> list[KeyframeFeature]:
        items = self.features
        if candidate_floor_id:
            items = [item for item in items if item.keyframe.floor_id == candidate_floor_id]
        route_edges = _edge_ids_from_route_prior(route_prior)
        if route_edges:
            matched = [item for item in items if item.keyframe.route_edge_id in route_edges]
            if matched:
                return matched
        return items
