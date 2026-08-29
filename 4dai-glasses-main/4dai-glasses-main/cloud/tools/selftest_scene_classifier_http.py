from __future__ import annotations

import argparse
import json
import math
import time
import uuid
from collections import Counter, defaultdict
from pathlib import Path
from statistics import mean
from urllib import request


def percentile(values: list[float], ratio: float) -> float | None:
    if not values:
        return None
    sorted_values = sorted(values)
    if len(sorted_values) == 1:
        return float(sorted_values[0])
    position = (len(sorted_values) - 1) * ratio
    low = math.floor(position)
    high = math.ceil(position)
    if low == high:
        return float(sorted_values[low])
    return float(sorted_values[low] + (sorted_values[high] - sorted_values[low]) * (position - low))


def round_or_none(value: float | None, digits: int = 2) -> float | None:
    return None if value is None else round(float(value), digits)


def build_multipart(fields: dict[str, str | int], image_path: Path) -> tuple[bytes, str]:
    boundary = "----ai-glasses-scene-selftest-" + uuid.uuid4().hex
    chunks: list[bytes] = []
    for key, value in fields.items():
        chunks.append(f"--{boundary}\r\n".encode("utf-8"))
        chunks.append(f'Content-Disposition: form-data; name="{key}"\r\n\r\n'.encode("utf-8"))
        chunks.append(str(value).encode("utf-8"))
        chunks.append(b"\r\n")
    chunks.append(f"--{boundary}\r\n".encode("utf-8"))
    chunks.append(
        f'Content-Disposition: form-data; name="image"; filename="{image_path.name}"\r\n'.encode("utf-8")
    )
    chunks.append(b"Content-Type: image/jpeg\r\n\r\n")
    chunks.append(image_path.read_bytes())
    chunks.append(b"\r\n")
    chunks.append(f"--{boundary}--\r\n".encode("utf-8"))
    return b"".join(chunks), f"multipart/form-data; boundary={boundary}"


def extract_booth_id(data: dict) -> str | None:
    matched_landmark = data.get("matched_landmark") or {}
    landmark_id = str(matched_landmark.get("landmark_id") or "")
    if landmark_id.startswith("scene_classifier_booth_"):
        return landmark_id.removeprefix("scene_classifier_booth_").upper()

    keyframe_id = str(data.get("matched_keyframe_id") or "")
    if keyframe_id.startswith("scene_classifier_"):
        return keyframe_id.removeprefix("scene_classifier_").upper()

    matched_keyframes = data.get("matched_keyframes") or []
    if matched_keyframes:
        top_keyframe_id = str(matched_keyframes[0].get("keyframe_id") or "")
        if top_keyframe_id.startswith("scene_classifier_"):
            return top_keyframe_id.removeprefix("scene_classifier_").upper()
    return None


def collect_images(samples_dir: Path, max_per_group: int | None) -> list[tuple[str, Path]]:
    items: list[tuple[str, Path]] = []
    for booth_dir in sorted(path for path in samples_dir.iterdir() if path.is_dir()):
        images = sorted(
            list(booth_dir.glob("*.jpg")) + list(booth_dir.glob("*.jpeg")) + list(booth_dir.glob("*.png"))
        )
        if max_per_group is not None:
            images = images[:max_per_group]
        for image_path in images:
            items.append((booth_dir.name.upper(), image_path))
    return items


def summarize(values: list[float]) -> dict[str, float | None]:
    if not values:
        return {"avg": None, "p50": None, "p90": None, "p95": None, "p99": None, "max": None}
    return {
        "avg": round(mean(values), 2),
        "p50": round_or_none(percentile(values, 0.50)),
        "p90": round_or_none(percentile(values, 0.90)),
        "p95": round_or_none(percentile(values, 0.95)),
        "p99": round_or_none(percentile(values, 0.99)),
        "max": round(max(values), 2),
    }


def post_visual_locate(
    *,
    url: str,
    venue_id: str,
    capture_mode: str,
    request_id: str,
    image_path: Path,
    timeout_seconds: int,
) -> tuple[dict, float]:
    fields = {
        "request_id": request_id,
        "capture_id": request_id,
        "venue_id": venue_id,
        "capture_timestamp_ms": int(time.time() * 1000),
        "capture_mode": capture_mode,
    }
    body, content_type = build_multipart(fields, image_path)
    http_request = request.Request(url, data=body, headers={"Content-Type": content_type}, method="POST")
    started_at = time.perf_counter()
    with request.urlopen(http_request, timeout=timeout_seconds) as response:
        payload = json.loads(response.read().decode("utf-8"))
    return payload, round((time.perf_counter() - started_at) * 1000, 2)


