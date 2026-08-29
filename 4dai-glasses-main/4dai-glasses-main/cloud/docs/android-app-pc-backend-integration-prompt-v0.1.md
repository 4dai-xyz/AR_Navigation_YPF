# Android App 接入 PC 后台联调 Prompt V0.1

更新时间：2026-06-09

## 1. 使用目标

本文档用于交给 Android App AI，要求手机 App 与当前 Cloud / PC 后台完成同 Wi-Fi 联调。

当前 PC 后台已支持：

- `GET /api/v1/health`
- `GET /debug/pairing`
- `GET /debug/pairing.json`
- `GET /debug/pairing.svg`
- `GET /debug/cards`
- `GET /debug/recent-requests`
- `POST /api/v1/localization/visual-locate`

当前阶段允许两类图片来源：

- Rokid 眼镜图片已经能传输到手机时，App 上传 Rokid 图片到 PC 后台。
- Rokid 图片不可用时，App 使用手机拍照作为 fallback。

## 2. App AI 开发 Prompt

你在仓库 `F:/hz/codex/AI_Glasses` 中负责 Android App。请只做 Android 侧最小必要改动，让 App 能与 Cloud / PC 后台联调，不修改 Cloud、Mapping 或 Contracts。

### 背景

PC 后台运行在同 Wi-Fi 局域网内，启动后监听：

```text
http://<PC局域网IP>:8000
```

当前演示场馆：

```text
venue_id=venue_exhibition_demo
```

当前目标是：Android App 获取 Rokid 眼镜图片或手机拍照图片，上传到 PC 后台 `visual-locate`，解析返回的 `floor_id`、`position`、`confidence`、`matched_landmark`，并在 App 上显示当前位置。

### 必须实现

1. 增加 PC 后台 `baseUrl` 配置入口，默认值可为空或保留现有云端地址，但调试时必须能填写：

```text
http://<PC局域网IP>:8000
```

2. 增加扫码配对入口。App 扫描 PC 后台页面二维码，二维码内容是：

```text
http://<PC局域网IP>:8000/debug/pairing.json
```

App 请求该 JSON，读取 `base_url`，然后调用 `GET <base_url>/api/v1/health`。health 成功后保存 `base_url`，后续所有 PC 后台请求都使用该地址。

`/debug/pairing.json` 关键字段：

| 字段 | 说明 |
| --- | --- |
| `type` | 固定为 `visionroute_pc_backend_pairing` |
| `version` | 当前为 `1` |
| `base_url` | App 应保存的后台地址 |
| `candidate_base_urls` | 可选候选地址列表 |
| `health_url` | 健康检查 URL |
| `visual_locate_url` | 图片定位 URL |
| `venue_id` | 当前场馆 ID |
| `recognition_mode` | 当前识别模式 |

3. 增加健康检查按钮或调试入口：

```http
GET /api/v1/health
```

成功后显示至少以下字段：

- `service_mode`
- `recognition_mode`
- `venue_id`
- `algorithm_backend_status`

4. 增加或复用视觉定位请求：

```http
POST /api/v1/localization/visual-locate
Content-Type: multipart/form-data
```

必填 multipart 字段：

| 字段 | 值 |
| --- | --- |
| `request_id` | App 生成的唯一请求 ID |
| `capture_id` | 本次图片采集 ID |
| `venue_id` | `venue_exhibition_demo` |
| `capture_timestamp_ms` | 图片采集时间戳，毫秒 |
| `capture_mode` | 见下方规则 |
| `image` | JPEG/PNG 图片文件 |

可选 multipart 字段：

| 字段 | 说明 |
| --- | --- |
| `candidate_floor_id` | 当前楼层先验，例如 `F1` |
| `target_poi_id` | 当前导航目标 POI |
| `imu_at_capture` | Rokid IMU JSON 字符串；没有可不传 |
| `debug_target` | 调试时可传 `B10`、`B17`、`toilet`、`hall` |

