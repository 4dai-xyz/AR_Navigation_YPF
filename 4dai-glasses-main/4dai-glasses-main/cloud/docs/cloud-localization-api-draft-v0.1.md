# 云端定位 API 草案 V0.1

更新日期：2026-04-29

## 1. 文档目的

本文档定义首版 Demo 的云端定位 API 草案，供以下团队对齐：

- 手机 App
- 云端服务
- 视觉定位算法
- 测试与日志评估

本文档重点覆盖“室内视觉重定位”主链路，不把未来版本的大量扩展能力提前塞进接口。

## 2. API 设计目标

首版云端定位 API 需要满足：

1. 输入尽量简单，便于 App 先跑通闭环
2. 输出足够支撑导航状态机
3. 能表达低置信度和失败状态
4. 便于后续挂日志和性能指标

## 3. 首版接口范围

建议首版先定义 4 组接口：

1. `POST /api/v1/localization/visual-locate`
室内视觉重定位主接口

2. `POST /api/v1/navigation/indoor-route`
室内路径规划接口

3. `GET /api/v1/venues/{venue_id}/meta`
获取场馆元信息

4. `GET /api/v1/health`
健康检查接口

如果要严格收敛 MVP，最核心的是前两项。

## 4. 通用约定

### 4.1 协议

- 协议：HTTPS
- 数据格式：JSON
- 图片上传：首版建议使用 `multipart/form-data`
- 字符编码：UTF-8

### 4.2 认证

首版 Demo 可先采用简单方案：

- Header：`Authorization: Bearer <token>`

如果当前阶段没有统一认证，可先在内网或测试环境中使用固定 token。

### 4.3 公共 Header

建议包含：

| Header | 必填 | 说明 |
| --- | --- | --- |
| `Authorization` | 否 | Demo 环境可选 |
| `X-Request-Id` | 否 | 请求唯一 ID，建议传，方便链路追踪 |
| `X-App-Version` | 否 | App 版本 |
| `X-Device-Id` | 否 | 设备 ID |

若 Header 未传，链路追踪默认以请求体中的 `request_id` 为主键。

### 4.4 通用响应结构

所有接口建议统一返回：

```json
{
  "code": 0,
  "message": "ok",
  "request_id": "req_20260428120001_001",
  "data": {}
}
```

字段说明：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `code` | integer | 业务码，`0` 表示成功 |
| `message` | string | 响应说明 |
| `request_id` | string | 服务端回传请求 ID |
| `data` | object | 具体响应体 |

## 5. 室内视觉重定位接口

### 5.1 接口定义

```text
POST /api/v1/localization/visual-locate
```

用途：

- 根据上传图片和场馆上下文，返回用户当前室内楼层与大体位置

### 5.2 请求方式

建议使用：

- `Content-Type: multipart/form-data`

其中结构包括：

- 文本字段
- 一张图片文件

### 5.3 请求字段

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `request_id` | string | 是 | 客户端请求 ID |
| `capture_id` | string | 否 | 单次采图 ID，便于端云日志对齐 |
| `venue_id` | string | 是 | 场馆 ID |
| `candidate_floor_id` | string | 否 | 候选楼层，App 可传 |
| `timestamp` | string | 是 | 图片采集时间 |
| `capture_timestamp_ms` | integer | 否 | 图片采集时间戳，毫秒 |
| `capture_mode` | string | 是 | `glasses_thumbnail`、`glasses_album_sync`、`glasses_private_stream`、`phone_camera_fallback`，用于链路分析和兜底决策 |
| `device_id` | string | 是 | 设备 ID |
| `session_id` | string | 否 | 导航会话 ID |
| `current_mode` | string | 否 | 当前模式，建议传 `indoor_navigation` |
| `image_width` | integer | 否 | 图片宽 |
| `image_height` | integer | 否 | 图片高 |
| `image_format` | string | 否 | 如 `jpg` |
| `image` | file | 是 | 当前环境图像 |
| `entrance_id` | string | 否 | 如果刚进入场馆，可传入口 ID |
| `target_poi_id` | string | 否 | 当前目标点 |
| `route_prior` | JSON string | 否 | 当前导航路线先验，用于缩小检索范围；multipart 下当前服务支持 JSON 字符串 |
| `route_id` | string | 否 | 兼容字段，未传 `route_prior` 时使用 |
| `route_edge_ids` | string | 否 | 兼容字段，逗号分隔，未传 `route_prior` 时使用 |
| `corridor_window_m` | number | 否 | 兼容字段，未传 `route_prior` 时使用 |

当前实现优先解析 `route_prior`，示例：`{"route_id":"route_001","edge_ids":["edge_a"],"corridor_window_m":2.0}`。若 App 侧暂不方便在 multipart 中传 JSON，可继续传 `route_id + route_edge_ids + corridor_window_m`。

