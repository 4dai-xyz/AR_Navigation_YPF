# PC 后台同 Wi-Fi 本地联调 Runbook V0.1

更新时间：2026-06-09

## 1. 目标

本 Runbook 用于在当前电脑启动 VisionRoute PC 后台，让 Android 手机 App 通过同一个 Wi-Fi 局域网访问：

```text
Android 手机 App
-> http://<PC局域网IP>:8000
-> POST /api/v1/localization/visual-locate
-> 返回 floor_id / position / confidence / matched_landmark
```

当前阶段优先验证手机与 PC 同 Wi-Fi 链路。若 Rokid 眼镜图片已能传输到手机，App 可上传 Rokid 图片；若 Rokid 图片暂不可用，继续使用手机拍照 fallback。`imu_at_capture` 字段预留但服务端不依赖它返回定位结果。不使用手机 IMU 做航向估计。

## 2. 启动

```powershell
cd F:\hz\codex\AI_Glasses
.\cloud\tools\setup_pc_backend.ps1
.\cloud\tools\run_pc_backend.ps1
```

如果需要更直观地确认后台是否运行，可使用 Windows 托盘入口：

```powershell
.\cloud\tools\start_pc_backend_tray.cmd
```

托盘入口提供：

- 快速启动/停止 PC 后台
- 查看当前 health 状态
- 打开 `GET /api/v1/health`
- 打开 `GET /debug/pairing`
- 打开 `GET /debug/cards`
- 打开 `GET /debug/visual-locate`
- 打开 `GET /debug/recent-requests`
- 复制 Local / LAN `baseUrl`

托盘菜单和气泡提示默认使用中文，`health`、`debug/pairing`、`debug/cards`、`debug/visual-locate`、`baseUrl` 等技术名保留英文。托盘图标绿色表示 health 可访问，黄色表示后台进程已启动但 health 还未就绪，红色表示当前未运行。托盘脚本只管理由它启动的后台进程；如果后台是手动命令行启动的，托盘仍能通过 health 显示运行状态，但停止时不会强杀外部进程。

## 2.1 App 半自动扫码配对

PC 后台提供半自动配对页：

```http
GET /debug/pairing
GET /debug/pairing.json
GET /debug/pairing.svg
```

现场操作：

1. PC 启动后台。
2. PC 打开 `http://<PC局域网IP>:8000/debug/pairing`，或从托盘右键打开“打开配对页”。
3. App 扫描页面二维码。
4. 二维码内容为 `http://<PC局域网IP>:8000/debug/pairing.json`。
5. App 请求该 JSON，读取 `base_url`。
6. App 调用 `GET <base_url>/api/v1/health`。
7. health 成功后保存 `base_url`，后续图片上传到 `<base_url>/api/v1/localization/visual-locate`。

`/debug/pairing.json` 返回示例：

```json
{
  "type": "visionroute_pc_backend_pairing",
  "version": 1,
  "base_url": "http://<PC局域网IP>:8000",
  "health_url": "http://<PC局域网IP>:8000/api/v1/health",
  "visual_locate_url": "http://<PC局域网IP>:8000/api/v1/localization/visual-locate",
  "venue_id": "venue_exhibition_demo",
  "service_mode": "pc_backend",
  "recognition_mode": "scene_classifier"
}
```

如果扫码后 App 连不上后台，仍按同 Wi-Fi、PC IP、服务监听 `0.0.0.0`、Windows 防火墙、端口占用的顺序排查。

如果现场 PC 有虚拟网卡，配对页首选地址选错，可在启动前指定：

```powershell
$env:AI_GLASSES_PC_BACKEND_LAN_BASE_URL="http://<PC局域网IP>:8000"
```

启动脚本默认配置：

| 配置 | 默认值 |
| --- | --- |
| host | `0.0.0.0` |
| port | `8000` |
| service_mode | `pc_backend` |
| venue package | `cloud/data/exhibition_demo` |
| recognition_mode | 检测到本机 booth classifier checkpoint 时为 `scene_classifier`，否则回退到 `scene_retrieval` / `template` |

