# Android 高德室内底图接入方案 v0.1

更新时间：2026-05-05

## 1. 目标

在现有 Android App 基础上增加高德官方室内底图展示能力，用于：

- 在 `Enter Venue` 后给室内阶段提供真实商场底图与楼层切换能力
- 提升室内定位结果、目标店铺位置、室内路径结果的可解释性
- 保持当前“高德室外导航 + 自建云端室内定位/路径规划”的总体方案不变

本方案只引入“室内底图展示”，不把室内定位和室内路径规划改成完全依赖高德。

当前需求口径下，这份文档只负责“室内底图接入”本身；当前室内主演示链路已经切换为手动模式，详见 [manual-indoor-demo-mode-v0.1.md](./manual-indoor-demo-mode-v0.1.md)。

## 2. 当前事实与假设

### 2.1 当前 App 事实

- 室外主地图当前由 `AMapNaviView` 承载。
- 室外导航、POI 搜索、当前位置、骑行/步行、回到当前位置、车头/北向切换、全览和外部高德兜底已经存在。
- 室外到室内的切换当前仍是手动 `Enter Venue`。
- 室内定位和室内路径规划当前仍以云端接口和样例场馆数据为准。

### 2.2 接入假设

- 目标商场已经被高德官方室内地图覆盖。
- 项目接受“部分场馆有高德室内底图，部分场馆没有”的现实。
- 高德室内底图只作为底图显示层，不作为室内定位真值源。
- 自建 `venue_id / floor_id / poi_id` 体系继续保留，不能被高德命名体系替代。

## 3. 官方能力与边界

高德官方公开文档中，地图 SDK 已提供以下室内能力：

- `AMap.showIndoorMap(true)`：开启室内底图显示
- `UiSettings.setIndoorSwitchEnabled(true)`：显示楼层切换控件
- `AMap.setOnIndoorBuildingActiveListener(...)`：监听当前激活的室内建筑
- `AMap.setIndoorBuildingInfo(...)`：切换当前显示楼层
- `IndoorBuildingInfo`：包含 `poiid`、当前楼层、楼层名称数组、楼层索引数组

这说明“显示官方室内底图 + 显示楼层控件 + 切楼层”是公开支持的。

但当前公开能力也有明确边界：

- 没有证据表明可把你自建点云地图直接上传给高德并变成官方室内底图
- 是否有室内底图，取决于该商场是否被高德覆盖
- 高德室内底图的楼层命名、商铺命名和你的内部场馆包字段不一定一致
- 高德室内底图不能替代你的云端视觉重定位与自定义路径规划

## 4. 设计结论

本项目不建议在现有 `AMapNaviView` 上直接“硬塞室内底图主流程”，而是采用“双地图承载，按状态切换”的方案：

- 室外阶段：继续使用现有 `AMapNaviView`
- 室内阶段：切换到普通 `MapView`
- `MapView` 内部的 `AMap` 负责显示高德室内底图、楼层控件和室内覆盖物

这样做的原因很直接：

- `AMapNaviView` 是室外导航视图，当前已承载高德导航 UI 与定制浮层
- 室内底图更适合基于普通 `AMap` 做楼层控制、覆盖物绘制和状态监听
- 室外不改主链路，能把风险隔离在室内阶段

## 5. 目标架构

### 5.1 地图承载结构

页面地图区域改成两个宿主容器：

- `amapNaviContainer`：继续承载室外 `AMapNaviView`
- `amapIndoorMapContainer`：新增，承载室内 `MapView`

显示规则：

- `OUTDOOR_*`、`ENTRY_HANDOFF_PENDING`：显示 `AMapNaviView`
- `INDOOR_*`：隐藏 `AMapNaviView`，显示 `MapView`
- `ERROR`、`ABORTED`：保持最近一次可解释的地图宿主，不强制切回

### 5.2 新增模块

