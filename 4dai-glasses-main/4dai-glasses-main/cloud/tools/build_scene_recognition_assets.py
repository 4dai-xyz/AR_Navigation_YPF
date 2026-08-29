from __future__ import annotations

import argparse
import json
import math
import re
from pathlib import Path
from typing import Any

import cv2
import numpy as np


BOOTH_LABEL_PATTERN = re.compile(r"^[ABEF]\d{2}$")


def read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as output:
        for row in rows:
            output.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")


def booth_sort_key(label: str) -> tuple[str, int]:
    return label[0], int(label[1:])


def extract_booth_points(labelme_payload: dict[str, Any]) -> list[dict[str, Any]]:
    booths: list[dict[str, Any]] = []
    for shape in labelme_payload.get("shapes", []):
        label = str(shape.get("label", "")).strip().upper()
        points = shape.get("points") or []
        if not BOOTH_LABEL_PATTERN.match(label) or len(points) != 1:
            continue
        x, y = points[0]
        booths.append({"booth_id": label, "x": float(x), "y": float(y)})
    return sorted(booths, key=lambda item: booth_sort_key(item["booth_id"]))


def estimate_pixels_per_meter(booths: list[dict[str, Any]], booth_size_m: float) -> float:
    distances: list[float] = []
    for i, left in enumerate(booths):
        for right in booths[i + 1 :]:
            distance = math.hypot(left["x"] - right["x"], left["y"] - right["y"])
            if 35.0 <= distance <= 75.0:
                distances.append(distance)
    if not distances:
        return 18.0
    distances.sort()
    median_distance = distances[len(distances) // 2]
    return median_distance / booth_size_m


def build_booth_coordinate_table(
    labelme_payload: dict[str, Any],
    source_json: Path,
    source_image: str,
    venue_id: str,
    floor_id: str,
    booth_size_m: float,
) -> dict[str, Any]:
    booths = extract_booth_points(labelme_payload)
    if not booths:
        raise ValueError(f"{source_json}: no booth labels matched {BOOTH_LABEL_PATTERN.pattern}")

    pixels_per_meter = estimate_pixels_per_meter(booths, booth_size_m)
    margin_px = pixels_per_meter * booth_size_m / 2.0
    min_x = min(item["x"] for item in booths) - margin_px
    min_y = min(item["y"] for item in booths) - margin_px
    max_x = max(item["x"] for item in booths) + margin_px
    max_y = max(item["y"] for item in booths) + margin_px

    rows = []
    for booth in booths:
        local_x = (booth["x"] - min_x) / pixels_per_meter
        local_y = (booth["y"] - min_y) / pixels_per_meter
        booth_id = booth["booth_id"]
        rows.append(
            {
                "booth_id": booth_id,
                "poi_id": f"poi_booth_{booth_id.lower()}",
                "display_name": f"{booth_id} booth",
                "venue_id": venue_id,
                "floor_id": floor_id,
                "position": {"x": round(local_x, 2), "y": round(local_y, 2)},
                "source_pixel_position": {"x": round(booth["x"], 2), "y": round(booth["y"], 2)},
                "approximate_footprint_m": {"width": booth_size_m, "depth": booth_size_m},
                "position_source": "huichang_json_point_center_approx",
                "tags": ["booth", "scene_retrieval_candidate"],
                "status": "active",
            }
        )

    return {
        "schema_version": "scene_booth_coordinates_v0.1",
        "venue_id": venue_id,
        "floor_id": floor_id,
        "source": {
            "labelme_json": str(source_json).replace("\\", "/"),
            "source_image": source_image,
            "source_image_width": int(labelme_payload.get("imageWidth", 0)),
            "source_image_height": int(labelme_payload.get("imageHeight", 0)),
        },
        "coordinate_system": {
            "type": "cropped_booth_local_2d",
            "unit": "meter",
            "origin": "top_left_of_booth_point_bbox_with_half_booth_margin",
            "x_axis": "source_image_right_east_approx",
            "y_axis": "source_image_down_south_approx",
            "pixels_per_meter": round(pixels_per_meter, 4),
            "booth_size_m": booth_size_m,
            "cropped_source_bbox_px": {
                "min_x": round(min_x, 2),
                "min_y": round(min_y, 2),
                "max_x": round(max_x, 2),
                "max_y": round(max_y, 2),
            },
            "accuracy_note": "Coordinates are demo-grade booth center approximations derived from huichang.json point labels.",
        },
        "booths": rows,
    }


def frame_quality(frame: np.ndarray) -> dict[str, float]:
    gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    return {
        "blur_laplacian_var": round(float(cv2.Laplacian(gray, cv2.CV_64F).var()), 2),
        "brightness_mean": round(float(gray.mean()), 2),
        "contrast_std": round(float(gray.std()), 2),
    }


def build_video_keyframe_manifest(
    video_path: Path,
    output_root: Path,
    sample_fps: float,
    image_width: int,
    max_frames: int | None,
    write_images: bool,
) -> dict[str, Any]:
    cap = cv2.VideoCapture(str(video_path))
    if not cap.isOpened():
        raise ValueError(f"{video_path}: OpenCV failed to open video")

    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    frame_count = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    duration_sec = frame_count / fps
    source_width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    source_height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    sample_step_frames = max(1, int(round(fps / sample_fps)))

    images_dir = output_root / "images"
    if write_images:
        images_dir.mkdir(parents=True, exist_ok=True)

    rows: list[dict[str, Any]] = []
    index = 0
    frame_index = 0
    target_index = 0
    while frame_index < frame_count:
        if max_frames is not None and index >= max_frames:
            break
        target_frame_index = target_index * sample_step_frames
        while frame_index < target_frame_index:
            if not cap.grab():
                break
            frame_index += 1
        if frame_index < target_frame_index:
            break
        ok, frame = cap.read()
        if not ok:
            break
        timestamp_sec = frame_index / fps

        image_ref = None
        if write_images:
            scale = image_width / frame.shape[1]
            resized = cv2.resize(frame, (image_width, int(frame.shape[0] * scale)))
            image_ref = f"images/yjdd_hd_{index:05d}_{int(timestamp_sec * 1000):08d}ms.jpg"
            cv2.imwrite(str(output_root / image_ref), resized, [int(cv2.IMWRITE_JPEG_QUALITY), 90])

        quality = frame_quality(frame)
        rows.append(
            {
                "keyframe_id": f"yjdd_hd_{index:05d}",
                "source_video": str(video_path).replace("\\", "/"),
                "timestamp_ms": int(timestamp_sec * 1000),
                "source_frame_index": frame_index,
                "image_ref": image_ref,
                "quality": quality,
                "label_status": "unlabeled",
                "booth_id": None,
                "position": None,
            }
        )
        index += 1
        frame_index += 1
        target_index += 1

    cap.release()

    manifest_path = output_root / "scene_keyframes_yjdd_hd.jsonl"
    write_jsonl(manifest_path, rows)
    summary = {
        "schema_version": "scene_keyframe_manifest_v0.1",
        "source_video": str(video_path).replace("\\", "/"),
        "fps": round(fps, 3),
        "frame_count": frame_count,
        "duration_sec": round(duration_sec, 2),
        "source_resolution": {"width": source_width, "height": source_height},
        "sample_fps": sample_fps,
        "image_width": image_width if write_images else None,
        "write_images": write_images,
        "keyframe_count": len(rows),
        "manifest": str(manifest_path).replace("\\", "/"),
        "images_dir": str(images_dir).replace("\\", "/") if write_images else None,
        "label_note": "Keyframes are intentionally unlabeled until timestamp-to-booth review is completed.",
    }
    write_json(output_root / "scene_keyframes_yjdd_hd_summary.json", summary)
    return summary


def main() -> None:
    parser = argparse.ArgumentParser(description="Build scene recognition coordinate and keyframe assets.")
    parser.add_argument("--booth-json", type=Path, required=True)
    parser.add_argument("--booth-output", type=Path, required=True)
    parser.add_argument("--source-image", default="OCR/huichang.jpg")
    parser.add_argument("--venue-id", default="venue_exhibition_demo")
    parser.add_argument("--floor-id", default="F1")
    parser.add_argument("--booth-size-m", type=float, default=3.0)
    parser.add_argument("--video", type=Path)
    parser.add_argument("--keyframe-output-root", type=Path)
    parser.add_argument("--sample-fps", type=float, default=1.0)
    parser.add_argument("--image-width", type=int, default=540)
    parser.add_argument("--max-frames", type=int)
    parser.add_argument("--no-write-images", action="store_true")
    args = parser.parse_args()

    labelme_payload = read_json(args.booth_json)
    booth_table = build_booth_coordinate_table(
        labelme_payload=labelme_payload,
        source_json=args.booth_json,
        source_image=args.source_image,
        venue_id=args.venue_id,
        floor_id=args.floor_id,
        booth_size_m=args.booth_size_m,
    )
    write_json(args.booth_output, booth_table)

    result: dict[str, Any] = {
        "booth_output": str(args.booth_output).replace("\\", "/"),
        "booth_count": len(booth_table["booths"]),
        "pixels_per_meter": booth_table["coordinate_system"]["pixels_per_meter"],
    }
    if args.video and args.keyframe_output_root:
        result["keyframes"] = build_video_keyframe_manifest(
            video_path=args.video,
            output_root=args.keyframe_output_root,
            sample_fps=args.sample_fps,
            image_width=args.image_width,
            max_frames=args.max_frames,
            write_images=not args.no_write_images,
        )

    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