可覆盖配置：

```powershell
.\cloud\tools\run_pc_backend.ps1 -Port 8000 -RecognitionMode mock
.\cloud\tools\run_pc_backend.ps1 -VenuePackageRoot F:\path\to\venue_package -RecognitionMode template
.\cloud\tools\run_pc_backend.ps1 -RecognitionMode scene_classifier
.\cloud\tools\run_pc_backend.ps1 -RecognitionMode scene_retrieval
```

`scene_classifier` 是当前展馆 Demo 主链路，默认优先加载 `scene_classifier_v3` GPU hard-partial checkpoint；如果 v3 文件不存在，则回退到 v2 / v1 checkpoint。`scene_retrieval` 保留为检索式备选路线：若 CUDA 可用且三模型融合索引存在，默认使用 GPU `fusion_resnet50_clip_vitb32_dinov2`；否则回退到 OpenCV `hybrid_v1` 轻量检索库。`hybrid_v1` 只适合链路 smoke，不适合作为现场准确定位算法。

当前电脑已验证：

| 项 | 结果 |
| --- | --- |
| GPU | `NVIDIA GeForce RTX 4060 Laptop GPU` |
| PyTorch | `.venv-ocr` 内 `torch 2.12.0+cu126` / `torchvision 0.27.0+cu126` |
| scene classifier device | `AI_GLASSES_SCENE_CLASSIFIER_DEVICE=auto`，CUDA 可用时解析为 `cuda` |
| scene classifier v3 HTTP 自测 | `52` 组 / `2349` 张，服务内 `p95=22ms`、`max=80ms`，端到端 HTTP `p95=47.46ms` |
| scene classifier v3 准确率 | `raw_top1_accuracy=99.87%`，`ok_top1_accuracy=100%`，`ok_rate=99.11%`，`latency_over_limit_count=0` |
| scene classifier v3 hard-stress | `top1_accuracy=95.75%`；同一 hard-stress 上 v2 为 `95.03%` |
| scene classifier v4 large 实验 | hard-stress `top1_accuracy=95.83%`，但 HTTP `ok_rate=78.54%`，暂不默认 |
| scene retrieval device | 备选模式下 `AI_GLASSES_SCENE_RETRIEVAL_DEVICE=auto`，模型加载后解析为 `cuda` |
| GPU fusion 热路径 | 备选模式下单张样本特征检索约 `80-150ms`，首帧模型冷加载约 `20s` |

如果需要手动切换模型：

```powershell
$env:AI_GLASSES_SCENE_RETRIEVAL_DEVICE="auto"   # auto / cuda / cpu
$env:AI_GLASSES_SCENE_RETRIEVAL_FEATURE_EXTRACTOR="fusion_resnet50_clip_vitb32_dinov2"
```

若现场 PC 需要补 CUDA PyTorch，可在 F 盘虚拟环境中执行：

```powershell
$env:PIP_CACHE_DIR="F:\hz\codex\pip-cache"
.\.venv-ocr\Scripts\python.exe -m pip install --upgrade --index-url https://download.pytorch.org/whl/cu126 torch==2.12.0+cu126 torchvision==0.27.0+cu126
```

新部署机器需要重新执行 `setup_pc_backend.ps1` 或 `pip install -e .\cloud`，以安装当前配对页依赖 `qrcode`。

展馆 Demo 分类器自测命令：

```powershell
.\.venv-ocr\Scripts\python.exe cloud\tools\selftest_scene_classifier_http.py
```

展馆 Demo v3 GPU hard-partial 训练命令：

