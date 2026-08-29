# 云端联调 Smoke 说明 V0.1

更新日期：2026-04-29

## 1. 目的

本文档用于真实场馆包到位前，固定云端最小联调方式。目标是让 App AI、Mapping AI 和云端基于同一套请求、响应和失败样例排查问题。

## 2. 启动方式

从仓库根目录启动：

```powershell
cd F:\hz\codex\AI_Glasses
.\.venv\Scripts\python -m pip install -e .\cloud
.\.venv\Scripts\python -m uvicorn cloud.app.main:app --host 0.0.0.0 --port 8000
```

默认加载样例包：

```text
mapping/examples/venue-package-example
```

切换真实包：

```powershell
$env:AI_GLASSES_VENUE_PACKAGE_ROOT="F:\path\to\real-venue-package"
.\.venv\Scripts\python -m uvicorn cloud.app.main:app --host 0.0.0.0 --port 8000
```

当前服务会缓存已加载包；切换 `AI_GLASSES_VENUE_PACKAGE_ROOT` 后需要重启进程。

## 3. Smoke 验证命令

```powershell
.\.venv\Scripts\python -m unittest discover cloud/tests
.\.venv\Scripts\python mapping/tools/validate_venue_package.py mapping/examples/venue-package-example --json
```

真实启动后先调用：

```powershell
Invoke-RestMethod http://127.0.0.1:8000/api/v1/health
Invoke-RestMethod http://127.0.0.1:8000/api/v1/venues/venue_demo_001/meta
```

## 4. 请求样例

### 4.1 Health

```powershell
Invoke-RestMethod http://127.0.0.1:8000/api/v1/health
```

成功响应：

```json
{
  "code": 0,
  "message": "ok",
  "request_id": "health_001",
  "data": {
    "status": "healthy",
    "service": "indoor-navigation-api",
    "version": "0.1.0"
  }
}
```

### 4.2 Venue Meta

```powershell
Invoke-RestMethod http://127.0.0.1:8000/api/v1/venues/venue_demo_001/meta
```

成功响应关注字段：

```json
{
  "code": 0,
  "message": "ok",
  "request_id": "meta_venue_demo_001",
  "data": {
    "venue_id": "venue_demo_001",
    "default_floor_id": "F1",
    "supported_floors": ["F1", "F2"],
    "target_poi_count": 1,
    "package_version": "0.1.0"
  }
}
```

### 4.3 Indoor Route

```powershell
$body = @{
  request_id = "req_route_001"
  venue_id = "venue_demo_001"
  floor_id = "F1"
  start_position = @{ x = 5.1; y = 8.1 }
  target_poi_id = "poi_store_a"
  route_strategy = "fastest"
} | ConvertTo-Json -Depth 8

Invoke-RestMethod `
  -Method Post `
  -Uri http://127.0.0.1:8000/api/v1/navigation/indoor-route `
  -ContentType "application/json" `
  -Body $body
```

成功响应关注字段：

```json
{
  "code": 0,
  "message": "ok",
  "request_id": "req_route_001",
  "data": {
    "target_poi_id": "poi_store_a",
    "path_nodes": ["node_f1_entry_west", "node_f1_escalator_up", "node_f2_escalator_down", "node_f2_store_a"],
    "next_turn": "take_escalator_up",
    "cross_floor_required": true
  }
}
```

### 4.4 Visual Locate

```powershell
curl.exe -X POST http://127.0.0.1:8000/api/v1/localization/visual-locate `
  -F "request_id=req_loc_001" `
  -F "venue_id=venue_demo_001" `
  -F "timestamp=2026-04-28T12:05:00+08:00" `
  -F "capture_mode=glasses_album_sync" `
  -F "device_id=device_001" `
  -F "candidate_floor_id=F1" `
  -F "route_prior={\"route_id\":\"route_req_route_001\",\"edge_ids\":[\"edge_f1_entry_to_escalator\"],\"corridor_window_m\":2.0}" `
  -F "image=@mapping/examples/venue-package-example/localization/images/kf_0001.jpg;type=image/jpeg"
```

成功响应关注字段：

```json
{
  "code": 0,
  "message": "ok",
  "request_id": "req_loc_001",
  "data": {
    "status": "ok",
    "venue_id": "venue_demo_001",
    "floor_id": "F1",
    "confidence": 0.86,
    "suggested_action": "continue_navigation",
    "latency_ms": 0
  }
}
```

## 5. 失败样例

### 5.1 参数错误

`capture_mode` 不在枚举内：

```json
{
  "code": 1001,
  "message": "invalid capture_mode",
  "request_id": "req_loc_bad_mode",
  "data": {
    "field": "capture_mode",
    "allowed": ["glasses_album_sync", "glasses_private_stream", "glasses_thumbnail", "phone_camera_fallback"]
  }
}
```

### 5.2 业务未命中

图片过小或质量不足时，`visual-locate` 仍返回 HTTP 200 和 `code=0`，由 `data.status` 表达业务结果：

```json
{
  "code": 0,
  "message": "not found",
  "request_id": "req_loc_002",
  "data": {
    "status": "not_found",
    "confidence": 0,
    "failure_stage": "image_quality_failed",
    "suggested_action": "retry_after_move"
  }
}
```

### 5.3 场馆包错误

缺少 `route_graph.json`：

```json
{
  "code": 9001,
  "message": "venue package required file missing",
  "request_id": "req_health_bad_pkg",
  "data": {
    "error_type": "venue_package_error",
    "stage": "missing_file",
    "package_root": "F:\\path\\to\\real-venue-package",
    "missing_files": ["route_graph.json"]
  }
}
```

路网引用了不存在的节点：

```json
{
  "code": 9001,
  "message": "venue package validation failed",
  "request_id": "req_health_bad_pkg",
  "data": {
    "error_type": "venue_package_error",
    "stage": "validation",
    "validation_errors": ["route_graph.edges[0].from_node_id: unknown node 'node_missing'"]
  }
}
```

### 5.4 超时

重定位超时：

```json
{
  "code": 2003,
  "message": "relocalization timed out",
  "request_id": "req_loc_timeout_001",
  "data": {
    "timeout_ms": 3000
  }
}
```

路径规划超时：

```json
{
  "code": 3002,
  "message": "route planning timeout",
  "request_id": "req_route_timeout_001",
  "data": {
    "timeout_ms": 1500
  }
}
```

## 6. 排障顺序

| 现象 | 优先排查 |
| --- | --- |
| `health` 返回 `9001 + venue_package_error` | Mapping AI 检查包路径、必需文件、JSON、路网和 keyframe 引用 |
| `meta` 返回 `1002` | App AI 使用的 `venue_id` 与当前包 `venue_id` 不一致 |
| `visual-locate` 返回 `1001` | App AI 检查 multipart 字段、`capture_mode`、`route_prior` JSON |
| `visual-locate` 返回 `status=not_found` | App AI 检查图片质量/来源；Mapping AI 检查 keyframe 覆盖 |
| `indoor-route` 返回 `1004` | App AI 检查目标 POI ID 是否来自当前 `venue meta` 或目标列表 |
| `indoor-route` 返回 `3001` | Mapping AI 检查 POI `route_node_id` 与路网连通性 |

## 7. 日志抓手

云端输出 JSON 日志，联调时优先按 `request_id` 串联：

- `request_completed`
- `api_error`
- `validation_error`
- `visual_locate`
- `indoor_route`

定位日志重点看：

- `venue_id`
- `capture_mode`
- `candidate_floor_id`
- `resolved_floor_id`
- `status`
- `confidence`
- `failure_stage`
- `latency_ms`
- `trace_id`
