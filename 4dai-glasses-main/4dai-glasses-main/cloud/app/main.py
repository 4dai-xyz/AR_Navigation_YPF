from __future__ import annotations

import asyncio
import base64
import html as html_lib
import json
import os
import socket
import time
from collections import defaultdict, deque
from typing import Annotated, Any, Callable
from urllib.parse import urlparse

from fastapi import FastAPI, File, Form, Header, Request, Response, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import HTMLResponse, JSONResponse

from cloud.app.core.error_codes import ApiError, BusinessCode, DEFAULT_MESSAGES
from cloud.app.core.logging import build_logger, log_event, log_exception
from cloud.app.core.settings import settings
from cloud.app.models.api import (
    ApiEnvelope,
    EntryPoint,
    ErrorEnvelope,
    HealthData,
    IndoorRouteData,
    IndoorRouteRequest,
    LocalizationData,
    RoutePrior,
    VenueMetaData,
)
from cloud.app.services.landmark_recognition import (
    SUPPORTED_RECOGNITION_MODES,
    explain_landmark_recognition,
    landmark_backend_status,
    load_landmarks,
    localize_landmark,
)
from cloud.app.services.relocalization import BaselineRelocalizer, LocalizationQuery
from cloud.app.services.routing import plan_route
from cloud.app.services.scene_classifier_adapter import (
    SceneClassifierConfig,
    explain_scene_classifier,
    localize_scene_classifier,
    scene_classifier_backend_status,
    warm_scene_classifier,
)
from cloud.app.services.scene_retrieval_adapter import (
    SceneRetrievalConfig,
    explain_scene_retrieval,
    localize_scene_retrieval,
    scene_retrieval_backend_status,
)
from cloud.app.services.simple_qr import qr_svg
from cloud.app.services.venue_package import VenuePackageError, find_poi, floor_ids, load_bundle

app = FastAPI(
    title="Indoor Navigation API",
    version="1.0.0",
    description="Cloud API skeleton for indoor visual localization and routing.",
)
logger = build_logger("ai_glasses.cloud")
SUPPORTED_CAPTURE_MODES = {
    "glasses_thumbnail",
    "glasses_album_sync",
    "glasses_private_stream",
    "phone_camera_fallback",
}
AUTH_EXEMPT_PATHS = {"/api/v1/health"}
RATE_LIMIT_EXEMPT_PATHS = {"/api/v1/health"}
RATE_LIMIT_WINDOWS: dict[str, deque[float]] = defaultdict(deque)
RECENT_VISUAL_LOCATE_REQUESTS: deque[dict[str, Any]] = deque(maxlen=50)
RECENT_VISUAL_LOCATE_DEBUGS: deque[dict[str, Any]] = deque(maxlen=20)
PC_RECOGNITION_MODES = {"mock", "template", "real_ocr_adapter", "scene_retrieval", "scene_classifier"}


def ok_response(request_id: str, data: Any, message: str = "ok") -> dict[str, Any]:
    return {"code": int(BusinessCode.OK), "message": message, "request_id": request_id, "data": data}


def error_response(
    *,
    code: BusinessCode,
    request_id: str,
    http_status: int,
    message: str | None = None,
    details: dict[str, Any] | None = None,
) -> JSONResponse:
    return JSONResponse(
        status_code=http_status,
        content=ErrorEnvelope(
            code=int(code),
            message=message or DEFAULT_MESSAGES[code],
            request_id=request_id,
            data=details,
        ).model_dump(),
    )


def request_id_from_request(request: Request, fallback: str) -> str:
    stored = getattr(request.state, "request_id", None)
    if isinstance(stored, str) and stored:
        return stored
    header_request_id = request.headers.get("X-Request-Id")
    if header_request_id:
        return header_request_id
    return fallback


def load_bundle_or_error(request_id: str):
    try:
        return load_bundle(str(settings.venue_package_root))
    except VenuePackageError as exc:
        raise ApiError(
            code=BusinessCode.INTERNAL_ERROR,
            request_id=request_id,
            message=exc.message,
            details=exc.to_details(),
            http_status=500,
        ) from exc


def is_pc_landmark_mode() -> bool:
    return settings.recognition_mode in {"mock", "template", "real_ocr_adapter"}


def is_scene_retrieval_mode() -> bool:
    return settings.recognition_mode == "scene_retrieval"


def is_scene_classifier_mode() -> bool:
    return settings.recognition_mode == "scene_classifier"


def visual_locate_timeout_ms() -> int:
    if settings.recognition_mode == "real_ocr_adapter":
        real_ocr_timeout_ms = int(os.getenv("AI_GLASSES_REAL_OCR_TIMEOUT_MS", "15000"))
        return max(settings.relocalization_timeout_ms, real_ocr_timeout_ms)
    if settings.recognition_mode == "scene_retrieval":
        return max(settings.relocalization_timeout_ms, settings.scene_retrieval_timeout_ms)
    if settings.recognition_mode == "scene_classifier":
        return max(settings.relocalization_timeout_ms, 3000)
    return settings.relocalization_timeout_ms


def build_scene_retrieval_config() -> SceneRetrievalConfig:
    return SceneRetrievalConfig(
        index_path=settings.scene_retrieval_index_path,
        metadata_path=settings.scene_retrieval_metadata_path,
        booth_coordinates_path=settings.scene_retrieval_booth_coordinates_path,
        feature_extractor=settings.scene_retrieval_feature_extractor,
        device=settings.scene_retrieval_device,
        top_k=settings.scene_retrieval_top_k,
        min_score=settings.scene_retrieval_min_score,
        ok_score=settings.scene_retrieval_ok_score,
    )


def build_scene_classifier_config() -> SceneClassifierConfig:
    return SceneClassifierConfig(
        checkpoint_path=settings.scene_classifier_checkpoint_path,
        booth_coordinates_path=settings.scene_retrieval_booth_coordinates_path,
        device=settings.scene_classifier_device,
        min_confidence=settings.scene_classifier_min_confidence,
        ok_confidence=settings.scene_classifier_ok_confidence,
    )


@app.on_event("startup")
def warm_pc_backend_models() -> None:
    if not is_scene_classifier_mode():
        return
    request_id = "startup_scene_classifier_warmup"
    started_at = time.perf_counter()
    try:
        bundle = load_bundle(str(settings.venue_package_root))
        warm_scene_classifier(bundle=bundle, config=build_scene_classifier_config())
        log_event(
            logger,
            "scene_classifier_warmed",
            request_id=request_id,
            latency_ms=int((time.perf_counter() - started_at) * 1000),
        )
    except Exception as exc:
        log_exception(logger, "scene_classifier_warmup_failed", request_id, exc)


def summarize_pois(bundle) -> list[dict[str, Any]]:
    return [
        {
            "poi_id": item.get("poi_id"),
            "type": item.get("poi_type"),
            "display_name": item.get("display_name") or item.get("poi_name"),
            "floor_id": item.get("floor_id"),
            "position": item.get("position"),
            "route_node_id": item.get("route_node_id"),
        }
        for item in bundle.pois
    ]


def summarize_route_nodes(bundle) -> list[dict[str, Any]]:
    return [
        {
            "node_id": item.get("node_id"),
            "floor_id": item.get("floor_id"),
            "x": item.get("x"),
            "y": item.get("y"),
            "node_type": item.get("node_type"),
            "ref_id": item.get("ref_id"),
        }
        for item in bundle.route_graph.get("nodes", [])
    ]


def remember_visual_locate_request(item: dict[str, Any]) -> None:
    RECENT_VISUAL_LOCATE_REQUESTS.appendleft(item)


def remember_visual_locate_debug(item: dict[str, Any]) -> None:
    item_key = item.get("debug_frame_id") or item.get("request_id")
    if item_key:
        for index, existing in enumerate(RECENT_VISUAL_LOCATE_DEBUGS):
            existing_key = existing.get("debug_frame_id") or existing.get("request_id")
            if existing_key == item_key:
                merged = {**existing, **item}
                RECENT_VISUAL_LOCATE_DEBUGS[index] = merged
                return
    RECENT_VISUAL_LOCATE_DEBUGS.appendleft(item)


def debug_base_url(request: Request) -> str:
    return str(request.base_url).rstrip("/")


def is_local_debug_host(host: str | None) -> bool:
    return host in {None, "", "localhost", "127.0.0.1", "::1"}


