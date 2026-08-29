# VisionRoute Android App README

更新时间：2026-06-10

Android 子项目当前主线是 **会场室内导航演示 App**。旧的室外高德导航、五道口购物中心、HeyCyan、USB 相机等能力仍保留，但现场测试和后续开发应优先围绕：

```text
会场室内地图 -> Rokid HTTP 图传 -> PC 后台 visual-locate -> 路径规划 -> 手机地图 + Rokid HUD
```

## 当前主线口径

### App 侧目标

- 打开 App 后展示会场室内地图。
- 支持展台号 / 设施搜索，例如 `B17`、`F05`、厕所、报告厅。
- 定位成功后用圆点展示当前位置，位置跳变时做平滑移动。
- 搜索目标后显示目标图标，并基于当前位置规划室内路径。
- 导航状态下隐藏目的地搜索栏，显示下一步动作、剩余距离 / 预计时间。
- 支持一键模拟室内步行，手机和眼镜端同步刷新当前位置与提示词。
- 自动尝试接入 Rokid HTTP 图传；图像上传 PC 后台进行 `visual-locate`。
- 将导航状态下发到 Rokid Bridge，眼镜端显示小地图、当前位置、路线、目标和动作提示。

### 当前不是主线

- 高德室外导航和室外到室内 handoff。
- 五道口购物中心手动演示。
- 手机拍照 fallback。
- HeyCyan 眼镜调试。
- USB 相机调试。
- 五道口 wudaokou2 室内图纸路径规划。

这些能力不删除，作为历史回归、备用调试或后续产品扩展入口。

## 仓库位置

- Android 工程：`android/ai-glasses-poc/`
- 手机 App 模块：`app/`
- Rokid 眼镜端 Bridge 模块：`rokid-bridge/`
- 当前 APK：`android/releases/VisionRoute-debug.apk`
- 手机 App 内置眼镜端更新包：`app/src/main/assets/rokid/visionroute_rokid_bridge.apk`

关键文档：

- APK 交付说明：`docs/apk-release-artifact-v0.1.md`
- App 状态机规格：`docs/app-state-machine-and-image-provider-v0.1.md`
- 会场系统快速说明：`../docs/exhibition-indoor-navigation-demo-system-brief-v0.1.md`
- 会场系统整体设计：`../docs/exhibition-indoor-navigation-demo-system-design-v0.1.md`
- Rokid CXR-L / Bridge 接手：`../docs/rokid-cxr-l-android-prep-v0.1.md`

## 当前代码结构

核心手机端代码：

- `ai-glasses-poc/app/src/main/java/com/aiglasses/poc/MainActivity.kt`
- `ai-glasses-poc/app/src/main/java/com/aiglasses/poc/MainViewModel.kt`
- `ai-glasses-poc/app/src/main/java/com/aiglasses/poc/PocUiState.kt`
- `ai-glasses-poc/app/src/main/java/com/aiglasses/poc/indoor/`
- `ai-glasses-poc/app/src/main/java/com/aiglasses/poc/image/`
- `ai-glasses-poc/app/src/main/java/com/aiglasses/poc/network/`
- `ai-glasses-poc/app/src/main/java/com/aiglasses/poc/rokid/`

Rokid 眼镜端：

- `ai-glasses-poc/rokid-bridge/src/main/java/com/aiglasses/rokidbridge/MainActivity.java`
- `ai-glasses-poc/rokid-bridge/src/main/res/drawable/conference_hud_map.*`

当前会场地图 assets：

- `ai-glasses-poc/app/src/main/assets/mapping/conference/huichang.jpg`
- `ai-glasses-poc/app/src/main/assets/mapping/conference/huichang_app_nav_graph.json`
- `ai-glasses-poc/app/src/main/assets/mapping/conference/huichang_poi_resolver_app_ready.json`

## 当前能力范围

### 已落地

- 会场室内底图展示、拖动、缩放。
- 展台 / 设施搜索与目标点展示。
- 当前定位圆点、默认 B17 附近兜底位置、平滑移动。
- 室内路径规划、路线显示、下一步动作、距离和预计时间。
- 导航退出与非导航默认态。
- 模拟室内步行导航。
- Rokid HTTP 图传接入，默认使用 `glasses_private_stream` 上传定位。
- PC 后台 baseUrl 配置 / 配对联调入口保留。
- Rokid Bridge HUD 同步：地图、当前位置、路线、目标、动作、距离、预计时间、低置信度提示。
- 手机 App 内置眼镜端 Bridge APK，支持后续通过 App 更新眼镜端。
- 旧能力保留：高德室外导航、五道口图纸、HeyCyan、USB 相机、Debug 抽屉。

### 当前待验证 / 待完成

