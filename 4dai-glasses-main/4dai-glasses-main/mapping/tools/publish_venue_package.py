#!/usr/bin/env python3
"""Validate and package a venue package into a zip archive."""

from __future__ import annotations

import argparse
import json
import sys
import zipfile
from pathlib import Path

try:
    from .validate_venue_package import build_declared_checksums, load_json, validate_package
except ImportError:
    from validate_venue_package import build_declared_checksums, load_json, validate_package


def build_archive_name(package_dir: Path, summary: dict[str, object]) -> str:
    venue_id = str(summary.get("venue_id") or package_dir.name)
    package_version = str(summary.get("package_version") or "0.0.0")
    return f"{venue_id}_{package_version}.zip"


def normalize_archive_name(raw_name: str) -> str:
    archive_name = Path(raw_name).name
    if not archive_name:
        raise ValueError("archive name must not be empty")
    if not archive_name.endswith(".zip"):
        archive_name = f"{archive_name}.zip"
    return archive_name


def create_archive(package_dir: Path, output_dir: Path, archive_name: str) -> Path:
    output_dir.mkdir(parents=True, exist_ok=True)
    archive_path = output_dir / archive_name
    with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_DEFLATED) as handle:
        for file_path in sorted(package_dir.rglob("*")):
            if file_path.is_file():
                handle.write(file_path, file_path.relative_to(package_dir))
    return archive_path


def write_json(path: Path, payload: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=True), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate and publish a venue package zip.")
    parser.add_argument("package_dir", help="Path to venue package directory")
    parser.add_argument(
        "--output-dir",
        default="dist",
        help="Directory used for output zip archives (default: dist)",
    )
    parser.add_argument(
        "--archive-name",
        default="",
        help="Optional explicit zip filename",
    )
    parser.add_argument(
        "--report-json",
        default="",
        help="Optional explicit path for the publish report JSON",
    )
    parser.add_argument(
        "--strict-warnings",
        action="store_true",
        help="Abort publish when validation warnings are present",
    )
    args = parser.parse_args()

    package_dir = Path(args.package_dir).resolve()
    output_dir = Path(args.output_dir).resolve()

    try:
        output_dir.relative_to(package_dir)
    except ValueError:
        pass
    else:
        print("Output directory must not be inside the package directory.")
        return 1

    try:
        result = validate_package(package_dir)
    except (OSError, ValueError) as exc:
        print(f"Package validation failed. Publish aborted.\n  - {exc}")
        return 1
    if not result.ok:
        print("Package validation failed. Publish aborted.")
        for error in result.errors:
            print(f"  - {error}")
        return 1
    if args.strict_warnings and result.warnings:
        print("Package validation produced warnings. Publish aborted due to --strict-warnings.")
        for warning in result.warnings:
            print(f"  - {warning}")
        return 1

    try:
        archive_name = normalize_archive_name(args.archive_name or build_archive_name(package_dir, result.summary))
    except ValueError as exc:
        print(str(exc))
        return 1
    archive_path = create_archive(package_dir, output_dir, archive_name)

    manifest = load_json(package_dir / "manifest.json")
    checksums = build_declared_checksums(package_dir, manifest.get("files", []))
    report_payload = {
        "status": "published",
        "package_dir": str(package_dir),
        "archive_path": str(archive_path),
        "summary": result.summary,
        "warnings": result.warnings,
        "checksums": checksums,
    }

    report_path = Path(args.report_json).resolve() if args.report_json else archive_path.with_suffix(".report.json")
    checksums_path = archive_path.with_suffix(".checksums.json")
    write_json(report_path, report_payload)
    write_json(
        checksums_path,
        {
            "package_dir": str(package_dir),
            "archive_path": str(archive_path),
            "checksums": checksums,
        },
    )

    print(json.dumps(report_payload, indent=2, ensure_ascii=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
