from __future__ import annotations

import json
import time
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient

import cloud.app.main as main_module
from cloud.app.main import app
from cloud.app.services.venue_package import VenuePackageError


class CloudApiBehaviorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.client = TestClient(app)
        self.repo_root = Path(__file__).resolve().parents[2]

    def test_validation_error_maps_to_business_code(self) -> None:
        response = self.client.post(
            "/api/v1/navigation/indoor-route",
            headers={"X-Request-Id": "req_invalid_001"},
            json={"venue_id": "venue_demo_001"},
        )
        payload = response.json()
        self.assertEqual(response.status_code, 400)
        self.assertEqual(payload["code"], 1001)
        self.assertEqual(payload["request_id"], "req_invalid_001")
        self.assertIn("validation_errors", payload["data"])

    def test_route_timeout_returns_business_timeout_code(self) -> None:
        original_plan_route = main_module.plan_route

        def slow_plan_route(*args, **kwargs):
            time.sleep(0.05)
            return original_plan_route(*args, **kwargs)

        patched_settings = replace(main_module.settings, route_timeout_ms=1)
        with patch("cloud.app.main.settings", patched_settings), patch(
            "cloud.app.main.plan_route",
            side_effect=slow_plan_route,
        ):
            response = self.client.post(
                "/api/v1/navigation/indoor-route",
                json={
                    "request_id": "req_route_timeout_001",
                    "venue_id": "venue_demo_001",
                    "floor_id": "F1",
                    "start_position": {"x": 5.1, "y": 8.1},
                    "target_poi_id": "poi_store_a",
                    "route_strategy": "fastest",
                },
            )
        payload = response.json()
        self.assertEqual(response.status_code, 504)
        self.assertEqual(payload["code"], 3002)
        self.assertEqual(payload["request_id"], "req_route_timeout_001")

    def test_relocalization_timeout_returns_business_timeout_code(self) -> None:
        image_path = (
            self.repo_root / "mapping" / "examples" / "venue-package-example" / "localization" / "images" / "kf_0001.jpg"
        )
        original_localize = main_module.BaselineRelocalizer.localize

        def slow_localize(*args, **kwargs):
            time.sleep(0.05)
            return original_localize(*args, **kwargs)

        patched_settings = replace(main_module.settings, relocalization_timeout_ms=1)
        with patch("cloud.app.main.settings", patched_settings), patch.object(
            main_module.BaselineRelocalizer,
            "localize",
            side_effect=slow_localize,
        ):
            with image_path.open("rb") as handle:
                response = self.client.post(
                    "/api/v1/localization/visual-locate",
                    files={"image": ("kf_0001.jpg", handle.read(), "image/jpeg")},
                    data={
                        "request_id": "req_loc_timeout_001",
                        "venue_id": "venue_demo_001",
                        "timestamp": "2026-04-28T12:05:00+08:00",
                        "capture_mode": "glasses_album_sync",
                        "device_id": "device_001",
                        "candidate_floor_id": "F1",
                    },
                )
        payload = response.json()
        self.assertEqual(response.status_code, 504)
        self.assertEqual(payload["code"], 2003)
        self.assertEqual(payload["request_id"], "req_loc_timeout_001")

    def test_health_reports_package_load_error(self) -> None:
        package_error = VenuePackageError(
            "venue package required file missing",
            package_root=self.repo_root / "missing-package",
            stage="missing_file",
            details={"missing_files": ["route_graph.json"]},
        )
        with patch("cloud.app.main.load_bundle", side_effect=package_error):
            response = self.client.get("/api/v1/health", headers={"X-Request-Id": "req_health_bad_pkg"})
        payload = response.json()
        self.assertEqual(response.status_code, 500)
        self.assertEqual(payload["code"], 9001)
        self.assertEqual(payload["message"], "venue package required file missing")
        self.assertEqual(payload["data"]["stage"], "missing_file")
        self.assertIn("route_graph.json", payload["data"]["missing_files"])

    def test_auth_placeholder_rejects_missing_bearer_when_enabled(self) -> None:
        patched_settings = replace(main_module.settings, auth_enabled=True, api_token="secret-token")
        with patch("cloud.app.main.settings", patched_settings):
            response = self.client.get("/api/v1/venues/venue_demo_001/meta")

        payload = response.json()
        self.assertEqual(response.status_code, 401)
        self.assertEqual(payload["code"], 4001)
        self.assertEqual(payload["message"], "unauthorized")

    def test_auth_placeholder_allows_expected_bearer_when_enabled(self) -> None:
        patched_settings = replace(main_module.settings, auth_enabled=True, api_token="secret-token")
        with patch("cloud.app.main.settings", patched_settings):
            response = self.client.get(
                "/api/v1/venues/venue_demo_001/meta",
                headers={"Authorization": "Bearer secret-token"},
            )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["code"], 0)

    def test_rate_limit_placeholder_returns_429_when_enabled(self) -> None:
        main_module.RATE_LIMIT_WINDOWS.clear()
        patched_settings = replace(main_module.settings, auth_enabled=False, rate_limit_per_minute=1)
        with patch("cloud.app.main.settings", patched_settings):
            first = self.client.get("/api/v1/venues/venue_demo_001/meta", headers={"X-Request-Id": "req_rate_1"})
            second = self.client.get("/api/v1/venues/venue_demo_001/meta", headers={"X-Request-Id": "req_rate_2"})
        main_module.RATE_LIMIT_WINDOWS.clear()

        self.assertEqual(first.status_code, 200)
        payload = second.json()
        self.assertEqual(second.status_code, 429)
        self.assertEqual(payload["code"], 4002)
        self.assertEqual(payload["data"]["limit_per_minute"], 1)

    def test_visual_locate_rejects_unknown_candidate_floor(self) -> None:
        response = self.client.post(
            "/api/v1/localization/visual-locate",
            files={"image": ("sample.jpg", b"sample-image-payload", "image/jpeg")},
            data={
                "request_id": "req_loc_bad_floor",
                "venue_id": "venue_demo_001",
                "timestamp": "2026-04-28T12:05:00+08:00",
                "capture_mode": "phone_camera_fallback",
                "device_id": "device_001",
                "candidate_floor_id": "F99",
            },
        )
        payload = response.json()
        self.assertEqual(response.status_code, 404)
        self.assertEqual(payload["code"], 1003)
        self.assertEqual(payload["data"]["floor_id"], "F99")

    def test_visual_locate_rejects_invalid_capture_mode(self) -> None:
        response = self.client.post(
            "/api/v1/localization/visual-locate",
            files={"image": ("sample.jpg", b"sample-image-payload", "image/jpeg")},
            data={
                "request_id": "req_loc_bad_mode",
                "venue_id": "venue_demo_001",
                "timestamp": "2026-04-28T12:05:00+08:00",
                "capture_mode": "unknown_source",
                "device_id": "device_001",
            },
        )
        payload = response.json()
        self.assertEqual(response.status_code, 400)
        self.assertEqual(payload["code"], 1001)
        self.assertEqual(payload["data"]["field"], "capture_mode")

    def test_visual_locate_accepts_route_prior_json_form(self) -> None:
        image_path = (
            self.repo_root / "mapping" / "examples" / "venue-package-example" / "localization" / "images" / "kf_0001.jpg"
        )
        with image_path.open("rb") as handle:
            response = self.client.post(
                "/api/v1/localization/visual-locate",
                files={"image": ("kf_0001.jpg", handle.read(), "image/jpeg")},
                data={
                    "request_id": "req_loc_route_prior",
                    "venue_id": "venue_demo_001",
                    "timestamp": "2026-04-28T12:05:00+08:00",
                    "capture_mode": "glasses_album_sync",
                    "device_id": "device_001",
                    "candidate_floor_id": "F1",
                    "route_prior": json.dumps(
                        {
                            "route_id": "route_req_route_001",
                            "edge_ids": ["edge_f1_entry_to_escalator"],
                            "corridor_window_m": 2.0,
                        }
                    ),
                },
            )
        payload = response.json()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(payload["data"]["status"], "ok")

    def test_visual_locate_rejects_invalid_route_prior(self) -> None:
        response = self.client.post(
            "/api/v1/localization/visual-locate",
            files={"image": ("sample.jpg", b"sample-image-payload", "image/jpeg")},
            data={
                "request_id": "req_loc_bad_route_prior",
                "venue_id": "venue_demo_001",
                "timestamp": "2026-04-28T12:05:00+08:00",
                "capture_mode": "phone_camera_fallback",
                "device_id": "device_001",
                "route_prior": "{bad-json",
            },
        )
        payload = response.json()
        self.assertEqual(response.status_code, 400)
        self.assertEqual(payload["code"], 1001)
        self.assertEqual(payload["data"]["field"], "route_prior")


if __name__ == "__main__":
    unittest.main()
