# App 子项目进度

更新时间：2026-06-10

## 当前状态

状态：进行中

当前 Android App `VisionRoute` 的默认主线已经切换为会场实时室内导航演示：

```text
会场室内地图 -> Rokid HTTP 图传 -> PC 后台 visual-locate -> 路径规划 -> 手机地图 + Rokid HUD
```

旧的高德室外导航、五道口购物中心、`manual_demo`、HeyCyan 和 USB 相机能力仍保留在代码中，但不作为当前现场默认演示入口。当前 App 侧重点是会场地图、展台搜索、路径规划、PC 后台配对、Rokid HTTP 图传接入、定位结果展示、模拟步行和 Rokid HUD 同步。

## 已完成

- [x] 建立 Android PoC 工程
- [x] 建立地图主视觉页面，包含顶部状态卡、底部主操作区和可折叠 Debug 面板
- [x] 接入真实高德室外导航能力
- [x] 接入当前位置获取、POI 搜索与选点，搜索栏支持免城市输入、输入联想、结果点选预览和结果右侧导航入口
- [x] 接入骑行 / 步行算路
- [x] 接入 GPS / 模拟导航
- [x] 接入回到当前位置、车头向上 / 北向上切换、全览、退出导航
- [x] 提供高德路线兜底绘制、已走路线灰色覆盖和偏航重算请求
- [x] 支持外部高德 App 兜底
- [x] 实现手动 `Enter Venue` / `Exit Indoor`
- [x] 接入室内 `MapView` 宿主与高德室内底图开关
- [x] 接入高德室内楼层控件与室内底图状态摘要
- [x] 建立 `ImageProvider` 抽象
- [x] 提供 `glasses_album_sync`
- [x] 提供 `glasses_thumbnail`
- [x] 提供 `phone_camera_fallback`
- [x] 提供 provider fallback 演示链路
- [x] 保留健康检查、场馆元数据、视觉定位、室内路径规划调试入口
- [x] 室内默认主链路切换为 `manual_demo`
- [x] 新增室内手动演示脚本控制器
- [x] 将默认室内手动脚本切换为五道口购物中心西门到 2F TATA 店铺门口
- [x] 新增十字方向键、`上楼 / 下楼 / 重置 / 退出室内` 控件
- [x] 新增室内手动演示纠错提示、回退和到达态
- [x] 新增室内高德底图上的当前位置、目标点、灰蓝路线覆盖物
- [x] 手动室内主流程优先使用高德室内底图与业务覆盖物；底图不可用时显示 App 内 fallback route preview
- [x] 新增 Debug 室内点位标注模式，可点击高德室内底图输出 `floor_id / x / y / lat / lng`
- [x] 固化 Debug 室内路线点校准层，支持当前路线点拖动、缩放、上下翻转、复制 CSV 与本机保存
- [x] 接入五道口 B1/F1 本地图纸路径规划 Debug 能力，支持 resolver 搜索、F1 入口起点、Dijkstra 规划、跨层提示和 B1/F1 图片底图路线绘制
- [x] B1/F1 本地图纸路线支持按楼层使用路线校准层保存 GCJ-02 点，保存后可在高德室内底图上绘制当前楼层规划路线
- [x] 接入 HeyCyan Android SDK 调试入口，支持扫描连接、通道初始化、设备信息、电量、媒体数量、拍照、录像、缩略图、相册同步和本机媒体预览
- [x] 新增 USB 相机调试页入口，支持 UVC 摄像头枚举、授权和实时预览
- [x] 新增 USB 相机拍照、录像、媒体预览与时间流列表
- [x] 新增 USB 相机拍摄参数面板，支持分辨率 / 格式 / FPS / 码率 / 音频参数调整与恢复默认
- [x] 新增 USB 相机图像控制面板，按当前摄像头能力动态显示可调参数
- [x] 接入 Rokid CXR-L 调试链路，已确认 `CUSTOMAPP` 会话可控制眼镜端本地录像
- [x] 新增 `rokid_glasses_frame` 图像来源，Rokid JPEG 可携带 `capture_id / capture_timestamp_ms / capture_mode / imu_at_capture` 进入定位请求
- [x] 新增 Rokid IMU / 语音命令 / HUD 下发的 CUSTOMAPP 协议壳和调试页摘要
- [x] 参数持久化：`baseUrl / venue_id / floor_id / poi_id / provider / outdoor search / outdoor travel mode / outdoor start / venue entry`
- [x] 补齐 Android JVM 单测基线
- [x] 补充 App 状态机、室内底图、手动演示和语音提醒方案文档
- [x] 发布当前 Debug APK 到 `android/releases/VisionRoute-debug.apk`，并补充 Git LFS 交付说明文档
- [x] 新增会场室内地图主线，支持展台 / 设施搜索、路径规划、定位圆点和模拟步行
- [x] 新增 Rokid Bridge 模块，支持眼镜端 HTTP 图传、状态上报、会场 HUD 小地图、当前位置、路线和动作提示
- [x] 手机 App 内置 Rokid Bridge APK，可用于后续眼镜端更新
- [x] 接入 PC 后台 baseUrl 配置和扫码配对，支持读取 `/debug/pairing.json` 并保存 `baseUrl`
- [x] App 可上传 Rokid HTTP 图传帧到 PC 后台 `visual-locate`，默认使用 `glasses_private_stream`
- [x] 手机地图和 Rokid HUD 可同步展示当前位置、路线、目标、下一动作、距离、预计时间和低置信度提示