```powershell
.\.venv-ocr\Scripts\python.exe cloud\tools\train_scene_booth_classifier.py --epochs 2 --batch-size 128 --lr 0.00003 --model-arch mobilenet_v3_small --experiment-name v3_gpu_hard_partial --init-checkpoint cloud\tmp_scene_recognition_probe\yjdd_hd_booth_classifier_mobilenet_v3_small_v2_aug\booth_classifier_mobilenet_v3_small_v2_aug.pt --cache-images --gpu-augment --gpu-augment-profile hard_partial
```

自测报告默认写入：

```text
cloud/tmp_scene_recognition_probe/yjdd_hd_booth_classifier_mobilenet_v3_small_v3_gpu_hard_partial/scene_classifier_v3_http_selftest_report.json
```

当前 v3 报告中仍有 `3` 张 raw wrong 样本，但都没有以 `status=ok` 放行；`ok` 状态样本准确率为 `100%`。因此当前 Demo 口径是“高置信才用于定位，低置信/未命中要求 App 继续取帧”。hard-stress 合成压力集只模拟亮度、模糊、半视角、遮挡、色偏、低清和 JPEG 压缩，不等价于真实跨摄像头/现场光照验证。

底层命令：

```powershell
$env:AI_GLASSES_SERVICE_MODE="pc_backend"
$env:AI_GLASSES_VENUE_PACKAGE_ROOT="F:\hz\codex\AI_Glasses\cloud\data\exhibition_demo"
$env:AI_GLASSES_RECOGNITION_MODE="scene_retrieval"
python -m uvicorn cloud.app.main:app --host 0.0.0.0 --port 8000
```

## 3. 控制台应看到的信息

启动脚本会打印：

- 本机 `Hostname`
- 可用局域网 IPv4 地址
- `Local URL`
- `LAN URL`
- `LAN Health URL`
- `LAN visual-locate URL`
- `LAN debug cards URL`
- `LAN visual debug URL`
- Android App `baseUrl`

如果没有检测到局域网 IP，先确认 PC 已连接 Wi-Fi，再执行：

```powershell
ipconfig
```

使用当前 Wi-Fi 网卡的 IPv4 地址。

## 4. 手机同 Wi-Fi 联调步骤

1. PC 连接 Wi-Fi。
2. 手机连接同一个 Wi-Fi。
3. PC 启动后台服务。
4. 从 PC 控制台复制 `LAN Health URL`。
5. 手机浏览器打开 `http://<PC局域网IP>:8000/api/v1/health`。
6. 手机浏览器打开 `http://<PC局域网IP>:8000/debug/cards`。
7. Android App `baseUrl` 填写：

```text
http://<PC局域网IP>:8000
```

8. App 执行健康检查。
9. App 上传 Rokid 图片；若 Rokid 图片暂不可用，则使用手机拍照上传测试卡。
10. PC 打开 `http://<PC局域网IP>:8000/debug/recent-requests`，确认收到请求。
11. PC 打开 `http://<PC局域网IP>:8000/debug/visual-locate`，查看接收到的图片缩略图和识别过程。
12. App 查看定位结果。

不要在真实手机上使用 `10.0.2.2`；`10.0.2.2` 只适用于 Android 模拟器访问宿主机。

## 5. 核心接口

### 5.1 Health

```http
GET /api/v1/health
```

返回服务状态、版本、`service_mode`、`recognition_mode`、当前 `venue_id`、场馆包路径和算法后端状态。

### 5.2 Debug Cards

```http
GET /debug/cards
```

页面展示 B10、B17、厕所、报告厅测试卡，可用手机拍屏测试。

### 5.3 Recent Requests

```http
GET /debug/recent-requests
```

返回最近 visual-locate 请求摘要，包含 `request_id`、`capture_id`、`venue_id`、`capture_mode`、`image_bytes`、`recognition_mode`、`matched_landmark_id`、`status`、`confidence`、`latency_ms`、`failure_stage`。

### 5.4 Visual Locate Debug

```http
GET /debug/visual-locate
```

展示最近 visual-locate 请求的图片缩略图和识别过程。页面包含：