- `IndoorBasemapController`
  - 负责 `MapView` 生命周期
  - 负责初始化 `AMap`
  - 开关室内底图与楼层控件
  - 监听当前激活建筑和楼层变化
  - 对外暴露切楼层、聚焦建筑、绘制定位点、绘制目标点、绘制路径

- `IndoorBasemapState`
  - 记录当前室内底图可用状态
  - 记录当前激活 `poiid`
  - 记录楼层列表、当前楼层、是否命中目标建筑

- `IndoorVenueBinding`
  - 负责把内部场馆标识映射到高德室内建筑标识
  - 负责楼层命名映射

## 6. 数据模型

### 6.1 新增场馆绑定字段

建议给每个支持高德室内底图的场馆增加一组绑定信息：

```json
{
  "venue_id": "venue_bj_wudaokou_shopping_center_demo",
  "amap_indoor": {
    "enabled": true,
    "poiid": "B0FFGXXXXXXXXX",
    "center_gcj02": {
      "lat": 39.991583,
      "lng": 116.338965
    },
    "default_floor_name": "1F",
    "floor_alias": {
      "F1": "1F",
      "F2": "2F",
      "B1": "B1"
    }
  }
}
```

字段含义：

- `enabled`：该场馆是否启用高德室内底图
- `poiid`：高德室内建筑唯一标识
- `center_gcj02`：用于聚焦该建筑的中心点
- `default_floor_name`：进入室内后的默认显示楼层
- `floor_alias`：内部 `floor_id` 到高德楼层名的映射

### 6.2 字段来源

首版推荐优先放在 App 侧可配置或本地静态配置中，不阻塞云端接口。

推荐优先级：

1. App 本地静态配置
2. Debug 面板可编辑配置
3. 后续再并入 `venue meta` 返回

这样可以先把显示链路跑通，再决定是否让云端统一下发。

## 7. UI 行为

### 7.1 室外到室内切换

用户点击 `Enter Venue` 后：

1. 停止把 `AMapNaviView` 作为主视觉
2. 显示 `MapView`
3. 初始化室内底图模式
4. 聚焦到绑定的 `center_gcj02`
5. 打开室内底图与楼层切换控件
6. 尝试切到 `default_floor_name`

如果未命中高德室内建筑：

- 顶部状态卡显示“高德室内底图不可用”
- 保留当前室内定位/路径主流程
- 回退到当前已有的最小室内预览能力

### 7.2 楼层策略

楼层切换来源分两类：

- 系统驱动：当云端定位结果返回 `floor_id` 时，若能映射到高德楼层名，则自动切换
- 用户驱动：用户可通过高德楼层控件手动切换

自动切楼规则：

- 只在 `INDOOR_READY`、`INDOOR_ROUTE_READY`、`INDOOR_LOW_CONFIDENCE` 执行
- `INDOOR_CAPTURING`、`INDOOR_LOCATING`、`INDOOR_ROUTING` 不主动改楼层，避免抖动

### 7.3 覆盖物策略

高德室内底图只负责背景，业务结果仍由 App 自己绘制：

- 当前定位点：绘制为自定义 marker
- 目标店铺点：绘制为自定义 marker
- 室内路径线：按云端返回结果绘制 polyline
- 低置信度态：顶部卡片和定位点样式同时提示

如果高德底图里恰好有官方商铺 POI，可作为辅助点击信息，但不替代内部 `poi_id`。

## 8. 状态机影响

现有 `NavState` 不需要新增状态，只补充地图宿主切换规则：

- `OUTDOOR_IDLE`
- `OUTDOOR_READY`
- `OUTDOOR_ROUTE_READY`
- `OUTDOOR_NAVIGATING`
- `ENTRY_HANDOFF_PENDING`

以上状态使用室外 `AMapNaviView`。

- `INDOOR_READY`
- `INDOOR_CAPTURING`
- `INDOOR_LOCATING`
- `INDOOR_LOW_CONFIDENCE`
- `INDOOR_ROUTING`
- `INDOOR_ROUTE_READY`

以上状态使用室内 `MapView`。