def append_unique_url(urls: list[str], url: str | None) -> None:
    if url and url not in urls:
        urls.append(url)


def lan_base_urls(port: int, scheme: str = "http") -> list[str]:
    urls: list[str] = []
    override = os.getenv("AI_GLASSES_PC_BACKEND_LAN_BASE_URL")
    append_unique_url(urls, override.rstrip("/") if override else None)
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.connect(("8.8.8.8", 80))
            default_route_ip = probe.getsockname()[0]
            if not default_route_ip.startswith("127.") and not default_route_ip.startswith("169.254."):
                append_unique_url(urls, f"{scheme}://{default_route_ip}:{port}")
    except OSError:
        pass
    try:
        hostname = socket.gethostname()
        addresses = socket.getaddrinfo(hostname, None, socket.AF_INET, socket.SOCK_STREAM)
    except OSError:
        addresses = []
    for item in addresses:
        ip = item[4][0]
        if ip.startswith("127.") or ip.startswith("169.254."):
            continue
        append_unique_url(urls, f"{scheme}://{ip}:{port}")
    return urls


def pairing_base_urls(request: Request) -> list[str]:
    parsed = urlparse(debug_base_url(request))
    scheme = parsed.scheme or "http"
    host = parsed.hostname
    port = parsed.port or settings.pc_backend_port
    request_url = f"{scheme}://{parsed.netloc}" if parsed.netloc else None
    urls: list[str] = []
    if not is_local_debug_host(host):
        append_unique_url(urls, request_url)
    for url in lan_base_urls(port, scheme):
        append_unique_url(urls, url)
    append_unique_url(urls, request_url)
    append_unique_url(urls, f"http://127.0.0.1:{port}")
    return urls


def build_pairing_payload(request: Request, request_id: str) -> dict[str, Any]:
    bundle = load_bundle_or_error(request_id)
    base_urls = pairing_base_urls(request)
    base_url = base_urls[0]
    return {
        "type": "visionroute_pc_backend_pairing",
        "version": 1,
        "service": "VisionRoute PC Backend",
        "base_url": base_url,
        "candidate_base_urls": base_urls,
        "health_url": f"{base_url}/api/v1/health",
        "visual_locate_url": f"{base_url}/api/v1/localization/visual-locate",
        "debug_cards_url": f"{base_url}/debug/cards",
        "debug_visual_url": f"{base_url}/debug/visual-locate",
        "recent_requests_url": f"{base_url}/debug/recent-requests",
        "pairing_url": f"{base_url}/debug/pairing.json",
        "venue_id": bundle.venue["venue_id"],
        "service_mode": settings.service_mode,
        "recognition_mode": settings.recognition_mode,
        "supported_capture_modes": sorted(SUPPORTED_CAPTURE_MODES),
        "preferred_capture_mode": "glasses_private_stream",
        "timestamp_ms": int(time.time() * 1000),
    }


def build_visual_debug_frame_id(request_id: str, capture_id: str | None) -> str:
    return f"{request_id}:{capture_id or 'no_capture'}:{time.time_ns()}"


def build_image_preview(image_bytes: bytes) -> dict[str, Any]:
    try:
        import cv2
        import numpy as np
    except Exception as exc:
        return {"available": False, "error": f"preview_dependency_unavailable: {exc}"}

    array = np.frombuffer(image_bytes, dtype=np.uint8)
    decoded = cv2.imdecode(array, cv2.IMREAD_COLOR)
    if decoded is None:
        return {"available": False, "error": "image_decode_failed"}

    height, width = decoded.shape[:2]
    max_side = 360
    scale = min(max_side / max(width, height), 1.0)
    preview = decoded
    if scale < 1.0:
        preview = cv2.resize(decoded, (int(width * scale), int(height * scale)))
    preview_height, preview_width = preview.shape[:2]
    ok, encoded = cv2.imencode(".jpg", preview, [int(cv2.IMWRITE_JPEG_QUALITY), 72])
    if not ok:
        return {"available": False, "error": "image_encode_failed", "width": width, "height": height}
    data_uri = "data:image/jpeg;base64," + base64.b64encode(encoded.tobytes()).decode("ascii")
    return {
        "available": True,
        "content_type": "image/jpeg",
        "width": width,
        "height": height,
        "preview_width": preview_width,
        "preview_height": preview_height,
        "data_uri": data_uri,
    }


def build_image_received_stage(
    *,
    image_filename: str | None,
    image_content_type: str | None,
    image_bytes: bytes,
    image_preview: dict[str, Any],
    received_timestamp: int,
) -> dict[str, Any]:
    return {
        "stage": "image_received",
        "status": "ok",
        "details": {
            "image_filename": image_filename,
            "image_content_type": image_content_type,
            "image_bytes": len(image_bytes),
            "preview_available": image_preview.get("available"),
            "preview_width": image_preview.get("preview_width"),
            "preview_height": image_preview.get("preview_height"),
            "received_timestamp": received_timestamp,
        },
    }


def build_visual_debug_received_item(
    *,
    debug_frame_id: str,
    request_id: str,
    capture_id: str | None,
    venue_id: str,
    capture_mode: str,
    image_filename: str | None,
    image_content_type: str | None,
    image_bytes: bytes,
    candidate_floor_id: str | None,
    target_poi_id: str | None,
    debug_target: str | None,
    received_timestamp: int,
    image_preview: dict[str, Any],
) -> dict[str, Any]:
    return {
        "debug_frame_id": debug_frame_id,
        "request_id": request_id,
        "capture_id": capture_id,
        "venue_id": venue_id,
        "capture_mode": capture_mode,
        "image_filename": image_filename,
        "image_content_type": image_content_type,
        "image_bytes": len(image_bytes),
        "candidate_floor_id": candidate_floor_id,
        "target_poi_id": target_poi_id,
        "debug_target": debug_target,
        "recognition_mode": settings.recognition_mode,
        "processing_state": "processing",
        "status": "processing",
        "confidence": None,
        "matched_landmark_id": None,
        "failure_stage": None,
        "latency_ms": None,
        "received_timestamp": received_timestamp,
        "timestamp": received_timestamp,
        "image_preview": image_preview,
        "stages": [
            build_image_received_stage(
                image_filename=image_filename,
                image_content_type=image_content_type,
                image_bytes=image_bytes,
                image_preview=image_preview,
                received_timestamp=received_timestamp,
            ),
            {
                "stage": "recognition",
                "status": "processing",
                "details": {
                    "recognition_mode": settings.recognition_mode,
                    "message": "waiting for recognition result",
                },
            },
        ],
    }


def build_visual_debug_item(
    *,
    debug_frame_id: str | None = None,
    request_id: str,
    capture_id: str | None,
    venue_id: str,
    capture_mode: str,
    image_filename: str | None,
    image_content_type: str | None,
    image_bytes: bytes,
    candidate_floor_id: str | None,
    target_poi_id: str | None,
    debug_target: str | None,
    result: LocalizationData,
    stages: list[dict[str, Any]],
    received_timestamp: int | None = None,
    image_preview: dict[str, Any] | None = None,
) -> dict[str, Any]:
    now_ms = int(time.time() * 1000)
    preview = image_preview or build_image_preview(image_bytes)
    first_received_timestamp = received_timestamp or now_ms
    return {
        "debug_frame_id": debug_frame_id,
        "request_id": request_id,
        "capture_id": capture_id,
        "venue_id": venue_id,
        "capture_mode": capture_mode,
        "image_filename": image_filename,
        "image_content_type": image_content_type,
        "image_bytes": len(image_bytes),
        "candidate_floor_id": candidate_floor_id,
        "target_poi_id": target_poi_id,
        "debug_target": debug_target,
        "recognition_mode": settings.recognition_mode,
        "processing_state": "done",
        "status": result.status,
        "confidence": result.confidence,
        "matched_landmark_id": result.matched_landmark.landmark_id if result.matched_landmark else None,
        "failure_stage": result.failure_stage,
        "latency_ms": result.latency_ms,
        "received_timestamp": first_received_timestamp,
        "timestamp": now_ms,
        "image_preview": preview,
        "stages": [
            build_image_received_stage(
                image_filename=image_filename,
                image_content_type=image_content_type,
                image_bytes=image_bytes,
                image_preview=preview,
                received_timestamp=first_received_timestamp,
            ),
            *stages,
        ],
    }


