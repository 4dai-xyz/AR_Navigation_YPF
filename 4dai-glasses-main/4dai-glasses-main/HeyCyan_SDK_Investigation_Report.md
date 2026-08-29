# HeyCyan AI 眼镜 SDK 调研报告

调研日期：2026-04-27

## 1. 结论摘要

基于当前拿到的 Android / iOS SDK 包，公开 SDK 能支持：

- 蓝牙扫描、连接、解绑
- 读取设备电量、版本、媒体数量
- 控制眼镜拍照、录像、录音
- 获取图片缩略图 / AI 拍照缩略图
- 通过 Wi-Fi / Wi-Fi P2P 同步眼镜中的照片、视频、录音到手机本地
- 语音/音频相关 Opus、Speex、PCM 处理
- DFU / OTA 固件升级相关能力

但当前公开 SDK 没有看到实时摄像头图传接口，也没有看到稳定支持“每秒实时获取一张完整照片到手机”的 API。

更准确地说：SDK 可以控制眼镜每秒拍照，但照片通常是先保存在眼镜本地，再通过 Wi-Fi / P2P 媒体同步下载到手机。这个链路不是实时快照流，延迟和吞吐取决于眼镜媒体数量、Wi-Fi 连接、文件大小和 SDK 同步逻辑。

## 2. SDK 文件概览

### Android SDK

文件：

```text
C:\Users\hz\Desktop\AI编程用\AI眼镜SDK\HeyCyan_Android_SDK_1.0.2_20250816.zip
```

主要内容：

- `LIB_GLASSES_SDK-release_3.aar`
- `GLASSES_SDK_DOC_CN.pdf`
- `GLASSES_SDK_DOC_EN.pdf`
- `GlassesSDKSample.zip`

Android AAR 中包含：

- Java/Kotlin class：约 539 个
- 包名线索：
  - `com.oudmon.ble.*`
  - `com.oudmon.wifi.*`
  - `com.jieli.jl_audio_decode.*`