- Rokid HTTP 图传长时间稳定性和发热控制。
- 现场 Wi-Fi 下 PC baseUrl 自动发现 / 扫码配对稳定性。
- 真实光照、遮挡和走动场景下 `scene_classifier` 识别稳定性。
- Rokid HUD 与手机地图在连续定位 / 模拟步行时的一致性。
- 真实语音入口当前受 Rokid 固件限制，不作为主演示入口。

## 测试人员操作路径

1. 安装手机 APK：`android/releases/VisionRoute-debug.apk`。
2. 确认 PC 后台启动，手机和 PC 在同一 Wi-Fi。
3. 在 PC 打开 `/debug/pairing`，或使用托盘菜单复制 LAN `baseUrl`。
4. 手机 App 配置 / 扫码连接 PC 后台。
5. 确认 App 首页为会场室内地图，不进入旧室外导航流程。
6. 确认 Rokid Bridge 已安装并打开。
7. 开启 / 等待 Rokid HTTP 图传，观察 App 中 HTTP 状态。
8. 搜索目标展台，例如 `B17` 或 `F05`。
9. 选择目标并开始导航。
10. 检查手机地图：当前位置、目标、路线、下一步动作、剩余距离 / 时间。
11. 检查眼镜 HUD：小地图、圆点、路线、目标和提示词与手机一致。
12. 如需演示移动，点击开始模拟步行。

更详细流程见：

- `../cloud/docs/pc-backend-local-wifi-smoke-runbook-v0.1.md`
- `../docs/demo-test-checklist-v0.1.md`

## 开发人员接手路径

1. 先读根 README，确认当前主线不是旧五道口 / 室外导航。
2. 读 `../docs/exhibition-indoor-navigation-demo-system-brief-v0.1.md` 和整体设计。
3. 跑通 PC 后台：`../cloud/README.md`。
4. 构建手机 App 和 Rokid Bridge。
5. 通过 `MainActivity.kt` 中的 `conference*` 方法追踪会场导航主链路。
6. 通过 `RokidHttpAutoStreamClient.kt` 追踪 HTTP 图传与状态轮询。
7. 通过 `rokid-bridge/MainActivity.java` 追踪眼镜端图传、状态和 HUD。

## 构建与安装

当前 Android 工程未提交 Gradle Wrapper，使用本机 Gradle：

```powershell
cd F:\hz\codex\AI_Glasses\android\ai-glasses-poc
F:\Gradle\gradle-8.6\bin\gradle.bat :rokid-bridge:assembleDebug --no-daemon --console=plain --max-workers=1
Copy-Item -Force .\rokid-bridge\build\outputs\apk\debug\rokid-bridge-debug.apk .\app\src\main\assets\rokid\visionroute_rokid_bridge.apk
F:\Gradle\gradle-8.6\bin\gradle.bat :app:assembleDebug --no-daemon --console=plain --max-workers=1
```

安装：

```powershell
adb -s <phone_serial> install -r app\build\outputs\apk\debug\app-debug.apk
adb -s <glasses_serial> install -r rokid-bridge\build\outputs\apk\debug\rokid-bridge-debug.apk
```

当前约定：安装后不自动启动 App，避免打断现场调试状态。

## 验证命令

```powershell
F:\Gradle\gradle-8.6\bin\gradle.bat :app:assembleDebug --no-daemon --console=plain --max-workers=1
F:\Gradle\gradle-8.6\bin\gradle.bat :app:testDebugUnitTest --no-daemon --console=plain --max-workers=1
F:\Gradle\gradle-8.6\bin\gradle.bat :rokid-bridge:assembleDebug --no-daemon --console=plain --max-workers=1
```

最近状态：以上 Android 构建 / 单测命令已通过。

## 旧能力说明

以下内容仍在代码中，但不是当前现场演示主线：

- 高德室外导航：用于早期外卖场景验证。
- 五道口购物中心：`wudaokou2` 已有全楼层资源和共享映射，可作为室内图纸路径规划参考。
- HeyCyan：保留 SDK 调试页。
- USB 相机：保留 UVC 预览 / 拍照 / 录像 / 参数调试页。
- `manual_demo`：保留历史室内手动演示逻辑。

如果要恢复这些旧链路，请先查 `android/PROGRESS.md` 和对应专项文档，避免与当前会场主线混用。

## 关联文档

- `docs/apk-release-artifact-v0.1.md`
- `../docs/exhibition-indoor-navigation-demo-system-brief-v0.1.md`
- `../docs/exhibition-indoor-navigation-demo-system-design-v0.1.md`
- `../docs/rokid-cxr-l-android-prep-v0.1.md`
- `../docs/rokid-realtime-video-transfer-handoff-v0.1.md`
- `../cloud/docs/pc-backend-local-wifi-smoke-runbook-v0.1.md`
- `../docs/demo-test-checklist-v0.1.md`
- `PROGRESS.md`