def build_visual_live_metrics(items: list[dict[str, Any]], now_ms: int) -> dict[str, Any]:
    window_ms = 5000
    received_timestamps = [
        int(item["received_timestamp"])
        for item in items
        if item.get("received_timestamp") and now_ms - int(item["received_timestamp"]) <= window_ms
    ]
    completed_timestamps = [
        int(item["timestamp"])
        for item in items
        if item.get("processing_state") == "done"
        and item.get("timestamp")
        and now_ms - int(item["timestamp"]) <= window_ms
    ]
    latest_received_timestamp = max(received_timestamps) if received_timestamps else None
    latest_item = max(
        (item for item in items if item.get("received_timestamp")),
        key=lambda item: int(item["received_timestamp"]),
        default=None,
    )
    return {
        "fps_window_ms": window_ms,
        "observed_fps": round(len(received_timestamps) / (window_ms / 1000.0), 2),
        "completed_fps": round(len(completed_timestamps) / (window_ms / 1000.0), 2),
        "processing_count": sum(1 for item in items if item.get("processing_state") == "processing"),
        "latest_frame_age_ms": now_ms - latest_received_timestamp if latest_received_timestamp else None,
        "latest_request_id": latest_item.get("request_id") if latest_item else None,
    }


def _json_for_html(payload: Any) -> str:
    return html_lib.escape(json.dumps(payload, ensure_ascii=False, indent=2, default=str))


def _render_debug_stage(stage: dict[str, Any]) -> str:
    status = str(stage.get("status") or "unknown")
    stage_name = html_lib.escape(str(stage.get("stage") or "stage"))
    details = stage.get("details", {})
    return f"""
      <div class="stage stage-{html_lib.escape(status)}">
        <div class="stage-head">
          <span class="stage-name">{stage_name}</span>
          <span class="badge">{html_lib.escape(status)}</span>
        </div>
        <pre>{_json_for_html(details)}</pre>
      </div>
    """


def _render_visual_debug_item(item: dict[str, Any]) -> str:
    preview = item.get("image_preview") or {}
    if preview.get("available") and preview.get("data_uri"):
        image_html = (
            f'<img src="{preview["data_uri"]}" '
            f'width="{preview.get("preview_width", "")}" '
            f'height="{preview.get("preview_height", "")}" '
            f'alt="received image preview" />'
        )
    else:
        image_html = f'<div class="image-missing">No preview: {html_lib.escape(str(preview.get("error")))}</div>'
    stages_html = "\n".join(_render_debug_stage(stage) for stage in item.get("stages", []))
    return f"""
      <article class="request-card">
        <header>
          <h2>{html_lib.escape(str(item.get("request_id")))}</h2>
          <div class="summary">
            <span class="badge">{html_lib.escape(str(item.get("status")))}</span>
            <span>confidence: <code>{html_lib.escape(str(item.get("confidence")))}</code></span>
            <span>mode: <code>{html_lib.escape(str(item.get("recognition_mode")))}</code></span>
            <span>latency: <code>{html_lib.escape(str(item.get("latency_ms")))}ms</code></span>
            <span>state: <code>{html_lib.escape(str(item.get("processing_state")))}</code></span>
          </div>
        </header>
        <div class="columns">
          <section>
            <h3>接收到的图片</h3>
            <div class="image-wrap">{image_html}</div>
            <pre>{_json_for_html({
                "capture_id": item.get("capture_id"),
                "venue_id": item.get("venue_id"),
                "capture_mode": item.get("capture_mode"),
                "image_filename": item.get("image_filename"),
                "image_content_type": item.get("image_content_type"),
                "image_bytes": item.get("image_bytes"),
                "candidate_floor_id": item.get("candidate_floor_id"),
                "target_poi_id": item.get("target_poi_id"),
                "debug_target": item.get("debug_target"),
                "matched_landmark_id": item.get("matched_landmark_id"),
                "failure_stage": item.get("failure_stage"),
                "processing_state": item.get("processing_state"),
                "received_timestamp": item.get("received_timestamp"),
                "timestamp": item.get("timestamp"),
            })}</pre>
          </section>
          <section>
            <h3>识别过程</h3>
            {stages_html}
          </section>
        </div>
      </article>
    """


def build_baseline_debug_stages(result: LocalizationData) -> list[dict[str, Any]]:
    return [
        {
            "stage": "baseline_relocalizer",
            "status": result.status,
            "details": {
                "recognition_mode": result.recognition_mode,
                "message": result.message,
                "failure_stage": result.failure_stage,
            },
        },
        {
            "stage": "final_result",
            "status": result.status,
            "details": {
                "floor_id": result.floor_id,
                "position": result.position,
                "confidence": result.confidence,
                "matched_landmark": result.matched_landmark.model_dump() if result.matched_landmark else None,
                "failure_stage": result.failure_stage,
                "message": result.message,
                "latency_ms": result.latency_ms,
            },
        },
    ]


def auth_error_response(request: Request, request_id: str) -> JSONResponse | None:
    if not settings.auth_enabled or request.url.path in AUTH_EXEMPT_PATHS:
        return None
    expected_token = settings.api_token
    authorization = request.headers.get("Authorization", "")
    expected_header = f"Bearer {expected_token}" if expected_token else None
    if expected_header and authorization == expected_header:
        return None
    return error_response(
        code=BusinessCode.AUTH_UNAUTHORIZED,
        request_id=request_id,
        http_status=401,
        details={"reason": "missing_or_invalid_bearer_token"},
    )


def rate_limit_error_response(request: Request, request_id: str, now: float) -> JSONResponse | None:
    limit = settings.rate_limit_per_minute
    if limit <= 0 or request.url.path in RATE_LIMIT_EXEMPT_PATHS:
        return None
    client_host = request.client.host if request.client else "unknown"
    key = f"{client_host}:{request.url.path}"
    window = RATE_LIMIT_WINDOWS[key]
    while window and now - window[0] >= 60.0:
        window.popleft()
    if len(window) >= limit:
        return error_response(
            code=BusinessCode.RATE_LIMITED,
            request_id=request_id,
            http_status=429,
            details={"limit_per_minute": limit, "scope": "client_path"},
        )
    window.append(now)
    return None


def parse_route_prior(
    *,
    request_id: str,
    route_prior: str | None,
    route_id: str | None,
    route_edge_ids: str | None,
    corridor_window_m: float | None,
) -> RoutePrior:
    if route_prior:
        try:
            payload = json.loads(route_prior)
        except json.JSONDecodeError as exc:
            raise ApiError(
                code=BusinessCode.INVALID_PARAMETER,
                request_id=request_id,
                message="invalid route_prior",
                details={"field": "route_prior", "reason": "must be valid JSON object"},
            ) from exc
        if not isinstance(payload, dict):
            raise ApiError(
                code=BusinessCode.INVALID_PARAMETER,
                request_id=request_id,
                message="invalid route_prior",
                details={"field": "route_prior", "reason": "must be JSON object"},
            )
        edge_ids = payload.get("edge_ids", [])
        if edge_ids is None:
            edge_ids = []
        if not isinstance(edge_ids, list) or not all(isinstance(item, str) for item in edge_ids):
            raise ApiError(
                code=BusinessCode.INVALID_PARAMETER,
                request_id=request_id,
                message="invalid route_prior",
                details={"field": "route_prior.edge_ids", "reason": "must be string array"},
            )
        if payload.get("route_id") is not None and not isinstance(payload.get("route_id"), str):
            raise ApiError(
                code=BusinessCode.INVALID_PARAMETER,
                request_id=request_id,
                message="invalid route_prior",
                details={"field": "route_prior.route_id", "reason": "must be string"},
            )
        if payload.get("corridor_window_m") is not None and not isinstance(payload.get("corridor_window_m"), (int, float)):
            raise ApiError(
                code=BusinessCode.INVALID_PARAMETER,
                request_id=request_id,
                message="invalid route_prior",
                details={"field": "route_prior.corridor_window_m", "reason": "must be number"},
            )
        return RoutePrior(
            route_id=payload.get("route_id"),
            edge_ids=edge_ids,
            corridor_window_m=payload.get("corridor_window_m"),
        )
    return RoutePrior(
        route_id=route_id,
        edge_ids=[item for item in (route_edge_ids or "").split(",") if item],
        corridor_window_m=corridor_window_m,
    )


