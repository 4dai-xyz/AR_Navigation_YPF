# 云端子项目 README

> 首次测试和启动请看上级 [`QUICKSTART.md`](../QUICKSTART.md)。命令应从当前 clone 的 `4dai-glasses-main/4dai-glasses-main` 目录执行，旧的绝对路径只是历史记录。

更新时间：2026-06-09

## 子项目目标

云端子项目负责首版 Demo 的定位与路径规划服务端能力，主要包括：

- 提供室内视觉定位 API
- 提供室内路径规划 API
- 加载并校验场馆地图包
- 执行日志、错误码、超时与置信度策略
- 为 App 提供联调基座

## 仓库位置

- 子项目根目录：`cloud/`
- OpenAPI：`../contracts/openapi/indoor-navigation-api-v1.yaml`
- API 草案：`docs/cloud-localization-api-draft-v0.1.md`
- 可观测性文档：`docs/cloud-observability-error-confidence-v0.1.md`

## 当前代码结构

主要文件如下：

- `app/main.py`
- `app/models/api.py`
- `app/core/error_codes.py`
- `app/core/confidence.py`
- `app/core/logging.py`
- `app/core/settings.py`
- `app/services/venue_package.py`
- `app/services/relocalization.py`
- `app/services/routing.py`
- `tests/`
- `pyproject.toml`

## 当前实现范围

当前仓库中的云端子项目已经具备：

- FastAPI 服务骨架
- 统一响应包结构
- 场馆元数据接口
- 视觉定位接口
- 室内路径规划接口
- 健康检查接口
- 场馆地图包加载
- 路径规划 baseline
- 视觉重定位 Track A 本地视觉原型（图像质量 + ORB 特征 + BFMatcher + RANSAC + 置信度融合）
- 离线重定位评估 harness
- Track B 深度重定位实验 adapter 骨架（默认不启用）
- OpenAPI 与运行时错误码一致性回归测试
- 场馆包字段、引用、版本和资源文件诊断
- 鉴权与限流最小占位（默认关闭）
- 业务错误码与超时错误返回
- 基础日志、trace header 和超时埋点输出
- 单元测试与 smoke test
- PC 后台同 Wi-Fi 联调模式（`pc_backend`）
- PC 后台 Windows 托盘控制脚本
- App 半自动扫码配对页面与配对 JSON
- Exhibition Demo 样例场馆包、Debug Cards 页面和最近请求排查接口

当前仓库中的云端子项目暂未包含：

- 多场馆动态装载平台
- 鉴权与权限系统
- 分布式部署方案
- 在线建图资产上传
- 生产级高精度重定位算法

## PC 后台同 Wi-Fi 快速联调

当前展馆演示优先使用“手机与 PC 连接同一个 Wi-Fi，通过局域网访问 PC 后台”的链路。

```powershell
cd <clone>\4dai-glasses-main\4dai-glasses-main
.\cloud\tools\setup_pc_backend.ps1
.\cloud\tools\run_pc_backend.ps1
```

如果希望从 Windows 托盘快速查看状态、启动/停止后台、打开调试 HTML，可双击或执行：

```powershell
.\cloud\tools\start_pc_backend_tray.cmd
```

托盘菜单和气泡提示默认使用中文，`health`、`debug/pairing`、`debug/cards`、`debug/visual-locate`、`debug/recent-requests`、`baseUrl` 等技术名保留英文。图标颜色含义：绿色表示 health 可访问，黄色表示后台进程已启动但 health 还未就绪，红色表示当前未运行。右键菜单可打开配对页和调试页面，也可复制 Local / LAN `baseUrl`。双击托盘图标会打开 `debug/visual-locate`。

默认启动参数：

- 监听地址：`0.0.0.0`
- 端口：`8000`
- 场馆包：`cloud/data/exhibition_demo`
- `service_mode`：`pc_backend`
- `recognition_mode`：检测到本机 booth classifier checkpoint 时默认 `scene_classifier`，否则回退到 `scene_retrieval` / `template`
- `scene_classifier`：当前展馆 Demo 主链路，使用 MobileNetV3 Small 展台分类器；CUDA 可用时通过 `.venv-ocr` 使用 GPU
- `scene_retrieval`：保留为检索式备选路线；CUDA 可用时优先使用 GPU 三模型融合索引，否则回退 `hybrid_v1` smoke 索引

启动后控制台会打印 `LAN Health URL`、`LAN pairing URL`、`LAN visual-locate URL`、`LAN debug cards URL` 和 `LAN visual debug URL`。手机和 PC 连接同一个 Wi-Fi 后，手机浏览器必须能打开：

