# Rokid 离线录像 + IMU 采集模式 V0.1

## 目标

眼镜端本地完成视频录像与 IMU 同步记录。手机端只负责开始、停止、查看状态和下载 Session；不做实时图传、不做实时定位。

## 眼镜端输出

每次采集生成一个 `session_yyyyMMdd_HHmmss_SSS` 目录：

- `video.mp4`：Camera2 + MediaCodec H.264 录像。
- `imu.jsonl`：眼镜端 SensorManager IMU 样本。
- `frames.csv`：逐帧 `presentation_time_us` 与推导的 `camera_sensor_timestamp_ns`。
- `events.jsonl`：开始、停止、相机、编码器、IMU 事件。
- `session.json`：Session 元数据、分辨率、帧率、IMU Hz、同步模式。

## 同步方式

- 视频使用 Camera2 + MediaCodec，不再依赖 MediaRecorder。
- 帧时间戳来自编码输出 `presentationTimeUs`，写入 `frames.csv`。
- IMU 使用 `SensorEvent.timestamp`，写入 `imu.jsonl`。
- 后处理按同一眼镜端时钟时间线对齐视频帧与 IMU 样本。

## HTTP API

- `GET /capabilities`：查询相机尺寸、FPS 范围、IMU 传感器。
- `POST /sessions/start?width=1280&height=720&fps=60`：开始离线采集。
- `POST /sessions/current/stop`：停止当前采集。
- `GET /status`：查询当前录制状态、实际 FPS、IMU Hz。
- `GET /sessions`：查询历史 Session。
- `GET /sessions/{id}`：查询单个 Session 元信息。
- `GET /sessions/{id}/download`：下载 Session zip。

## 手机端入口

进入 Rokid 调试页，使用“裸机 HTTP 图传”的眼镜 IP 输入框，然后在“离线采集（录像 + IMU）”卡片中操作：

1. 点击“采集状态”确认眼镜端在线。
2. 点击“开始采集”。
3. 手机端实时显示录制状态、分辨率、视频 FPS、IMU Hz、帧数、时长。
4. 点击“停止采集”。
5. 点击“下载最新 Session”，zip 保存到手机 App 外部私有下载目录。

## 当前限制

- 默认请求 `1280x720@60fps`，实际能力以 Rokid 相机 HAL 和编码器返回为准。
- 严格逐帧对齐依赖 Camera2/MediaCodec 时间戳与 SensorEvent 时间戳处于可比较时钟域；后处理应校验 `frames.csv` 与 `imu.jsonl` 的单调性和采样率。
- 当前不录音；如后续需要音频，需要增加音频采集与 muxer 音轨。
