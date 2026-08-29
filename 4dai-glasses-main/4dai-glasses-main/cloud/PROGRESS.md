# 云端子项目进度

更新时间：2026-06-10

## 当前状态

状态：进行中

当前云端已经具备可联调的 FastAPI 服务骨架、可配置场馆包加载能力、视觉重定位 baseline、室内路径规划 baseline、错误码与超时语义、离线评估、包诊断、OpenAPI 一致性检查、最小治理占位，以及展馆演示用 PC 后台同 Wi-Fi 联调基座。

需要明确的是：

- 云端能力仍然保留并持续演进
- 当前展馆演示优先使用 `pc_backend` 跑通手机/Rokid 图像上传重定位链路
- Rokid 眼镜图像已可由 App 上传到 PC 后台；服务端继续复用当前 `visual-locate` 输入输出，且不依赖 `imu_at_capture` 才能返回结果
- PC 后台已新增 Windows 托盘入口，可快速查看 health、启动/停止后台、打开调试页和配对页
- App 半自动扫码配对已落地：`/debug/pairing` 展示二维码，`/debug/pairing.json` 返回 `base_url`、`health_url`、`visual_locate_url` 等连接信息
- App 侧已支持扫码读取 `/debug/pairing.json` 并自动保存 `baseUrl`

本地已知验证状态：

- `.venv-ocr\Scripts\python.exe -m compileall -q cloud` 可通过
- `python -m unittest cloud.tests.test_openapi_contract_consistency` 可通过
- `.venv-ocr\Scripts\python.exe -m unittest cloud.tests.test_pc_backend_demo` 可通过；当前系统 Python 缺少 `cv2` 时不可直接运行该测试
- 完整 `python -m unittest discover cloud/tests` 需本机已安装 `opencv-python-headless`
- `/debug/pairing.json` 本机 smoke 已返回当前局域网 `base_url`；二维码生成使用轻量依赖 `qrcode`
- `scene_classifier_v3` HTTP 自测覆盖 `52` 个展台分组、`2349` 张样本图：`raw_top1_accuracy=99.87%`，`ok_top1_accuracy=100%`，`ok_rate=99.11%`，服务内 `latency_ms p95=22ms / max=80ms`，`latency_over_limit_count=0`

## 已完成

