# S20 采集到室内地图生产 SOP V0.1

更新日期：2026-04-29

## 1. 文档目的

本文档说明如何把 `S20` 采集到的原始数据，转成当前项目可用的室内地图包。

本文档回答 3 个问题：

1. 每个阶段需要使用什么工具
2. 每一步具体怎么操作
3. 最终要产出哪些文件，才能接入当前仓库的场馆地图包规格

本文档默认目标是当前项目的真实场馆数据生产链路：

- 固定 1-2 个商场 / 办公楼
- 为高德室内底图 + `manual_demo` 提供真实场馆底座
- 为后续恢复云端视觉重定位与室内路径规划提供场馆包和定位资产
- 输出符合当前仓库 `mapping/examples/venue-package-example/` 的数据结构

说明：

- 当前 Android 默认室内主演示链路已经切到“高德室内底图 + `manual_demo`”
- 本 SOP 负责真实场馆包、路网和定位资产生产，不决定当前手机端默认交互流程

本文档采用如下假设：

- `S20` 默认指 `SHARE SLAM S20`
- 外业采集软件默认使用 `SHARE Capture`
- 厂商后处理软件默认使用 `SHARE PointClouds Studio`
- 点云清洗和俯视底图生成推荐使用 `CloudCompare`
- 导航语义标注推荐使用 `QGIS`

说明：

- `S20` 可以很好地提供高精度点云和空间几何底图，但它不会一键生成本项目需要的 `POI + 路网 + 视觉定位关键帧`。
- 因为你在线定位依赖的是眼镜 / 手机的单目 RGB 图像，所以 `S20 点云地图` 之外，还必须补一层 `视觉定位地图`。
- 文中个别厂商菜单名和导出项是基于公开资料与常见内业流程做的工程化归纳；若你手头软件版本不同，以“产出同类数据”为准。

## 2. 核心结论

从当前项目角度看，S20 采集后的地图生产要拆成 3 层：

1. `几何底图`
   - 来源：S20 点云、轨迹、彩色点云
   - 用途：确定场馆尺度、楼层、走廊、扶梯、入口位置
2. `导航语义图`
   - 来源：人工标注
   - 用途：生成 `entrances / pois / connectors / route_graph`
3. `视觉定位图`
   - 来源：手机或眼镜补采的 RGB 图像 + 特征索引
   - 用途：生成 `cameras.json / keyframes.jsonl / descriptors.faiss / feature files`

一句话概括：

`S20 负责几何底图，人工标注负责导航语义，补采 RGB 负责视觉定位。`

## 3. 推荐工具栈

| 阶段 | 工具 | 作用 | 是否必需 | 主要产出 |
| --- | --- | --- | --- | --- |
| 外业采集 | `SHARE Capture` + `S20` | 采集原始点云 / 图像 / 轨迹 | 是 | 原始项目数据 |
| 厂商后处理 | `SHARE PointClouds Studio` | 后解算、导出点云 | 是 | `.las` / `.ply` / `.pcd` 点云 |
| 点云清洗 | `CloudCompare` | 切层、裁剪、去噪、生成俯视底图 | 是 | 每层底图、清洗点云 |
| 语义标注 | `QGIS` | 标注入口、店铺门口、扶梯、路网 | 是 | 标注图层、GeoJSON / DXF / CSV |
| RGB 补采 | 手机相机 / 眼镜相机 | 采集视觉定位参考图 | 是 | 参考图片集 |
| 视觉位姿恢复 | `COLMAP` | 关键帧稀疏重建、估计相机关系 | 可选但强烈推荐 | 稀疏模型、相机位姿 |
| 特征与检索 | Python 特征脚本 + `FAISS` | 生成局部特征和检索索引 | 是 | `descriptors.faiss`、特征文件 |
| 地图包装配 | JSON 编辑器 / 自定义转换脚本 | 组装场馆地图包 | 是 | `manifest.json` 等 |
| 校验与打包 | 仓库脚本 | 校验字段和引用关系 | 是 | 校验结果、zip 包 |

## 4. 输入输出总览