```text
http://<PC局域网IP>:8000/api/v1/health
```

Android App 的 `baseUrl` 配置为：

```text
http://<PC局域网IP>:8000
```

半自动配对入口：

```text
http://<PC局域网IP>:8000/debug/pairing
```

App 扫描该页面二维码后请求 `/debug/pairing.json`，读取 `base_url`，再调用 `/api/v1/health` 验证成功后保存。

不要在真实手机上使用 `10.0.2.2`；它只适用于 Android 模拟器访问宿主机。

PC 后台联调文档：

- `docs/pc-backend-local-wifi-smoke-runbook-v0.1.md`
- `docs/pc-backend-onsite-migration-runbook-v0.1.md`
- `docs/android-app-pc-backend-integration-prompt-v0.1.md`
- `data/exhibition_demo/README.md`

## 快速启动

请从仓库根目录启动服务，避免 Python 包路径不一致：

```bash
cd <clone>\4dai-glasses-main\4dai-glasses-main
python -m venv .venv
.\.venv\Scripts\pip install -e .\cloud
.\.venv\Scripts\python -m uvicorn cloud.app.main:app --host 0.0.0.0 --port 8000
```

可选环境变量：

- `AI_GLASSES_VENUE_PACKAGE_ROOT`：场馆地图包根目录
- `AI_GLASSES_VENUE_PACKAGE_VERSION`：可选包版本约束；设置后 `manifest.package_version` 必须一致
- `AI_GLASSES_MAX_UPLOAD_BYTES`：单张图片最大载荷
- `AI_GLASSES_RELOCALIZATION_TIMEOUT_MS`：视觉重定位超时阈值
- `AI_GLASSES_ROUTE_TIMEOUT_MS`：路径规划超时阈值
- `AI_GLASSES_SERVICE_MODE`：服务模式；PC 后台联调用 `pc_backend`
- `AI_GLASSES_PC_BACKEND_HOST`：PC 后台监听地址，默认 `0.0.0.0`
- `AI_GLASSES_PC_BACKEND_PORT`：PC 后台端口，默认 `8000`
- `AI_GLASSES_PC_BACKEND_LAN_BASE_URL`：可选，覆盖配对页首选局域网地址；多网卡现场可设置为 `http://<PC局域网IP>:8000`
- `AI_GLASSES_RECOGNITION_MODE`：识别模式；支持 `baseline`、`mock`、`template`、`real_ocr_adapter`、`scene_retrieval`、`scene_classifier`
- `AI_GLASSES_SCENE_CLASSIFIER_CHECKPOINT_PATH`：可选，覆盖 scene classifier checkpoint 路径
- `AI_GLASSES_SCENE_CLASSIFIER_DEVICE`：可选，`auto` / `cuda` / `cpu`，默认 `auto`
- `AI_GLASSES_SCENE_CLASSIFIER_MIN_CONFIDENCE`：可选，低于该阈值返回 `not_found`，默认 `0.20`
- `AI_GLASSES_SCENE_CLASSIFIER_OK_CONFIDENCE`：可选，高于该阈值返回 `ok`，默认 `0.50`
- `AI_GLASSES_SCENE_RETRIEVAL_INDEX_PATH`：可选，覆盖 scene retrieval 索引路径
- `AI_GLASSES_SCENE_RETRIEVAL_METADATA_PATH`：可选，覆盖 scene retrieval metadata 路径
- `AI_GLASSES_SCENE_RETRIEVAL_BOOTH_COORDINATES_PATH`：可选，覆盖展台坐标表路径
- `AI_GLASSES_SCENE_RETRIEVAL_FEATURE_EXTRACTOR`：可选，默认 `fusion_resnet50_clip_vitb32_dinov2`
- `AI_GLASSES_SCENE_RETRIEVAL_DEVICE`：可选，`auto` / `cuda` / `cpu`，默认 `auto`
- `AI_GLASSES_REAL_OCR_DETECTOR_PTH`：可选，覆盖真实 OCR detector 权重路径
- `AI_GLASSES_REAL_OCR_CLASSIFIER_PTH`：可选，覆盖真实 OCR classifier 权重路径
- `AI_GLASSES_REAL_OCR_CLASSES_TXT`：可选，覆盖真实 OCR 类别名文件路径
- `AI_GLASSES_REAL_OCR_TIMEOUT_MS`：真实 OCR 首帧模型加载/推理超时，默认 `15000`
- `AI_GLASSES_AUTH_ENABLED`：鉴权占位开关，默认 `false`
- `AI_GLASSES_API_TOKEN`：鉴权占位使用的 Bearer token
- `AI_GLASSES_RATE_LIMIT_PER_MINUTE`：限流占位阈值，默认 `0` 表示关闭