`PocUiState` 需增加一个室内底图摘要模型，至少包含：

```kotlin
data class IndoorBasemapUiModel(
    val enabled: Boolean = false,
    val available: Boolean = false,
    val expectedPoiId: String? = null,
    val activePoiId: String? = null,
    val activeFloorName: String? = null,
    val availableFloorNames: List<String> = emptyList(),
    val mismatchWarning: String? = null,
)
```

## 9. 实施顺序

### 阶段 1：接入地图宿主

- 新增 `MapView` 容器
- 新增 `IndoorBasemapController`
- 跑通 `MapView` 生命周期
- 室内阶段实现地图宿主切换

完成标准：

- 不影响现有室外导航链路
- 进入室内后可稳定显示普通高德地图

### 阶段 2：打开室内底图能力

- 打开 `showIndoorMap`
- 打开楼层切换控件
- 接入室内建筑激活监听
- 聚焦到目标场馆建筑

完成标准：

- 对有覆盖的商场，进入室内后能显示楼层条和室内底图

### 阶段 3：接入楼层与场馆绑定

- 新增 `poiid` 与 `floor_alias`
- 实现按内部 `floor_id` 切换高德楼层
- 在顶部卡片展示“当前楼层 / 底图是否可用 / 是否命中目标建筑”

完成标准：

- 云端返回 `floor_id` 后，App 能同步切换到对应高德楼层

### 阶段 4：叠加业务覆盖物

- 绘制当前定位点
- 绘制目标点
- 绘制室内路径
- 处理低置信度与路径不可用态

完成标准：

- 室内阶段能在高德底图上展示你自己的定位和路径结果

## 10. 验证口径

### 10.1 功能验证

- 进入室内后，地图宿主从 `AMapNaviView` 切到 `MapView`
- 支持场馆能显示高德室内底图与楼层控件
- 不支持场馆能优雅降级，不阻断现有室内流程
- 云端返回 `floor_id` 时能切换到对应高德楼层
- 定位点、目标点、路径线能叠加到底图上

### 10.2 回归验证

- 室外骑行/步行算路不回归
- `Use Current Location`、`Search POI`、`Prepare Outdoor Route`、`Start Outdoor Nav` 不回归
- `Enter Venue` / `Exit Indoor` 不回归
- `phone_camera_fallback` 不回归
- Debug 面板与持久化配置不回归

## 11. 风险与约束

- 高德是否覆盖目标商场，不由 App 控制
- 高德楼层名和你的内部 `floor_id` 可能不一致，必须做映射
- 高德室内底图坐标系仍是 GCJ-02，经纬度锚点必须统一
- 当前云端路径结果若不是经纬度几何，而是内部平面坐标，无法直接叠加到底图上，需额外提供坐标映射
- 若只知道 `venue_id` 而不知道高德 `poiid`，进入室内时命中建筑的稳定性会下降

## 12. 本方案的最小版本

如果只追求尽快落地，建议先做这个最小版本：

- 室内阶段新增 `MapView`
- 只接 `showIndoorMap`、楼层控件、`poiid` 绑定
- 先不画室内路径
- 先只画“当前定位点 + 目标点”
- 场馆无高德覆盖时继续使用现有最小室内预览

这个版本代码改动小，且最容易验证“高德室内底图是否对演示有增益”。

## 13. 参考资料

- [高德 Android/Harmony Map AMap 室内地图能力](https://a.amap.com/lbs/static/unzip/AMap_HarmonyOS_API_3DMap_Doc/com/amap/api/maps/AMap.html)
- [高德 Android Map UiSettings 室内楼层控件](https://a.amap.com/lbs/static/unzip/Android_Map_Doc/3D/com/amap/api/maps/UiSettings.html)
- [高德 IndoorBuildingInfo](https://a.amap.com/lbs/static/unzip/AMap_HarmonyOS_API_3DMap_Doc/com/amap/api/maps/model/IndoorBuildingInfo.html)
