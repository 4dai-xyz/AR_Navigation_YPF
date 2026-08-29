from __future__ import annotations

import unittest
from pathlib import Path

from cloud.app.services.venue_package import load_bundle
from cloud.app.services.relocalization import BaselineRelocalizer, LocalizationQuery
from cloud.app.services.visual_features import KeyframeFeatureIndex
from cloud.app.services.visual_matching import match_keyframe_candidates


class VisualMatchingTest(unittest.TestCase):
    def setUp(self) -> None:
        repo_root = Path(__file__).resolve().parents[2]
        self.package_root = repo_root / "mapping" / "examples" / "venue-package-example"
        load_bundle.cache_clear()
        self.bundle = load_bundle(str(self.package_root))
        self.index = KeyframeFeatureIndex.from_bundle(self.bundle)
        self.kf1_path = self.package_root / "localization" / "images" / "kf_0001.jpg"

    def test_top1_matches_kf_0001(self) -> None:
        result = match_keyframe_candidates(self.kf1_path.read_bytes(), self.index, top_k=2)
        self.assertTrue(result.matched_keyframes)
        self.assertEqual(result.matched_keyframes[0].keyframe_id, "kf_0001")
        self.assertGreaterEqual(result.matched_keyframes[0].good_match_count, 4)

    def test_candidate_floor_filter_to_f2(self) -> None:
        candidates = self.index.candidates(candidate_floor_id="F2")
        self.assertEqual(len(candidates), 1)
        self.assertEqual(candidates[0].keyframe.floor_id, "F2")
        self.assertEqual(candidates[0].keyframe.keyframe_id, "kf_0002")

    def test_online_relocalizer_uses_floor_prior_as_soft_hint(self) -> None:
        relocalizer = BaselineRelocalizer(self.bundle)
        result = relocalizer.localize(
            LocalizationQuery(
                request_id="req_wrong_floor_prior",
                venue_id="venue_demo_001",
                capture_mode="glasses_album_sync",
                image_bytes=self.kf1_path.read_bytes(),
                candidate_floor_id="F2",
            )
        )
        self.assertEqual(result.floor_id, "F1")
        self.assertEqual(result.status, "low_confidence")


if __name__ == "__main__":
    unittest.main()
