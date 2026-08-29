from __future__ import annotations

import unittest
from pathlib import Path

from fastapi.testclient import TestClient

from cloud.app.main import app


class CloudSmokeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.client = TestClient(app)
        self.repo_root = Path(__file__).resolve().parents[2]

    def test_health(self) -> None:
        response = self.client.get("/api/v1/health")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["data"]["status"], "healthy")

    def test_venue_meta(self) -> None:
        response = self.client.get("/api/v1/venues/venue_demo_001/meta")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["data"]["package_version"], "0.1.0")

    def test_route(self) -> None:
        response = self.client.post(
            "/api/v1/navigation/indoor-route",
            json={
                "request_id": "req_route_001",
                "venue_id": "venue_demo_001",
                "floor_id": "F1",
                "start_position": {"x": 5.1, "y": 8.1},
                "target_poi_id": "poi_store_a",
                "route_strategy": "fastest",
            },
        )
        self.assertEqual(response.status_code, 200)
        self.assertGreaterEqual(len(response.json()["data"]["path_nodes"]), 2)

    def test_visual_locate_ok(self) -> None:
        image_path = (
            self.repo_root / "mapping" / "examples" / "venue-package-example" / "localization" / "images" / "kf_0001.jpg"
        )
        with image_path.open("rb") as handle:
            response = self.client.post(
                "/api/v1/localization/visual-locate",
                files={"image": ("kf_0001.jpg", handle.read(), "image/jpeg")},
                data={
                    "request_id": "req_loc_001",
                    "venue_id": "venue_demo_001",
                    "timestamp": "2026-04-28T12:05:00+08:00",
                    "capture_mode": "glasses_album_sync",
                    "device_id": "device_001",
                    "candidate_floor_id": "F1",
                },
            )
        self.assertEqual(response.status_code, 200)
        payload = response.json()
        self.assertEqual(payload["data"]["status"], "ok")
        self.assertEqual(payload["data"]["floor_id"], "F1")

    def test_visual_locate_not_found(self) -> None:
        response = self.client.post(
            "/api/v1/localization/visual-locate",
            files={"image": ("tiny.jpg", b"x", "image/jpeg")},
            data={
                "request_id": "req_loc_002",
                "venue_id": "venue_demo_001",
                "timestamp": "2026-04-28T12:05:00+08:00",
                "capture_mode": "phone_camera_fallback",
                "device_id": "device_001",
            },
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["data"]["status"], "not_found")


if __name__ == "__main__":
    unittest.main()