async def run_blocking_with_timeout(
    *,
    request_id: str,
    operation: str,
    timeout_ms: int,
    timeout_code: BusinessCode,
    timeout_message: str,
    func: Callable[..., Any],
    args: tuple[Any, ...],
    kwargs: dict[str, Any] | None = None,
) -> Any:
    try:
        return await asyncio.wait_for(
            asyncio.to_thread(func, *args, **(kwargs or {})),
            timeout=max(timeout_ms, 1) / 1000.0,
        )
    except TimeoutError as exc:
        log_event(
            logger,
            "operation_timeout",
            request_id=request_id,
            operation=operation,
            timeout_ms=timeout_ms,
            code=int(timeout_code),
        )
        raise ApiError(
            code=timeout_code,
            request_id=request_id,
            message=timeout_message,
            details={"timeout_ms": timeout_ms, "operation": operation},
            http_status=504,
        ) from exc


@app.middleware("http")
async def request_logging_middleware(request: Request, call_next):
    started_at = time.perf_counter()
    fallback_request_id = request.headers.get("X-Request-Id") or (
        f"{request.method.lower()}_{request.url.path.strip('/').replace('/', '_') or 'root'}"
    )
    request.state.request_id = fallback_request_id
    trace_id = request.headers.get("X-Trace-Id") or fallback_request_id
    request.state.trace_id = trace_id
    response = auth_error_response(request, fallback_request_id)
    if response is None:
        response = rate_limit_error_response(request, fallback_request_id, started_at)
    if response is None:
        response = await call_next(request)
    latency_ms = int((time.perf_counter() - started_at) * 1000)
    request_id = request_id_from_request(request, fallback_request_id)
    response.headers["X-Request-Id"] = request_id
    response.headers["X-Trace-Id"] = trace_id
    log_event(
        logger,
        "request_completed",
        request_id=request_id,
        trace_id=trace_id,
        method=request.method,
        path=request.url.path,
        status_code=response.status_code,
        latency_ms=latency_ms,
        client_host=request.client.host if request.client else None,
        auth_enabled=settings.auth_enabled,
        rate_limit_per_minute=settings.rate_limit_per_minute,
    )
    return response


@app.exception_handler(ApiError)
async def handle_api_error(request: Request, exc: ApiError) -> JSONResponse:
    request.state.request_id = exc.request_id
    log_event(
        logger,
        "api_error",
        request_id=exc.request_id,
        path=request.url.path,
        code=int(exc.code),
        http_status=exc.http_status,
        details=exc.details,
    )
    return JSONResponse(
        status_code=exc.http_status,
        content=ErrorEnvelope(
            code=int(exc.code),
            message=exc.message,
            request_id=exc.request_id,
            data=exc.details,
        ).model_dump(),
    )


@app.exception_handler(RequestValidationError)
async def handle_validation_error(request: Request, exc: RequestValidationError) -> JSONResponse:
    request_id = request_id_from_request(request, "invalid_request")
    request.state.request_id = request_id
    details = {"validation_errors": exc.errors()}
    log_event(
        logger,
        "validation_error",
        request_id=request_id,
        path=request.url.path,
        code=int(BusinessCode.INVALID_PARAMETER),
        details=details,
    )
    return JSONResponse(
        status_code=400,
        content=ErrorEnvelope(
            code=int(BusinessCode.INVALID_PARAMETER),
            message=DEFAULT_MESSAGES[BusinessCode.INVALID_PARAMETER],
            request_id=request_id,
            data=details,
        ).model_dump(),
    )


@app.exception_handler(Exception)
async def handle_unexpected_error(request: Request, exc: Exception) -> JSONResponse:
    request_id = request_id_from_request(request, "unexpected")
    request.state.request_id = request_id
    log_exception(
        logger,
        "internal_error",
        request_id=request_id,
        path=request.url.path,
        code=int(BusinessCode.INTERNAL_ERROR),
    )
    return JSONResponse(
        status_code=500,
        content=ErrorEnvelope(
            code=int(BusinessCode.INTERNAL_ERROR),
            message=f"{DEFAULT_MESSAGES[BusinessCode.INTERNAL_ERROR]}: {exc}",
            request_id=request_id,
            data=None,
        ).model_dump(),
    )


@app.get("/api/v1/health")
def health(request: Request) -> ApiEnvelope[HealthData]:
    request_id = request.headers.get("X-Request-Id") or "health_001"
    request.state.request_id = request_id
    bundle = load_bundle_or_error(request_id)
    algorithm_backend_status = (
        scene_retrieval_backend_status(bundle, build_scene_retrieval_config())
        if is_scene_retrieval_mode()
        else scene_classifier_backend_status(bundle, build_scene_classifier_config())
        if is_scene_classifier_mode()
        else landmark_backend_status(bundle, settings.recognition_mode)
    )
    data = HealthData(
        status="healthy",
        service=settings.service_name,
        version=settings.api_version,
        service_mode=settings.service_mode,
        recognition_mode=settings.recognition_mode,
        venue_id=bundle.venue["venue_id"],
        venue_package_root=str(bundle.root),
        venue_package_version=bundle.manifest["package_version"],
        algorithm_backend_status=algorithm_backend_status,
    )
    return ApiEnvelope[HealthData](**ok_response(request_id, data.model_dump()))


@app.get("/debug/pairing.json")
def debug_pairing_json(request: Request) -> dict[str, Any]:
    request_id = request.headers.get("X-Request-Id") or "debug_pairing_json"
    request.state.request_id = request_id
    return build_pairing_payload(request, request_id)


@app.get("/debug/pairing.svg")
def debug_pairing_svg(request: Request) -> Response:
    request_id = request.headers.get("X-Request-Id") or "debug_pairing_svg"
    request.state.request_id = request_id
    payload = build_pairing_payload(request, request_id)
    svg = qr_svg(payload["pairing_url"])
    return Response(
        content=svg,
        media_type="image/svg+xml",
        headers={"Cache-Control": "no-store, max-age=0", "Pragma": "no-cache"},
    )


@app.get("/debug/pairing", response_class=HTMLResponse)
def debug_pairing(request: Request) -> HTMLResponse:
    request_id = request.headers.get("X-Request-Id") or "debug_pairing"
    request.state.request_id = request_id
    payload = build_pairing_payload(request, request_id)
    base_url = payload["base_url"]
    pairing_url = payload["pairing_url"]
    candidate_html = "\n".join(
        f"<li><code>{html_lib.escape(url)}</code></li>" for url in payload["candidate_base_urls"]
    )
    payload_json = json.dumps(payload, ensure_ascii=False, indent=2)
    html = f"""
    <!doctype html>
    <html lang="zh-CN">
    <head>
      <meta charset="utf-8" />
      <meta name="viewport" content="width=device-width, initial-scale=1" />
      <title>VisionRoute PC 后台配对</title>
      <style>
        body {{ font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; background: #f8fafc; color: #0f172a; margin: 0; padding: 28px; }}
        .layout {{ display: grid; grid-template-columns: minmax(280px, 420px) minmax(320px, 1fr); gap: 24px; align-items: start; }}
        .panel {{ background: white; border: 1px solid #e2e8f0; border-radius: 18px; padding: 20px; box-shadow: 0 10px 30px rgba(15, 23, 42, 0.08); }}
        .qr {{ text-align: center; }}
        .qr img {{ width: min(100%, 360px); height: auto; border: 12px solid white; box-shadow: 0 4px 18px rgba(15, 23, 42, 0.12); }}
        h1 {{ margin-top: 0; }}
        code, pre {{ background: #e2e8f0; border-radius: 8px; padding: 2px 6px; }}
        pre {{ overflow-x: auto; padding: 14px; line-height: 1.5; }}
        .hint {{ color: #475569; line-height: 1.7; }}
        .ok {{ color: #15803d; font-weight: 700; }}
        .warn {{ color: #b45309; font-weight: 700; }}
        .links a {{ display: inline-block; margin: 6px 10px 6px 0; color: #2563eb; }}
        @media (max-width: 860px) {{ .layout {{ grid-template-columns: 1fr; }} }}
      </style>
    </head>
    <body>
      <h1>VisionRoute PC 后台配对</h1>
      <div class="layout">
        <section class="panel qr">
          <h2>App 扫码配对</h2>
          <img src="/debug/pairing.svg?ts={payload["timestamp_ms"]}" alt="VisionRoute pairing QR" />
          <p class="hint">二维码内容是 <code>/debug/pairing.json</code>。App 扫码后请求该 JSON，取 <code>base_url</code> 保存为后台地址。</p>
          <p class="hint">当前推荐 baseUrl：</p>
          <p><code>{html_lib.escape(base_url)}</code></p>
        </section>
        <section class="panel">
          <h2>配对信息</h2>
          <p><span class="ok">venue_id</span>：<code>{html_lib.escape(payload["venue_id"])}</code></p>
          <p><span class="ok">recognition_mode</span>：<code>{html_lib.escape(payload["recognition_mode"])}</code></p>
          <p><span class="ok">pairing_url</span>：<code>{html_lib.escape(pairing_url)}</code></p>
          <p class="warn">如果手机扫码后连不上，请先用手机浏览器打开 Health URL 验证同 Wi-Fi / 防火墙 / IP 是否正确。</p>
          <h3>候选 baseUrl</h3>
          <ul>{candidate_html}</ul>
          <h3>快捷入口</h3>
          <div class="links">
            <a href="{html_lib.escape(payload["health_url"])}">Health</a>
            <a href="{html_lib.escape(payload["debug_cards_url"])}">Debug Cards</a>
            <a href="{html_lib.escape(payload["debug_visual_url"])}">Visual Debug</a>
            <a href="{html_lib.escape(payload["recent_requests_url"])}">Recent Requests</a>
            <a href="{html_lib.escape(pairing_url)}">Pairing JSON</a>
          </div>
          <h3>App 侧处理建议</h3>
          <ol class="hint">
            <li>扫码得到 <code>pairing_url</code>。</li>
            <li>GET <code>pairing_url</code>。</li>
            <li>读取 <code>base_url</code> 并调用 <code>/api/v1/health</code>。</li>
            <li>health 成功后保存 <code>base_url</code>。</li>
            <li>后续图片上传到 <code>/api/v1/localization/visual-locate</code>。</li>
          </ol>
        </section>
      </div>
      <section class="panel" style="margin-top: 24px;">
        <h2>Pairing JSON Preview</h2>
        <pre>{html_lib.escape(payload_json)}</pre>
      </section>
    </body>
    </html>
    """
    return HTMLResponse(html, headers={"Cache-Control": "no-store, max-age=0", "Pragma": "no-cache"})