| 步骤 | 输入 | 工具 | 输出 |
| --- | --- | --- | --- |
| 1. 外业采集 | 场馆现场 | `S20 + SHARE Capture` | 原始采集工程 |
| 2. 厂商后处理 | 原始采集工程 | `SHARE PointClouds Studio` | 彩色点云、轨迹、图片素材 |
| 3. 点云清洗与切层 | 彩色点云 | `CloudCompare` | F1/F2/F3 清洗点云、俯视底图 |
| 4. 导航语义标注 | 俯视底图 | `QGIS` | 入口、POI、扶梯、路网图层 |
| 5. RGB 补采 | 主路线与关键点位 | 手机 / 眼镜 | 参考图片集 |
| 6. 视觉定位资产生产 | 参考图片集 | `COLMAP + 特征脚本 + FAISS` | `cameras.json`、`keyframes.jsonl`、特征索引 |
| 7. 地图包装配 | 上述全部产物 | JSON 编辑器 / 自定义脚本 | 场馆地图包目录 |
| 8. 校验发布 | 场馆地图包目录 | 仓库脚本 | 校验结果、发布 zip |

## 5. 第 1 步：S20 外业采集

### 5.1 采前准备

推荐工具：

- `S20`
- `SHARE Capture`
- 充满电的电池手柄 / 备用电源
- TF 卡
- 现场路线清单

具体操作：

1. 提前冻结要演示的场馆、楼层、入场口、目标店铺门口和主要路线。
2. 到现场先走一遍路线，确认灯光、门禁、扶梯是否正常，避免边扫边找路。
3. 在 `SHARE Capture` 中新建项目，项目名建议使用：
   - `venue_<场馆id>_<日期>_<楼层>`
4. 检查电量、TF 卡、镜头和保护罩。
5. 进入场馆前，把会移动的门、灯光、扶梯状态尽量固定下来。

### 5.2 扫描姿态与速度

推荐操作：

1. 扫描时把设备竖直放在身体前方，不要明显侧持或斜持。
2. 常规场景移动速度控制在约 `1m/s`。
3. 狭窄走廊、门口、扶梯口、转角位置，把速度降到 `0.5m/s` 以下。
4. 在关键点位短暂停留数秒，增加点云密度和颜色完整度。

### 5.3 初始化与路线规划

推荐操作：

1. 在特征丰富的位置初始化，不要在空旷区域、镜面区域或大量动态人流旁边初始化。
2. 初始化时尽量离墙至少 `1m`。
3. 扫描路线尽量形成闭环，不要只走单向长直线。
4. 优先走 `O` 形闭环；若场馆结构复杂，可补 `8` 字回环。
5. 起点和终点尽量重合，并保证 `10-20m` 重叠轨迹。

### 5.4 特殊场景处理

推荐操作：

1. 过门时缓慢进入，停 `3-5 秒`，避免门和人同时运动导致特征匹配失败。
2. 走廊转弯时放慢速度，让设备正面对着内侧转角。
3. 楼梯 / 扶梯转弯时，尽量让 LiDAR 朝向扶手内侧，避免身体遮挡。
4. 玻璃幕墙、镜面地坪、粉尘大、雨雪环境都容易引入误差，首版 Demo 尽量避开。

### 5.5 采集完成后的现场检查

具体操作：

1. 在 App 预览中检查是否有明显断裂、楼层漂移、角落缺点。
2. 核对是否覆盖了这些关键区域：
   - 入场口
   - 主通道
   - 扶梯上下口
   - 目标店铺门口
   - 每个关键转角
3. 若发现断层，现场立即补扫，而不是回去后再发现。

### 5.6 外业阶段交付物

外业结束后，至少要拿到：

- 原始采集项目
- 每层完整闭环扫描
- 主路线覆盖完整
- 关键场景补扫完整

## 6. 第 2 步：厂商后处理与几何底图生成

### 6.1 在 SHARE PointClouds Studio 中后处理

推荐工具：

- `SHARE PointClouds Studio`

具体操作：

1. 将原始项目拷贝到电脑，不要直接在 TF 卡上做长期处理。
2. 在 `SHARE PointClouds Studio` 中导入项目。
3. 执行后解算 / 点云重建。
4. 检查以下问题：
   - 是否存在明显漂移
   - 楼层是否串层
   - 入口、走廊、扶梯口是否连续
