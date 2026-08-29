from __future__ import annotations

import unittest

from cloud.app.core.confidence import VisualRelocalizationEvidence, assess_visual_relocalization


class VisualConfidenceTest(unittest.TestCase):
    def test_strong_visual_evidence_is_accepted(self) -> None:
        decision = assess_visual_relocalization(
            VisualRelocalizationEvidence(
                match_score=300.0,
                second_match_score=20.0,
                good_match_count=90,
                inlier_count=70,
                inlier_ratio=0.78,
                payload_bytes=4096,
                image_decoded=True,
                brightness_score=80.0,
                blur_score=160.0,
            )
        )
        self.assertEqual(decision.status, "ok")
        self.assertIsNone(decision.failure_stage)

    def test_ambiguous_visual_evidence_stays_low_confidence(self) -> None:
        decision = assess_visual_relocalization(
            VisualRelocalizationEvidence(
                match_score=507.0,
                second_match_score=498.0,
                good_match_count=169,
                inlier_count=169,
                inlier_ratio=1.0,
                payload_bytes=24,
                image_decoded=False,
            )
        )
        self.assertEqual(decision.status, "low_confidence")
        self.assertEqual(decision.failure_stage, "low_confidence")

    def test_insufficient_matches_are_not_found(self) -> None:
        decision = assess_visual_relocalization(
            VisualRelocalizationEvidence(
                match_score=3.0,
                second_match_score=0.0,
                good_match_count=3,
                inlier_count=0,
                inlier_ratio=0.0,
                payload_bytes=4096,
                image_decoded=True,
            )
        )
        self.assertEqual(decision.status, "not_found")
        self.assertEqual(decision.failure_stage, "match_insufficient")


if __name__ == "__main__":
    unittest.main()