@app.get("/debug/cards", response_class=HTMLResponse)
def debug_cards(request: Request) -> HTMLResponse:
    request_id = request.headers.get("X-Request-Id") or "debug_cards"
    request.state.request_id = request_id
    bundle = load_bundle_or_error(request_id)
    base_url = debug_base_url(request)
    cards = [
        ("B10", "poi_booth_b10", "lm_booth_b10_card", "#2563eb"),
        ("B17", "poi_booth_b17", "lm_booth_b17_card", "#16a34a"),
        ("厕所 / TOILET", "poi_toilet_f1", "lm_toilet_f1_sign", "#f59e0b"),
        ("报告厅 / HALL", "poi_hall_main", "lm_hall_main_sign", "#9333ea"),
    ]
    card_html = "\n".join(
        f"""
        <section class="card" style="border-color:{color};">
          <div class="marker" style="background:{color};">{label}</div>
          <div class="meta">poi_id: <code>{poi_id}</code></div>
          <div class="meta">landmark_id: <code>{landmark_id}</code></div>
        </section>
        """
        for label, poi_id, landmark_id, color in cards
    )
    html = f"""
    <!doctype html>
    <html lang="zh-CN">
    <head>
      <meta charset="utf-8" />
      <meta name="viewport" content="width=device-width, initial-scale=1" />
      <title>VisionRoute PC Backend Debug Cards</title>
      <style>
        body {{ font-family: Arial, "Microsoft YaHei", sans-serif; margin: 24px; background: #f8fafc; color: #0f172a; }}
        h1 {{ margin-bottom: 8px; }}
        .info {{ line-height: 1.7; background: white; padding: 16px; border-radius: 12px; border: 1px solid #e2e8f0; }}
        .grid {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 18px; margin-top: 20px; }}
        .card {{ background: white; border: 8px solid; border-radius: 20px; padding: 18px; min-height: 230px; box-shadow: 0 8px 24px rgba(15, 23, 42, 0.10); }}
        .marker {{ color: white; border-radius: 16px; height: 150px; display: flex; align-items: center; justify-content: center; font-size: 46px; font-weight: 800; letter-spacing: 2px; text-align: center; }}
        .meta {{ margin-top: 12px; font-size: 15px; }}
        code {{ background: #e2e8f0; border-radius: 4px; padding: 2px 5px; }}
      </style>
    </head>
    <body>
      <h1>VisionRoute PC Backend Debug Cards</h1>
      <div class="info">
        <div>venue_id: <code>{bundle.venue["venue_id"]}</code></div>
        <div>recognition_mode: <code>{settings.recognition_mode}</code></div>
        <div>service_mode: <code>{settings.service_mode}</code></div>
        <div>Health URL: <code>{base_url}/api/v1/health</code></div>
        <div>visual-locate URL: <code>{base_url}/api/v1/localization/visual-locate</code></div>
        <div>Pairing URL: <code>{base_url}/debug/pairing</code></div>
        <div>Recent requests URL: <code>{base_url}/debug/recent-requests</code></div>
        <div>Visual debug URL: <code>{base_url}/debug/visual-locate</code></div>
      </div>
      <div class="grid">{card_html}</div>
    </body>
    </html>
    """
    return HTMLResponse(html)


@app.get("/debug/recent-requests")
def debug_recent_requests(request: Request, limit: int = 20) -> dict[str, Any]:
    request_id = request.headers.get("X-Request-Id") or "debug_recent_requests"
    request.state.request_id = request_id
    limit = max(1, min(limit, 50))
    return {
        "request_id": request_id,
        "count": min(limit, len(RECENT_VISUAL_LOCATE_REQUESTS)),
        "items": list(RECENT_VISUAL_LOCATE_REQUESTS)[:limit],
    }


@app.get("/debug/visual-locate/live-data")
def debug_visual_locate_live_data(request: Request, response: Response, limit: int = 3) -> dict[str, Any]:
    request_id = request.headers.get("X-Request-Id") or "debug_visual_locate_live_data"
    request.state.request_id = request_id
    response.headers["Cache-Control"] = "no-store, max-age=0"
    response.headers["Pragma"] = "no-cache"
    limit = max(1, min(limit, 3))
    now_ms = int(time.time() * 1000)
    all_items = list(RECENT_VISUAL_LOCATE_DEBUGS)
    items = list(RECENT_VISUAL_LOCATE_DEBUGS)[:limit]
    return {
        "request_id": request_id,
        "server_timestamp": now_ms,
        "count": len(items),
        **build_visual_live_metrics(all_items, now_ms),
        "items": items,
    }