5. `capture_mode` 规则：

| 图片来源 | `capture_mode` |
| --- | --- |
| Rokid 实时/准实时取帧 | `glasses_private_stream` |
| Rokid 拍照后同步到手机 | `glasses_album_sync` |
| 手机相机 fallback | `phone_camera_fallback` |

不要用手机 IMU 作为用户航向。`imu_at_capture` 只允许传 Rokid 眼镜端 IMU；没有 Rokid IMU 时字段留空。

6. 解析定位响应：

```json
{
  "code": 0,
  "message": "ok",
  "request_id": "req_app_001",
  "data": {
    "request_id": "req_app_001",
    "status": "ok",
    "venue_id": "venue_exhibition_demo",
    "floor_id": "F1",
    "position": {"x": 36.0, "y": 12.0},
    "confidence": 0.94,
    "matched_landmark": {
      "landmark_id": "lm_booth_b17_card",
      "poi_id": "poi_booth_b17",
      "display_name": "B17 展台测试卡",
      "score": 0.94
    },
    "heading_hint": {
      "map_heading_deg": 90.0,
      "source": "landmark_facing",
      "confidence": 0.7
    },
    "failure_stage": null,
    "recognition_mode": "mock",
    "message": "landmark localized",
    "latency_ms": 0
  }
}
```

7. App 状态处理：

| 条件 | App 行为 |
| --- | --- |
| HTTP 200 且 `code=0` 且 `data.status=ok` | 更新当前位置 |
| `data.status=low_confidence` | 显示低置信度提示，不强制更新位置 |
| `data.status=not_found` | 显示未识别，允许用户重新拍摄 |
| `code!=0` | 显示后端错误码和 message |
| 网络失败 | 提示检查同 Wi-Fi、baseUrl、防火墙、health |

8. 调试验证流程：

1. PC 启动后台。
2. 手机和 PC 连接同一个 Wi-Fi。
3. 手机浏览器打开 `http://<PC局域网IP>:8000/api/v1/health`。
4. PC 或手机浏览器打开 `http://<PC局域网IP>:8000/debug/pairing`。
5. App 扫描配对二维码并自动保存 `baseUrl`。
6. 手机浏览器打开 `http://<PC局域网IP>:8000/debug/cards`。
7. App 点击健康检查。
8. App 上传 Rokid 图片；若 Rokid 图片暂不可用，则上传手机拍摄的 debug card。
9. PC 打开 `http://<PC局域网IP>:8000/debug/recent-requests`，确认收到请求。
10. App 显示定位结果。

### 验收标准

- App 可配置 PC 后台 `baseUrl`。
- App 可扫码读取 `/debug/pairing.json` 并保存 `baseUrl`。
- App 健康检查成功并展示 PC 后台状态。
- App 可上传 Rokid 图片或手机 fallback 图片。
- App 能稳定解析 `ok`、`low_confidence`、`not_found`、`code!=0`。
- App 定位成功时显示 `floor_id`、`position`、`confidence`、`matched_landmark.display_name`。
- PC 后台 `/debug/recent-requests` 能看到 App 请求摘要。

### 不要做

- 不要使用 `10.0.2.2` 连接真实手机；它只适用于 Android 模拟器。
- 不要依赖手机 IMU 推断用户航向。
- 不要修改 Cloud、Mapping 或 OpenAPI 文件。
- 不要把真实 OCR 算法写进 Android；App 只负责采图、上传、展示结果。

## 3. Cloud 侧联调事实

- `debug_target=B10` 返回 `poi_booth_b10`。
- `debug_target=B17` 返回 `poi_booth_b17`。
- `debug_target=toilet` 返回 `poi_toilet_f1`。
- `debug_target=hall` 返回 `poi_hall_main`。
- unknown 图片在 mock/template 模式下返回 `status=not_found`，不是系统崩溃。