默认不设置 `AI_GLASSES_VENUE_PACKAGE_ROOT` 时，服务加载 `mapping/examples/venue-package-example`。

## 场馆包切换

当前云端保持单场馆单包模式，不做热切换平台。切换真实包时只改配置：

```powershell
$env:AI_GLASSES_VENUE_PACKAGE_ROOT="F:\path\to\real-venue-package"
.\.venv\Scripts\python -m uvicorn cloud.app.main:app --host 0.0.0.0 --port 8000
```

切包流程：

1. 先用 `mapping/tools/validate_venue_package.py <package-root> --json` 校验真实包。
2. 设置 `AI_GLASSES_VENUE_PACKAGE_ROOT` 指向真实包根目录。
3. 如需锁定版本，设置 `AI_GLASSES_VENUE_PACKAGE_VERSION`，否则只按目录加载。
4. 重启云端服务；当前进程内会缓存已加载的场馆包，不做运行时热切换。
5. 调用 `GET /api/v1/health`，确认场馆包可加载。
6. 调用 `GET /api/v1/venues/{venue_id}/meta`，确认 `venue_id`、楼层、入口、POI 数量符合预期。

## 核心接口行为

| 接口 | 成功行为 | 主要失败语义 |
| --- | --- | --- |
| `GET /api/v1/health` | 返回服务健康；同时验证场馆包可加载 | 包缺失/校验失败返回 `9001` |
| `GET /api/v1/venues/{venue_id}/meta` | 返回场馆、楼层、入口和包版本 | 场馆不匹配返回 `1002` |
| `POST /api/v1/localization/visual-locate` | 返回 `ok`、`low_confidence` 或 `not_found` | 参数错误 `1001`，楼层不存在 `1003`，重定位超时 `2003` |
| `POST /api/v1/navigation/indoor-route` | 返回路径节点、下一步动作、距离和是否跨层 | 目标不存在 `1004`，路径失败 `3001`，路径超时 `3002` |
| `GET /debug/pairing` | 返回 App 半自动扫码配对页面和二维码 | 包缺失/校验失败返回 `9001` |
| `GET /debug/pairing.json` | 返回 `base_url`、`health_url`、`visual_locate_url` 等 App 配对信息 | 包缺失/校验失败返回 `9001` |
| `GET /debug/pairing.svg` | 返回配对二维码 SVG，二维码内容为 `/debug/pairing.json` | 包缺失/校验失败返回 `9001` |
| `GET /debug/cards` | 返回 B10、B17、厕所、报告厅测试卡 HTML | 包缺失/校验失败返回 `9001` |
| `GET /debug/recent-requests` | 返回最近 visual-locate 请求摘要 | 包缺失/校验失败返回 `9001` |
| `GET /debug/visual-locate` | 返回最近 visual-locate 图片缩略图和识别阶段 HTML | 仅用于 PC 后台现场排查 |

`visual-locate` 的 `low_confidence` 和 `not_found` 是可消费业务结果，仍返回 `code=0`；真正参数、资源、包加载、超时和系统问题才返回非 `0` 业务码。

若开启治理占位，除 `GET /api/v1/health` 外的接口都需要 Bearer token；鉴权失败返回 `401 + code=4001`，限流命中返回 `429 + code=4002`。默认配置下二者关闭，不影响局域网联调。

## 错误与排障

统一错误响应仍使用：

```json
{
  "code": 9001,
  "message": "venue package validation failed",
  "request_id": "req_xxx",
  "data": {
    "error_type": "venue_package_error",
    "stage": "validation",
    "package_root": "F:\\path\\to\\package",
    "validation_errors": ["route_graph.edges[0].from_node_id: unknown node 'node_missing'"]
  }
}
```

`code=9001` 表示服务端内部错误，不只代表场馆包错误。包相关失败会通过 `data.error_type=venue_package_error` 与 `data.stage` 区分 `root_not_found`、`missing_file`、`json_parse`、`validation` 等阶段；未捕获异常也会返回 `code=9001`，但 `data=null`。当前会校验必需文件、字段、楼层引用、POI/node/edge 引用、入口/连接点引用、keyframe 图像/特征文件引用、相机内参引用和可选包版本。

## 常用验证命令

