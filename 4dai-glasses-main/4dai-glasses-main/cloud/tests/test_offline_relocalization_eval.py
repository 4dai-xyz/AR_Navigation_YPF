from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from cloud.app.services.offline_relocalization_eval import evaluate_relocalization, load_eval_queries
from cloud.app.services.venue_package import load_bundle


class OfflineRelocalizationEvalTest(unittest.TestCase):
    def setUp(self) -> None:
        repo_root = Path(__file__).resolve().parents[2]
        self.package_root = repo_root / "mapping" / "examples" / "venue-package-example"
        load_bundle.cache_clear()
        self.bundle = load_bundle(str(self.package_root))

    def test_evaluate_sample_query(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            queries_path = Path(temp_dir) / "queries.jsonl"
            queries_path.write_text(
                '{"query_id":"q1","venue_id":"venue_demo_001",'
                '"image_path":"localization/images/kf_0001.jpg",'
                '"expected_keyframe_id":"kf_0001","expected_floor_id":"F1"}\n',
                encoding="utf-8",
            )

            queries = load_eval_queries(queries_path)
            report = evaluate_relocalization(self.bundle, queries, query_root=self.package_root)

        self.assertEqual(report["query_count"], 1)
        self.assertEqual(report["localized_count"], 1)
        self.assertEqual(report["top1_accuracy"], 1.0)
        self.assertEqual(report["top3_accuracy"], 1.0)
        self.assertEqual(report["floor_accuracy"], 1.0)
        self.assertEqual(report["report_schema_version"], "cloud_relocalization_eval_v1")
        self.assertEqual(report["by_floor"]["F1"]["query_count"], 1)
        self.assertEqual(report["by_venue"]["venue_demo_001"]["top1_accuracy"], 1.0)

    def test_missing_image_is_reported(self) -> None:
        report = evaluate_relocalization(
            self.bundle,
            [
                load_eval_queries_from_text(
                    '{"query_id":"q_missing","venue_id":"venue_demo_001","image_path":"missing.jpg"}'
                )
            ],
            query_root=self.package_root,
        )

        self.assertEqual(report["query_count"], 1)
        self.assertEqual(report["localized_count"], 0)
        self.assertEqual(report["failure_stage_counts"]["image_file_missing"], 1)
        self.assertEqual(report["by_venue"]["venue_demo_001"]["failure_stage_counts"]["image_file_missing"], 1)


def load_eval_queries_from_text(payload: str):
    with tempfile.TemporaryDirectory() as temp_dir:
        path = Path(temp_dir) / "queries.jsonl"
        path.write_text(payload + "\n", encoding="utf-8")
        return load_eval_queries(path)[0]


if __name__ == "__main__":
    unittest.main()
