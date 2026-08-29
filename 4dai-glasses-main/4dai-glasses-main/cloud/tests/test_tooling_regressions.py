from __future__ import annotations

import io
import json
import shutil
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))
TOOLS_DIR = REPO_ROOT / "mapping" / "tools"
if str(TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(TOOLS_DIR))

from mapping.algorithms.relocalization import evaluate_offline
from validate_venue_package import validate_package


class VenuePackageValidationRegressionTest(unittest.TestCase):
    def _copy_example_package(self, temp_root: Path) -> Path:
        source_dir = REPO_ROOT / "mapping" / "examples" / "venue-package-example"
        package_dir = temp_root / "venue-package-example"
        shutil.copytree(source_dir, package_dir)
        return package_dir

    def _rewrite_first_manifest_path(self, package_dir: Path, raw_path: str) -> None:
        manifest_path = package_dir / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["files"][0]["path"] = raw_path
        manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=True), encoding="utf-8")

    def test_validate_package_rejects_parent_escape_path(self) -> None:
        with tempfile.TemporaryDirectory() as temp_root_str:
            temp_root = Path(temp_root_str)
            package_dir = self._copy_example_package(temp_root)
            (temp_root / "outside.txt").write_text("outside", encoding="utf-8")
            self._rewrite_first_manifest_path(package_dir, "../outside.txt")

            result = validate_package(package_dir)

        self.assertFalse(result.ok)
        self.assertIn("manifest.files[0]: path must not resolve outside package", result.errors)

    def test_validate_package_rejects_absolute_path(self) -> None:
        with tempfile.TemporaryDirectory() as temp_root_str:
            temp_root = Path(temp_root_str)
            package_dir = self._copy_example_package(temp_root)
            outside_path = temp_root / "outside.txt"
            outside_path.write_text("outside", encoding="utf-8")
            self._rewrite_first_manifest_path(package_dir, str(outside_path))

            result = validate_package(package_dir)

        self.assertFalse(result.ok)
        self.assertIn("manifest.files[0]: path must be a relative path inside package", result.errors)


class OfflineEvaluationRegressionTest(unittest.TestCase):
    def test_report_json_matches_stdout_after_failure_export(self) -> None:
        fixture_path = REPO_ROOT / "mapping" / "algorithms" / "relocalization" / "fixtures" / "baseline_eval_fixture.json"
        with tempfile.TemporaryDirectory() as temp_root_str:
            temp_root = Path(temp_root_str)
            report_path = temp_root / "report.json"
            failure_dir = temp_root / "failures"
            stdout_buffer = io.StringIO()
            argv = [
                "evaluate_offline.py",
                str(fixture_path),
                "--report-json",
                str(report_path),
                "--failure-dir",
                str(failure_dir),
            ]
            with patch.object(sys, "argv", argv), redirect_stdout(stdout_buffer):
                exit_code = evaluate_offline.main()

            stdout_payload = json.loads(stdout_buffer.getvalue())
            report_payload = json.loads(report_path.read_text(encoding="utf-8"))

        self.assertEqual(exit_code, 0)
        self.assertIn("exported_failure_samples", stdout_payload)
        self.assertEqual(report_payload, stdout_payload)


if __name__ == "__main__":
    unittest.main()