- [x] 建立 `cloud/` 服务工程
- [x] 建立 FastAPI 应用入口
- [x] 建立统一响应结构
- [x] 建立健康检查接口
- [x] 建立场馆元数据接口
- [x] 建立视觉定位接口
- [x] 建立室内路径规划接口
- [x] 建立场馆地图包加载逻辑
- [x] 建立路径规划 baseline
- [x] 建立视觉重定位 baseline
- [x] 建立错误码模型
- [x] 建立日志输出结构
- [x] 建立超时控制与业务超时码
- [x] 建立单元测试与 smoke test
- [x] 补齐 OpenAPI 3.0 文档
- [x] 收口 `AI_GLASSES_VENUE_PACKAGE_ROOT` 场馆包切换方式
- [x] 补强场馆包缺失、JSON 解析、字段引用异常的可解释错误
- [x] 固化 `health / venue meta / visual locate / indoor route` 核心接口回归测试
- [x] 补充联调 smoke 请求、响应、失败样例和排障说明
- [x] 明确重定位算法双轨开发策略和阶段验收门槛
- [x] Track A：接入图像质量评估、ORB keyframe 特征索引、BFMatcher 和 RANSAC 几何验证
- [x] Track A：接入匹配数、内点数、内点率、Top1/Top2 margin、先验与图像质量置信度融合
- [x] Track A：建立 JSONL 离线评估 harness，输出 Top-1、Top-3、楼层准确率、耗时和失败分布
- [x] Track B：建立 `SuperPoint + LightGlue + hloc-style retrieval` 实验 adapter 骨架
- [x] 补齐 OpenAPI 的 timeout、low confidence、package error、auth、rate limit 示例
- [x] 建立 OpenAPI 错误码与运行时 `BusinessCode` 的一致性回归测试
- [x] 扩展离线评估报告，支持按楼层、按场馆统计和失败样本导出
- [x] 补强场馆包诊断：入口、连接点、相机内参、keyframe 引用和版本约束
- [x] 建立最小治理占位：Bearer 鉴权、单机内存限流、`X-Trace-Id` 头和超时基点
- [x] 收口 OpenAPI `500 + 9001` 语义，区分 `venue_package_error` 与通用 `internal error`
- [x] 在 OpenAPI 中显式声明 Bearer 鉴权适用范围，`/api/v1/health` 保持免鉴权
- [x] 形成单商场 Demo 的 Landmark OCR / LOGO 重定位方案文档
- [x] 建立展馆 PC 后台同 Wi-Fi 联调模式：`pc_backend`
- [x] 建立 `mock / template / real_ocr_adapter` 识别模式占位
- [x] 新增 `cloud/data/exhibition_demo` 样例包，覆盖 B10、B17、厕所、报告厅
- [x] 新增 `/debug/cards` 和 `/debug/recent-requests` 现场排查接口
- [x] 新增 Windows 初始化与启动脚本：`setup_pc_backend.ps1`、`run_pc_backend.ps1`
- [x] 新增 PC 本地联调与现场迁移 Runbook
- [x] 基于 `PC/yjdd_hd.mov` 和人工时间段标注建立 scene retrieval 样本与离线评估
- [x] 将 `scene_retrieval` 模式接入 PC 后台 `visual-locate`
- [x] 基于 `PC/yjdd_hd.mov` 样本训练并接入 `scene_classifier` 展台分类器作为当前展馆 Demo 主链路
- [x] 固化 `scene_classifier` HTTP 多组图片自测脚本和报告指标，当前 `ok` 状态无误放行且延迟低于 `200ms`
- [x] 训练 `scene_classifier_v2` 强增强微调模型，默认启动优先使用 v2 checkpoint，缺失时回退 v1
- [x] 完成 v2 clean / 合成压力集 / HTTP 全量自测：合成压力集 v2 `99.92%`，v1 `99.75%`
- [x] 训练 `scene_classifier_v3`：MobileNetV3 Small + GPU batch hard-partial 增强，默认启动优先使用 v3，缺失时回退 v2 / v1
- [x] 训练 `scene_classifier_v4`：MobileNetV3 Large 大容量路线；hard-stress 接近 v3，但置信度未校准，暂不默认
- [x] 扩展训练脚本支持缓存图片、GPU 批增强、hard-stress 评估、`mobilenet_v3_small / mobilenet_v3_large` 多架构训练
- [x] PC 后台可视化页支持显示上传帧、识别阶段、接收 FPS 和识别完成 FPS
- [x] 新增 Windows 托盘入口：`pc_backend_tray.ps1` / `start_pc_backend_tray.cmd`，支持状态查看、启动/停止、打开调试页和复制 baseUrl
- [x] 新增 App 半自动扫码配对：`/debug/pairing`、`/debug/pairing.json`、`/debug/pairing.svg`
- [x] 新增配对二维码生成依赖 `qrcode`，并纳入 `cloud/pyproject.toml`
- [x] 启动脚本和托盘菜单已暴露 pairing URL，便于现场扫码联调
- [x] App 侧接入 prompt 已同步扫码配对、health 校验、`candidate_base_urls` 兜底和 visual-locate 字段要求
- [x] App 侧已实现扫码读取 `/debug/pairing.json` 并自动保存 `baseUrl`
- [x] 确认现场无 `A11`，当前 Demo 样本、检索库和联调目标不纳入 `A11`

## 进行中

- [ ] 使用 Rokid 连续 4FPS 图传做 `scene_classifier` 热态现场联调与多帧稳定性验证
- [ ] 补采真实跨摄像头、强光/暗光、视角不全样本后做 v3 hard-example 回归
- [ ] 等待真实云端部署环境后确认鉴权、限流和运行参数

