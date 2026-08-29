# PC 后台现场迁移 Runbook V0.1

更新时间：2026-06-09

## 1. 迁移目标

把当前仓库中的 PC 后台服务、配置、样例数据、启动脚本迁移到现场 PC，使 Android 手机 App 可以在同一 Wi-Fi 下访问：

```text
http://<现场PC局域网IP>:8000
```

Tailscale 仅作为备用网络模式，不作为默认链路。

## 2. 需要复制的目录

最小复制：

```text
cloud/
contracts/openapi/
```

建议保留仓库根目录结构，至少包含：

```text
AI_Glasses/
  cloud/
  contracts/openapi/
```

不要复制本地虚拟环境、pip cache、模型大文件或无关 Android / Mapping / SDK 大文件。

## 3. Python 依赖安装

推荐现场 PC 使用 Python 3.11+。

```powershell
$InstallRoot = "D:\VisionRoute"
$RepoDir = Join-Path $InstallRoot "AI_Glasses"
$PipCacheDir = Join-Path $InstallRoot "pip-cache"

cd $RepoDir
.\cloud\tools\setup_pc_backend.ps1 -VenvPath "$RepoDir\.venv" -PipCacheDir $PipCacheDir
```

如果现场 PC Python 不在 PATH，可指定：

```powershell
.\cloud\tools\setup_pc_backend.ps1 -PythonPath "<PYTHON_EXE_PATH>" -VenvPath "$RepoDir\.venv" -PipCacheDir $PipCacheDir
```

脚本会创建 `.venv` 并安装 `cloud/pyproject.toml` 中的依赖，包括 FastAPI、OpenCV、`qrcode` 等。pip 缓存默认可通过 `-PipCacheDir` 指定到现场 PC 的非系统盘。

## 4. 启动服务

```powershell
cd $RepoDir
.\cloud\tools\run_pc_backend.ps1
```

默认启动：

```text
host=0.0.0.0
port=8000
venue_package_root=cloud/data/exhibition_demo
recognition_mode=scene_classifier if the local classifier checkpoint exists, otherwise scene_retrieval / template
```

切换模式：

```powershell
.\cloud\tools\run_pc_backend.ps1 -RecognitionMode mock
.\cloud\tools\run_pc_backend.ps1 -RecognitionMode template
.\cloud\tools\run_pc_backend.ps1 -RecognitionMode real_ocr_adapter
.\cloud\tools\run_pc_backend.ps1 -RecognitionMode scene_classifier
.\cloud\tools\run_pc_backend.ps1 -RecognitionMode scene_retrieval
```

指定真实场馆包：

```powershell
.\cloud\tools\run_pc_backend.ps1 -VenuePackageRoot "F:\venue_packages\exhibition_real"
```

如果需要托盘入口快速查看状态、启动/停止后台、打开调试页和配对页：

```powershell
.\cloud\tools\start_pc_backend_tray.cmd
```

## 5. 场馆包要求

当前样例包位于：

```text
cloud/data/exhibition_demo/
```

真实场馆包需要至少包含：

- `manifest.json`
- `venue.json`
- `floors.json`
- `pois.json`
- `entrances.json`
- `connectors.json`
- `route_graph.json`
- `localization/cameras.json`
- `localization/keyframes.jsonl`
- 可选 `landmarks.json`

如果使用 PC landmark mock/template 模式，建议提供 `landmarks.json`，并让每个 landmark 绑定：

- `landmark_id`
- `floor_id`
- `poi_id`
- `route_node_id`
- `display_name`
- `aliases`
- `map_heading_deg`
- `visibility_area.route_edge_ids`

## 6. 服务启动成功检查

PC 浏览器：

```text
http://127.0.0.1:8000/api/v1/health
http://127.0.0.1:8000/debug/pairing
http://127.0.0.1:8000/debug/cards
http://127.0.0.1:8000/debug/visual-locate
http://127.0.0.1:8000/debug/recent-requests
```

手机浏览器：

```text
http://<现场PC局域网IP>:8000/api/v1/health
http://<现场PC局域网IP>:8000/debug/pairing
http://<现场PC局域网IP>:8000/debug/cards
```

只有手机浏览器能打开 health，才算网络连通。

## 7. Android App 配置

App `baseUrl`：

```text
http://<现场PC局域网IP>:8000
```

推荐 App 优先使用半自动扫码配对：

1. PC 打开 `http://<现场PC局域网IP>:8000/debug/pairing`。
2. App 扫描页面二维码。
3. 二维码内容为 `http://<现场PC局域网IP>:8000/debug/pairing.json`。
4. App 请求该 JSON，读取 `base_url`。
5. App 调用 `GET <base_url>/api/v1/health`。
6. health 成功后保存 `base_url`。

