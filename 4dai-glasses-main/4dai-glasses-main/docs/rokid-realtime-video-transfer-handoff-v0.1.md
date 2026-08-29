# Rokid 实时视频传输验证交接文档 V0.1

更新时间：2026-06-06

## 1. 当前可做且已完成的非侵入式工作

在不修改 Android App 具体代码、不占用 Rokid 眼镜/手机硬件调试的前提下，本轮完成以下工作：

- 梳理当前项目的 Rokid 接入事实，确认现有链路仍是 `CXR-L takePhoto -> JPEG 回调`。
- 复核 Rokid Glasses 裸机开发文档，确认其说明了 `YodaOS-Sprite / Android Go`、ADB、按键拍照和录像，但没有承诺实时图传 API。
- 复核 Rokid Sprite/Glass3 文档和样例，确认存在 `requestVideoStream`、`onNv21Data`、`onVideoH264Stream` 的实时视频预览方向。
- 整理后续 App 工程师可直接执行的验证清单、统计指标和决策门槛。
- 明确当前不要做的事项，避免打断正在进行的 App 开发任务。

## 1.1 2026-06-06 App 侧最小 POC 落地状态

本轮已在 Android App 的 Rokid 调试页增加独立的 Sprite/Glass3 实时视频探测入口，不替换现有 CXR-L 拍照链路。

- 已接入依赖：`com.rokid.security:phone.sdk:2.2.0-E`。
- 已新增调试入口：`初始化视频 SDK`、`开始 60 秒探测`、`停止探测`、`CXR-L 拍照基准`。
- 已新增统计字段：
  - `first_frame_latency_ms`
  - `frame_width`
  - `frame_height`
  - `frame_count`
  - `estimated_fps`
  - `avg_frame_interval_ms`
  - `max_frame_interval_ms`
  - `callback_type=nv21/h264`
- 已保留现有链路：`CXR-L takePhoto -> IImageStreamCbk.onImageReceived -> JPEG`。
- 已确认真机信息：
  - `ro.product.model=RG-glasses`
  - `ro.product.manufacturer=Rokid`
  - `ro.product.device=glasses`
  - `ro.build.version.release=12`
  - `rokid.cxr-service.version=1.126`
  - `ro.product.build.version.incremental=1.19.009-20260520-150201`

当前 POC 的判定方式：

1. 在 Rokid 调试页点击 `初始化视频 SDK`。
2. 点击 `开始 60 秒探测`。
3. 如果 60 秒内出现 `callback_type=nv21` 或 `callback_type=h264` 且 `frame_count > 0`，则当前设备链路支持 Sprite/Glass3 实时视频回调。
4. 如果 SDK 初始化成功但 60 秒内 `frame_count=0`，则需要检查 Glass3/Sprite SDK 的蓝牙与 P2P 配对状态，不应把结果解释成 CXR-L 拍照链路失败。

## 2. 项目现状判断

### 2.1 当前项目已接入的 Rokid 能力

当前 Android App 使用 `com.rokid.cxr:client-l:1.0.3`，定位在 CXR-L 手机侧协同链路。

已确认的图像路径：

1. 手机端建立 Rokid CXR-L 会话。
2. 手机端调用 `takePhoto(width, height, quality)`。
3. 眼镜端拍照并通过 `IImageStreamCbk.onImageReceived` 回传 JPEG 字节。
4. 手机端解码 JPEG，并把最近一次图像作为 `rokid_glasses_frame` 图像来源。

该路径适合作为低频视觉重定位输入，不适合作为连续视频流或 SLAM 级实时帧源。

### 2.2 当前项目尚未接入的能力

当前项目尚未接入以下能力：

- Sprite/Glass3 手机端 SDK 的 `requestVideoStream`。
- NV21 原始帧回调 `onNv21Data`。
- H264 视频流回调 `onVideoH264Stream`。
- 眼镜裸机 App 自研 Camera2 / MediaCodec / WebRTC 推流。
- USB/UVC 直连图传。

## 3. 外部资料结论

### 3.1 Rokid Glasses 裸机开发文档

用户提供的裸机开发文档标题为 `Rokid Glasses 裸机开发指南`。

文档可确认：

- Rokid Glasses 裸机开发与普通 Android 应用开发基本一致。
- 系统为 `YodaOS-Sprite`，基于 `Android Go`，需要遵循 Android Go 开发约束。
- 左侧镜腿触点同时是充电和数据传输触点，可通过专用开发线连接电脑。
- 需要通过 Rokid AI App 打开 ADB。
- 系统交互里包含点击按键拍照、长按按键录像。

文档未确认：