- 请求基础信息：`request_id`、`capture_id`、`capture_mode`、图片大小、候选楼层和目标 POI。
- 接收到的图片缩略图：只保存在内存中，不落盘。
- 识别阶段：`landmark_catalog`、`debug_or_filename_match`、`template_color_match`、`prior_check`、`final_result`。
- 命中结果：`status`、`confidence`、`matched_landmark_id`、`failure_stage`、`latency_ms`。
- 实时指标：接收 FPS、识别完成 FPS、处理中请求数、最新帧延迟。

页面会每 250ms 自动请求 `/debug/visual-locate/live-data`，因此 App/Rokid 只要持续上传图片，PC 页面就会准实时显示最新画面。后台在读到图片后会先显示 `processing` 帧，识别完成后再用最终 `ok` / `not_found` / `low_confidence` 结果更新同一张卡片。后台会为每次上传生成 `debug_frame_id`，即使 App 连续帧复用了同一个 `request_id`，页面也会把它们当作不同帧显示；App 侧仍建议每帧传不同的 `capture_id`，便于排查。页面上的 FPS 是 PC 后台最近 5 秒观察到的“上传帧接收 FPS”和“识别完成 FPS”，不是 Rokid 硬件相机 FPS。后台不会主动直连 Rokid 眼镜；“实时画面”的刷新频率由 App 上传频率决定。GPU fusion 模型首帧会冷加载，建议启动后先用一张样本图 warm-up，再开始 4FPS 联调。

该页面用于现场排查“App 是否把图发到 PC”“后台识别走到哪一步”“为什么未命中或低置信度”。

### 5.5 visual-locate

```http
POST /api/v1/localization/visual-locate
```

multipart/form-data 字段：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `request_id` | 是 | 请求 ID |
| `capture_id` | 是 | 采集 ID |
| `venue_id` | 是 | `venue_exhibition_demo` |
| `capture_timestamp_ms` | 是 | 采集时间戳 |
| `capture_mode` | 是 | Rokid 实时取帧用 `glasses_private_stream`，Rokid 拍照同步用 `glasses_album_sync`，手机拍照 fallback 用 `phone_camera_fallback` |
| `image` | 是 | 手机上传图片 |
| `candidate_floor_id` | 否 | 当前楼层先验 |
| `target_poi_id` | 否 | 当前导航目标 |
| `imu_at_capture` | 否 | Rokid IMU JSON 字符串，当前只记录不依赖 |
| `debug_target` | 否 | `B10`、`B17`、`toilet`、`hall` |

## 6. 识别模式

| 模式 | 用途 | 行为 |
| --- | --- | --- |
| `mock` | App 到 PC 链路调试 | 根据 `debug_target` 或文件名返回固定地标 |
| `template` | 手机拍测试卡 | 先走 `mock`，再用 OpenCV 识别 Debug Cards 的主色块 |
| `real_ocr_adapter` | 现场真实 OCR / LOGO 模型 | 加载 `OCR/huichang_logo_detector_binary.pth` 和 `OCR/huichang_logo_model_final.pth`，输出 logo 候选并按 landmark aliases 映射位置 |
| `scene_classifier` | 当前展馆真实画面 Demo 主链路 | 加载一镜到底视频训练出的展台分类器，返回 booth、位置和 top-k 分类结果 |
| `scene_retrieval` | Rokid/手机真实画面重定位 | 加载一镜到底视频生成的检索库，返回最相似展台、位置和 top-k 关键帧 |
| `baseline` | 原云端视觉重定位 baseline | 使用现有 ORB/keyframe 逻辑 |

`real_ocr_adapter` 使用可选重型依赖。当前 `.venv` 未安装 `torch/torchvision` 时服务仍可启动，但该模式会返回 `status=not_found` 和 `failure_stage=real_ocr_dependency_unavailable`。若使用 `.venv-ocr` 或安装了依赖，则会实际执行模型推理。