## 进行中

- [ ] 使用真实 Rokid 连续图传做长时间热态联调，观察发热、掉帧、Wi-Fi 和电量
- [ ] 在现场 Wi-Fi 下验证 PC baseUrl 扫码配对和多网卡环境稳定性
- [ ] 用真实走动、遮挡、强光 / 暗光场景验证 PC 后台 `scene_classifier` 返回与 App 展示一致性
- [ ] 验证 Rokid HUD 与手机地图在连续定位 / 模拟步行时的一致性

## 待完成

- [ ] 补强现场真实样本下的多帧稳定策略
- [ ] 补强 PC 后台不可达、低置信度、未识别和图传中断时的现场兜底 UI
- [ ] 扩展 App 侧自动化测试覆盖会场主线、PC 配对、Rokid 图传状态和 HUD 同步
- [ ] 评估真实 Rokid 语音入口；当前不作为主演示入口

## 真实场馆接入需替换项

- PC 后台 `baseUrl`
- 会场底图和 `conference` assets
- 展台 / 设施 resolver
- 路网与坐标映射
- PC 后台识别模型 / 样本
- Rokid Bridge 安装包
- Release 签名 keystore 与正式包名配置

## 现有产物

- Android 代码目录：`ai-glasses-poc/`
- 当前 APK：`../android/releases/VisionRoute-debug.apk`
- Rokid Bridge 模块：`ai-glasses-poc/rokid-bridge/`
- 会场地图 assets：`ai-glasses-poc/app/src/main/assets/mapping/conference/`
- 状态机文档：`docs/app-state-machine-and-image-provider-v0.1.md`
- 室内底图方案：`docs/amap-indoor-basemap-integration-v0.1.md`
- 室内手动演示方案：`docs/manual-indoor-demo-mode-v0.1.md`
- 室内语音提醒方案：`docs/voice-guidance-plan-v0.1.md`
- UI 执行规格：`docs/ui-optimization-plan-v0.1.md`
- 子项目 PRD：`PRD.md`

## 最近验证

- 2026-04-29：接入高德 `navi-3dmap` SDK、Manifest Key 与隐私合规初始化后，`:app:assembleDebug` 通过
- 2026-04-29：在 `Pixel_6_API_34` 模拟器完成高德 Outdoor 冒烟，`AMapNaviView` 可见，`Prepare Outdoor Route` 返回路线摘要，`Start Outdoor Nav` 进入 `OUTDOOR_NAVIGATING`
- 2026-04-29：补充高德定位、POI 搜索、选点导航、路线兜底、回到当前位置、车头/北向切换后，`:app:assembleDebug` 通过
- 2026-04-30：落地地图主视觉、顶部状态卡、底部主操作区、可折叠 Debug 面板和结构化 UI 契约后，`:app:assembleDebug` 通过
- 2026-04-30：补充外部高德 App 兜底、室内预览、provider 摘要和 JVM 单测后，`:app:assembleDebug` 与 `:app:testDebugUnitTest` 通过
- 2026-05-05：接入高德室内底图宿主，室内状态可切换到普通 `MapView`；`:app:assembleDebug` 与 `:app:testDebugUnitTest` 通过，单测执行 8 个用例
- 2026-05-06：室内默认主链路切换为 `manual_demo`，新增手动演示脚本、十字方向键、上楼/下楼按钮、纠错提示、灰蓝路线高亮与单测覆盖
- 2026-05-06：手动室内主流程隐藏室内路线预览卡，当前位置、目标点、已完成段和待执行段改为仅在高德室内主地图上展示
- 2026-05-06：默认手动室内演示脚本切到五道口购物中心西门到 2F TATA 店铺门口，`:app:assembleDebug` 与 `:app:testDebugUnitTest` 通过
- 2026-05-06：新增 Debug 室内点位标注模式，支持点击高德室内底图输出可复制点位表
- 2026-05-07：室内手动演示补齐五道口 TATA route-only 分层预览、完整动作到达测试和旧演示参数迁移
- 2026-05-07：登记西门 Debug 点位 `manual_point_001`，默认场馆入口更新为 GCJ-02 `39.991583,116.338965`，并兼容高德楼层名 `1F / 2F`
- 2026-05-07：登记 2F TATA 门口 Debug 点位 `manual_point_001`，高德底图目标 marker 使用 GCJ-02 `39.991556,116.339568`
- 2026-05-07：登记跨层节点 Debug 点位，1F 上行扶梯口使用 GCJ-02 `39.992093,116.339331`，2F 扶梯出口使用 GCJ-02 `39.992189,116.339304`
- 2026-05-07：接入 Mapping `wudaokou_tata_amap_gcj02_overlay.json` 到 Android assets，高德室内底图路线叠加改为使用 `nodes[].gcj02` 和按楼层分组的 `route_polylines`
- 2026-05-09：固化室内路线点校准方式，路线校准层支持人工拖动/缩放/上下翻转，校准结果按 `routeId / venueId / targetPoiId` 保存在 App 本地，后续同一路线直接复用保存点位
- 2026-05-09：接入 Mapping 五道口 B1/F1 `image_pixel` 草案路网到 Android assets，新增本地图片底图路径规划与 JVM 单测覆盖
- 2026-05-09：复用室内路线校准层支持 B1/F1 规划路线按楼层保存 GCJ-02 点，保存后高德室内底图优先显示已校准的当前楼层路线
- 2026-05-11：接入 HeyCyan Android SDK AAR 与 AI 眼镜调试页，`:app:assembleDebug` 与 `:app:testDebugUnitTest` 通过
- 2026-05-17：新增 USB 相机调试页，补齐预览渲染、媒体时间流、拍摄参数设置、图像控制与相册细节
- 2026-05-18：当前工作区运行 `:app:assembleDebug` 未通过，阻塞在 `app/src/main/java/com/aiglasses/poc/usb/UsbCameraDebugActivity.kt`，存在 `formatKey` 与 `USB_PREVIEW_FRAME_TIMEOUT_MS` 未解析，同时伴随 Kotlin daemon 内存/句柄异常
- 2026-05-18：当前工作区运行 `:app:testDebugUnitTest` 未通过，失败原因同样是 `UsbCameraDebugActivity.kt` Kotlin 编译错误
- 2026-06-05：Rokid CXR-L 手机端升级到 `client-l 1.0.3`，眼镜端协同 App 同步到 `cxr-service-bridge:1.0-20260522.063600-105`；用户反馈协同 App 问题已解决，手机端已能够控制眼镜端录像
- 2026-06-05：新增 Rokid 展馆导航联调壳，覆盖 JPEG capture 元数据、Rokid IMU 样本、Rokid 语音命令解析、HUD_UPDATE 下发和定位请求 `imu_at_capture` 扩展字段
- 2026-06-10：当前主线切换为会场室内地图、Rokid HTTP 图传、PC 后台 `visual-locate`、路径规划和 Rokid HUD 同步；`:app:assembleDebug`、`:app:testDebugUnitTest`、`:rokid-bridge:assembleDebug` 最近已知通过

