# Android App UI 执行规格 v0.1

更新时间：2026-05-06

## 1. 背景与目标

当前 Android App 已经具备真实高德室外导航能力，也已经接入高德室内底图与室内手动演示模式。当前 UI 目标不是再做一版联调控制台，而是把现有能力收口成“演示人员不开 Debug 也能顺畅走完主流程”的界面。

当前真实主链路是：

- 室外：真实高德导航
- 室内：高德室内底图 + `manual_demo`

云端视觉重定位和室内路径规划仍保留在 Debug 面板中，但不是当前主界面的首层主流程。

## 2. 当前已实现基线

以下能力已存在，UI 只允许保留、搬迁和收口，不允许把已有效能力做回归：

- 真实高德 SDK
- 骑行 / 步行切换
- 当前定位
- POI 搜索与选点
- 回到当前位置
- 车头向上 / 北向上切换
- 退出导航
- 高德原生全览与 App 兜底全览
- 手动 `Enter Venue`
- 手动 `Exit Indoor`
- 高德室内底图宿主
- 室内手动演示脚本
- `phone_camera_fallback`
- Debug 保留链路

## 3. 当前边界

以下内容必须如实保留，不得写成现状已实现：

- 自动入场判断未实现
- 自动室外切室内未实现
- 真实眼镜链路未接入
- 当前室内默认不依赖云端重定位
- Debug 能力不能删除

## 4. 本批不做范围

- 不改 Compose
- 不大改 `NavState` 枚举
- 不改云端和 Mapping 代码
- 不做真实自动切室内
- 不做真实连续重定位产品化闭环
- 不做完整商业级室内自由漫游

## 5. 页面结构

主页面固定为三层：

1. 底层：地图宿主
2. 顶层上方：顶部状态卡
3. 顶层下方：底部主操作区 + 可折叠 Debug 面板

地图宿主规则：

- 室外状态显示 `AMapNaviView`
- 室内状态显示普通 `MapView`

## 6. 结构化 UI 契约

### 6.1 TopCardUi

顶部状态卡至少展示：

- 当前阶段
- 当前 headline
- 当前摘要
- 当前目标摘要
- 当前 provider 摘要
- warning
- error

在室内 `manual_demo` 中，摘要必须优先展示：

- 当前模式：室内手动演示
- 当前楼层
- 当前提示
- 当前节点
- 目标节点
- 纠错提示或到达态

### 6.2 BottomActionBarUi

底部主操作区必须按状态切换，当前首层只保留主流程动作。

室外准备态：

- `Use Current Location`
- `Search POI`
- `Prepare Outdoor Route`

室外路线就绪态：

- `Start Outdoor Nav`
- `Enter Venue`

室外导航态：

- `Continue Navigation`
- `Overview`
- `Exit Outdoor Navi`
- `Enter Venue`

室内手动演示态：

- `上`
- `左`
- `右`
- `下`
- `B1 / F1 / F2 ...`
- `重置`
- `Exit Indoor`

### 6.3 MapChromeUi

地图控件最少包括：

- `showRecenter`
- `showOrientationToggle`
- `showOverview`
- `showExitNavigation`

规则：

- 室外阶段可显示回到当前位置、车头/北向、全览、退出导航
- 室内阶段只保留合理的地图级控件，不再冒充室外导航控件

### 6.4 DebugPanelUi

Debug 面板保留，但默认折叠。

必须保留：

- `baseUrl / venue_id / floor_id / poi_id`
- provider 选择
- `Check Health`
- `Load Venue Meta`
- `Capture & Locate`
- `Request Route`
- `Simulate Low Confidence`
- `Simulate Provider Failure / Fallback`
- 日志

必须明确：

- `Capture & Locate`
- `Request Route`

属于保留链路，不是当前室内主流程。

## 7. NavState 到 UI 映射

### `OUTDOOR_IDLE`

- 顶部卡：显示当前室外配置和高德准备状态
- 底部区：`Use Current Location / Search POI / Prepare Outdoor Route`
- 地图控件：回到当前位置、车头/北向

### `OUTDOOR_READY`

