from __future__ import annotations

import unittest
from pathlib import Path

from cloud.app.models.api import IndoorRouteRequest, Point2D
from cloud.app.services.routing import classify_next_turn, plan_route
from cloud.app.services.venue_package import load_bundle


class RoutingDirectionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        repo_root = Path(__file__).resolve().parents[2]
        cls.bundle = load_bundle(str(repo_root / "mapping" / "examples" / "venue-package-example"))

    def test_classify_next_turn_uses_first_cross_floor_segment(self) -> None:
        next_turn = classify_next_turn(
            self.bundle,
            ["node_f1_escalator_up", "node_f2_escalator_down", "node_f2_store_a"],
        )
        self.assertEqual(next_turn, "take_escalator_up")

    def test_plan_route_reports_up_when_starting_at_f1_escalator(self) -> None:
        route = plan_route(
            self.bundle,
            IndoorRouteRequest(
                request_id="req_route_escalator_001",
                venue_id="venue_demo_001",
                floor_id="F1",
                start_position=Point2D(x=14.0, y=12.0),
                target_poi_id="poi_store_a",
                route_strategy="fastest",
            ),
        )
        self.assertEqual(route.path_nodes[0], "node_f1_escalator_up")
        self.assertEqual(route.next_turn, "take_escalator_up")


if __name__ == "__main__":
    unittest.main()