## 依赖与阻塞

- HeyCyan SDK 调试入口已接入，BLE 扫描连接、拍照录像和 Wi-Fi P2P 相册同步仍需真实眼镜硬件验证
- USB 相机调试页已接入，但当前工作区仍存在 `UsbCameraDebugActivity.kt` 编译错误，尚未恢复到可构建状态
- 五道口 TATA 高德叠加当前使用 Mapping 两锚点配准生成的 GCJ-02 overlay；如果高德室内底图仍存在局部偏移，需要 Mapping 侧补每层更多锚点并重新生成 floor-specific transform
- 室内路线点校准结果保存在当前设备 App 数据中，卸载 App 或清除应用数据后需要重新校准；该能力只固化 App 侧人工校准和复用流程，不等同于自动建图或自动定位
- 五道口 B1/F1 自动路径规划不会把 `image_pixel` 坐标直接当高德坐标；当前高德室内 overlay 依赖 App 本地按楼层人工保存的 GCJ-02 校准点，未保存楼层仍使用本地图纸兜底
- 模拟器环境下高德原生全览仍可能触发 SDK 内部异常，当前继续使用 App 路线兜底
- 高德 `search` SDK 与 `navi-3dmap` 存在 duplicate class 风险，当前继续使用 `app/libs/search-9.7.1-no-utils.jar`
- 自动入场判断未实现，当前 handoff 仍以手动 `Enter Venue` 为准
- 室内导航当前使用预置 `manual_demo` 脚本，真实路径几何、真实店铺坐标和真实跨层节点尚未替换
- 高德室内底图可见性取决于目标商场是否被高德覆盖
- 真实云端是否需要 token 尚未确认
- 当前 `ImageProvider` 主链路仍保留 Mock provider / 手机兜底；HeyCyan 同步媒体暂作为调试页本地相册，不自动上传云端
- Rokid CXR-L 录像控制已跑通；IMU / 语音 / HUD 已有 App 侧协议壳，仍需真实眼镜端联调确认；录像文件同步、图传、文件名绑定和多段录像匹配策略尚未收口

## 验收口径

当前阶段验收关注以下五项：

1. App 首页进入会场室内地图，不误入旧室外导航主流程。
2. App 能通过手动输入或扫码配对连接 PC 后台，并完成 health 校验。
3. Rokid HTTP 图传帧能上传 PC 后台 `visual-locate`，App 能展示返回的定位结果。
4. App 能搜索展台 / 设施、生成路径，并同步手机地图与 Rokid HUD。
5. 低置信度、PC 不可达、图传中断和未识别场景有明确 UI 状态。