5. 导出后处理结果。

推荐导出项：

- 彩色点云：优先 `LAS`，备选 `PLY`
- 轨迹：若软件版本支持，导出轨迹 / 位姿表
- 图片素材：若软件版本支持，导出原始照片或相机帧

当前项目推荐保留以下目录：

```text
data/
└─ venue_demo_001/
   ├─ raw/
   ├─ processed/
   │  ├─ s20_global.las
   │  ├─ s20_global.ply
   │  └─ trajectory/
   └─ photos/
```

### 6.2 在 CloudCompare 中做清洗、裁剪、切层

推荐工具：

- `CloudCompare`

具体操作：

1. 打开 `CloudCompare`，导入 `s20_global.las` 或 `s20_global.ply`。
2. 先保存一份只读副本，再开始清洗。
3. 使用 `Edit > Segment` 删除明显无关区域：
   - 场馆外数据
   - 大片天花板噪声
   - 镜面反射导致的离群点
4. 若数据量较大，可先按楼层粗切：
   - 用 `Tools > Segmentation > Cross Section`
   - 或按高度范围分层
5. 对每层生成单独点云：
   - `F1_clean.las`
   - `F2_clean.las`
   - `F3_clean.las`

### 6.3 生成每层俯视底图

具体操作：

1. 在选中单层点云后，使用 `Tools > Projection > Rasterize`。
2. 选择俯视投影，生成 2.5D 栅格。
3. 根据点密度设置合适的 `grid step`，以能看清通道边界和扶梯口为准。
4. 导出为：
   - 栅格图像
   - 或 GeoTIFF / 普通图片
5. 每层保留一张可用于标注的底图：

```text
data/
└─ venue_demo_001/
   └─ processed/
      ├─ floorplans/
      │  ├─ F1_topdown.png
      │  ├─ F2_topdown.png
      │  └─ F3_topdown.png
      └─ floors/
         ├─ F1_clean.las
         ├─ F2_clean.las
         └─ F3_clean.las
```

### 6.4 坐标约定

当前项目推荐做法：

1. 不要把室内导航坐标直接做成经纬度。
2. 保留 `S20` 的局部米制坐标作为场馆坐标系。
3. `route_graph`、`pois`、`entrances.indoor_position`、`keyframes.venue_xy` 全部使用这一套局部坐标。
4. 与高德的衔接只发生在 `entrances.geo_position`。

## 7. 第 3 步：导航语义标注

### 7.1 推荐工具

推荐工具：

- `QGIS`

首版不推荐一上来做复杂自动语义识别，原因很简单：

- 你只有 1-2 个固定场馆
- 语义对象不多
- 首版更需要稳定可演示，而不是全自动

所以当前最稳的路线是：

`S20 生成几何底图 -> QGIS 手工标注导航语义 -> 输出 JSON`

### 7.2 在 QGIS 中建立标注工程

具体操作：

1. 新建空白项目。
2. 导入每层俯视底图。
3. 项目坐标统一使用同一套局部工程坐标，不要中途改投影。
4. 为每类对象分别建立图层。

推荐图层：

| 图层名 | 几何类型 | 用途 | 对应输出 |
| --- | --- | --- | --- |
| `entrances` | Point | 入场口 | `entrances.json` |
| `pois` | Point | 店铺门口、服务台等目标点 | `pois.json` |
| `connectors` | Point | 扶梯 / 跨层连接点锚点 | `connectors.json` |
| `route_nodes` | Point | 路网节点 | `route_graph.nodes` |
| `route_edges` | Line | 路网边 | `route_graph.edges` |

### 7.3 创建图层的方法

具体操作：

1. 在 QGIS 中选择 `Layer > Create Layer > New GeoPackage Layer`。
2. 为点图层选择 `Point`，为路线图层选择 `LineString`。
3. 给每个图层添加必填属性字段。
4. 图层建好后切到编辑模式，开始逐个落点 / 画线。

推荐字段如下：

#### `entrances`

| 字段名 | 类型 | 说明 |
| --- | --- | --- |
| `entrance_id` | Text | 唯一 ID |
| `entrance_name` | Text | 入口名称 |
| `venue_id` | Text | 场馆 ID |
| `floor_id` | Text | 对应室内楼层 |
| `status` | Text | `active` / `inactive` |