首版可选扩展字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `approx_lat` | number | 否 | 手机近似地理纬度 |
| `approx_lng` | number | 否 | 手机近似地理经度 |
| `network_type` | string | 否 | 网络类型 |
| `network_latency_ms` | integer | 否 | App 侧估算网络延迟 |
| `image_quality` | object | 否 | App 侧初步图像质量信息 |

### 5.4 请求示例

文本字段示例：

```text
request_id=req_20260428120500_001
capture_id=cap_20260428120500_001
venue_id=venue_sh_mall_001
candidate_floor_id=F1
timestamp=2026-04-28T12:05:00+08:00
capture_timestamp_ms=1777358700000
capture_mode=glasses_album_sync
device_id=glass_001
session_id=session_1001
current_mode=indoor_navigation
image_width=1600
image_height=1200
image_format=jpg
target_poi_id=poi_store_001
route_prior={"route_id":"route_001","edge_ids":["edge_f1_032"],"corridor_window_m":2.0}
```

### 5.5 成功响应字段

`data` 内建议字段如下：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `status` | string | 是 | `ok`、`low_confidence`、`not_found` |
| `venue_id` | string | 是 | 场馆 ID |
| `floor_id` | string | 否 | 识别出的楼层 |
| `position` | object | 否 | 识别出的平面位置 |
| `confidence` | number | 是 | 0 到 1 |
| `uncertainty_m` | number | 否 | 估算误差半径，单位米 |
| `matched_keyframe_id` | string | 否 | 命中的关键帧 |
| `matched_keyframes` | array | 否 | Top-K 匹配关键帧摘要 |
| `inlier_count` | integer | 否 | 几何验证内点数 |
| `failure_stage` | string | 否 | 失败或低置信度阶段 |
| `route_snap` | object | 否 | 路网吸附结果 |
| `next_capture_hint` | string | 否 | 下一次采图建议 |
| `suggested_action` | string | 是 | App 建议动作 |
| `trace_id` | string | 否 | 服务端链路追踪 ID |
| `latency_ms` | integer | 否 | 服务端处理耗时 |

`position` 建议字段：

- `x`
- `y`

### 5.6 成功响应示例

```json
{
  "code": 0,
  "message": "ok",
  "request_id": "req_20260428120500_001",
  "data": {
    "status": "ok",
    "venue_id": "venue_sh_mall_001",
    "floor_id": "F1",
    "position": {
      "x": 18.4,
      "y": 42.7
    },
    "confidence": 0.87,
    "uncertainty_m": 1.8,
    "matched_keyframe_id": "kf_f1_2031",
    "inlier_count": 68,
    "route_snap": {
      "edge_id": "edge_f1_032",
      "distance_m": 0.7
    },
    "next_capture_hint": "normal",
    "suggested_action": "continue_navigation",
    "trace_id": "trace_8f2d1b",
    "latency_ms": 1420
  }
}
```

### 5.7 低置信度响应示例

```json
{
  "code": 0,
  "message": "low confidence",
  "request_id": "req_20260428120501_002",
  "data": {
    "status": "low_confidence",
    "venue_id": "venue_sh_mall_001",
    "floor_id": "F1",
    "position": {
      "x": 16.9,
      "y": 39.1
    },
    "confidence": 0.42,
    "uncertainty_m": 5.6,
    "failure_stage": "low_confidence",
    "next_capture_hint": "face_forward_and_retry",
    "suggested_action": "request_more_images",
    "trace_id": "trace_91aa77",
    "latency_ms": 1580
  }
}
```

### 5.8 未命中响应示例

```json
{
  "code": 0,
  "message": "location not found",
  "request_id": "req_20260428120503_003",
  "data": {
    "status": "not_found",
    "venue_id": "venue_sh_mall_001",
    "confidence": 0.0,
    "failure_stage": "retrieval_no_hit",
    "next_capture_hint": "move_1_to_2_meters_and_retry",
    "suggested_action": "retry_after_move",
    "trace_id": "trace_c18d0a",
    "latency_ms": 1310
  }
}
```

### 5.9 App 侧建议处理策略

根据 `status` 建议如下：

| `status` | App 建议行为 |
| --- | --- |
| `ok` | 更新定位，继续导航 |
| `low_confidence` | 暂停强提示，提示补充图片信息或继续前进 |
| `not_found` | 不更新位置，提示重试 |

## 6. 室内路径规划接口

### 6.1 接口定义

```text
POST /api/v1/navigation/indoor-route
```

用途：

- 根据起点位置和目标店铺门口，生成室内路径

### 6.2 请求字段

建议使用 JSON：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `request_id` | string | 是 | 请求 ID |
| `venue_id` | string | 是 | 场馆 ID |
| `floor_id` | string | 是 | 当前楼层 |
| `start_position` | object | 是 | 起点位置 |
| `target_poi_id` | string | 是 | 目标 POI |
| `route_strategy` | string | 否 | 首版建议固定 `fastest` |

