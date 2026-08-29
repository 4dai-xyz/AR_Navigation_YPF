from __future__ import annotations

import argparse
import csv
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class LabelSegment:
    start_ms: int
    end_ms: int
    booth_id: str | None
    label_status: str
    confidence: float | None
    notes: str


def read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as input_file:
        for line_no, line in enumerate(input_file, start=1):
            stripped = line.strip()
            if not stripped:
                continue
            try:
                rows.append(json.loads(stripped))
            except json.JSONDecodeError as exc:
                raise ValueError(f"{path}:{line_no}: invalid JSONL row") from exc
    return rows


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as output:
        for row in rows:
            output.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def open_csv_with_fallback(path: Path) -> tuple[str, str]:
    raw = path.read_bytes()
    for encoding in ("utf-8-sig", "utf-8", "gb18030"):
        try:
            return raw.decode(encoding), encoding
        except UnicodeDecodeError:
            continue
    raise ValueError(f"{path}: failed to decode CSV as utf-8-sig, utf-8 or gb18030")


def parse_seconds(value: str, field_name: str, row_no: int) -> float:
    try:
        parsed = float(value)
    except ValueError as exc:
        raise ValueError(f"segments row {row_no}: {field_name} must be seconds") from exc
    if parsed < 0:
        raise ValueError(f"segments row {row_no}: {field_name} must be >= 0")
    return parsed


def normalize_booth_id(value: str | None) -> str | None:
    if value is None:
        return None
    stripped = value.strip().upper()
    return stripped or None


def load_booth_index(booth_coordinates_path: Path) -> dict[str, dict[str, Any]]:
    payload = read_json(booth_coordinates_path)
    booth_index: dict[str, dict[str, Any]] = {}
    for booth in payload.get("booths", []):
        booth_id = str(booth.get("booth_id", "")).strip().upper()
        if booth_id:
            booth_index[booth_id] = booth
    if not booth_index:
        raise ValueError(f"{booth_coordinates_path}: no booths found")
    return booth_index


def load_segments(path: Path, booth_index: dict[str, dict[str, Any]]) -> list[LabelSegment]:
    segments: list[LabelSegment] = []
    csv_text, _encoding = open_csv_with_fallback(path)
    reader = csv.DictReader(row for row in csv_text.splitlines() if not row.lstrip().startswith("#"))
    required_fields = {"start_sec", "end_sec"}
    if reader.fieldnames is None or not required_fields.issubset(reader.fieldnames):
        raise ValueError(f"{path}: required columns are start_sec,end_sec,booth_id,label_status,confidence,notes")
    for row_no, row in enumerate(reader, start=2):
        start_sec = parse_seconds(row.get("start_sec", ""), "start_sec", row_no)
        end_sec = parse_seconds(row.get("end_sec", ""), "end_sec", row_no)
        if end_sec <= start_sec:
            raise ValueError(f"segments row {row_no}: end_sec must be greater than start_sec")

        booth_id = normalize_booth_id(row.get("booth_id"))
        label_status = (row.get("label_status") or ("labeled" if booth_id else "ignore")).strip() or "labeled"
        if label_status not in {"labeled", "ignore", "transition", "unlabeled"}:
            raise ValueError(f"segments row {row_no}: unsupported label_status {label_status!r}")
        if label_status == "labeled" and booth_id is None:
            raise ValueError(f"segments row {row_no}: labeled rows require booth_id")
        if booth_id is not None and booth_id not in booth_index:
            raise ValueError(f"segments row {row_no}: unknown booth_id {booth_id!r}")

        confidence_value = (row.get("confidence") or "").strip()
        confidence = float(confidence_value) if confidence_value else None
        if confidence is not None and not 0.0 <= confidence <= 1.0:
            raise ValueError(f"segments row {row_no}: confidence must be between 0 and 1")

        segments.append(
            LabelSegment(
                start_ms=int(start_sec * 1000),
                end_ms=int(end_sec * 1000),
                booth_id=booth_id,
                label_status=label_status,
                confidence=confidence,
                notes=(row.get("notes") or "").strip(),
            )
        )

    return sorted(segments, key=lambda segment: (segment.start_ms, segment.end_ms))


def find_segment(timestamp_ms: int, segments: list[LabelSegment]) -> LabelSegment | None:
    matched: list[LabelSegment] = [
        segment for segment in segments if segment.start_ms <= timestamp_ms < segment.end_ms
    ]
    if len(matched) > 1:
        ranges = ", ".join(f"{item.start_ms / 1000:.2f}-{item.end_ms / 1000:.2f}" for item in matched)
        raise ValueError(f"timestamp {timestamp_ms / 1000:.2f}s matches overlapping segments: {ranges}")
    return matched[0] if matched else None


def apply_segments(
    keyframes: list[dict[str, Any]],
    segments: list[LabelSegment],
    booth_index: dict[str, dict[str, Any]],
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    labeled = 0
    ignored = 0
    transition = 0
    unmatched = 0
    booth_counts: dict[str, int] = {}
    output_rows: list[dict[str, Any]] = []

    for row in keyframes:
        updated = dict(row)
        timestamp_ms = int(updated.get("timestamp_ms", 0))
        segment = find_segment(timestamp_ms, segments)
        if segment is None:
            updated["label_status"] = "unlabeled"
            updated["label_source"] = "scene_segment_csv"
            unmatched += 1
        elif segment.label_status == "labeled" and segment.booth_id is not None:
            booth = booth_index[segment.booth_id]
            updated["label_status"] = "labeled"
            updated["label_source"] = "scene_segment_csv"
            updated["booth_id"] = segment.booth_id
            updated["poi_id"] = booth.get("poi_id")
            updated["position"] = booth.get("position")
            updated["label_confidence"] = segment.confidence if segment.confidence is not None else 1.0
            updated["label_notes"] = segment.notes
            labeled += 1
            booth_counts[segment.booth_id] = booth_counts.get(segment.booth_id, 0) + 1
        else:
            updated["label_status"] = segment.label_status
            updated["label_source"] = "scene_segment_csv"
            updated["booth_id"] = None
            updated["poi_id"] = None
            updated["position"] = None
            updated["label_confidence"] = segment.confidence
            updated["label_notes"] = segment.notes
            if segment.label_status == "transition":
                transition += 1
            else:
                ignored += 1
        output_rows.append(updated)

    summary = {
        "schema_version": "scene_keyframe_label_summary_v0.1",
        "total_keyframes": len(keyframes),
        "segments": len(segments),
        "labeled_keyframes": labeled,
        "ignored_keyframes": ignored,
        "transition_keyframes": transition,
        "unmatched_keyframes": unmatched,
        "booth_counts": dict(sorted(booth_counts.items())),
    }
    return output_rows, summary


def main() -> None:
    parser = argparse.ArgumentParser(description="Apply manual scene label time segments to keyframe JSONL.")
    parser.add_argument("--keyframes", type=Path, required=True)
    parser.add_argument("--segments", type=Path, required=True)
    parser.add_argument("--booth-coordinates", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--summary-output", type=Path)
    args = parser.parse_args()

    booth_index = load_booth_index(args.booth_coordinates)
    keyframes = read_jsonl(args.keyframes)
    segments = load_segments(args.segments, booth_index)
    labeled_keyframes, summary = apply_segments(keyframes, segments, booth_index)

    write_jsonl(args.output, labeled_keyframes)
    if args.summary_output:
        write_json(args.summary_output, summary)

    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