@app.get("/debug/visual-locate", response_class=HTMLResponse)
def debug_visual_locate(request: Request, limit: int = 3) -> HTMLResponse:
    request_id = request.headers.get("X-Request-Id") or "debug_visual_locate"
    request.state.request_id = request_id
    limit = max(1, min(limit, 3))
    base_url = debug_base_url(request)
    items = list(RECENT_VISUAL_LOCATE_DEBUGS)[:limit]
    items_html = "\n".join(_render_visual_debug_item(item) for item in items)
    if not items_html:
        items_html = """
          <div class="empty">
            还没有 visual-locate 请求。先用 App 上传图片，或用 debug_target/curl 发一次请求。
          </div>
        """
    html = f"""
    <!doctype html>
    <html lang="zh-CN">
    <head>
      <meta charset="utf-8" />
      <meta name="viewport" content="width=device-width, initial-scale=1" />
      <title>VisionRoute Visual Locate Debug</title>
      <style>
        body {{ font-family: Arial, "Microsoft YaHei", sans-serif; margin: 24px; background: #0f172a; color: #e2e8f0; }}
        a {{ color: #93c5fd; }}
        code {{ background: #1e293b; color: #bfdbfe; border-radius: 4px; padding: 2px 5px; }}
        pre {{ background: #020617; color: #dbeafe; border-radius: 10px; overflow: auto; padding: 12px; font-size: 12px; line-height: 1.45; }}
        .toolbar {{ background: #111827; border: 1px solid #334155; border-radius: 14px; padding: 16px; margin-bottom: 18px; line-height: 1.8; }}
        .request-card {{ background: #111827; border: 1px solid #334155; border-radius: 18px; padding: 18px; margin-bottom: 18px; box-shadow: 0 12px 30px rgba(0, 0, 0, 0.25); }}
        .request-card h2 {{ margin: 0 0 10px; }}
        .summary {{ display: flex; flex-wrap: wrap; gap: 10px 16px; align-items: center; }}
        .latest-panel {{ background: #111827; border: 1px solid #2563eb; border-radius: 18px; padding: 18px; margin-bottom: 18px; }}
        .latest-grid {{ display: grid; grid-template-columns: minmax(320px, 1fr) minmax(260px, 0.75fr); gap: 18px; align-items: start; }}
        .latest-panel h2 {{ margin-top: 0; }}
        #latest-image {{ max-width: 100%; height: auto; border-radius: 10px; }}
        #latest-empty {{ color: #cbd5e1; padding: 24px; }}
        .columns {{ display: grid; grid-template-columns: minmax(280px, 0.9fr) minmax(320px, 1.1fr); gap: 18px; margin-top: 16px; }}
        .image-wrap {{ background: #020617; border: 1px solid #334155; border-radius: 14px; padding: 12px; text-align: center; }}
        .image-wrap img {{ max-width: 100%; height: auto; border-radius: 10px; }}
        .image-missing {{ color: #fca5a5; padding: 24px; }}
        .stage {{ border: 1px solid #334155; border-radius: 12px; padding: 12px; margin-bottom: 10px; background: #0b1120; }}
        .stage-head {{ display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }}
        .stage-name {{ font-weight: 700; }}
        .badge {{ display: inline-block; border-radius: 999px; padding: 3px 10px; background: #334155; color: #e2e8f0; font-size: 12px; }}
        .stage-ok .badge {{ background: #166534; }}
        .stage-processing .badge {{ background: #1d4ed8; }}
        .stage-low_confidence .badge {{ background: #a16207; }}
        .stage-not_found .badge, .stage-not_configured .badge {{ background: #991b1b; }}
        .stage-skipped .badge {{ background: #475569; }}
        .empty {{ background: #111827; border: 1px dashed #64748b; border-radius: 14px; padding: 24px; color: #cbd5e1; }}
        .live-row {{ display: flex; flex-wrap: wrap; gap: 10px 16px; align-items: center; }}
        .live-dot {{ width: 10px; height: 10px; border-radius: 50%; background: #22c55e; display: inline-block; box-shadow: 0 0 12px #22c55e; }}
        .live-error {{ color: #fca5a5; }}
        @media (max-width: 900px) {{ .columns, .latest-grid {{ grid-template-columns: 1fr; }} }}
      </style>
    </head>
    <body>
      <h1>VisionRoute Visual Locate Debug</h1>
      <div class="toolbar">
        <div>当前页面展示最近 <code>{len(items)}</code> 次 visual-locate 请求的图片缩略图和识别过程。</div>
        <div>Health: <code>{base_url}/api/v1/health</code></div>
        <div>Debug Cards: <code>{base_url}/debug/cards</code></div>
        <div>Recent Requests JSON: <code>{base_url}/debug/recent-requests</code></div>
        <div>Live Data JSON: <code>{base_url}/debug/visual-locate/live-data?limit={limit}</code></div>
        <div class="live-row">
          <span class="live-dot"></span>
          <span>自动刷新：<code>250ms</code></span>
          <span>接收FPS：<code id="live-fps">0.00</code></span>
          <span>识别FPS：<code id="complete-fps">0.00</code></span>
          <span>处理中：<code id="processing-count">0</code></span>
          <span>最新帧延迟：<code id="frame-age">-</code></span>
          <span id="live-status">等待最新数据...</span>
          <a href="{base_url}/debug/visual-locate?limit={limit}">手动刷新页面</a>
        </div>
      </div>
      <section class="latest-panel">
        <h2>实时最新画面</h2>
        <div class="latest-grid">
          <div class="image-wrap">
            <img id="latest-image" alt="latest received frame" style="display:none" />
            <div id="latest-empty">等待 App/Rokid 上传图片帧...</div>
          </div>
          <div>
            <div class="summary">
              <span>frame: <code id="latest-frame-id">-</code></span>
              <span>request: <code id="latest-request-id">-</code></span>
              <span>state: <code id="latest-state">-</code></span>
            </div>
            <pre id="latest-meta">{{}}</pre>
          </div>
        </div>
      </section>
      <div id="live-root" data-live-url="/debug/visual-locate/live-data?limit={limit}">
        {items_html}
      </div>
      <script>
        const liveRoot = document.getElementById("live-root");
        const liveStatus = document.getElementById("live-status");
        const liveFps = document.getElementById("live-fps");
        const completeFps = document.getElementById("complete-fps");
        const processingCount = document.getElementById("processing-count");
        const frameAge = document.getElementById("frame-age");
        const latestImage = document.getElementById("latest-image");
        const latestEmpty = document.getElementById("latest-empty");
        const latestFrameId = document.getElementById("latest-frame-id");
        const latestRequestId = document.getElementById("latest-request-id");
        const latestState = document.getElementById("latest-state");
        const latestMeta = document.getElementById("latest-meta");
        const liveUrl = liveRoot.dataset.liveUrl;
        let refreshInFlight = false;
        let latestRenderedFrameKey = "";

        function escapeHtml(value) {{
          return String(value ?? "")
            .replaceAll("&", "&amp;")
            .replaceAll("<", "&lt;")
            .replaceAll(">", "&gt;")
            .replaceAll('"', "&quot;")
            .replaceAll("'", "&#039;");
        }}

        function renderJson(value) {{
          return `<pre>${{escapeHtml(JSON.stringify(value ?? {{}}, null, 2))}}</pre>`;
        }}

        function renderStage(stage) {{
          const status = String(stage.status || "unknown");
          return `
            <div class="stage stage-${{escapeHtml(status)}}">
              <div class="stage-head">
                <span class="stage-name">${{escapeHtml(stage.stage || "stage")}}</span>
                <span class="badge">${{escapeHtml(status)}}</span>
              </div>
              ${{renderJson(stage.details || {{}})}}
            </div>
          `;
        }}

        function renderItem(item) {{
          const preview = item.image_preview || {{}};
          const imageHtml = preview.available && preview.data_uri
            ? `<img src="${{preview.data_uri}}" alt="received image preview" />`
            : `<div class="image-missing">No preview: ${{escapeHtml(preview.error || "")}}</div>`;
          const requestMeta = {{
            capture_id: item.capture_id,
            debug_frame_id: item.debug_frame_id,
            venue_id: item.venue_id,
            capture_mode: item.capture_mode,
            image_filename: item.image_filename,
            image_content_type: item.image_content_type,
            image_bytes: item.image_bytes,
            candidate_floor_id: item.candidate_floor_id,
            target_poi_id: item.target_poi_id,
            debug_target: item.debug_target,
            matched_landmark_id: item.matched_landmark_id,
            failure_stage: item.failure_stage,
            processing_state: item.processing_state,
            received_timestamp: item.received_timestamp,
            timestamp: item.timestamp,
          }};
          return `
            <article class="request-card">
              <header>
                <h2>${{escapeHtml(item.request_id)}}</h2>
                <div class="summary">
                  <span>frame: <code>${{escapeHtml(item.debug_frame_id)}}</code></span>
                  <span class="badge">${{escapeHtml(item.status)}}</span>
                  <span>confidence: <code>${{escapeHtml(item.confidence)}}</code></span>
                  <span>mode: <code>${{escapeHtml(item.recognition_mode)}}</code></span>
                  <span>latency: <code>${{escapeHtml(item.latency_ms)}}ms</code></span>
                  <span>state: <code>${{escapeHtml(item.processing_state)}}</code></span>
                </div>
              </header>
              <div class="columns">
                <section>
                  <h3>接收到的图片</h3>
                  <div class="image-wrap">${{imageHtml}}</div>
                  ${{renderJson(requestMeta)}}
                </section>
                <section>
                  <h3>识别过程</h3>
                  ${{(item.stages || []).map(renderStage).join("")}}
                </section>
              </div>
            </article>
          `;
        }}

        function renderLatestFrame(item) {{
          if (!item) {{
            latestImage.style.display = "none";
            latestEmpty.style.display = "block";
            latestFrameId.textContent = "-";
            latestRequestId.textContent = "-";
            latestState.textContent = "-";
            latestMeta.textContent = "{{}}";
            latestRenderedFrameKey = "";
            return;
          }}
          const preview = item.image_preview || {{}};
          const frameKey = `${{item.debug_frame_id || item.request_id || ""}}:${{item.timestamp || ""}}:${{item.image_bytes || ""}}`;
          if (preview.available && preview.data_uri && frameKey !== latestRenderedFrameKey) {{
            latestImage.src = preview.data_uri;
            latestImage.style.display = "block";
            latestEmpty.style.display = "none";
            latestRenderedFrameKey = frameKey;
          }} else if (!preview.available) {{
            latestImage.style.display = "none";
            latestEmpty.style.display = "block";
            latestEmpty.textContent = `No preview: ${{preview.error || "unknown"}}`;
          }}
          latestFrameId.textContent = item.debug_frame_id || "-";
          latestRequestId.textContent = item.request_id || "-";
          latestState.textContent = item.processing_state || item.status || "-";
          latestMeta.textContent = JSON.stringify({{
            debug_frame_id: item.debug_frame_id,
            request_id: item.request_id,
            capture_id: item.capture_id,
            capture_mode: item.capture_mode,
            image_filename: item.image_filename,
            image_bytes: item.image_bytes,
            status: item.status,
            confidence: item.confidence,
            matched_landmark_id: item.matched_landmark_id,
            failure_stage: item.failure_stage,
            latency_ms: item.latency_ms,
            received_timestamp: item.received_timestamp,
            timestamp: item.timestamp,
          }}, null, 2);
        }}

        async function refreshLiveData() {{
          if (refreshInFlight) {{
            return;
          }}
          refreshInFlight = true;
          try {{
            const response = await fetch(liveUrl, {{ cache: "no-store" }});
            if (!response.ok) {{
              throw new Error(`HTTP ${{response.status}}`);
            }}
            const payload = await response.json();
            if (!payload.items || payload.items.length === 0) {{
              liveRoot.innerHTML = '<div class="empty">还没有 visual-locate 请求。先用 App 上传图片，或用 debug_target/curl 发一次请求。</div>';
            }} else {{
              liveRoot.innerHTML = payload.items.map(renderItem).join("");
            }}
            renderLatestFrame(payload.items && payload.items.length > 0 ? payload.items[0] : null);
            const time = new Date(payload.server_timestamp || Date.now()).toLocaleTimeString();
            liveFps.textContent = Number(payload.observed_fps || 0).toFixed(2);
            completeFps.textContent = Number(payload.completed_fps || 0).toFixed(2);
            processingCount.textContent = String(payload.processing_count || 0);
            frameAge.textContent = payload.latest_frame_age_ms === null || payload.latest_frame_age_ms === undefined
              ? "-"
              : `${{payload.latest_frame_age_ms}}ms`;
            liveStatus.className = "";
            liveStatus.textContent = `最近更新：${{time}}，请求数：${{payload.count || 0}}，最新请求：${{payload.latest_request_id || "-"}}`;
          }} catch (error) {{
            liveStatus.className = "live-error";
            liveStatus.textContent = `实时刷新失败：${{error.message}}`;
          }} finally {{
            refreshInFlight = false;
          }}
        }}

        refreshLiveData();
        setInterval(refreshLiveData, 250);
      </script>
    </body>
    </html>
    """
    return HTMLResponse(html, headers={"Cache-Control": "no-store, max-age=0", "Pragma": "no-cache"})