#### `pois`

| 字段名 | 类型 | 说明 |
| --- | --- | --- |
| `poi_id` | Text | 唯一 ID |
| `poi_type` | Text | 首版建议 `store_door` |
| `poi_name` | Text | POI 名称 |
| `venue_id` | Text | 场馆 ID |
| `floor_id` | Text | 楼层 ID |
| `route_node_id` | Text | 绑定路网节点 |
| `status` | Text | `active` |

#### `connectors`

| 字段名 | 类型 | 说明 |
| --- | --- | --- |
| `connector_id` | Text | 唯一 ID |
| `connector_type` | Text | 首版建议 `escalator` |
| `from_floor_id` | Text | 起始楼层 |
| `to_floor_id` | Text | 目标楼层 |
| `from_node_id` | Text | 起点节点 |
| `to_node_id` | Text | 终点节点 |
| `direction` | Text | `up` / `down` / `bidirectional` |
| `status` | Text | `active` |

#### `route_nodes`

| 字段名 | 类型 | 说明 |
| --- | --- | --- |
| `node_id` | Text | 节点 ID |
| `floor_id` | Text | 楼层 ID |
| `node_type` | Text | `walkway` / `entrance` / `poi_anchor` / `connector` |
| `ref_id` | Text | 可关联 entrance / poi / connector |

#### `route_edges`

| 字段名 | 类型 | 说明 |
| --- | --- | --- |
| `edge_id` | Text | 边 ID |
| `from_node_id` | Text | 起点节点 |
| `to_node_id` | Text | 终点节点 |
| `distance` | Decimal | 米 |
| `travel_mode` | Text | `walk` / `escalator` |
| `bidirectional` | Whole number | `1` / `0` |
| `cost_seconds` | Decimal | 通行耗时 |
| `status` | Text | `active` |

### 7.4 标注顺序

推荐顺序：

1. 先标 `route_nodes`
2. 再画 `route_edges`
3. 再把 `entrances / pois / connectors` 绑定到节点

具体操作：

1. 在每层主通道、转角、扶梯上下口、店铺门口放 `route_nodes`。
2. 用 `route_edges` 把节点连成可走路径。
3. 所有 `pois.route_node_id` 必须能在 `route_nodes` 中找到。
4. 所有 `connector.from_node_id / to_node_id` 必须能在 `route_nodes` 中找到。
5. `entrance` 至少需要一个室内节点和一个高德经纬度。

### 7.5 导出标注结果

具体操作：

1. 右键图层，选择 `Export > Save Features As...`。
2. 导出为 `GeoJSON`、`CSV` 或 `DXF`。
3. 首版建议保留 `GeoJSON` 作为中间产物，再手工或脚本转换为项目 JSON。

推荐保留目录：

```text
data/
└─ venue_demo_001/
   └─ annotations/
      ├─ entrances.geojson
      ├─ pois.geojson
      ├─ connectors.geojson
      ├─ route_nodes.geojson
      └─ route_edges.geojson
```

### 7.6 标注图层与最终 JSON 的对应关系

| 标注图层 / 中间文件 | 最终文件 | 写入方式 |
| --- | --- | --- |
| `entrances.geojson` | `entrances.json` | 逐条拷贝入口属性；几何坐标写入 `indoor_position` |
| `pois.geojson` | `pois.json` | 逐条拷贝店铺门口属性；几何坐标写入 `position` |
| `connectors.geojson` | `connectors.json` | 逐条拷贝扶梯属性；节点引用保持一致 |
| `route_nodes.geojson` | `route_graph.json` 的 `nodes` | 点坐标写入 `x / y` |
| `route_edges.geojson` | `route_graph.json` 的 `edges` | 线属性写入 `from_node_id / to_node_id / distance / travel_mode` |
| 入口经纬度清单 | `entrances.json` | 补写 `geo_position.lat / lng` |

首版推荐做法：

1. 先在 QGIS 中把图层画对。
2. 再根据图层导出的属性表，手工填到项目 JSON。
3. 场馆数量少时，手工方式最稳，也最容易排错。

## 8. 第 4 步：视觉定位地图生产

