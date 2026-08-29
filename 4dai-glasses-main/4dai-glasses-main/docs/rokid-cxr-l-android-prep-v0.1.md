# Rokid CXR-L Android 准备说明

## 目标范围

本次准备仅覆盖以下开发顺序：

1. 跑通 `CXR-L Android CUSTOMVIEW`
2. 跑通 `拍照 JPEG 回传`
3. 跑通 `CUSTOMAPP + 自定义指令`
4. 暂不进入 `CXR-S`

## 已准备好的本地资源

- 手机侧官方 Sample 压缩包：
  `F:\hz\codex\AI_Glasses\SDK\rokid\CXR-L\v1.0.1\cxrlsample101.zip`
- 手机侧官方 Sample 解压目录：
  `F:\hz\codex\AI_Glasses\SDK\rokid\CXR-L\v1.0.1\cxrlsample101`
- 眼镜侧协同 Sample 压缩包：
  `F:\hz\codex\AI_Glasses\SDK\rokid\CXR-L\v1.0.1\sSDKSampleforCXR.zip`
- 眼镜侧协同 Sample 解压目录：
  `F:\hz\codex\AI_Glasses\SDK\rokid\CXR-L\v1.0.1\sSDKSampleforCXR`
- 官方文档本地 HTML 备份目录：
  `F:\hz\codex\AI_Glasses\research\rokid\CXR-L\v1.0.1\html`
- 官方文档本地 Markdown 目录（部分章节可直接读）：
  `F:\hz\codex\AI_Glasses\research\rokid\CXR-L\v1.0.1\docs`

## 已确认的开发环境

- Android Studio：
  `C:\Program Files\Android\Android Studio\bin\studio64.exe`
- JDK：
  `D:\DevTools\OpenJDK17\bin\java.exe`
- `javac`：
  `D:\DevTools\OpenJDK17\bin\javac.exe`
- `adb`：
  `F:\Android\Sdk\platform-tools\adb.exe`
- Android SDK 平台：
  已存在 `android-34`、`android-34-2`、`android-35`、`android-36.1`
- Android Build Tools：
  已存在 `34.0.0`、`36.1.0`、`37.0.0`

## 已做的本地配置

- 已在两个官方 Sample 根目录写入 `local.properties`
- `sdk.dir` 已指向 `F:\Android\Sdk`

对应文件：

- `F:\hz\codex\AI_Glasses\SDK\rokid\CXR-L\v1.0.1\cxrlsample101\local.properties`
- `F:\hz\codex\AI_Glasses\SDK\rokid\CXR-L\v1.0.1\sSDKSampleforCXR\local.properties`

## 官方 Sample 关键参数

### 手机侧 `CXRLSample`

- 工程目录：
  `F:\hz\codex\AI_Glasses\SDK\rokid\CXR-L\v1.0.1\cxrlsample101`
- `applicationId`：
  `com.rokid.cxrlsample`
- `minSdk`：
  `31`
- 依赖：
  `com.rokid.cxr:client-l:1.0.1`
- Maven 仓库：
  `https://maven.rokid.com/repository/maven-public/`

### 眼镜侧 `sSDKSampleforCXR`

- 工程目录：
  `F:\hz\codex\AI_Glasses\SDK\rokid\CXR-L\v1.0.1\sSDKSampleforCXR`
- `applicationId`：
  `com.rokid.cxrswithcxrl`
- 与手机侧 `CUSTOMAPP` 默认包名一致
- 默认主页面：
  `MainActivity`
- 自定义指令示例里使用的客户端键值：
  `rk_custom_client`

## 手机侧 Sample 主要入口

- 首页与授权：
  `app/src/main/java/com/rokid/cxrlsample/activities/main/MainActivity.kt`
- `MainViewModel`：
  `app/src/main/java/com/rokid/cxrlsample/activities/main/MainViewModel.kt`
- `CUSTOMVIEW`：
  `app/src/main/java/com/rokid/cxrlsample/activities/customViewType/CustomViewTypeActivity.kt`
- `CUSTOMAPP`：
  `app/src/main/java/com/rokid/cxrlsample/activities/customAppType/CustomAppTypeActivity.kt`
- 拍照：
  `app/src/main/java/com/rokid/cxrlsample/activities/photo/PhotoUsageActivity.kt`
- 自定义指令：
  `app/src/main/java/com/rokid/cxrlsample/activities/customCMD/CustomCmdActivity.kt`
