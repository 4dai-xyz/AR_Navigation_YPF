from __future__ import annotations

import json
import shutil
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch

from cloud.app.services.venue_package import VenuePackageError, load_bundle
import cloud.app.services.venue_package as venue_package_module


class VenuePackageLoadingTest(unittest.TestCase):
    def setUp(self) -> None:
        self.repo_root = Path(__file__).resolve().parents[2]
        self.example_package = self.repo_root / "mapping" / "examples" / "venue-package-example"
        load_bundle.cache_clear()

    def tearDown(self) -> None:
        load_bundle.cache_clear()

    def copy_example_package(self, target: Path) -> Path:
        package_root = target / "venue-package"
        shutil.copytree(self.example_package, package_root)
        return package_root

    def test_missing_required_file_is_explainable(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            package_root = self.copy_example_package(Path(temp_dir))
            (package_root / "route_graph.json").unlink()

            with self.assertRaises(VenuePackageError) as context:
                load_bundle(str(package_root))

        error = context.exception
        self.assertEqual(error.stage, "missing_file")
        self.assertEqual(error.message, "venue package required file missing")
        self.assertIn("route_graph.json", error.to_details()["missing_files"])

    def test_invalid_route_graph_reference_is_explainable(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            package_root = self.copy_example_package(Path(temp_dir))
            route_graph_path = package_root / "route_graph.json"
            route_graph = json.loads(route_graph_path.read_text(encoding="utf-8"))
            route_graph["edges"][0]["from_node_id"] = "node_missing"
            route_graph_path.write_text(json.dumps(route_graph), encoding="utf-8")

            with self.assertRaises(VenuePackageError) as context:
                load_bundle(str(package_root))

        error = context.exception
        self.assertEqual(error.stage, "validation")
        validation_errors = error.to_details()["validation_errors"]
        self.assertTrue(any("unknown node 'node_missing'" in item for item in validation_errors))

    def test_unknown_keyframe_intrinsics_is_explainable(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            package_root = self.copy_example_package(Path(temp_dir))
            keyframes_path = package_root / "localization" / "keyframes.jsonl"
            rows = [json.loads(line) for line in keyframes_path.read_text(encoding="utf-8").splitlines()]
            rows[0]["intrinsics_id"] = "cam_missing"
            keyframes_path.write_text("\n".join(json.dumps(row) for row in rows), encoding="utf-8")

            with self.assertRaises(VenuePackageError) as context:
                load_bundle(str(package_root))

        validation_errors = context.exception.to_details()["validation_errors"]
        self.assertTrue(any("keyframes[0].intrinsics_id: unknown camera 'cam_missing'" in item for item in validation_errors))

    def test_connector_unknown_edge_is_explainable(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            package_root = self.copy_example_package(Path(temp_dir))
            connectors_path = package_root / "connectors.json"
            connectors = json.loads(connectors_path.read_text(encoding="utf-8"))
            connectors["connectors"][0]["edge_id"] = "edge_missing"
            connectors_path.write_text(json.dumps(connectors), encoding="utf-8")

            with self.assertRaises(VenuePackageError) as context:
                load_bundle(str(package_root))

        validation_errors = context.exception.to_details()["validation_errors"]
        self.assertTrue(any("connectors[0].edge_id: unknown edge 'edge_missing'" in item for item in validation_errors))

    def test_package_version_mismatch_is_explainable(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            package_root = self.copy_example_package(Path(temp_dir))
            patched_settings = replace(venue_package_module.settings, venue_package_version="9.9.9")
            with patch("cloud.app.services.venue_package.settings", patched_settings):
                with self.assertRaises(VenuePackageError) as context:
                    load_bundle(str(package_root))

        details = context.exception.to_details()
        self.assertEqual(context.exception.stage, "validation")
        self.assertEqual(details["expected_package_version"], "9.9.9")
        self.assertEqual(details["actual_package_version"], "0.1.0")


if __name__ == "__main__":
    unittest.main()
