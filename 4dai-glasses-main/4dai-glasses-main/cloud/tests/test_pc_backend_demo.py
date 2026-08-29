from __future__ import annotations

import json
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient

import cloud.app.main as main_module
import cloud.app.services.scene_retrieval_adapter as scene_retrieval_module
from cloud.app.main import app
from cloud.app.services.real_ocr_adapter import RealOcrCandidate, RealOcrResult, REAL_OCR_DEBUGS


class PcBackendDemoTest(unittest.TestCase):
    def setUp(self) -> None:
        self.client = TestClient(app)
        self.repo_root = Path(__file__).resolve().parents[2]
        self.demo_root = self.repo_root / "cloud" / "data" / "exhibition_demo"
        main_module.load_bundle.cache_clear()
        main_module.RECENT_VISUAL_LOCATE_REQUESTS.clear()
        main_module.RECENT_VISUAL_LOCATE_DEBUGS.clear()
        REAL_OCR_DEBUGS.clear()
        scene_retrieval_module._ADAPTER_CACHE.clear()

    def tearDown(self) -> None:
        main_module.load_bundle.cache_clear()
        main_module.RECENT_VISUAL_LOCATE_REQUESTS.clear()
        main_module.RECENT_VISUAL_LOCATE_DEBUGS.clear()
        REAL_OCR_DEBUGS.clear()
        scene_retrieval_module._ADAPTER_CACHE.clear()

    def patched_settings(self, recognition_mode: str = "mock", **overrides):
        return replace(
            main_module.settings,
            service_mode="pc_backend",
            venue_package_root=self.demo_root,
            recognition_mode=recognition_mode,
            **overrides,
        )

    def test_health_reports_pc_backend_mode(self) -> None:
        with patch("cloud.app.main.settings", self.patched_settings()):
            response = self.client.get("/api/v1/health")

        self.assertEqual(response.status_code, 200)
        data = response.json()["data"]
        self.assertEqual(data["service_mode"], "pc_backend")
        self.assertEqual(data["recognition_mode"], "mock")
        self.assertEqual(data["venue_id"], "venue_exhibition_demo")
        self.assertEqual(data["algorithm_backend_status"]["landmark_count"], 4)

    def test_health_reports_scene_retrieval_asset_status(self) -> None:
        import numpy as np

        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            index_path = temp_root / "scene_index.npz"
            metadata_path = temp_root / "scene_metadata.jsonl"
            booth_coordinates_path = temp_root / "booth_coordinates.json"
            np.savez(index_path, features=np.array([[1.0, 0.0]], dtype=np.float32))
            metadata_path.write_text(
                json.dumps({"keyframe_id": "kf_b17_001", "booth_id": "B17"}, ensure_ascii=False) + "\n",
                encoding="utf-8",
            )
            booth_coordinates_path.write_text(
                json.dumps(
                    {
                        "booths": [
                            {
                                "booth_id": "B17",
                                "display_name": "B17 booth",
                                "floor_id": "F1",
                                "position": {"x": 42.51, "y": 22.43},
                            }
                        ]
                    },
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )

            with patch(
                "cloud.app.main.settings",
                self.patched_settings(
                    "scene_retrieval",
                    scene_retrieval_index_path=index_path,
                    scene_retrieval_metadata_path=metadata_path,
                    scene_retrieval_booth_coordinates_path=booth_coordinates_path,
                    scene_retrieval_feature_extractor="fusion_resnet50_clip_vitb32_dinov2",
                ),
            ):
                response = self.client.get("/api/v1/health")

        self.assertEqual(response.status_code, 200)
        status = response.json()["data"]["algorithm_backend_status"]
        self.assertTrue(status["available"])
        self.assertEqual(status["recognition_mode"], "scene_retrieval")
        self.assertTrue(status["index_exists"])
        self.assertTrue(status["metadata_exists"])
        self.assertTrue(status["booth_coordinates_exists"])
        self.assertEqual(status["sample_count"], 1)

    def test_visual_locate_scene_retrieval_returns_ok_with_stub_feature(self) -> None:
        import numpy as np

        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            index_path = temp_root / "scene_index.npz"
            metadata_path = temp_root / "scene_metadata.jsonl"
            booth_coordinates_path = temp_root / "booth_coordinates.json"
            np.savez(
                index_path,
                features=np.array(
                    [
                        [1.0, 0.0, 0.0],
                        [0.96, 0.02, 0.0],
                        [0.0, 1.0, 0.0],
                    ],
                    dtype=np.float32,
                ),
            )
            metadata_rows = [
                {"keyframe_id": "kf_b17_001", "booth_id": "B17", "poi_id": "poi_booth_b17"},
                {"keyframe_id": "kf_b17_002", "booth_id": "B17", "poi_id": "poi_booth_b17"},
                {"keyframe_id": "kf_b10_001", "booth_id": "B10", "poi_id": "poi_booth_b10"},
            ]
            metadata_path.write_text(
                "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in metadata_rows),
                encoding="utf-8",
            )
            booth_coordinates_path.write_text(
                json.dumps(
                    {
                        "booths": [
                            {
                                "booth_id": "B17",
                                "display_name": "B17 booth",
                                "floor_id": "F1",
                                "position": {"x": 42.51, "y": 22.43},
                            }
                        ]
                    },
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )

            with (
                patch(
                    "cloud.app.main.settings",
                    self.patched_settings(
                        "scene_retrieval",
                        scene_retrieval_index_path=index_path,
                        scene_retrieval_metadata_path=metadata_path,
                        scene_retrieval_booth_coordinates_path=booth_coordinates_path,
                        scene_retrieval_feature_extractor="stub",
                        scene_retrieval_top_k=3,
                        scene_retrieval_min_score=0.82,
                        scene_retrieval_ok_score=0.9,
                    ),
                ),
                patch.object(
                    scene_retrieval_module.SceneRetrievalAdapter,
                    "extract_query_feature",
                    return_value=np.array([1.0, 0.0, 0.0], dtype=np.float32),
                ),
            ):
                response = self.client.post(
                    "/api/v1/localization/visual-locate",
                    files={"image": ("rokid_frame.jpg", b"demo-image", "image/jpeg")},
                    data={
                        "request_id": "req_scene_b17",
                        "capture_id": "cap_scene_b17",
                        "venue_id": "venue_exhibition_demo",
                        "capture_timestamp_ms": "1777358700000",
                        "capture_mode": "glasses_private_stream",
                    },
                )
                live_data = self.client.get("/debug/visual-locate/live-data")

        payload = response.json()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(payload["data"]["status"], "ok")
        self.assertEqual(payload["data"]["recognition_mode"], "scene_retrieval")
        self.assertEqual(payload["data"]["floor_id"], "F1")
        self.assertEqual(payload["data"]["position"], {"x": 42.51, "y": 22.43})
        self.assertEqual(payload["data"]["matched_landmark"]["landmark_id"], "scene_booth_b17")
        self.assertEqual(payload["data"]["matched_landmark"]["match_source"], "scene_retrieval")
        self.assertEqual(payload["data"]["matched_keyframe_id"], "kf_b17_001")
        self.assertEqual(live_data.status_code, 200)
        self.assertEqual(live_data.json()["items"][0]["matched_landmark_id"], "scene_booth_b17")

    def test_debug_cards_page_loads(self) -> None:
        with patch("cloud.app.main.settings", self.patched_settings("template")):
            response = self.client.get("/debug/cards")

        self.assertEqual(response.status_code, 200)
        self.assertIn("B10", response.text)
        self.assertIn("venue_exhibition_demo", response.text)
        self.assertIn("visual-locate", response.text)
        self.assertIn("/debug/visual-locate", response.text)
        self.assertIn("/debug/pairing", response.text)

    def test_debug_pairing_json_reports_app_connection_payload(self) -> None:
        with patch("cloud.app.main.settings", self.patched_settings("template")):
            response = self.client.get("/debug/pairing.json", headers={"host": "192.168.1.50:8000"})

        self.assertEqual(response.status_code, 200)
        payload = response.json()
        self.assertEqual(payload["type"], "visionroute_pc_backend_pairing")
        self.assertEqual(payload["version"], 1)
        self.assertEqual(payload["base_url"], "http://192.168.1.50:8000")
        self.assertEqual(payload["health_url"], "http://192.168.1.50:8000/api/v1/health")
        self.assertEqual(payload["visual_locate_url"], "http://192.168.1.50:8000/api/v1/localization/visual-locate")
        self.assertEqual(payload["pairing_url"], "http://192.168.1.50:8000/debug/pairing.json")
        self.assertEqual(payload["venue_id"], "venue_exhibition_demo")
        self.assertEqual(payload["service_mode"], "pc_backend")
        self.assertEqual(payload["recognition_mode"], "template")
        self.assertIn("glasses_private_stream", payload["supported_capture_modes"])

    def test_debug_pairing_page_and_qr_load(self) -> None:
        with patch("cloud.app.main.settings", self.patched_settings("template")):
            page_response = self.client.get("/debug/pairing", headers={"host": "192.168.1.50:8000"})
            svg_response = self.client.get("/debug/pairing.svg", headers={"host": "192.168.1.50:8000"})

        self.assertEqual(page_response.status_code, 200)
        self.assertIn("VisionRoute PC 后台配对", page_response.text)
        self.assertIn("http://192.168.1.50:8000", page_response.text)
        self.assertIn("/debug/pairing.json", page_response.text)
        self.assertEqual(svg_response.status_code, 200)
        self.assertIn("image/svg+xml", svg_response.headers["content-type"])
        self.assertIn("<svg", svg_response.text)
        self.assertIn("<path", svg_response.text)

    def test_visual_locate_mock_debug_target_returns_ok(self) -> None:
        with patch("cloud.app.main.settings", self.patched_settings()):
            response = self.client.post(
                "/api/v1/localization/visual-locate",
                files={"image": ("phone_upload.jpg", b"demo-image", "image/jpeg")},
                data={
                    "request_id": "req_pc_b17",
                    "capture_id": "cap_pc_b17",
                    "venue_id": "venue_exhibition_demo",
                    "capture_timestamp_ms": "1777358700000",
                    "capture_mode": "phone_camera_fallback",
                    "debug_target": "B17",
                },
            )

        payload = response.json()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(payload["data"]["status"], "ok")
        self.assertEqual(payload["data"]["floor_id"], "F1")
        self.assertEqual(payload["data"]["matched_landmark"]["landmark_id"], "lm_booth_b17_card")
        self.assertEqual(payload["data"]["position"], {"x": 36.0, "y": 12.0})

    def test_visual_locate_template_color_card_returns_ok(self) -> None:
        import cv2
        import numpy as np

        image = np.zeros((240, 360, 3), dtype=np.uint8)
        image[:, :] = (11, 158, 245)
        ok, encoded = cv2.imencode(".jpg", image)
        self.assertTrue(ok)
        with patch("cloud.app.main.settings", self.patched_settings("template")):
            response = self.client.post(
                "/api/v1/localization/visual-locate",
                files={"image": ("phone_upload.jpg", encoded.tobytes(), "image/jpeg")},
                data={
                    "request_id": "req_pc_toilet_template",
                    "capture_id": "cap_pc_toilet_template",
                    "venue_id": "venue_exhibition_demo",
                    "capture_timestamp_ms": "1777358700000",
                    "capture_mode": "phone_camera_fallback",
                },
            )

        payload = response.json()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(payload["data"]["status"], "ok")
        self.assertEqual(payload["data"]["matched_landmark"]["landmark_id"], "lm_toilet_f1_sign")
        self.assertEqual(payload["data"]["matched_landmark"]["match_source"], "color_template")

    def test_visual_locate_unknown_returns_not_found_and_recent_request(self) -> None:
        with patch("cloud.app.main.settings", self.patched_settings()):
            response = self.client.post(
                "/api/v1/localization/visual-locate",
                files={"image": ("unknown.jpg", b"demo-image", "image/jpeg")},
                data={
                    "request_id": "req_pc_unknown",
                    "capture_id": "cap_pc_unknown",
                    "venue_id": "venue_exhibition_demo",
                    "capture_timestamp_ms": "1777358700000",
                    "capture_mode": "phone_camera_fallback",
                },
            )
            recent = self.client.get("/debug/recent-requests")

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["data"]["status"], "not_found")
        self.assertEqual(response.json()["data"]["failure_stage"], "landmark_no_hit")
        self.assertEqual(recent.status_code, 200)
        self.assertEqual(recent.json()["items"][0]["request_id"], "req_pc_unknown")
        self.assertEqual(recent.json()["items"][0]["status"], "not_found")

    def test_visual_debug_page_shows_image_and_recognition_stages(self) -> None:
        import cv2
        import numpy as np

        image = np.zeros((120, 180, 3), dtype=np.uint8)
        image[:, :] = (11, 158, 245)
        ok, encoded = cv2.imencode(".jpg", image)
        self.assertTrue(ok)

        with patch("cloud.app.main.settings", self.patched_settings("template")):
            locate_response = self.client.post(
                "/api/v1/localization/visual-locate",
                files={"image": ("toilet_card.jpg", encoded.tobytes(), "image/jpeg")},
                data={
                    "request_id": "req_visual_debug",
                    "capture_id": "cap_visual_debug",
                    "venue_id": "venue_exhibition_demo",
                    "capture_timestamp_ms": "1777358700000",
                    "capture_mode": "glasses_private_stream",
                },
            )
            page_response = self.client.get("/debug/visual-locate")
            live_data_response = self.client.get("/debug/visual-locate/live-data")

        self.assertEqual(locate_response.status_code, 200)
        self.assertEqual(page_response.status_code, 200)
        self.assertEqual(live_data_response.status_code, 200)
        self.assertIn("req_visual_debug", page_response.text)
        self.assertIn("data:image/jpeg;base64,", page_response.text)
        self.assertIn("/debug/visual-locate/live-data", page_response.text)
        self.assertIn("接收FPS", page_response.text)
        self.assertIn("实时最新画面", page_response.text)
        self.assertIn("setInterval(refreshLiveData, 250)", page_response.text)
        self.assertIn("image_received", page_response.text)
        self.assertIn("landmark_catalog", page_response.text)
        self.assertIn("debug_or_filename_match", page_response.text)
        self.assertIn("template_color_match", page_response.text)
        self.assertIn("final_result", page_response.text)
        live_data = live_data_response.json()
        self.assertEqual(live_data["count"], 1)
        self.assertIn("observed_fps", live_data)
        self.assertIn("completed_fps", live_data)
        self.assertIn("latest_frame_age_ms", live_data)
        self.assertEqual(live_data["items"][0]["request_id"], "req_visual_debug")
        self.assertEqual(live_data["items"][0]["processing_state"], "done")
        self.assertIn("debug_frame_id", live_data["items"][0])
        self.assertIn("received_timestamp", live_data["items"][0])
        self.assertIn("data:image/jpeg;base64,", live_data["items"][0]["image_preview"]["data_uri"])

    def test_visual_debug_upserts_processing_item_by_request_id(self) -> None:
        with patch("cloud.app.main.settings", self.patched_settings("mock")):
            processing_item = main_module.build_visual_debug_received_item(
                debug_frame_id="frame_processing",
                request_id="req_processing",
                capture_id="cap_processing",
                venue_id="venue_exhibition_demo",
                capture_mode="glasses_private_stream",
                image_filename="rokid_frame.jpg",
                image_content_type="image/jpeg",
                image_bytes=b"not-a-real-jpeg",
                candidate_floor_id=None,
                target_poi_id=None,
                debug_target=None,
                received_timestamp=123,
                image_preview={"available": False, "error": "image_decode_failed"},
            )
        main_module.remember_visual_locate_debug(processing_item)
        main_module.remember_visual_locate_debug(
            {
                "debug_frame_id": "frame_processing",
                "request_id": "req_processing",
                "status": "ok",
                "processing_state": "done",
                "confidence": 0.9,
                "timestamp": 456,
            }
        )

        self.assertEqual(len(main_module.RECENT_VISUAL_LOCATE_DEBUGS), 1)
        item = main_module.RECENT_VISUAL_LOCATE_DEBUGS[0]
        self.assertEqual(item["status"], "ok")
        self.assertEqual(item["processing_state"], "done")
        self.assertEqual(item["received_timestamp"], 123)
        self.assertEqual(item["image_preview"]["error"], "image_decode_failed")

    def test_visual_debug_keeps_reused_request_id_as_separate_frames(self) -> None:
        first_item = {
            "debug_frame_id": "frame_1",
            "request_id": "same_req",
            "status": "done",
            "received_timestamp": 100,
            "timestamp": 100,
        }
        second_item = {
            "debug_frame_id": "frame_2",
            "request_id": "same_req",
            "status": "processing",
            "received_timestamp": 200,
            "timestamp": 200,
        }
        main_module.remember_visual_locate_debug(first_item)
        main_module.remember_visual_locate_debug(second_item)

        self.assertEqual(len(main_module.RECENT_VISUAL_LOCATE_DEBUGS), 2)
        self.assertEqual(main_module.RECENT_VISUAL_LOCATE_DEBUGS[0]["debug_frame_id"], "frame_2")
        self.assertEqual(main_module.RECENT_VISUAL_LOCATE_DEBUGS[1]["debug_frame_id"], "frame_1")

    def test_real_ocr_adapter_candidate_maps_to_landmark(self) -> None:
        fake_result = RealOcrResult(
            status="ok",
            failure_stage=None,
            message="real OCR adapter produced logo candidates",
            candidates=[
                RealOcrCandidate(
                    label="B17",
                    class_index=17,
                    score=0.91,
                    detection_confidence=0.95,
                    classification_confidence=0.96,
                    bbox=(10, 20, 80, 60),
                )
            ],
            details={"labels_configured": True, "class_source": "test"},
        )
        with (
            patch("cloud.app.main.settings", self.patched_settings("real_ocr_adapter")),
            patch("cloud.app.services.landmark_recognition.run_real_ocr_adapter", return_value=fake_result),
        ):
            response = self.client.post(
                "/api/v1/localization/visual-locate",
                files={"image": ("rokid_frame.jpg", b"demo-image", "image/jpeg")},
                data={
                    "request_id": "req_real_ocr_b17",
                    "capture_id": "cap_real_ocr_b17",
                    "venue_id": "venue_exhibition_demo",
                    "capture_timestamp_ms": "1777358700000",
                    "capture_mode": "glasses_private_stream",
                },
            )
            debug_page = self.client.get("/debug/visual-locate")

        payload = response.json()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(payload["data"]["status"], "ok")
        self.assertEqual(payload["data"]["matched_landmark"]["landmark_id"], "lm_booth_b17_card")
        self.assertEqual(payload["data"]["matched_landmark"]["match_source"], "real_ocr_adapter")
        self.assertEqual(payload["data"]["position"], {"x": 36.0, "y": 12.0})
        self.assertIn("real_ocr_adapter", debug_page.text)
        self.assertIn("B17", debug_page.text)

    def test_real_ocr_adapter_unmapped_candidate_returns_explainable_not_found(self) -> None:
        fake_result = RealOcrResult(
            status="ok",
            failure_stage=None,
            message="real OCR adapter produced logo candidates",
            candidates=[
                RealOcrCandidate(
                    label="unknown_brand",
                    class_index=3,
                    score=0.86,
                    detection_confidence=0.9,
                    classification_confidence=0.96,
                    bbox=(10, 20, 80, 60),
                )
            ],
            details={"labels_configured": True, "class_source": "test"},
        )
        with (
            patch("cloud.app.main.settings", self.patched_settings("real_ocr_adapter")),
            patch("cloud.app.services.landmark_recognition.run_real_ocr_adapter", return_value=fake_result),
        ):
            response = self.client.post(
                "/api/v1/localization/visual-locate",
                files={"image": ("rokid_frame.jpg", b"demo-image", "image/jpeg")},
                data={
                    "request_id": "req_real_ocr_unmapped",
                    "capture_id": "cap_real_ocr_unmapped",
                    "venue_id": "venue_exhibition_demo",
                    "capture_timestamp_ms": "1777358700000",
                    "capture_mode": "glasses_private_stream",
                },
            )

        payload = response.json()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(payload["data"]["status"], "not_found")
        self.assertEqual(payload["data"]["failure_stage"], "real_ocr_no_landmark_binding")


if __name__ == "__main__":
    unittest.main()