- 全局 `CXRLink`：
  `app/src/main/java/com/rokid/cxrlsample/CXRLSampleApplication.kt`

## 官方资料入口

- 官方页面：
  `https://custom.rokid.com/prod/rokid_web/84feb39f8ef141b0ad0326f902ab881f/pc/cn/9adcfb07939846e5945e79dfbd923f63.html`
- 快速开始：
  `https://ar-independent-manager.rokid.com/out/document/selectWebDocument/e60854773258464d8097c47ac16dbfdf`
- SDK 导入：
  `https://ar-independent-manager.rokid.com/out/document/selectWebDocument/05332d84ac464d42897c0eb2f65d0e7a`
- 鉴权：
  `https://ar-independent-manager.rokid.com/out/document/selectWebDocument/b2bb662a3ef64c4295b1743fc033a7fd`
- 连接与会话：
  `https://ar-independent-manager.rokid.com/out/document/selectWebDocument/2df0b9c9da484a0096896ad484c8a144`
- 眼镜端自定义 View：
  `https://ar-independent-manager.rokid.com/out/document/selectWebDocument/ebed38da8b2d49319a76329bd7f641a8`
- 拍照：
  `https://ar-independent-manager.rokid.com/out/document/selectWebDocument/e4d52e8f897e4b8994f2afa2fac39098`
- 眼镜端自定义应用：
  `https://ar-independent-manager.rokid.com/out/document/selectWebDocument/e7d8b8c3f06142f0bb42124ac2ce02ae`
- 自定义指令：
  `https://ar-independent-manager.rokid.com/out/document/selectWebDocument/f676d13413c64fadbc804606b5d60734`

## 建议的实际执行顺序

1. 用 Android Studio 打开：
   `F:\hz\codex\AI_Glasses\SDK\rokid\CXR-L\v1.0.1\cxrlsample101`
2. 等待 Gradle 首次同步
3. 手机安装并登录 `Rokid AI App`
4. 手机与眼镜完成配对连接
5. 先跑首页授权，确认拿到 `token`
6. 进入 `CUSTOMVIEW`，先把眼镜侧场景打开
7. 在 `CUSTOMVIEW` 场景已打开后验证拍照 JPEG 回传
8. 再切到 `CUSTOMAPP`
9. 使用 `sSDKSampleforCXR` 作为目标眼镜端应用验证自定义指令

## 当前剩余注意点

- 我已经把 Android SDK 平台补到 `36.1`，这部分不再是阻塞项
- Shell 下预热 Gradle Wrapper 时，`gradle-9.3.1` 首次分发下载较慢，Android Studio 首次打开工程时仍可能需要等待一段时间
- 当前阶段不需要开发线；只有未来进入 `CXR-S` 或更深的眼镜端独立调试时，才需要专用开发线与 ADB

## 2026-06-05 调试交接记录

### 本轮目标

- 解决手机端 `CUSTOMAPP` 会话一直停在“正在建立 / 等待连接”的问题。
- 跑通手机端发送 `START_RECORD` / `STOP_RECORD`，眼镜端协同 App 本地录像并回传文件信息。
- 保留 `CUSTOMVIEW` 拍照 JPEG 回传能力，不把未确认的实时预览或录像能力伪装成已完成能力。

### 已确认现象

- `4a1455d Add Rokid CXR-L debug integration` 当时 `CUSTOMVIEW` 已实测可用。
- 当前手机端 VisionRoute 和官方 `CXRLSample` 都出现过 `CXRLink.connect(token)` 返回 accepted，但未收到 `onCXRLConnected` / `onGlassBtConnected` 回调。
- 手机端 `dumpsys activity services com.rokid.sprite.aiapp/.externalapp.service.CXRLinkService` 曾显示第三方 App 已绑定 `CXRLinkService`，但 `IntentBindRecord` 中 `binder=null`。
- Rokid AI App 普通蓝牙连接状态可显示 `Glasses_1284`、电量 `100%`、已连接；这不等于 CXR-L binder 已就绪。
- 眼镜端 `com.rokid.cxrswithcxrl` 已安装，`CAMERA` 权限已 granted；眼镜端 App 卡住无法退出的问题已通过 `EXIT_APP` 与 Back 退出逻辑修复。

### 关键研究结论