如果现场 PC 有虚拟网卡导致配对页首选地址错误，可在启动前指定：

```powershell
$env:AI_GLASSES_PC_BACKEND_LAN_BASE_URL="http://<现场PC局域网IP>:8000"
```

当前拍照阶段请求字段：

| 字段 | 值 |
| --- | --- |
| `venue_id` | 当前包的 `venue_id`，样例为 `venue_exhibition_demo` |
| `capture_mode` | `phone_camera_fallback` |
| `capture_id` | App 生成 |
| `capture_timestamp_ms` | App 生成 |
| `image` | 手机拍照图片 |
| `imu_at_capture` | 可选，当前不依赖 |

状态处理：

| status | App 行为 |
| --- | --- |
| `ok` | 更新当前位置并进入/保持导航 |
| `low_confidence` | 不强制更新，可提示重新拍摄或显示候选 |
| `not_found` | 保持最近位置，提示面向测试卡或地标重拍 |
| `error` | 服务/请求异常，保留本地状态并提示错误 |

## 8. curl Smoke

Health：

```powershell
curl.exe http://127.0.0.1:8000/api/v1/health
```

Pairing：

```powershell
curl.exe http://127.0.0.1:8000/debug/pairing.json
```

visual-locate mock：

```powershell
curl.exe -F "request_id=req_pc_b10" `
  -F "capture_id=cap_pc_b10" `
  -F "venue_id=venue_exhibition_demo" `
  -F "capture_timestamp_ms=1777358700000" `
  -F "capture_mode=phone_camera_fallback" `
  -F "debug_target=B10" `
  -F "image=@cloud\data\exhibition_demo\README.md;type=image/jpeg;filename=b10.jpg" `
  http://127.0.0.1:8000/api/v1/localization/visual-locate
```

预期返回 `status=ok`、`floor_id=F1`、`matched_landmark.landmark_id=lm_booth_b10_card`。

## 9. 现场排障表

| 问题 | 检查 |
| --- | --- |
| 手机打不开 health | 同 Wi-Fi、PC IP、服务是否监听 `0.0.0.0`、防火墙、端口 |
| 配对二维码扫到的 IP 不通 | PC 多网卡；启动前设置 `AI_GLASSES_PC_BACKEND_LAN_BASE_URL` |
| App health 失败但浏览器能打开 | App `baseUrl` 是否包含 `http://`，是否误用 `10.0.2.2` |
| recent-requests 没请求 | App 没发到 PC，先查 baseUrl / 网络 |
| 返回 `venue not found` | App `venue_id` 与场馆包不一致 |
| 返回 `invalid capture_mode` | 当前手机拍照应传 `phone_camera_fallback` |
| 返回 `not_found` | mock 模式检查 `debug_target` 或文件名；template 模式重新拍摄测试卡 |
| 服务启动失败 | 依赖未安装、端口占用、场馆包缺文件或 JSON 格式错误 |

## 10. Windows 防火墙

- 第一次运行 Python 服务时允许当前 Wi-Fi 网络访问。
- 不要关闭全部防火墙。
- 如需放行 8000 端口，可用管理员 PowerShell：

```powershell
New-NetFirewallRule -DisplayName "VisionRoute PC Backend 8000" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8000
```

## 11. Tailscale 备用模式

当手机和 PC 无法连接同一 Wi-Fi 时，才考虑 Tailscale：

- 手机和 PC 登录同一 tailnet。
- App `baseUrl` 改为 `http://<PC tailscale IP>:8000`。
- 仍需确认 PC 防火墙允许对应网络访问。

Tailscale 不作为当前默认链路，现场优先使用同 Wi-Fi。

## 12. 已知限制

- 当前 `template` 模式是轻量彩色测试卡识别，不代表真实 OCR / LOGO 精度。
- 当前展馆 Demo 默认优先使用 `scene_classifier`；缺少 classifier checkpoint 时启动脚本才回退到 `scene_retrieval` / `template`。
- App 半自动配对依赖 `qrcode` 生成 `/debug/pairing.svg`；新机器需重新安装 `cloud/pyproject.toml` 依赖。
- `real_ocr_adapter` 已接入 `OCR/huichang_logo_detector_binary.pth` 和 `OCR/huichang_logo_model_final.pth`；现场 PC 需要安装 `torch/torchvision`，并提供与训练顺序一致的 `OCR/huichang_logo_classes.txt`。
- 真实 OCR / LOGO 候选必须能通过场馆包 `landmarks.json` 的 `aliases` 映射到 landmark；否则会返回 `failure_stage=real_ocr_no_landmark_binding`。
- `imu_at_capture` 当前不参与位置估计；后续由 App 侧用于航向锚点和 Rokid IMU 桥接。
