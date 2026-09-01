# AI Glasses / VisionRoute

> 干净 clone 到第一次运行的最短路径见 [`QUICKSTART.md`](QUICKSTART.md)。源码根目录以本地 clone 为准，文中历史绝对路径不适用于新机器。

更新时间：2026-06-10

`VisionRoute` 当前主线是一套 **会场实时室内导航演示系统**：

```text
Rokid 眼镜 HTTP 图传 / HUD
        -> Android 手机 App
        -> PC 后台 visual-locate
        -> 手机会场室内地图导航
        -> Rokid 眼镜端 HUD 指向
```

早期“外卖员室外到室内一体化导航”“五道口购物中心”“高德室外导航”等能力仍保留在代码和历史文档中，但它们现在不是默认演示主线。测试人员和接手开发人员应优先围绕“会场室内地图 + Rokid + PC 后台”进行安装、联调和验收。

项目愿景：

`让世界没有难送的外卖`

## 当前主线

### 演示目标

- 手机 App 打开后进入会场室内地图，支持拖动、缩放、展台搜索、路径规划和模拟步行。
- Rokid 眼镜端 Bridge 提供 HTTP 图传、状态上报、会场 HUD 小地图、当前位置圆点、路线和下一步动作提示。
- PC 后台通过局域网接收手机上传的 Rokid 图片，运行 `visual-locate`，返回展台 / 地标定位结果。
- 手机 App 根据定位结果更新当前位置，并将导航状态同步到 Rokid HUD。

### 当前默认链路

1. PC 启动后台，打开 `/debug/pairing` 或托盘菜单获取局域网 `baseUrl`。
2. 手机安装由 `android/ai-glasses-poc/` 本地构建的 Debug APK（干净 clone 不附带预编译 APK）。
3. 手机 App 配置或扫码连接 PC 后台。
4. Rokid 眼镜端打开 / 更新 `VisionRoute RokidBridge`。
5. 眼镜通过 HTTP 图传把图像送到手机，手机上传 PC 后台。
6. PC 后台识别展台 / 地标，App 更新当前位置。
7. 用户搜索目标展台，例如 `B17`、`F05`，App 规划会场室内路线。
8. 手机和 Rokid HUD 同步显示当前位置、路线、下一步动作、剩余距离 / 时间和低置信度提示。

### 当前保留能力

以下能力仍可用于调试或历史回归，但不应作为现场测试默认入口：

- 高德室外导航、室外到室内 handoff。
- 五道口购物中心 `wudaokou2` 全楼层图纸、resolver、共享高德映射。
- HeyCyan 眼镜调试页。
- USB 相机调试页。
- 手机拍照 fallback。
- 旧的 `manual_demo` 手动室内演示。

## 快速入口

### 测试人员

- APK 交付与安装：`android/docs/apk-release-artifact-v0.1.md`
- Demo 测试清单：`docs/demo-test-checklist-v0.1.md`
- PC 同 Wi-Fi 联调：`cloud/docs/pc-backend-local-wifi-smoke-runbook-v0.1.md`
- PC 现场迁移：`cloud/docs/pc-backend-onsite-migration-runbook-v0.1.md`

### 后续开发人员

- Android App 说明：`android/README.md`
- Cloud / PC 后台说明：`cloud/README.md`
- 会场系统快速说明：`docs/exhibition-indoor-navigation-demo-system-brief-v0.1.md`
- 会场系统整体设计：`docs/exhibition-indoor-navigation-demo-system-design-v0.1.md`
- Rokid CXR-L / Bridge 接手：`docs/rokid-cxr-l-android-prep-v0.1.md`
- Rokid 实时图传交接：`docs/rokid-realtime-video-transfer-handoff-v0.1.md`

## 仓库结构