- 官方 Maven 元数据确认 `com.rokid.cxr:client-l` 最新版是 `1.0.3`，`lastUpdated=20260601112841`。
- 当前可访问的元数据地址：
  `https://maven.rokid.com/repository/maven-public/com/rokid/cxr/client-l/maven-metadata.xml`
- `client-l:1.0.1` 的 `connect(token)` 只向 Rokid AI App 的 `MEDIA_STREAM_SERVICE` 传 `auth_token`。
- `client-l:1.0.3` 的 `connect(token)` 会额外传 `auth_package`，并依赖 `cxr-service-bridge:1.0-20260522.063600-105`。
- 固件 / Rokid AI App 升级后如果开始校验授权包名，旧 SDK 就可能出现“`bindService` accepted 但 `onBind` 返回 null”的表现；这与本轮 `binder=null` 现象高度吻合。
- 因官方 Sample 在同一设备上也失败，问题不能只归因于 VisionRoute 业务代码。

### 当前代码侧改动状态

- 手机端 VisionRoute 已从 `com.rokid.cxr:client-l:1.0.1` 升级到 `1.0.3`。
  - 文件：`F:\hz\codex\AI_Glasses\android\ai-glasses-poc\app\build.gradle`
- 因新版 bridge `minSdk=28`，App 仍保持自身 `minSdk=24`，通过 manifest merger override 允许合并。
  - 文件：`F:\hz\codex\AI_Glasses\android\ai-glasses-poc\app\src\main\AndroidManifest.xml`
  - 当前写法：`tools:overrideLibrary="com.rokid.cxr.client.extend,com.rokid.cxr"`
- 新版 `sendCustomCmd` 改为传 `Caps`，不再传 `Caps.serialize()` 的 byte array。
  - 文件：`F:\hz\codex\AI_Glasses\android\ai-glasses-poc\app\src\main\java\com\aiglasses\poc\rokid\RokidRepository.kt`
- `CUSTOMAPP` 的 image/custom command callback 注册已恢复到 `connect()` 前，避免连接时序差异。
- 新版 `ICXRLinkCbk` 增加了 `onGlassDeviceInfo`、`onGlassWearingStatus`、`onGlassAiInterrupt` 回调，当前实现为日志记录。
- 新版授权 API 需要显式传 `GlassPermission` 数组，当前请求 `CAMERA`、`MEDIA`、`MICROPHONE`。
  - 文件：`F:\hz\codex\AI_Glasses\android\ai-glasses-poc\app\src\main\java\com\aiglasses\poc\rokid\RokidAuthManager.kt`
- 眼镜端协同 App 已把 bridge 升级到 `cxr-service-bridge:1.0-20260522.063600-105`。
  - 文件：`F:\hz\codex\AI_Glasses\SDK\rokid\CXR-L\v1.0.1\sSDKSampleforCXR\app\build.gradle.kts`
- 眼镜端协同 App 已支持：
  - `START_RECORD`
  - `STOP_RECORD`
  - `EXIT_APP`
  - Back 退出
  - 退出前停止正在进行的录像

### 当前验证状态

- 手机端构建已通过：
  `F:\Gradle\gradle-8.6\bin\gradle.bat :app:assembleDebug --no-daemon --console=plain --max-workers=1`
- 眼镜端协同 App 构建已通过：
  `F:\Gradle\gradle-9.3.1\bin\gradle.bat :app:assembleDebug --no-daemon --console=plain --max-workers=1`
- 眼镜端 APK 已安装到眼镜设备 `1906092613101284`：
  `adb -s 1906092613101284 install -r SDK\rokid\CXR-L\v1.0.1\sSDKSampleforCXR\app\build\outputs\apk\debug\app-debug.apk`
- 2026-06-05 用户反馈：Rokid 协同 App 问题已解决，手机端已经能够控制眼镜端录像。
- 录像闭环状态已更新为已跑通：
  - `CUSTOMAPP` 会话可建立
  - 手机端可发送 `START_RECORD`
  - 眼镜端可开始本地录像
  - 手机端可发送 `STOP_RECORD`
  - 录像控制链路可完成
- 仍建议后续接手者补留一次完整日志归档，记录 `RECORD_STARTED` / `RECORD_STOPPED` 回包和录像文件路径，便于后续接入图传或视频文件同步。

### 2026-06-05 展馆导航联调壳

