# Demo 测试清单 V0.2

更新时间：2026-06-10

## 1. 目标

本清单用于在现场演示前确认当前默认主线是否可用。当前默认主线为：

```text
会场室内地图
-> Rokid HTTP 图传
-> Android App 上传 PC 后台 visual-locate
-> PC 后台 scene_classifier 返回位置
-> 手机地图路径规划
-> Rokid HUD 同步路线和下一动作
```

旧的高德室外导航、五道口购物中心、`manual_demo`、HeyCyan 和 USB 相机能力保留为历史回归或备用调试入口，不作为当前现场默认演示主线。

## 2. PC 后台检查

| 检查项 | 是否完成 | 备注 |
| --- | --- | --- |
| PC 和手机连接同一个 Wi-Fi |  |  |
| PC 后台已启动 |  |  |
| `/api/v1/health` 可在 PC 浏览器打开 |  |  |
| 手机浏览器可打开 PC 的 `/api/v1/health` |  |  |
| `/debug/pairing` 可显示配对页和二维码 |  |  |
| `/debug/visual-locate` 可显示实时调试页 |  |  |
| `/debug/recent-requests` 可查看最近请求 |  |  |
| 当前 `recognition_mode` 已确认 |  |  |
| Windows 防火墙已允许手机访问 8000 端口 |  |  |
| 托盘入口可启动 / 停止后台并复制 LAN baseUrl |  |  |

## 3. Android App 检查

| 检查项 | 是否完成 | 备注 |
| --- | --- | --- |
| `VisionRoute-debug.apk` 已安装到测试手机 |  |  |
| App 首页进入会场室内地图 |  |  |
| 未误进入旧高德室外导航主流程 |  |  |
| 可手动填写 PC 后台 `baseUrl` |  |  |
| 可扫码读取 `/debug/pairing.json` 并保存 `baseUrl` |  |  |
| App 健康检查成功 |  |  |
| 会场底图可显示、拖动、缩放 |  |  |
| 展台 / 设施搜索可用，例如 `B17`、`F05`、厕所、报告厅 |  |  |
| 搜索目标后可显示目标图标 |  |  |
| 路径规划可生成路线和下一动作 |  |  |
| 模拟室内步行可推进位置和提示 |  |  |

## 4. Rokid Bridge 检查

| 检查项 | 是否完成 | 备注 |
| --- | --- | --- |
| Rokid Bridge APK 已安装到眼镜 |  |  |
| 眼镜端 Bridge 可打开 |  |  |
| 手机 App 能检测到 Rokid HTTP 状态 |  |  |
| Rokid HTTP 图传有帧进入手机 App |  |  |
| App 上传 Rokid 图像时 `capture_mode=glasses_private_stream` |  |  |
| HUD 可显示会场小地图 |  |  |
| HUD 可显示当前位置圆点 |  |  |
| HUD 可显示路线和目标点 |  |  |
| HUD 可显示下一动作、距离和预计时间 |  |  |
| 低置信度或无定位时 HUD 有明确提示 |  |  |

## 5. PC 识别定位检查

| 检查项 | 是否完成 | 备注 |
| --- | --- | --- |
| App 上传图片后 PC `/debug/recent-requests` 有请求记录 |  |  |
| 请求包含 `request_id / capture_id / capture_mode / image_bytes` |  |  |
| PC 返回 `ok / low_confidence / not_found / error` 中的稳定状态 |  |  |
| `ok` 结果包含 `floor_id / position / confidence / matched_landmark` |  |  |
| App 能根据 PC 返回位置更新地图圆点 |  |  |
| 低置信度结果不会被 App 当作强定位成功 |  |  |
| 未识别场景不会导致 App 或 PC 后台崩溃 |  |  |
| PC 调试页能看到上传帧、处理阶段、识别结果和 FPS |  |  |

## 6. 主演示流程检查

| 检查项 | 是否完成 | 备注 |
| --- | --- | --- |
| 打开 PC 后台并确认 LAN baseUrl |  |  |
| 手机 App 配对 PC 后台成功 |  |  |
| Rokid Bridge 打开并开始 HTTP 图传 |  |  |
| App 收到 Rokid 图像并上传 PC 后台 |  |  |
| PC 后台返回展台 / 地标定位结果 |  |  |
| 手机地图更新当前位置 |  |  |
| 用户搜索目标展台，例如 `B17` 或 `F05` |  |  |
| App 生成路线和下一动作 |  |  |
| Rokid HUD 与手机地图同步路线和下一动作 |  |  |
| 模拟步行过程中手机和 HUD 同步更新 |  |  |
| 到达目标附近后显示到达状态 |  |  |

## 7. 备用流程检查

| 检查项 | 是否完成 | 备注 |
| --- | --- | --- |
| Rokid 图传不可用时，可使用手机拍照 fallback 上传 |  |  |
| PC 后台 `mock` 或 `template` 模式可用于快速链路自测 |  |  |
| App 可手动重新配置 `baseUrl` |  |  |
| PC 多网卡时可通过环境变量指定 LAN baseUrl |  |  |
| 旧五道口 / manual_demo 链路未干扰当前会场主流程 |  |  |

## 8. 结果记录

| 项目 | 结果 |
| --- | --- |
| PC 后台是否稳定运行 |  |
| 手机是否能通过同 Wi-Fi 访问 PC 后台 |  |
| Rokid HTTP 图传是否稳定 |  |
| PC 识别是否返回可用位置 |  |
| 手机地图定位与路线是否正确 |  |
| Rokid HUD 是否同步正确 |  |
| 现场最大问题 |  |