@app.get("/api/v1/venues/{venue_id}/meta")
def venue_meta(
    request: Request,
    venue_id: str,
    x_request_id: Annotated[str | None, Header(alias="X-Request-Id")] = None,
) -> ApiEnvelope[VenueMetaData]:
    request_id = x_request_id or f"meta_{venue_id}"
    request.state.request_id = request_id
    bundle = load_bundle_or_error(request_id)
    if venue_id != bundle.venue["venue_id"]:
        raise ApiError(
            code=BusinessCode.VENUE_NOT_FOUND,
            request_id=request_id,
            details={"venue_id": venue_id},
            http_status=404,
        )
    data = VenueMetaData(
        venue_id=bundle.venue["venue_id"],
        venue_name=bundle.venue["venue_name"],
        default_floor_id=bundle.manifest.get("default_floor_id", bundle.floors[0]["floor_id"]),
        supported_floors=[item["floor_id"] for item in bundle.floors],
        entry_points=[
            EntryPoint(
                entrance_id=item["entrance_id"],
                entrance_name=item["entrance_name"],
                floor_id=item["floor_id"],
            )
            for item in bundle.entrances
        ],
        target_poi_count=len(bundle.pois),
        package_version=bundle.manifest["package_version"],
        floors=bundle.floors,
        pois=summarize_pois(bundle),
        landmarks=load_landmarks(bundle),
        route_nodes=summarize_route_nodes(bundle),
    )
    return ApiEnvelope[VenueMetaData](**ok_response(request_id, data.model_dump()))


@app.post("/api/v1/navigation/indoor-route")
async def indoor_route(http_request: Request, request: IndoorRouteRequest) -> ApiEnvelope[IndoorRouteData]:
    http_request.state.request_id = request.request_id
    bundle = load_bundle_or_error(request.request_id)
    if request.venue_id != bundle.venue["venue_id"]:
        raise ApiError(
            code=BusinessCode.VENUE_NOT_FOUND,
            request_id=request.request_id,
            details={"venue_id": request.venue_id},
            http_status=404,
        )
    if request.floor_id not in floor_ids(bundle):
        raise ApiError(
            code=BusinessCode.FLOOR_NOT_FOUND,
            request_id=request.request_id,
            details={"floor_id": request.floor_id},
            http_status=404,
        )
    if find_poi(bundle, request.target_poi_id) is None:
        raise ApiError(
            code=BusinessCode.TARGET_POI_NOT_FOUND,
            request_id=request.request_id,
            details={"target_poi_id": request.target_poi_id},
            http_status=404,
        )
    route = await run_blocking_with_timeout(
        request_id=request.request_id,
        operation="indoor_route",
        timeout_ms=settings.route_timeout_ms,
        timeout_code=BusinessCode.ROUTE_PLANNING_TIMEOUT,
        timeout_message=DEFAULT_MESSAGES[BusinessCode.ROUTE_PLANNING_TIMEOUT],
        func=plan_route,
        args=(bundle, request),
    )
    log_event(
        logger,
        "indoor_route",
        request_id=request.request_id,
        venue_id=request.venue_id,
        floor_id=request.floor_id,
        target_poi_id=request.target_poi_id,
        path_nodes=route.path_nodes,
        next_turn=route.next_turn,
    )
    return ApiEnvelope[IndoorRouteData](**ok_response(request.request_id, route.model_dump()))