- 手机端新增 `rokid_glasses_frame` 图像来源，可把最近一次 Rokid JPEG 作为定位图像，并携带：
  - `capture_id`
  - `capture_timestamp_ms`
  - `capture_mode=rokid_glasses_frame`
  - `imu_at_capture`
- 手机端 `visual-locate` 请求已扩展上传 `imu_at_capture` 和 `heading_source=rokid_imu`；没有 Rokid IMU 时不会使用手机 IMU 兜底。
- 手机端 `RokidRepository` 已支持解析 CUSTOMAPP 回包事件：
  - `IMU_SAMPLE`
  - `VOICE_COMMAND`
  - `HUD_ACK`
- 眼镜端协同 App 已新增：
  - Rotation Vector / Game Rotation Vector IMU 周期上报
  - `HUD_UPDATE` 指令接收与屏幕 HUD 文本展示
  - `HUD_ACK` 回包
- Rokid 语音识别公开入口尚未最终确认；当前手机端已能解析 `VOICE_COMMAND raw_text`，并在调试页保留“模拟语音”注入口。

### 接手验证清单

1. 让手机重新出现在 `adb devices -l` 中。
2. 安装新版 VisionRoute debug APK：
   `adb -s <phone_serial> install -r F:\hz\codex\AI_Glasses\android\ai-glasses-poc\app\build\outputs\apk\debug\app-debug.apk`
3. 打开 VisionRoute 隐藏调试页，进入 `Rokid` 调试界面。
4. 点击授权，确认返回后 token 已刷新。
5. 先测 `CUSTOMVIEW`：
   - 建立会话
   - 打开 View
   - 触发拍照
   - 手机端收到 JPEG
6. 回归 `CUSTOMAPP`：
   - 建立会话
   - 查询 / 打开眼镜端协同 App
   - 发送 `PING`
   - 发送 `START_RECORD`
   - 等待眼镜端返回 `RECORD_STARTED`
   - 发送 `STOP_RECORD`
   - 等待眼镜端返回录像文件信息
7. 如果再次出现 `binder=null`，同时保存以下日志：
   - `adb -s <phone_serial> shell dumpsys activity services com.rokid.sprite.aiapp/.externalapp.service.CXRLinkService`
   - `adb -s <phone_serial> logcat -d > android\qa-archive\rokid-phone-after-connect.txt`
   - `adb -s <glass_serial> logcat -d > android\qa-archive\rokid-glass-after-connect.txt`

### 常用设备与命令

- 曾使用的手机设备：`7e98495f`，型号 `23116PN5BC`。
- 曾使用的眼镜设备：`1906092613101284`，型号 `RG_glasses`。
- 手机端 Rokid AI App 包名：`com.rokid.sprite.aiapp`。
- 手机端官方 Sample 包名：`com.rokid.cxrlsample`。
- VisionRoute 包名：`com.aiglasses.poc`。
- 眼镜端协同 App 包名：`com.rokid.cxrswithcxrl`。
- Rokid AI App 版本曾记录为：
  - `versionName=1.7.14.0525`
  - `versionCode=10070014`
- 眼镜端 CXR service 版本曾记录为：
  - `versionName=12`
  - `versionCode=32`

### 当前风险边界

- `client-l:1.0.3` 依赖的 bridge 要求 `minSdk=28`，VisionRoute 当前用 `tools:overrideLibrary` 保持主 App `minSdk=24`；Rokid 调试页已有 Android 9+ 拦截，低版本设备不应进入该能力。
- `sendCustomCmd(String, Caps)` 在新版 SDK 中标记为 deprecated，但当前仍可编译；如果后续发生大 payload 问题，应使用 `sendCustomCmd(String, Caps, ByteArray)` 的流式版本。
- 当前录像控制只覆盖眼镜端协同 App 本地录像，不代表公开 CXR-L SDK 已支持手机端实时预览或通用录像控制。
- 2026-06-05 已确认 `CUSTOMAPP` 录像控制链路可用；后续风险主要转为录像文件同步、图传、文件名绑定和多段录像误匹配。
- 如果新版 VisionRoute 仍无法连接，而官方 Sample 升级到 `client-l:1.0.3` 后可以连接，则继续对比 VisionRoute 授权 token、`auth_package`、`CUSTOMAPP` 包名与 callback 注册时序。
- 如果官方 Sample 升级后仍无法连接，应优先排查 Rokid AI App、眼镜固件、CXR service 与授权状态，而不是继续盲改 VisionRoute。