- 顶部卡：显示室外就绪与入口摘要
- 底部区：`Prepare Outdoor Route / Start Outdoor Nav / Enter Venue`
- 地图控件：回到当前位置、车头/北向

### `OUTDOOR_ROUTE_READY`

- 顶部卡：显示路线摘要、剩余距离、预计时间
- 底部区：`Start Outdoor Nav / Enter Venue`
- 地图控件：回到当前位置、车头/北向、全览

### `OUTDOOR_NAVIGATING`

- 顶部卡：显示导航中摘要
- 底部区：`Continue Navigation / Overview / Exit Outdoor Navi / Enter Venue`
- 地图控件：回到当前位置、车头/北向、全览
- Debug：默认收起

### `ENTRY_HANDOFF_PENDING`

- 顶部卡：显示“等待进入室内”
- 底部区：`Enter Venue`
- 地图控件：保留回到当前位置

### `INDOOR_READY`

- 顶部卡：显示室内手动演示模式、当前楼层和首条提示
- 底部区：方向键、楼层键、重置、退出室内
- 地图：显示室内底图、当前位置、目标点和路线

### `INDOOR_ROUTE_READY`

- 顶部卡：显示当前提示、当前节点、目标节点、纠错提示或到达态
- 底部区：方向键、楼层键、重置、退出室内
- 地图：显示灰色已完成段和蓝色待执行段

### `ERROR`

- 顶部卡：显示错误摘要
- 底部区：保留可恢复主流程动作
- Debug：允许展开查看详情

### `ABORTED`

- 顶部卡：显示流程已中止
- 底部区：允许返回室外

## 8. 室内手动演示交互规格

### 8.1 方向键

- `上`：推进直行步骤
- `左`：推进左转步骤
- `右`：推进右转步骤
- `下`：回退一步

### 8.2 楼层键

- 点击 `B1 / F1 / F2` 等按钮，模拟跨层动作
- 若当前步骤期望的是某个楼层动作，按对才推进
- 楼层切换后室内底图和覆盖物必须同步切层

### 8.3 错误输入

- 错误按键不推进步骤
- 顶部卡片必须显示“当前应执行：...”提示
- 日志记录 `manual_demo_wrong_action`

### 8.4 重置

- 回到脚本第一步
- 重置当前楼层、当前点、已完成段和提示

## 9. 连续执行计划

### 阶段 0：事实口径冻结

- 明确当前室内主链路为 `manual_demo`
- 明确云端室内链路是保留能力

验收闸口：

- README、PROGRESS、状态机文档口径一致

### 阶段 1：地图主视觉收口

- 室外和室内地图宿主切换稳定
- 顶部卡、底部区与 Debug 面板层次稳定

验收闸口：

- 室外导航不回归
- 室内底图可见

### 阶段 2：室外主流程收口

- 室外准备、路线就绪、导航中三态按钮清晰
- 全览、继续导航、退出导航语义稳定

验收闸口：

- 不展开 Debug 也能完成室外演示

### 阶段 3：室内手动演示收口

- 方向键、楼层键、回退、重置和到达态稳定
- 室内地图叠加层稳定

验收闸口：

- 不依赖云端也能完成室内演示

### 阶段 4：文档与测试封板

- 文档全部统一到当前实现口径
- JVM 单测覆盖关键 UI / 脚本逻辑

验收闸口：

- `:app:assembleDebug`
- `:app:testDebugUnitTest`

## 10. 最终验收标准

以下条件满足时，当前 UI 可视为收口完成：

- 演示人员不展开 Debug 也能完成一次完整的室外到室内演示
- 室外真实高德能力不回归
- 室内默认进入 `manual_demo`
- 室内顶部卡和地图能同步展示步骤、楼层、纠错提示和到达态
- 云端室内链路保留但不冒充当前主流程

## 11. 风险与约束

- 高德 SDK 内建控件与自定义浮层可能产生遮挡
- 高德室内底图是否可见取决于目标商场是否被高德覆盖
- 当前 `PocUiState` 仍有部分字符串摘要字段，渲染层不能继续依赖解析这些文案决定业务状态
- 自动入场判断未实现，任何文案都不能暗示“当前已自动切到室内”
