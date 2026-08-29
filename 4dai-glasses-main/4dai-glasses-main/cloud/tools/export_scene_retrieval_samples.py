from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

import cv2


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


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as output:
        for row in rows:
            output.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")


def booth_sort_key(booth_id: str) -> tuple[str, int]:
    return booth_id[0], int(booth_id[1:])


def blur_score(row: dict[str, Any]) -> float:
    quality = row.get("quality") if isinstance(row.get("quality"), dict) else {}
    return float(quality.get("blur_laplacian_var") or 0.0)


def select_rows(
    rows: list[dict[str, Any]],
    min_blur: float,
    max_per_booth: int,
    min_gap_ms: int,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    groups: dict[str, list[dict[str, Any]]] = {}
    rejected_blur: dict[str, int] = {}
    for row in rows:
        if row.get("label_status") != "labeled" or not row.get("booth_id"):
            continue
        booth_id = str(row["booth_id"]).upper()
        if blur_score(row) < min_blur:
            rejected_blur[booth_id] = rejected_blur.get(booth_id, 0) + 1
            continue
        groups.setdefault(booth_id, []).append(row)

    selected: list[dict[str, Any]] = []
    selected_counts: dict[str, int] = {}
    for booth_id in sorted(groups, key=booth_sort_key):
        last_timestamp_ms: int | None = None
        booth_rows = sorted(groups[booth_id], key=lambda item: int(item["timestamp_ms"]))
        filtered_by_gap: list[dict[str, Any]] = []
        for row in booth_rows:
            timestamp_ms = int(row["timestamp_ms"])
            if last_timestamp_ms is not None and timestamp_ms - last_timestamp_ms < min_gap_ms:
                continue
            filtered_by_gap.append(row)
            last_timestamp_ms = timestamp_ms

        if len(filtered_by_gap) > max_per_booth:
            if max_per_booth <= 1:
                filtered_by_gap = filtered_by_gap[:1]
            else:
                step = (len(filtered_by_gap) - 1) / (max_per_booth - 1)
                filtered_by_gap = [filtered_by_gap[round(index * step)] for index in range(max_per_booth)]

        selected.extend(filtered_by_gap)
        selected_counts[booth_id] = len(filtered_by_gap)

    summary = {
        "schema_version": "scene_retrieval_sample_export_summary_v0.1",
        "source_labeled_keyframes": len(rows),
        "min_blur_laplacian_var": min_blur,
        "max_per_booth": max_per_booth,
        "min_gap_ms": min_gap_ms,
        "selected_total": len(selected),
        "selected_counts": selected_counts,
        "rejected_by_blur_counts": dict(sorted(rejected_blur.items())),
        "low_count_booths_lt15": {
            booth_id: count for booth_id, count in selected_counts.items() if count < 15
        },
    }
    return sorted(selected, key=lambda item: int(item["source_frame_index"])), summary


def export_images(
    video_path: Path,
    selected_rows: list[dict[str, Any]],
    output_root: Path,
    image_width: int,
    jpeg_quality: int,
) -> list[dict[str, Any]]:
    if not selected_rows:
        return []

    cap = cv2.VideoCapture(str(video_path))
    if not cap.isOpened():
        raise ValueError(f"{video_path}: OpenCV failed to open video")

    rows_by_frame: dict[int, list[dict[str, Any]]] = {}
    for row in selected_rows:
        rows_by_frame.setdefault(int(row["source_frame_index"]), []).append(row)

    max_frame_index = max(rows_by_frame)
    exported: list[dict[str, Any]] = []
    frame_index = 0
    while frame_index <= max_frame_index:
        ok, frame = cap.read()
        if not ok:
            break
        if frame_index in rows_by_frame:
            for row in rows_by_frame[frame_index]:
                booth_id = str(row["booth_id"]).upper()
                booth_dir = output_root / "images" / booth_id
                booth_dir.mkdir(parents=True, exist_ok=True)
                timestamp_ms = int(row["timestamp_ms"])
                image_ref = f"images/{booth_id}/{row['keyframe_id']}_{timestamp_ms:08d}ms.jpg"
                output_path = output_root / image_ref
                scale = image_width / frame.shape[1]
                resized = cv2.resize(frame, (image_width, int(frame.shape[0] * scale)))
                cv2.imwrite(str(output_path), resized, [int(cv2.IMWRITE_JPEG_QUALITY), jpeg_quality])
                exported_row = dict(row)
                exported_row["sample_image_ref"] = image_ref.replace("\\", "/")
                exported.append(exported_row)
        frame_index += 1

    cap.release()
    return exported


def main() -> None:
    parser = argparse.ArgumentParser(description="Export labeled scene retrieval image samples.")
    parser.add_argument("--labeled-keyframes", type=Path, required=True)
    parser.add_argument("--video", type=Path, required=True)
    parser.add_argument("--output-root", type=Path, required=True)
    parser.add_argument("--min-blur", type=float, default=80.0)
    parser.add_argument("--max-per-booth", type=int, default=80)
    parser.add_argument("--min-gap-ms", type=int, default=250)
    parser.add_argument("--image-width", type=int, default=540)
    parser.add_argument("--jpeg-quality", type=int, default=90)
    parser.add_argument("--no-write-images", action="store_true")
    args = parser.parse_args()

    rows = read_jsonl(args.labeled_keyframes)
    selected_rows, summary = select_rows(
        rows=rows,
        min_blur=args.min_blur,
        max_per_booth=args.max_per_booth,
        min_gap_ms=args.min_gap_ms,
    )
    if args.no_write_images:
        exported_rows = selected_rows
    else:
        exported_rows = export_images(
            video_path=args.video,
            selected_rows=selected_rows,
            output_root=args.output_root,
            image_width=args.image_width,
            jpeg_quality=args.jpeg_quality,
        )
        summary["exported_total"] = len(exported_rows)

    write_jsonl(args.output_root / "scene_retrieval_samples.jsonl", exported_rows)
    write_json(args.output_root / "scene_retrieval_samples_summary.json", summary)
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