### 8.1 为什么 S20 点云还不够

因为当前项目在线定位链路是：

`眼镜/手机拍照 -> 云端视觉定位 -> 返回 floor + position`

所以云端真正要匹配的是 `RGB 图像特征`，不是激光点云本身。

这意味着：

- `S20` 负责提供高精几何参考
- 视觉定位仍然需要手机或眼镜补采 RGB 图像

### 8.2 RGB 补采推荐工具

推荐工具：

- 安卓手机相机
- 眼镜拍照能力（若链路稳定）
- `COLMAP`
- 特征提取脚本
- `FAISS`

### 8.3 采图规则

具体操作：

1. 按外卖员真实步行视角补采，不要只拍天花板或只拍店招。
2. 沿主演示路线每隔约 `1-2m` 拍一张。
3. 在以下位置必须加密采图：
   - 入场口
   - 每个转角
   - 扶梯上下口
   - 店铺门口
   - 光照变化明显区域
4. 每一层都要有覆盖，不能只拍目标店铺附近。
5. 图片命名必须有顺序，例如：

```text
F1_0001.jpg
F1_0002.jpg
F1_0003.jpg
```

### 8.4 P0 推荐做法：手工选关键帧 + 手工吸附到路网

这是首版 Demo 最推荐的路线。

具体操作：

1. 从补采图片中筛出清晰、不模糊、无遮挡的图片作为关键帧。
2. 每张关键帧人工填写：
   - `keyframe_id`
   - `floor_id`
   - `venue_xy`
   - `heading`
   - `route_edge_id`
   - `image_ref`
3. `venue_xy` 不要求毫米级，只要稳定吸附到正确通道和楼层即可。
4. `route_edge_id` 优先绑定到当前所处通道边。
5. 所有图片路径统一放到：

```text
localization/images/
```

这种方式的特点：

- 人工成本高一点
- 但最适合 1-2 个场馆的 Demo
- 便于快速验证视觉定位是否可用

### 8.5 增强做法：用 COLMAP 做稀疏重建

若你想减少关键帧人工位姿估计，可以加上 `COLMAP`。

推荐操作：

1. 把补采图片放到一个工作目录。
2. 使用 `Sequential Matching`，因为采图是按路线连续采的。
3. 在 COLMAP 中完成：
   - 导入图片
   - 特征提取
   - 顺序匹配
   - 稀疏重建
4. 导出相机、图像、点云结果。
5. 将 COLMAP 结果与 S20 俯视底图人工对齐，把关键帧吸附到场馆局部坐标系。

说明：

- COLMAP 更适合做 `关键帧之间的相对关系恢复`
- 它不能自动知道你的 `route_graph`、`入口`、`店铺门口`
- 所以最终仍然要做一次 `与导航语义图对齐`

### 8.6 生成项目所需的视觉定位资产

当前项目至少需要这些文件：

- `localization/cameras.json`
- `localization/keyframes.jsonl`
- `localization/retrieval/descriptors.faiss`
- `localization/features/superpoint/features_index.json`
- `localization/features/superpoint/*.npz`

推荐操作：

1. 先整理相机参数，生成 `cameras.json`。
2. 再整理关键帧清单，生成 `keyframes.jsonl`。
3. 对每张关键帧提取局部特征，保存到 `features/superpoint/`。
4. 对整库关键帧生成全局检索描述子，并建立 `FAISS` 索引。
5. 把每个关键帧的 `image_ref / feature_ref` 写回 `keyframes.jsonl`。

说明：

- 当前仓库已经定义了 `地图包格式` 和 `校验脚本`
- 但还没有提供自动生成 `FAISS` 索引和 `superpoint` 特征的脚本
- 因此这一步当前仍属于 `算法侧 / 数据生产侧` 的内业流程

## 9. 第 5 步：组装成当前项目的场馆地图包

### 9.1 必备文件

最终至少要组装出这些文件：

```text
venue-package/
├─ manifest.json
├─ venue.json
├─ floors.json
├─ pois.json
├─ entrances.json
├─ connectors.json
├─ route_graph.json
└─ localization/
   ├─ cameras.json
   ├─ keyframes.jsonl
   ├─ retrieval/
   │  └─ descriptors.faiss
   └─ features/
      └─ superpoint/
         ├─ features_index.json
         └─ *.npz
```