- 手机端实时拉取眼镜相机预览流。
- 眼镜端 App 可直接开放 Camera2 帧给第三方应用。
- NV21 / H264 / RTMP / WebRTC / GB28181 图传 API。
- 裸机应用和手机 App 之间的低延迟视频传输协议。

因此，裸机开发文档只能证明“可做眼镜端 Android 应用开发”，不能单独证明“支持实时视频传输到手机”。

### 3.2 Sprite/Glass3 实时视频预览方向

Rokid Sprite Enterprise / Glass3 相关资料显示，手机端样例存在实时视频预览方向：

- 手机端请求视频流：`requestVideoStream(...)`。
- 消息监听接收 NV21：`onNv21Data(data, width, height)`。
- 消息监听接收 H264：`onVideoH264Stream(buffer)`。
- 示例参数包含 `GlassVideoStreamParam`，样例里出现 `1920x1080` 和 AR Mix 开关。

这条链路是当前最优先验证方向，因为它比自研裸机推流更接近官方能力，也更可能获得稳定权限和协议保障。

### 3.3 USB/UVC 旧方案方向

旧 Rokid Glass2 / GlassMobile 资料存在 UVC 预览帧和编码推送示例，例如：

- `setOnPreviewFrameListener(bytes -> { ... })`，帧格式标注为 `NV21`。
- `UVCCameraHelper.getInstance().startPusher(...)`，可获得编码结果回调。

该方向仅作为备选。是否适用于当前 Rokid Glasses，需要硬件连接形态和 SDK 兼容性实测确认。

## 4. 可选技术路径对比

| 路径 | 当前状态 | 预期延迟 | 适用场景 | 风险 |
| --- | --- | --- | --- | --- |
| CXR-L 拍照 JPEG 回传 | 已接入 | 约数百毫秒到 2 秒，需真机统计 | 低频重定位、兜底截图 | 不能并发连续拍照，不是视频流 |
| Sprite/Glass3 视频预览 | 未接入，官方样例存在 | 目标 100-500ms 首帧后连续帧 | 连续视觉帧、近实时预览 | 需确认设备型号、SDK 权限、依赖冲突 |
| 裸机 Camera2 自研推流 | 未验证 | 理论可低延迟 | 深度定制、摆脱手机侧 SDK 限制 | 相机权限/系统限制/性能发热不确定 |
| USB/UVC 图传 | 未验证 | 理论 50-150ms 帧到达 | 直连低延迟 | 当前硬件是否支持未知 |
| RTMP 类直播推流 | 未接入 | 通常更高 | 云端直播/远程观看 | 不适合本地导航低延迟闭环 |
| WebRTC 自研推流 | 未接入 | 目标 150-500ms | 眼镜到手机/云端低延迟 | 实现复杂，需眼镜端 Camera 可用 |

## 5. 建议的工程验证顺序

### 5.1 第一优先级：Sprite/Glass3 视频流探测

目标：确认当前 Rokid Glasses 是否支持官方实时视频预览能力。

最小验证项：

1. 确认真机系统信息是否为 `YodaOS-Sprite`。
2. 确认可获取 Sprite/Glass3 手机端 SDK 依赖和初始化文档。
3. 在独立调试入口或独立 Activity 中注册消息监听。
4. 调用 `requestVideoStream(...)`。
5. 统计是否收到 `onNv21Data` 或 `onVideoH264Stream`。

验收门槛：

- 能收到 NV21 或 H264 任一回调。
- 首帧延迟可记录。
- 连续运行 60 秒能记录帧数、平均帧间隔和断流情况。
- 不破坏现有 CXR-L 拍照、CUSTOMVIEW、CUSTOMAPP 功能。

### 5.2 第二优先级：CXR-L 拍照延迟基准

目标：即使实时视频流不可用，也明确现有拍照链路的可用边界。

建议测试矩阵：

| 分辨率 | JPEG 质量 | 次数 |
| --- | --- | --- |
| 640x480 | 60 | 30 |
| 800x600 | 70 | 30 |
| 1024x768 | 80 | 30 |

需要统计：

- `capture_latency_ms` 的 P50 / P95 / Max。
- JPEG 平均字节数。
- Bitmap 解码耗时。
- 请求失败次数。
- 连续拍照最小安全间隔。

### 5.3 第三优先级：裸机 Camera2 可用性探测

仅在 Sprite/Glass3 视频流不可用或权限受限时进行。

最小验证项：

1. 眼镜端裸机 App 枚举 `CameraManager.cameraIdList`。
2. 请求相机权限。
3. 用 `ImageReader` 尝试获取 `YUV_420_888` 或 JPEG。
4. 若可获取帧，再评估 `MediaCodec` H264 编码和本地网络传输。