`start_position`：

- `x`
- `y`

### 6.3 成功响应字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `route_id` | string | 路径 ID |
| `target_poi_id` | string | 目标点 |
| `path_nodes` | array | 路径节点序列 |
| `next_turn` | string | 下一步动作 |
| `distance_to_next_turn` | number | 到下一个动作点距离 |
| `distance_to_target` | number | 到目标距离 |
| `cross_floor_required` | boolean | 是否跨层 |

### 6.4 响应示例

```json
{
  "code": 0,
  "message": "ok",
  "request_id": "req_route_001",
  "data": {
    "route_id": "route_20260428_001",
    "target_poi_id": "poi_store_001",
    "path_nodes": [
      "node_f1_001",
      "node_f1_002",
      "node_f1_003"
    ],
    "next_turn": "turn_left",
    "distance_to_next_turn": 8.2,
    "distance_to_target": 35.6,
    "cross_floor_required": false
  }
}
```

## 7. 场馆元信息接口

### 7.1 接口定义

```text
GET /api/v1/venues/{venue_id}/meta
```

用途：

- 给 App 返回基础场馆信息
- 支撑目标选择、模式切换和缓存策略

### 7.2 响应建议字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `venue_id` | string | 场馆 ID |
| `venue_name` | string | 场馆名 |
| `default_floor_id` | string | 默认楼层 |
| `supported_floors` | array | 支持楼层 |
| `entry_points` | array | 可用入口 |
| `target_poi_count` | integer | 目标点数量 |
| `package_version` | string | 当前场馆包版本 |

## 8. 健康检查接口

### 8.1 接口定义

```text
GET /api/v1/health
```

用途：

- 服务健康检查
- 用于运维和 Demo 环境快速诊断

### 8.2 响应示例

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

## 9. 业务错误码建议

首版建议统一一组简单错误码：

| code | 含义 | 说明 |
| --- | --- | --- |
| `0` | 成功 | 成功返回 |
| `1001` | 参数错误 | 必填字段缺失或格式错误 |
| `1002` | 场馆不存在 | `venue_id` 无效 |
| `1003` | 楼层不存在 | `floor_id` 无效 |
| `1004` | 目标点不存在 | `target_poi_id` 无效 |
| `2001` | 图像解析失败 | 图片损坏或格式错误 |
| `2002` | 重定位失败 | 未命中位置 |
| `2003` | 重定位超时 | 算法处理超时 |
| `2004` | 图像质量不足 | 模糊、过暗或分辨率不足 |
| `3001` | 路径规划失败 | 起终点无可用路线 |
| `3002` | 路径规划超时 | 路径规划处理超时 |
| `4001` | 鉴权占位拒绝 | 启用 `AI_GLASSES_AUTH_ENABLED` 后 Bearer token 缺失或错误 |
| `4002` | 限流占位拒绝 | 启用 `AI_GLASSES_RATE_LIMIT_PER_MINUTE` 后命中单机内存限流 |
| `9001` | 服务内部错误 | 未分类异常；当前也承载 `venue_package_error` 包加载/校验失败 |

建议 `failure_stage` 使用下面枚举，便于 App 判断是否重拍、前进、暂停强提示：

- `image_quality_failed`
- `retrieval_no_hit`
- `match_insufficient`
- `geometry_failed`
- `route_snap_failed`
- `low_confidence`

## 10. 首版性能目标

首版 API 设计建议围绕这些目标：

| 指标 | 目标 |
| --- | --- |
| 单次定位接口平均返回时间 | 2-3 秒 |
| 图片上传建议频率 | 1 秒 1 张 |
| 定位成功状态返回 | 支撑 3 米级大体定位 |
| 低置信度状态返回 | 必须可区分 |

## 11. 日志字段建议

为了后续排查“定位不准”和“时延过大”，建议服务端日志至少记录：

| 字段 | 说明 |
| --- | --- |
| `request_id` | 请求 ID |
| `trace_id` | 链路 ID |
| `capture_id` | 单次采图 ID |
| `capture_mode` | 采图来源 |
| `venue_id` | 场馆 ID |
| `candidate_floor_id` | 候选楼层 |
| `resolved_floor_id` | 实际识别楼层 |
| `status` | 定位结果状态 |
| `confidence` | 置信度 |
| `failure_stage` | 失败阶段 |
| `uncertainty_m` | 估算误差半径 |
| `inlier_count` | 几何验证内点数 |
| `latency_ms` | 总耗时 |
| `image_width` | 图像宽 |
| `image_height` | 图像高 |
| `error_code` | 错误码 |

## 12. 首版暂不纳入的 API 字段

为了避免接口膨胀，以下字段建议首版不做：

- IMU 原始数据
- 双目左右目图像
- 视频流式上传
- 语音指令上下文
- 动态障碍物信息
- 众包采集审核字段