参考：

- `mapping/examples/venue-package-example/`
- `contracts/venue-package/venue-map-package-spec-v0.1.md`

### 9.2 组装原则

具体操作：

1. `venue.json` 只写场馆基础信息和默认策略。
2. `floors.json` 定义所有楼层。
3. `pois.json` 只放导航要用的点位，首版优先 `store_door`。
4. `entrances.json` 要同时写：
   - `geo_position`
   - `indoor_position`
5. `route_graph.json` 中：
   - `nodes` 表示路网节点
   - `edges` 表示可行走路径
6. `keyframes.jsonl` 中每条记录必须至少有：
   - `keyframe_id`
   - `floor_id`
   - `venue_xy`
7. `manifest.json` 中 `files` 声明的必需文件，磁盘上必须真的存在。

### 9.3 真实场馆装包前准备

在真实场馆素材到位前，先按以下文档冻结模板、命名和装配清单：

- `mapping/docs/real-venue-package-prep-v0.1.md`
- `mapping/docs/real-venue-production-runbook-v0.1.md`

该文档已经明确：

- 真实场馆最小可演示包必须包含哪些文件。
- `venue_id / floor_id / poi_id / entrance_id / connector_id / route_node_id` 如何命名。
- 真实场馆包需要人工补齐哪些字段、素材、图像和 keyframe。
- 首版 Demo 只标注哪些楼层、入口、POI、扶梯和主路线。
- 真实场馆包进入 Cloud 前必须完成哪些 QA 检查。

真实数据到位后的第一步不是重新讨论格式，而是复制该准备清单并逐项填值。

## 10. 第 6 步：校验与发布

### 10.1 校验

仓库已提供脚本：

- `mapping/tools/validate_venue_package.py`

具体操作：

```bash
python mapping/tools/validate_venue_package.py mapping/examples/venue-package-example --json
```

你自己的场馆包使用方式相同：

```bash
python mapping/tools/validate_venue_package.py <你的场馆包目录> --json
```

脚本会检查：

- 必需文件是否存在
- JSON / JSONL 是否可解析
- 关键字段是否齐全
- `poi / connector / entrance / route_graph` 的引用关系是否正确
- `keyframes.jsonl` 的 `image_ref / feature_ref` 是否存在

### 10.2 发布打包

仓库已提供脚本：

- `mapping/tools/publish_venue_package.py`

具体操作：

```bash
python mapping/tools/publish_venue_package.py <你的场馆包目录> --output-dir dist
```

发布脚本会：

1. 先调用校验逻辑
2. 校验通过后打成 zip
3. 输出 package 摘要
4. 输出 zip 路径

## 11. 首版最推荐的落地方案

对你当前项目，最稳的实际做法是：

1. 用 `S20 + SHARE Capture` 采完整场馆几何底图。
2. 用 `SHARE PointClouds Studio` 导出彩色点云。
3. 用 `CloudCompare` 做楼层切分和俯视底图。
4. 用 `QGIS` 手工标 `entrances / pois / connectors / route_graph`。
5. 用手机按真实送餐路线补采 RGB 图。
6. 手工筛关键帧，并吸附到路网坐标。
7. 由算法侧生成 `superpoint` 特征和 `FAISS` 索引。
8. 按本仓库格式组装地图包并运行校验脚本。

这样做的优点是：

- 工具成熟
- 人工可控
- 最适合 1-2 个场馆 Demo
- 与当前仓库规格完全一致

## 12. 与当前仓库文档的关系

本 SOP 主要解决“怎么做”。

相关文档分别解决：

- `contracts/venue-package/venue-map-package-spec-v0.1.md`：地图包字段定义
- `mapping/docs/real-venue-package-prep-v0.1.md`：真实场馆最小包模板、命名和交付清单
- `mapping/docs/real-venue-production-runbook-v0.1.md`：真实生产演练、QA 检查和发布门禁
- `docs/development-spec-v0.1.md`：开发阶段约束
- `cloud/docs/cloud-localization-api-draft-v0.1.md`：云端接口语义
- `mapping/examples/venue-package-example/`：最小可用样例