```text
4dai-glasses-main/
|- android/
|  |- ai-glasses-poc/          # VisionRoute 手机 App + Rokid Bridge
|  |- docs/                    # App、APK、UI、室内导航说明
|  `- qa-archive/              # 历史真机 / 模拟器记录
|- cloud/
|  |- app/                     # FastAPI PC 后台
|  |- data/exhibition_demo/    # 当前会场 Demo 样例包
|  |- docs/                    # PC 后台联调 / 迁移 Runbook
|  |- tests/
|  `- tools/
|- mapping/                    # 建图、标注、五道口历史资源与生成脚本
|- contracts/                  # OpenAPI 与场馆包规范
|- docs/                       # 跨端设计、测试、Rokid 交接文档
|- SDK/                        # 已收集 SDK / 样例
`- README.md
```

## 当前子系统状态

### Android App

- 当前主线：会场室内地图、展台搜索、路径规划、模拟步行、Rokid HTTP 图传接入、Rokid HUD 同步。
- 已保留：高德室外导航、五道口室内图纸、HeyCyan、USB 相机、调试抽屉。
- APK 不保证存在于干净 clone；请按 Quickstart 从 `android/ai-glasses-poc/` 本地构建。
- 详细说明：`android/README.md`。

### Rokid Bridge

- 眼镜端 Android App，包内资源由手机 App 内置，也可通过 ADB 单独安装。
- 当前能力：Camera2 低 FPS 按需图传、HTTP `/status`、HUD 更新、会场小地图、当前位置圆点、路线和导航提示。
- 详细交接：`docs/rokid-cxr-l-android-prep-v0.1.md`、`docs/rokid-realtime-video-transfer-handoff-v0.1.md`。

### PC 后台 / Cloud

- 当前主线：`pc_backend`，手机和 PC 在同一 Wi-Fi 下联调。
- 提供：`/api/v1/health`、`/api/v1/localization/visual-locate`、`/debug/pairing`、`/debug/visual-locate`、`/debug/recent-requests`。
- 支持：`mock`、`template`、`scene_classifier`、`real_ocr_adapter` 等识别模式。
- 详细说明：`cloud/README.md`。

### Mapping

- 当前会场 Demo 地图与展台识别数据主要由 App assets 与 `cloud/data/exhibition_demo/` 消费。
- 五道口 `wudaokou2` 资源已保留为历史 / 备用 indoor map 资产，不是当前现场主线。
- 详细说明：`mapping/README.md`、`mapping/resource/wudaokou2/README.md`。

## 最近验证

- Android：`F:\Gradle\gradle-8.6\bin\gradle.bat :app:assembleDebug --no-daemon --console=plain --max-workers=1` 通过。
- Android：`F:\Gradle\gradle-8.6\bin\gradle.bat :app:testDebugUnitTest --no-daemon --console=plain --max-workers=1` 通过。
- Rokid Bridge：`:rokid-bridge:assembleDebug` 通过。
- Cloud：`.venv\Scripts\python.exe -m unittest cloud.tests.test_pc_backend_demo -v` 通过。
- PC 托盘：`cloud\tools\pc_backend_tray.ps1 -ValidateOnly` 通过。
- App 会场资源：`android/ai-glasses-poc/app/src/main/assets/mapping/conference` 与 wudaokou2 assets 的 JSON / 引用检查通过。

## 当前主要风险

- Rokid HTTP 图传长时间运行仍可能受发热、Wi-Fi、Camera2 帧率和电量影响，需要现场持续观察。
- PC 后台依赖手机和 PC 同 Wi-Fi；多网卡环境下需要确认 App 使用正确 `baseUrl`。
- 当前会场定位依赖已有样本和识别模式；真实跨光照、遮挡、角度仍需现场回归。
- Rokid 真实语音能力受固件限制，当前不作为主演示入口。
- 旧室外 / 五道口链路仍在仓库内，测试时不要误把它当作当前主流程。

## 常用命令

### Android 构建

```powershell
cd <clone>\4dai-glasses-main\4dai-glasses-main\android\ai-glasses-poc
F:\Gradle\gradle-8.6\bin\gradle.bat :rokid-bridge:assembleDebug --no-daemon --console=plain --max-workers=1
Copy-Item -Force .\rokid-bridge\build\outputs\apk\debug\rokid-bridge-debug.apk .\app\src\main\assets\rokid\visionroute_rokid_bridge.apk
F:\Gradle\gradle-8.6\bin\gradle.bat :app:assembleDebug --no-daemon --console=plain --max-workers=1
```

### PC 后台启动

```powershell
cd <clone>\4dai-glasses-main\4dai-glasses-main
.\cloud\tools\run_pc_backend.ps1
```

托盘入口：

```powershell
.\cloud\tools\start_pc_backend_tray.cmd
```

### Git LFS APK

```powershell
git lfs install
git lfs pull
```

## 文档索引

### 当前主线必读

1. `android/docs/apk-release-artifact-v0.1.md`
2. `cloud/docs/pc-backend-local-wifi-smoke-runbook-v0.1.md`
3. `docs/demo-test-checklist-v0.1.md`
4. `docs/exhibition-indoor-navigation-demo-system-brief-v0.1.md`
5. `docs/exhibition-indoor-navigation-demo-system-design-v0.1.md`
6. `android/README.md`
7. `cloud/README.md`
8. `docs/rokid-cxr-l-android-prep-v0.1.md`

### 历史 / 保留能力

- `android/docs/manual-indoor-demo-mode-v0.1.md`
- `android/docs/amap-indoor-basemap-integration-v0.1.md`
- `mapping/resource/wudaokou2/README.md`
- `mapping/drafts/wudaokou-route-demo-v0.1/README.md`
- `HeyCyan_SDK_Investigation_Report.md`