@app.post("/api/v1/localization/visual-locate")
async def visual_locate(
    request: Request,
    request_id: Annotated[str, Form(...)],
    venue_id: Annotated[str, Form(...)],
    capture_mode: Annotated[str, Form(...)],
    image: Annotated[UploadFile, File(...)],
    capture_id: Annotated[str | None, Form()] = None,
    timestamp: Annotated[str | None, Form()] = None,
    candidate_floor_id: Annotated[str | None, Form()] = None,
    capture_timestamp_ms: Annotated[int | None, Form()] = None,
    device_id: Annotated[str | None, Form()] = None,
    session_id: Annotated[str | None, Form()] = None,
    current_mode: Annotated[str | None, Form()] = None,
    image_width: Annotated[int | None, Form()] = None,
    image_height: Annotated[int | None, Form()] = None,
    image_format: Annotated[str | None, Form()] = None,
    entrance_id: Annotated[str | None, Form()] = None,
    target_poi_id: Annotated[str | None, Form()] = None,
    route_id: Annotated[str | None, Form()] = None,
    route_edge_ids: Annotated[str | None, Form()] = None,
    route_prior: Annotated[str | None, Form()] = None,
    corridor_window_m: Annotated[float | None, Form()] = None,
    imu_at_capture: Annotated[str | None, Form()] = None,
    debug_target: Annotated[str | None, Form()] = None,
) -> ApiEnvelope[LocalizationData]:
    request.state.request_id = request_id
    del timestamp, capture_timestamp_ms, session_id, current_mode
    del image_width, image_height, image_format, entrance_id, device_id, imu_at_capture

    bundle = load_bundle_or_error(request_id)
    if venue_id != bundle.venue["venue_id"]:
        raise ApiError(
            code=BusinessCode.VENUE_NOT_FOUND,
            request_id=request_id,
            details={"venue_id": venue_id},
            http_status=404,
        )
    if capture_mode not in SUPPORTED_CAPTURE_MODES:
        raise ApiError(
            code=BusinessCode.INVALID_PARAMETER,
            request_id=request_id,
            message="invalid capture_mode",
            details={"field": "capture_mode", "allowed": sorted(SUPPORTED_CAPTURE_MODES)},
        )
    if candidate_floor_id and candidate_floor_id not in floor_ids(bundle):
        raise ApiError(
            code=BusinessCode.FLOOR_NOT_FOUND,
            request_id=request_id,
            details={"floor_id": candidate_floor_id},
            http_status=404,
        )
    if image.content_type and not image.content_type.startswith("image/"):
        raise ApiError(
            code=BusinessCode.IMAGE_PARSE_FAILED,
            request_id=request_id,
            message="image content type is not supported",
            details={"content_type": image.content_type},
        )
    image_bytes = await image.read()
    if not image_bytes:
        raise ApiError(
            code=BusinessCode.IMAGE_PARSE_FAILED,
            request_id=request_id,
            message="image payload is empty",
        )
    if len(image_bytes) > settings.max_upload_bytes:
        raise ApiError(
            code=BusinessCode.INVALID_PARAMETER,
            request_id=request_id,
            message="image payload too large",
            details={"max_upload_bytes": settings.max_upload_bytes},
        )
    if settings.recognition_mode not in SUPPORTED_RECOGNITION_MODES:
        raise ApiError(
            code=BusinessCode.INVALID_PARAMETER,
            request_id=request_id,
            message="invalid recognition_mode",
            details={"field": "AI_GLASSES_RECOGNITION_MODE", "allowed": sorted(SUPPORTED_RECOGNITION_MODES)},
        )
    parsed_route_prior = parse_route_prior(
        request_id=request_id,
        route_prior=route_prior,
        route_id=route_id,
        route_edge_ids=route_edge_ids,
        corridor_window_m=corridor_window_m,
    )
    received_timestamp = int(time.time() * 1000)
    debug_frame_id = build_visual_debug_frame_id(request_id, capture_id)
    image_preview = build_image_preview(image_bytes)
    remember_visual_locate_debug(
        build_visual_debug_received_item(
            debug_frame_id=debug_frame_id,
            request_id=request_id,
            capture_id=capture_id,
            venue_id=venue_id,
            capture_mode=capture_mode,
            image_filename=image.filename,
            image_content_type=image.content_type,
            image_bytes=image_bytes,
            candidate_floor_id=candidate_floor_id,
            target_poi_id=target_poi_id,
            debug_target=debug_target,
            received_timestamp=received_timestamp,
            image_preview=image_preview,
        )
    )
    debug_stages: list[dict[str, Any]]
    if is_pc_landmark_mode():
        result = await run_blocking_with_timeout(
            request_id=request_id,
            operation="visual_locate",
            timeout_ms=visual_locate_timeout_ms(),
            timeout_code=BusinessCode.RELOCALIZATION_TIMEOUT,
            timeout_message=DEFAULT_MESSAGES[BusinessCode.RELOCALIZATION_TIMEOUT],
            func=localize_landmark,
            args=(),
            kwargs={
                "bundle": bundle,
                "request_id": request_id,
                "venue_id": venue_id,
                "recognition_mode": settings.recognition_mode,
                "image_bytes": image_bytes,
                "image_filename": image.filename,
                "debug_target": debug_target,
                "candidate_floor_id": candidate_floor_id,
                "target_poi_id": target_poi_id,
            },
        )
        debug_stages = explain_landmark_recognition(
            bundle=bundle,
            recognition_mode=settings.recognition_mode,
            image_bytes=image_bytes,
            image_filename=image.filename,
            debug_target=debug_target,
            candidate_floor_id=candidate_floor_id,
            target_poi_id=target_poi_id,
            result=result,
        )
    elif is_scene_retrieval_mode():
        result = await run_blocking_with_timeout(
            request_id=request_id,
            operation="visual_locate",
            timeout_ms=visual_locate_timeout_ms(),
            timeout_code=BusinessCode.RELOCALIZATION_TIMEOUT,
            timeout_message=DEFAULT_MESSAGES[BusinessCode.RELOCALIZATION_TIMEOUT],
            func=localize_scene_retrieval,
            args=(),
            kwargs={
                "bundle": bundle,
                "request_id": request_id,
                "venue_id": venue_id,
                "image_bytes": image_bytes,
                "candidate_floor_id": candidate_floor_id,
                "target_poi_id": target_poi_id,
                "config": build_scene_retrieval_config(),
            },
        )
        debug_stages = explain_scene_retrieval(result)
    elif is_scene_classifier_mode():
        result = localize_scene_classifier(
            bundle=bundle,
            request_id=request_id,
            venue_id=venue_id,
            image_bytes=image_bytes,
            candidate_floor_id=candidate_floor_id,
            target_poi_id=target_poi_id,
            config=build_scene_classifier_config(),
        )
        if result.latency_ms > visual_locate_timeout_ms():
            raise ApiError(
                code=BusinessCode.RELOCALIZATION_TIMEOUT,
                request_id=request_id,
                message=DEFAULT_MESSAGES[BusinessCode.RELOCALIZATION_TIMEOUT],
                details={"timeout_ms": visual_locate_timeout_ms(), "operation": "visual_locate"},
                http_status=504,
            )
        debug_stages = explain_scene_classifier(result)
    else:
        result = await run_blocking_with_timeout(
            request_id=request_id,
            operation="visual_locate",
            timeout_ms=visual_locate_timeout_ms(),
            timeout_code=BusinessCode.RELOCALIZATION_TIMEOUT,
            timeout_message=DEFAULT_MESSAGES[BusinessCode.RELOCALIZATION_TIMEOUT],
            func=BaselineRelocalizer(bundle).localize,
            args=(
                LocalizationQuery(
                    request_id=request_id,
                    venue_id=venue_id,
                    capture_mode=capture_mode,
                    image_bytes=image_bytes,
                    candidate_floor_id=candidate_floor_id,
                    route_prior=parsed_route_prior,
                ),
            ),
        )
        result.recognition_mode = settings.recognition_mode
        result.message = result.status.replace("_", " ")
        debug_stages = build_baseline_debug_stages(result)
    log_event(
        logger,
        "visual_locate",
        request_id=request_id,
        capture_id=capture_id,
        venue_id=venue_id,
        capture_mode=capture_mode,
        image_bytes=len(image_bytes),
        candidate_floor_id=candidate_floor_id,
        target_poi_id=target_poi_id,
        recognition_mode=settings.recognition_mode,
        matched_landmark_id=result.matched_landmark.landmark_id if result.matched_landmark else None,
        resolved_floor_id=result.floor_id,
        status=result.status,
        confidence=result.confidence,
        failure_stage=result.failure_stage,
        latency_ms=result.latency_ms,
        trace_id=result.trace_id,
    )
    remember_visual_locate_request(
        {
            "request_id": request_id,
            "capture_id": capture_id,
            "venue_id": venue_id,
            "capture_mode": capture_mode,
            "image_bytes": len(image_bytes),
            "candidate_floor_id": candidate_floor_id,
            "target_poi_id": target_poi_id,
            "recognition_mode": settings.recognition_mode,
            "matched_landmark_id": result.matched_landmark.landmark_id if result.matched_landmark else None,
            "status": result.status,
            "confidence": result.confidence,
            "latency_ms": result.latency_ms,
            "failure_stage": result.failure_stage,
            "timestamp": int(time.time() * 1000),
        }
    )
    remember_visual_locate_debug(
        build_visual_debug_item(
            debug_frame_id=debug_frame_id,
            request_id=request_id,
            capture_id=capture_id,
            venue_id=venue_id,
            capture_mode=capture_mode,
            image_filename=image.filename,
            image_content_type=image.content_type,
            image_bytes=image_bytes,
            candidate_floor_id=candidate_floor_id,
            target_poi_id=target_poi_id,
            debug_target=debug_target,
            result=result,
            stages=debug_stages,
            received_timestamp=received_timestamp,
            image_preview=image_preview,
        )
    )
    message = result.status.replace("_", " ")
    return ApiEnvelope[LocalizationData](**ok_response(request_id, result.model_dump(), message=message))