def run_selftest(args: argparse.Namespace) -> dict:
    items = collect_images(args.samples_dir, args.max_per_group)
    if not items:
        raise SystemExit(f"No test images found under {args.samples_dir}")

    print(f"Running scene classifier HTTP selftest: {len(items)} images / {len({item[0] for item in items})} groups")
    status_counts: Counter[str] = Counter()
    per_booth = defaultdict(lambda: {"total": 0, "raw_correct": 0, "ok": 0, "ok_correct": 0, "latencies": []})
    latencies: list[float] = []
    wall_latencies: list[float] = []
    scores: list[float] = []
    failures: list[dict] = []

    raw_correct = 0
    ok_count = 0
    ok_correct = 0
    started_at = time.perf_counter()
    for index, (expected_booth, image_path) in enumerate(items, 1):
        request_id = f"{args.request_prefix}_{index:04d}_{expected_booth}"
        try:
            payload, wall_latency_ms = post_visual_locate(
                url=args.url,
                venue_id=args.venue_id,
                capture_mode=args.capture_mode,
                request_id=request_id,
                image_path=image_path,
                timeout_seconds=args.timeout_seconds,
            )
            data = payload.get("data") or {}
            status = str(data.get("status") or "missing_status")
        except Exception as exc:
            wall_latency_ms = 0.0
            data = {"status": "http_error", "message": str(exc)}
            status = "http_error"

        predicted_booth = extract_booth_id(data)
        latency_ms = data.get("latency_ms")
        score = data.get("confidence")
        raw_match = predicted_booth == expected_booth
        ok_match = status == "ok" and raw_match

        status_counts[status] += 1
        wall_latencies.append(wall_latency_ms)
        if isinstance(latency_ms, (int, float)):
            latencies.append(float(latency_ms))
            per_booth[expected_booth]["latencies"].append(float(latency_ms))
        if isinstance(score, (int, float)):
            scores.append(float(score))

        per_booth[expected_booth]["total"] += 1
        if raw_match:
            raw_correct += 1
            per_booth[expected_booth]["raw_correct"] += 1
        if status == "ok":
            ok_count += 1
            per_booth[expected_booth]["ok"] += 1
        if ok_match:
            ok_correct += 1
            per_booth[expected_booth]["ok_correct"] += 1

        over_latency = isinstance(latency_ms, (int, float)) and latency_ms > args.max_latency_ms
        unsafe_wrong = status == "ok" and not raw_match
        if (not raw_match) or over_latency or unsafe_wrong or status == "http_error":
            failures.append(
                {
                    "request_id": request_id,
                    "expected": expected_booth,
                    "raw_pred": predicted_booth,
                    "status": status,
                    "latency_ms": latency_ms,
                    "wall_latency_ms": wall_latency_ms,
                    "score": score,
                    "failure_stage": data.get("failure_stage"),
                    "image": str(image_path),
                }
            )

        if args.progress_every and index % args.progress_every == 0:
            print(f"  {index}/{len(items)} done")

    query_count = len(items)
    per_booth_report = {}
    for booth_id, stats in sorted(per_booth.items()):
        total = stats["total"]
        booth_latencies = stats["latencies"]
        per_booth_report[booth_id] = {
            "total": total,
            "raw_top1_accuracy": round(stats["raw_correct"] / total, 4),
            "ok_rate": round(stats["ok"] / total, 4),
            "ok_top1_accuracy": round(stats["ok_correct"] / stats["ok"], 4) if stats["ok"] else None,
            "latency_p95_ms": round_or_none(percentile(booth_latencies, 0.95)),
        }

    report = {
        "schema_version": "scene_classifier_http_selftest_v0.6",
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "url": args.url,
        "venue_id": args.venue_id,
        "capture_mode": args.capture_mode,
        "samples_dir": str(args.samples_dir),
        "query_count": query_count,
        "booth_group_count": len(per_booth),
        "raw_top1_accuracy": round(raw_correct / query_count, 4),
        "ok_rate": round(ok_count / query_count, 4),
        "ok_top1_accuracy": round(ok_correct / ok_count, 4) if ok_count else None,
        "status_counts": dict(status_counts),
        "latency_ms": summarize(latencies),
        "wall_latency_ms": summarize(wall_latencies),
        "score_summary": {
            "avg": round_or_none(mean(scores), 4) if scores else None,
            "p10": round_or_none(percentile(scores, 0.10), 4),
            "p50": round_or_none(percentile(scores, 0.50), 4),
            "p90": round_or_none(percentile(scores, 0.90), 4),
        },
        "raw_wrong_count": query_count - raw_correct,
        "ok_wrong_count": ok_count - ok_correct,
        "latency_over_limit_count": sum(1 for value in latencies if value > args.max_latency_ms),
        "max_latency_ms_limit": args.max_latency_ms,
        "per_booth": per_booth_report,
        "first_50_failures": failures[:50],
        "elapsed_seconds": round(time.perf_counter() - started_at, 2),
    }
    return report


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="HTTP selftest for scene_classifier visual-locate.")
    parser.add_argument("--url", default="http://127.0.0.1:8000/api/v1/localization/visual-locate")
    parser.add_argument("--venue-id", default="venue_exhibition_demo")
    parser.add_argument("--capture-mode", default="glasses_private_stream")
    parser.add_argument(
        "--samples-dir",
        type=Path,
        default=Path(
            "cloud/tmp_scene_recognition_probe/yjdd_hd_scene_samples_8fps_blur80/images"
        ),
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(
            "cloud/tmp_scene_recognition_probe/yjdd_hd_booth_classifier_mobilenet_v3_small/"
            "scene_classifier_http_selftest_report_latest.json"
        ),
    )
    parser.add_argument("--request-prefix", default="scene_selftest")
    parser.add_argument("--max-per-group", type=int, default=None)
    parser.add_argument("--max-latency-ms", type=float, default=200.0)
    parser.add_argument("--timeout-seconds", type=int, default=10)
    parser.add_argument("--progress-every", type=int, default=100)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    report = run_selftest(args)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    summary_keys = [
        "query_count",
        "booth_group_count",
        "raw_top1_accuracy",
        "ok_rate",
        "ok_top1_accuracy",
        "status_counts",
        "latency_ms",
        "wall_latency_ms",
        "raw_wrong_count",
        "ok_wrong_count",
        "latency_over_limit_count",
        "elapsed_seconds",
    ]
    print(json.dumps({key: report[key] for key in summary_keys}, ensure_ascii=False, indent=2))
    print(f"Wrote {args.output}")


if __name__ == "__main__":
    main()