## 待完成

- [ ] 多场馆动态装载平台
- [ ] 真实高精地图包接入与回归验证
- [ ] 真实 OCR / LOGO / 海报 adapter 或更高鲁棒性的 scene recognition 数据回归
- [ ] 生产级部署方式与服务治理
- [ ] 生产级鉴权、限流与安全策略
- [ ] 更高鲁棒性的重定位算法实现

## 现有产物

- 服务代码目录：`cloud/`
- OpenAPI：`../contracts/openapi/indoor-navigation-api-v1.yaml`
- API 草案：`docs/cloud-localization-api-draft-v0.1.md`
- 可观测性文档：`docs/cloud-observability-error-confidence-v0.1.md`
- 算法研究文档：`docs/cloud-relocalization-algorithm-research-v0.1.md`
- Landmark OCR / LOGO 方案：`docs/cloud-landmark-ocr-logo-relocalization-plan-v0.1.md`
- 双轨开发方案：`docs/cloud-relocalization-dual-track-development-plan-v0.1.md`
- 联调 smoke 说明：`docs/cloud-integration-smoke-v0.1.md`
- PC 后台同 Wi-Fi 联调：`docs/pc-backend-local-wifi-smoke-runbook-v0.1.md`
- PC 后台现场迁移：`docs/pc-backend-onsite-migration-runbook-v0.1.md`
- Android App 接入 PC 后台 prompt：`docs/android-app-pc-backend-integration-prompt-v0.1.md`
- 展馆样例包：`data/exhibition_demo/`
- Windows 托盘入口：`tools/start_pc_backend_tray.cmd`
- App 半自动配对页：`GET /debug/pairing`
- 离线评估脚本：`tools/evaluate_relocalization.py`
- OpenAPI 一致性测试：`tests/test_openapi_contract_consistency.py`

## 依赖与阻塞

- 服务能力依赖可用的场馆地图包
- 定位稳定性依赖建图标注侧产出的 keyframe 与特征质量
- 端到端演示效果依赖 App 侧图像来源接入与状态机收敛
- 当前同 Wi-Fi 联调依赖 Windows 防火墙放行 Python / 8000 端口
- App 半自动配对已具备扫码接入能力；若现场 PC 多网卡导致首选地址错误，可启动前设置 `AI_GLASSES_PC_BACKEND_LAN_BASE_URL`
- `template` 模式仅用于 Debug Cards 彩色测试卡，不代表真实 OCR / LOGO 精度
- 真实包切换要求先通过 `mapping/tools/validate_venue_package.py`
- 若本地环境缺少 `cv2`，完整云端测试发现无法跑通
- `qrcode` 已加入云端依赖；新电脑部署需重新执行 `setup_pc_backend.ps1` 或 `pip install -e .\cloud`

## 验收口径

当前阶段验收关注以下五项：

1. API 契约可被 App 和测试脚本稳定消费。
2. 场馆包可被服务正确加载并驱动定位与路径规划。
3. 错误码、超时和日志语义稳定。
4. 单测与基础联调验证可运行。
5. PC 后台配对页能提供可扫码的 `pairing.json`，App 可基于 `base_url` 自动完成 health 校验。

## 当前真实包接入步骤

1. Mapping 侧产出真实场馆包并运行 `mapping/tools/validate_venue_package.py <package-root> --json`
2. 云端设置 `AI_GLASSES_VENUE_PACKAGE_ROOT=<package-root>`
3. 如需锁定版本，再设置 `AI_GLASSES_VENUE_PACKAGE_VERSION=<package_version>` 并重启服务
4. 调用 `GET /api/v1/health` 验证包可加载
5. 调用 `GET /api/v1/venues/{venue_id}/meta` 确认场馆、楼层、入口和 POI 数据
6. 用真实 query 运行 `cloud/tools/evaluate_relocalization.py` 做离线回归
7. 若返回 `9001 + venue_package_error`，优先按 `data.stage` 和 `validation_errors / missing_files` 回查 Mapping 数据