真实 OCR 首次请求需要加载两个 `.pth`，默认超时会自动放宽到 `AI_GLASSES_REAL_OCR_TIMEOUT_MS=15000`。如果现场 PC CPU 较慢，可继续调大该环境变量。

`huichang_logo_model_final.pth` 本身未携带 46 类类别名。若要把模型输出映射到具体品牌和位置，需要提供：

```text
OCR/huichang_logo_classes.txt
```

该文件必须按训练时 `ImageFolder` 类别顺序逐行写入 46 个类别名。场馆包 `landmarks.json` 的 `aliases` 需要包含这些类别名或去掉 `logo_` 前缀后的品牌名。否则模型能输出候选，但会返回 `failure_stage=real_ocr_no_landmark_binding`。

稳定命中示例：

| 输入 | 返回 |
| --- | --- |
| `debug_target=B10` | `poi_booth_b10` |
| `debug_target=B17` | `poi_booth_b17` |
| `debug_target=toilet` | `poi_toilet_f1` |
| `debug_target=hall` | `poi_hall_main` |

## 7. curl Smoke

```powershell
curl.exe -F "request_id=req_pc_b17" `
  -F "capture_id=cap_pc_b17" `
  -F "venue_id=venue_exhibition_demo" `
  -F "capture_timestamp_ms=1777358700000" `
  -F "capture_mode=phone_camera_fallback" `
  -F "debug_target=B17" `
  -F "image=@cloud\data\exhibition_demo\README.md;type=image/jpeg;filename=b17.jpg" `
  http://127.0.0.1:8000/api/v1/localization/visual-locate
```

该请求用于链路 smoke，`mock`/`template` 模式会通过 `debug_target` 命中 B17。

unknown 场景：

```powershell
curl.exe -F "request_id=req_pc_unknown" `
  -F "capture_id=cap_pc_unknown" `
  -F "venue_id=venue_exhibition_demo" `
  -F "capture_timestamp_ms=1777358700000" `
  -F "capture_mode=phone_camera_fallback" `
  -F "image=@cloud\data\exhibition_demo\README.md;type=image/jpeg;filename=unknown.jpg" `
  http://127.0.0.1:8000/api/v1/localization/visual-locate
```

预期返回 `status=not_found`，`failure_stage=landmark_no_hit`。

## 8. Windows 防火墙

第一次启动 Python 服务时，Windows 可能弹出防火墙授权，需要允许当前 Wi-Fi 网络访问。

如果手机无法访问 health：

1. 不要关闭全部防火墙。
2. 检查是否允许 Python 或 8000 端口入站。
3. 确认网络配置文件是当前 Wi-Fi。
4. 如需手工放行，可在管理员 PowerShell 中添加入站规则：

```powershell
New-NetFirewallRule -DisplayName "VisionRoute PC Backend 8000" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8000
```

该命令需要管理员权限，默认脚本不会自动执行。

## 9. 网络排查顺序

1. 手机和 PC 是否连接同一个 Wi-Fi。
2. PC 控制台打印的 LAN IP 是否是 Wi-Fi 网卡 IPv4。
3. 服务是否监听 `0.0.0.0`，不是 `127.0.0.1`。
4. 端口是否被占用。
5. Windows 防火墙是否放行 Python / 8000。
6. 手机浏览器是否能打开 `http://<PC局域网IP>:8000/api/v1/health`。
7. App `baseUrl` 是否是 `http://<PC局域网IP>:8000`。
8. `/debug/recent-requests` 是否能看到 App 请求。

## 10. 当前限制

- `template` 模式只面向 `/debug/cards` 的彩色测试卡，不等价于真实 OCR 精度。
- `real_ocr_adapter` 已接入 OCR 目录下的 ResNet18 detector/classifier 权重；真实可解释品牌名依赖 `OCR/huichang_logo_classes.txt` 和场馆 `landmarks.json` aliases。
- `imu_at_capture` 当前只预留和记录，不参与位置估计。
- 当前主链路只验证同 Wi-Fi 局域网，不默认使用 Tailscale。