- native so：
  - `libjl_opus.so`
  - `libjl_speex.so`
  - `libst_opus.so`
  - `libst_speex.so`
  - 支持 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`

### iOS SDK

文件：

```text
C:\Users\hz\Desktop\AI编程用\AI眼镜SDK\HeyCyan_iOS_SDK_V1.0.0_20250901.zip
```

主要内容：

- `QCSDKDemo/`
- `iOS_SDK_Development_Guide.pdf`
- `QCSDK.framework`
- `JLAudioUnitKit.framework`
- `JLLogHelper.framework`
- Objective-C Demo 源码：
  - `QCCentralManager`
  - `QCScanViewController`
  - `ViewController`

## 3. 芯片 / 方案线索

当前 SDK 没有直接暴露明确芯片型号，例如没有看到可直接确认的 `ACxxxx`、`JLxxxx`、`BKxxxx`、`BESxxxx` 等具体 SoC 型号。

但有较强的杰理 JieLi / JL 线索：

- Android 包中有：
  - `com.jieli.jl_audio_decode.*`
  - `libjl_opus.so`
  - `libjl_speex.so`
  - `createBondBluetoothJieLi(...)`
- iOS 包中有：
  - `JLAudioUnitKit.framework`
  - `JLLogHelper.framework`
  - 版权/路径中出现 `ZhuHai JieLi Technology`、`zh-jieli.com`

判断：

- 可以说 SDK 使用了杰理相关音频/蓝牙组件。
- 不能仅凭当前 SDK 严谨断言整机主控、摄像头 SoC 或 Wi-Fi SoC 的具体型号。
- 如需确认芯片，需要厂商提供硬件 BOM、设备广播信息、固件版本映射表，或拆机/日志进一步确认。

## 4. 是否支持摄像头实时图传

当前没有发现实时图传能力。

已搜索/检查的关键词包括：

```text
rtsp
mjpeg
h264
h.264
preview
stream
live
realtime
real-time
camera
video
thumbnail
media.config
/files/
```

结果：

- 没有发现 RTSP、MJPEG、H.264、实时 preview、live view 等视频流接口。
- 出现的 `stream` 主要是 Opus / Speex 音频流。
- iOS / Android 都主要暴露“控制拍照/录像”和“下载媒体文件”能力。

Android AAR 中 `GlassesControl` 的媒体下载逻辑显示：

```text
media.config
http://<眼镜IP>/files/<文件名>
```

这说明当前 SDK 的媒体同步链路更像：

1. 眼镜建立 Wi-Fi / Wi-Fi P2P 连接
2. 手机下载 `media.config`
3. 根据配置里的文件名逐个下载照片、视频、录音

这不是实时摄像头画面传输。

## 5. 每秒获取一张实时照片的可行性

用户目标：

```text
每秒获取到一张实时照片
```

当前 SDK 下的判断：

- 每秒发送拍照命令：可以尝试。
- 每秒稳定拿到一张完整照片文件到手机：当前公开 SDK 不支持可靠实现。

原因：

- 拍照命令走蓝牙控制。
- 照片文件先保存在眼镜端。
- 手机端再通过 Wi-Fi / P2P 同步媒体。
- SDK 暴露的是媒体同步流程，不是单帧实时回传流程。
- `GlassesControl.importAlbum()` 看起来会基于 `media.config` 拉取媒体文件，可能是批量/全量同步，不适合严格 1 FPS 的实时取图。

### 可尝试的替代方案

1. 使用 AI 拍照缩略图能力

SDK 里有类似：

```kotlin
LargeDataHandler.getInstance().getPictureThumbnails { cmdType, success, data ->
    // data 可保存为 jpg 图片
}
```

以及“AI 拍照，上报缩略图”的逻辑。它可能比下载完整照片更快，但限制是：

- 可能只是缩略图，不是原图。
- 分辨率和画质未知。
- 是否能稳定每秒触发一次，需要真机验证。

2. 向厂商索要单张照片下载接口

理想接口形态：

```text
takePhoto()
onPhotoReady(fileName)
downloadFile(fileName)
```

或者：

```text
takePhotoAndDownloadLatest()
```

这样才有机会做接近 1 FPS 的“拍照 -> 拉单张”。

3. 向厂商索要实时预览 / 快照协议

需要确认是否存在未公开协议，例如：

- HTTP snapshot
- RTSP
- MJPEG
- H.264 over TCP/UDP
- Wi-Fi Direct 单帧接口
- BLE/Wi-Fi 混合控制 + 数据通道

## 6. 已创建 Android Demo

已基于 Android SDK 自带 sample 创建 Demo：

```text
D:\CODE\other\HeyCyanTimedPhotoDemo
```

说明文件：

```text
D:\CODE\other\HeyCyanTimedPhotoDemo\README_TIMED_PHOTO_DEMO.md
```

主要改动：

- 新增 `Start 1s photo loop / Stop 1s photo loop` 按钮
- 每秒发送一次 SDK 拍照命令：

```kotlin
LargeDataHandler.getInstance().glassesControl(
    byteArrayOf(0x02, 0x01, 0x01)
) { _, response ->
    ...
}
```

- 每 10 次拍照命令后自动触发一次媒体同步
- 停止循环时触发最终同步
- 保存目录使用 SDK 原有目录：

```text
/storage/emulated/0/Android/data/com.sdk.glassessdksample/files/DCIM_1
```

Windows 本地工程中对应逻辑文件：

```text
D:\CODE\other\HeyCyanTimedPhotoDemo\app\src\main\java\com\sdk\glassessdksample\MainActivity.kt
```

### Demo 当前限制

这个 Demo 不能保证“每秒实时拿到一张图片文件”。它实现的是：

```text
每秒控制眼镜拍照 -> 周期性同步眼镜媒体到手机
```

这和实时获取照片是两种能力。

### 构建状态

已做静态检查：

- XML 可解析
- 新增 view id / 字符串资源存在
- Kotlin 文件大括号数量匹配
- 关键调用存在：
  - `binding.btnTimedPhoto`
  - `byteArrayOf(0x02, 0x01, 0x01)`
  - `importAlbum()`

未完成 `assembleDebug`，原因是 Gradle Wrapper 下载失败：

```text
Downloading https://services.gradle.org/distributions/gradle-8.7-bin.zip failed: timeout
```

这属于网络/Gradle 环境问题，尚未进入真正 Android 编译阶段。

## 7. 建议向 SDK 厂商确认的问题

建议直接向厂商确认以下问题：

1. 是否有实时预览接口？
   - RTSP / MJPEG / H.264 / HTTP snapshot / 自定义 TCP/UDP 均可。

2. 是否有“拍照完成事件”？
   - 拍照成功后是否能回调最新照片文件名？

3. 是否支持下载单个指定媒体文件？
   - 不经过全量 `media.config` 同步。

4. `getPictureThumbnails` 返回的数据规格是什么？
   - JPEG 还是其他格式？
   - 分辨率是多少？
   - 是否为最新照片？
   - 是否能稳定每秒触发？

5. `media.config` 的格式是否公开？
   - 是否能增量解析新增文件？
   - 是否能区分已下载 / 未下载？

6. 眼镜端 Wi-Fi HTTP 服务是否有隐藏接口？
   - 例如 `/latest.jpg`
   - `/snapshot`
   - `/preview`
   - `/files/<fileName>`

7. 设备主控 / 摄像头 / Wi-Fi 芯片型号是什么？
   - 是否有 SoC SDK 或底层协议文档？

## 8. 当前最终判断

当前公开 SDK 适合做：

- AI 眼镜基础控制 App
- 拍照/录像/录音控制
- 媒体同步 App
- 低频拍照采集 Demo
- 可能的缩略图采集 Demo

当前公开 SDK 不适合直接做：

- 摄像头实时预览
- 手机端实时图传
- 严格每秒获取一张完整照片
- 低延迟视觉识别闭环

如目标是“手机每秒拿到一张当前画面”，下一步优先验证 `getPictureThumbnails` / AI 拍照缩略图是否可稳定 1 FPS；如果不行，需要厂商提供额外实时取图或单张下载协议。

## 9. 对项目方案的图像链路准入结论

在 PRD 和架构中，不能再把“眼镜稳定 1 秒 1 张完整图片到手机”作为默认前提，应改为 P0 准入项。

建议按下面分层验证：

| 模式 | 来源 | 项目用途 | 判断 |
| --- | --- | --- | --- |
| `glasses_thumbnail` | `getPictureThumbnails` / AI 拍照缩略图 | 粗定位、链路验证 | 不承诺 3 米定位 |
| `glasses_album_sync` | 拍照后 Wi-Fi 相册同步 | 眼镜主链路候选 | 需测 P95 时延和失败率 |
| `glasses_private_stream` | 厂商私有实时图传 | 理想链路 | 需厂商明确 API |
| `phone_camera_fallback` | 手机摄像头 | Demo 兜底 | 眼镜链路不达标时启用 |

P0 实验必须记录：

- 触发拍照到 App 拿到图片的平均时延和 P95
- 图片分辨率、压缩质量、时间戳可用性
- 连续 10 分钟采集失败率
- 手机连接眼镜 Wi-Fi / P2P 时是否还能稳定访问公网
- 设备发热、耗电、存储占用

建议准入门槛：

- P95 小于 1.2 秒：可作为眼镜主采图链路
- P95 在 1.2-3 秒：只适合作为低频校正链路
- P95 大于 3 秒或只能拿缩略图：必须启用手机摄像头兜底
