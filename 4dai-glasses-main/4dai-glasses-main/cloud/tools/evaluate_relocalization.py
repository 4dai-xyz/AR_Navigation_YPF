from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser(description="Run offline visual relocalization evaluation.")
    parser.add_argument("--queries", required=True, help="JSONL query file.")
    parser.add_argument("--query-root", help="Base directory for relative image_path values.")
    parser.add_argument("--venue-package-root", help="Override AI_GLASSES_VENUE_PACKAGE_ROOT for this run.")
    parser.add_argument("--report-json", help="Optional path to write the full report JSON.")
    parser.add_argument("--failure-json", help="Optional path to write failure_samples JSON.")
    parser.add_argument("--max-failure-samples", type=int, default=20, help="Maximum failure samples kept in report.")
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[2]
    if str(repo_root) not in sys.path:
        sys.path.insert(0, str(repo_root))
    if args.venue_package_root:
        os.environ["AI_GLASSES_VENUE_PACKAGE_ROOT"] = str(Path(args.venue_package_root).resolve())

    from cloud.app.services.offline_relocalization_eval import evaluate_relocalization, load_eval_queries
    from cloud.app.services.venue_package import load_bundle

    query_path = Path(args.queries).resolve()
    query_root = Path(args.query_root).resolve() if args.query_root else query_path.parent
    report = evaluate_relocalization(
        load_bundle(),
        load_eval_queries(query_path),
        query_root=query_root,
        max_failure_samples=args.max_failure_samples,
    )
    if args.report_json:
        Path(args.report_json).resolve().write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    if args.failure_json:
        Path(args.failure_json).resolve().write_text(
            json.dumps(report["failure_samples"], ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
