from __future__ import annotations

import sys
import unittest
from pathlib import Path

import numpy as np

REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

try:
    import cv2
except ModuleNotFoundError as exc:
    cv2 = None
    CV2_IMPORT_ERROR = exc
else:
    CV2_IMPORT_ERROR = None

from cloud.app.services.image_quality import evaluate_image_quality


class ImageQualityTest(unittest.TestCase):
    def _require_cv2(self) -> None:
        if cv2 is None:
            self.fail(f"OpenCV (cv2) is required for image quality tests: {CV2_IMPORT_ERROR}")

    def test_empty_bytes_fail_quality(self) -> None:
        self._require_cv2()
        result = evaluate_image_quality(b"")
        self.assertFalse(result.decoded)
        self.assertEqual(result.payload_bytes, 0)
        self.assertEqual(result.failure_stage, "image_quality_failed")
        self.assertEqual(result.details.get("reason"), "empty_payload")

    def test_non_image_bytes_fail_quality(self) -> None:
        self._require_cv2()
        result = evaluate_image_quality(b"not-an-image-payload")
        self.assertFalse(result.decoded)
        self.assertEqual(result.failure_stage, "image_quality_failed")
        self.assertEqual(result.details.get("reason"), "decode_failed")

    def test_sample_keyframe_jpg_or_synthetic_image_passes_thresholds(self) -> None:
        self._require_cv2()
        image_path = (
            REPO_ROOT
            / "mapping"
            / "examples"
            / "venue-package-example"
            / "localization"
            / "images"
            / "kf_0001.jpg"
        )
        self.assertTrue(image_path.exists())

        sample_result = evaluate_image_quality(image_path.read_bytes())
        if sample_result.decoded:
            self.assertIsNone(sample_result.failure_stage)
            return

        synthetic = np.random.default_rng(42).integers(0, 256, size=(320, 320), dtype=np.uint8)
        encoded_ok, encoded = cv2.imencode(".jpg", synthetic)
        self.assertTrue(encoded_ok)
        result = evaluate_image_quality(encoded.tobytes())

        self.assertTrue(result.decoded)
        self.assertGreater(result.width, 0)
        self.assertGreater(result.height, 0)
        self.assertIsNone(result.failure_stage)
        self.assertEqual(result.suggested_action, "proceed")


if __name__ == "__main__":
    unittest.main()