```bash
.\.venv\Scripts\python -m unittest discover cloud/tests
python mapping/tools/validate_venue_package.py mapping/examples/venue-package-example --json
python mapping/tools/publish_venue_package.py mapping/examples/venue-package-example --output-dir dist
```

离线重定位评估 JSONL 最小样例：

```json
{"query_id":"q1","venue_id":"venue_demo_001","image_path":"localization/images/kf_0001.jpg","expected_keyframe_id":"kf_0001","expected_floor_id":"F1"}
```

从仓库根目录运行：

```bash
.\.venv\Scripts\python cloud/tools/evaluate_relocalization.py --queries F:\path\to\queries.jsonl --query-root mapping/examples/venue-package-example --report-json F:\path\to\report.json --failure-json F:\path\to\failures.json
```

报告会输出 `top1_accuracy`、`top3_accuracy`、`floor_accuracy`、`average_latency_ms`、`status_counts`、`failure_stage_counts`、`by_floor`、`by_venue` 和失败样本摘要。样例包中的 keyframe 图片目前是占位字节，Track A 会走 fallback 图像用于 smoke；真实视觉效果仍依赖 Mapping AI 后续提供真实 keyframe/query 图片。

展馆 Demo `scene_classifier` HTTP 自测：

```bash
.\.venv-ocr\Scripts\python.exe cloud/tools/selftest_scene_classifier_http.py
```

展馆 Demo `scene_classifier_v3` GPU hard-partial 训练：

```bash
.\.venv-ocr\Scripts\python.exe cloud/tools/train_scene_booth_classifier.py --epochs 2 --batch-size 128 --lr 0.00003 --model-arch mobilenet_v3_small --experiment-name v3_gpu_hard_partial --init-checkpoint cloud/tmp_scene_recognition_probe/yjdd_hd_booth_classifier_mobilenet_v3_small_v2_aug/booth_classifier_mobilenet_v3_small_v2_aug.pt --cache-images --gpu-augment --gpu-augment-profile hard_partial
```

当前默认优先使用 `scene_classifier_v3`，缺失时回退 v2 / v1。v3 HTTP 自测覆盖 `52` 个展台分组、`2349` 张样本图：`raw_top1_accuracy=99.87%`，`ok_top1_accuracy=100%`，`ok_rate=99.11%`，服务内 `latency_ms p95=22ms / max=80ms`，`latency_over_limit_count=0`。hard-stress 合成压力集上 v3 `top1_accuracy=95.75%`，v2 为 `95.03%`。v4 `mobilenet_v3_large` 已完成实验，hard-stress `top1_accuracy=95.83%`，但 HTTP `ok_rate=78.54%`，需要单独做置信度校准，暂不作为默认。合成压力集覆盖亮度、模糊、半视角、遮挡、色偏、低清和 JPEG 压缩模拟，不等价于真实跨摄像头/现场光照验证。

更多联调请求、响应和失败样例见 `docs/cloud-integration-smoke-v0.1.md`。

## 关键依赖

- FastAPI
- Pydantic
- OpenCV headless
- 场馆地图包
- 离线重定位评估脚本
- Track B 深度重定位实验 adapter（可选依赖未安装时保持不可用）
- 展馆 Demo `scene_classifier` adapter（可选依赖在 `.venv-ocr` 中启用）
- OpenAPI 契约
- 重定位双轨开发方案

## 关联文档

- [云端子项目 PRD](./PRD.md)
- [云端子项目进度](./PROGRESS.md)
- [OpenAPI 3.0](../contracts/openapi/indoor-navigation-api-v1.yaml)
- [云端 API 草案](./docs/cloud-localization-api-draft-v0.1.md)
- [日志、错误码、置信度策略](./docs/cloud-observability-error-confidence-v0.1.md)
- [云端重定位算法研究](./docs/cloud-relocalization-algorithm-research-v0.1.md)
- [云端重定位双轨开发方案](./docs/cloud-relocalization-dual-track-development-plan-v0.1.md)
- [Landmark OCR / LOGO 重定位方案](./docs/cloud-landmark-ocr-logo-relocalization-plan-v0.1.md)
- [Scene Classifier 技术方案汇总](./docs/cloud-scene-classifier-technical-summary-v0.1.md)
- [云端联调 smoke 说明](./docs/cloud-integration-smoke-v0.1.md)
- [PC 后台同 Wi-Fi 联调 Runbook](./docs/pc-backend-local-wifi-smoke-runbook-v0.1.md)
- [PC 后台现场迁移 Runbook](./docs/pc-backend-onsite-migration-runbook-v0.1.md)