该方向目前不建议先做，因为它容易占用硬件调试，并且可能和系统拍照/录像能力冲突。

## 6. 建议记录的数据结构

### 6.1 视频流探测日志

```text
rokid_video_probe_start sdk_line=sprite_glass3 width=1920 height=1080 armix=true
rokid_video_first_frame callback_type=nv21 first_frame_latency_ms=...
rokid_video_frame callback_type=nv21 width=... height=... bytes=... interval_ms=...
rokid_video_summary duration_ms=60000 callback_type=nv21 frame_count=... estimated_fps=... avg_interval_ms=... max_gap_ms=... disconnect_count=...
rokid_video_error code=... message=...
```

### 6.2 CXR-L 拍照基准日志

```text
rokid_photo_benchmark_start width=1024 height=768 quality=80 rounds=30
rokid_photo_sample index=1 capture_latency_ms=... bytes=... decode_ms=... success=true
rokid_photo_benchmark_summary width=1024 height=768 quality=80 p50_ms=... p95_ms=... max_ms=... avg_bytes=... failures=...
```

## 7. 决策门槛

### 7.1 采用实时视频 Provider

满足以下条件时，建议新增 `rokid_live_video_frame_provider`：

- Sprite/Glass3 SDK 可稳定收到 NV21 或 H264。
- 连续 60 秒断流次数为 0，或断流可自动恢复。
- 有效帧率大于等于 10 FPS。
- 首帧延迟小于 1 秒。
- 单帧到手机端可控在 100-500ms 级别。

### 7.2 保留 CXR-L 拍照 Provider

满足以下任一条件时，建议继续使用 `rokid_glasses_frame` 作为低频图像来源：

- Sprite/Glass3 SDK 不支持当前硬件。
- 视频流 SDK 需要额外企业授权且短期无法获取。
- 视频流回调不稳定或帧率低于 5 FPS。
- 视频流接入会破坏当前 CXR-L 调试功能。

### 7.3 暂不采用裸机自研推流

出现以下情况时，不建议投入裸机自研推流：

- Camera2 无法枚举相机。
- 第三方裸机 App 无法获取相机权限。
- 获取帧会和系统拍照/录像按键能力冲突。
- 眼镜端编码导致明显发热或掉帧。

## 8. 当前不要做的事项

为避免影响正在进行的 App 开发任务，当前阶段不要做：

- 不修改 `android/ai-glasses-poc` 下的具体 Kotlin、XML、Gradle 代码。
- 不升级或替换现有 CXR-L 依赖。
- 不启动 Android Studio 同步或大规模 Gradle 构建。
- 不占用 Rokid 眼镜、手机或 ADB 调试链路。
- 不清理工作区已有未提交改动。
- 不删除或移动 `SDK`、`research`、`android/qa-archive` 下的现有资料。

## 9. 可继续由非硬件侧提前完成的任务

如果后续仍需要在不打断 App 工程师的情况下推进，可继续做：

1. 拉取并归档 Sprite/Glass3 官方 SDK 文档和样例源码到 `research`。
2. 整理 Sprite/Glass3 SDK 依赖清单、Maven 仓库、权限声明和初始化流程。
3. 输出 `rokid_live_video_frame_provider` 的接口设计，不写 App 实现代码。
4. 设计视频帧到定位请求的抽象数据模型，例如 `FrameSource.LiveVideo`、`FrameSource.PhotoJpeg`。
5. 准备真机验证记录模板，方便工程师一次测试就能回填数据。

## 10. 参考资料

- Rokid Glasses 裸机开发指南：
  `https://custom.rokid.com/prod/rokid_web/57e35cd3ae294d16b1b8fc8dcbb1b7c7/pc/cn/13083daf77dd40bf84cf5c59711e987a.html`
- Rokid Sprite Enterprise 文档：
  `https://x-docs.rokid.com/docs/en/`
- Rokid 实时视频预览文档：
  `https://x-docs.rokid.com/docs/en/%E4%BB%A3%E7%A0%81%E7%A4%BA%E4%BE%8B/20-message-transfer/06-%E5%AE%9E%E6%97%B6%E8%A7%86%E9%A2%91%E9%A2%84%E8%A7%88.html`
- Rokid Glass3 手机端视频接收样例：
  `https://gitee.com/as_pixar/glass3sdkdemo/raw/main/glass3sdkphonedemo/app/src/main/java/com/rokid/phone/VideoReceiveActivity.kt`
- 当前项目 CXR-L 准备说明：
  `F:\hz\codex\AI_Glasses\docs\rokid-cxr-l-android-prep-v0.1.md`
