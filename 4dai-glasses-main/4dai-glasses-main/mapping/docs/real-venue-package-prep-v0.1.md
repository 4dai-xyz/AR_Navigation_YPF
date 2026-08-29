# 真实场馆最小包准备说明 V0.1

更新时间：2026-04-30

## 1. 文档目的

本文档冻结“不依赖真实采图结果”的建图准备项，目标是在拿到真实场馆、入口、目标店铺、主路线、S20 数据和 RGB 补采素材后，可以直接装配真实场馆包并运行校验、发布和离线评估。

本文档不替代 `contracts/venue-package/venue-map-package-spec-v0.1.md`，只把真实场馆首版 Demo 的最小交付模板、命名规范、装配清单和人工待提供清单收口到建图子项目内。

## 2. 当前冻结与待补内容

### 2.1 已冻结

- 真实场馆最小可演示包的目录结构和必备文件。
- `venue_id / floor_id / poi_id / entrance_id / connector_id / route_node_id / edge_id / keyframe_id` 的命名规则。
- 首版只围绕主演示路线建设数据，不追求整馆完备。
- 校验和发布工具使用 `validate_venue_package.py` 与 `publish_venue_package.py`。
- 离线评估输入采用当前 `evaluate_offline.py` 支持的 fixture 结构。
- 真实生产演练与 QA 使用 `mapping/docs/real-venue-production-runbook-v0.1.md`。

### 2.2 当前样例、规格与工具一致性

当前一致性基线：

- 样例包包含 `validate_venue_package.py` 要求的全部必备文件。
- 样例包 `manifest.files` 声明 10 个业务必备文件，`manifest.json` 本身作为包入口存在但不在 `manifest.files` 中声明。
- 样例包中的 `localization/images/` 已与 `keyframes.jsonl.image_ref` 对齐。
- 样例包中的 `pois[].arrival_radius_m` 是可选 Demo 字段，已在场馆包规格中收口。
- 发布脚本当前输出 `<venue_id>_<package_version>.zip`；如需按 `venue_code` 命名，发布时使用 `--archive-name`。

### 2.3 等真实数据后补齐

- 真实场馆名称、地址、城市、地理中心和边界。
- 真实楼层范围、楼层高度和楼层顺序。
- 目标店铺门口 POI、最优入场口、扶梯或跨层方式。
- S20 点云、楼层俯视底图、场馆局部坐标原点和比例。
- RGB 补采图片、筛选后的 keyframe、相机参数、特征文件和检索索引。
- `manifest.checksums` 的最终值和发布产物归档信息。

## 3. 真实场馆最小可演示包模板

### 3.1 最小目录

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
   ├─ images/
   │  └─ kf_<floor>_<nnnn>.jpg
   ├─ retrieval/
   │  └─ descriptors.faiss
   └─ features/
      └─ superpoint/
         ├─ features_index.json
         └─ kf_<floor>_<nnnn>.npz
```

说明：

- `assets/`、`coverage_report.json`、`sfm/colmap/` 可后补，首版真实 Demo 不作为必需项。
- `localization/images/` 不需要在 `manifest.files` 中逐图声明，但 `keyframes.jsonl.image_ref` 引用的图片必须真实存在。
- `localization/features/superpoint/*.npz` 不需要逐个写入 `manifest.files`，但 `features_index.json` 和 `keyframes.jsonl.feature_ref` 引用的特征文件必须真实存在。

### 3.2 `manifest.json` 最小要求

必须声明以下 10 个必备文件：

| name | path | type | required |
| --- | --- | --- | --- |
| `venue` | `venue.json` | `json` | `true` |
| `floors` | `floors.json` | `json` | `true` |
| `pois` | `pois.json` | `json` | `true` |
| `entrances` | `entrances.json` | `json` | `true` |
| `connectors` | `connectors.json` | `json` | `true` |
| `route_graph` | `route_graph.json` | `json` | `true` |
| `cameras` | `localization/cameras.json` | `json` | `true` |
| `keyframes` | `localization/keyframes.jsonl` | `jsonl` | `true` |
| `descriptors` | `localization/retrieval/descriptors.faiss` | `faiss` | `true` |
| `features_index` | `localization/features/superpoint/features_index.json` | `json` | `true` |

冻结规则：

- 装配早期可以暂时留空 `checksums`，但校验会给 warning。
- 最终发布前应把 `publish_venue_package.py` 输出的 `*.checksums.json` 回填到 `manifest.checksums`，再用 `--strict-warnings` 验证。
- `default_floor_id` 必须能在 `floors[].floor_id` 中找到。

### 3.3 顶层 JSON 最小内容

| 文件 | 最小内容 | 数据来源 |
| --- | --- | --- |
| `venue.json` | `venue_id`、`venue_name`、`venue_type`，建议补 `city / address / geo_center / bounding_box` | 人工确认场馆信息 |
| `floors.json` | 主演示路线涉及的所有楼层，每层有 `floor_id / floor_name / floor_index` | 场馆资料与 S20 切层 |
| `pois.json` | 目标店铺门口 POI，每个 POI 有 `poi_id / poi_type / poi_name / venue_id / floor_id / position / route_node_id` | 人工标注 |
| `entrances.json` | 最优入场口，至少一个入口有 `geo_position / indoor_position / route_node_id` | 室外导航点与室内标注 |
| `connectors.json` | 主路线跨层点，至少覆盖真实会走的扶梯或跨层方式 | 人工标注 |
| `route_graph.json` | 主路线可规划节点和边，能从入口规划到目标 POI | QGIS 或人工整理 |

### 3.4 `localization/` 最小内容

| 文件或目录 | 最小内容 | 备注 |
| --- | --- | --- |
| `cameras.json` | 至少一个 `intrinsics_id`，包含 `model / width / height / fx / fy / cx / cy` | 先用补采设备近似内参，后续可更新 |
| `keyframes.jsonl` | 每行一个 keyframe，至少有 `keyframe_id / floor_id / venue_xy` | 建议补 `route_edge_id / image_ref / feature_ref` |
| `images/` | 所有 `image_ref` 指向的 RGB 关键帧图片 | 图片需来自真实补采 |
| `retrieval/descriptors.faiss` | 全局检索索引 | 当前由算法侧或数据生产侧生成 |
| `features/superpoint/features_index.json` | `feature_files[]`，每项含 `keyframe_id / path` | `keyframe_id` 必须存在于 `keyframes.jsonl` |
| `features/superpoint/*.npz` | 所有局部特征文件 | `feature_ref` 和 `features_index.path` 必须能找到 |

## 4. 命名规范

### 4.1 通用规则

- 除 `floor_id` 外，所有 ID 使用小写英文、数字和下划线。
- 不使用中文、空格、连字符或临时编号如 `test1`。
- ID 发布后保持稳定；位置修正不改 ID，语义对象变化才新建 ID。
- 同类对象编号从 `01` 或 `0001` 开始，便于人工排序。
- `slug` 使用简短拼音或英文，不追求完整商户名。

### 4.2 ID 规则

| 对象 | 规则 | 示例 |
| --- | --- | --- |
| `venue_id` | `venue_<city>_<site_slug>_<nnn>` | `venue_sh_demo_mall_001` |
| `venue_code` | `<city>_<site_slug>_<nnn>` | `sh_demo_mall_001` |
| `package_id` | `pkg_<venue_code>_<package_version>` | `pkg_sh_demo_mall_001_0.1.0` |
| `floor_id` | 地上 `F1 / F2`，地下 `B1 / B2` | `F1` |
| `poi_id` | `poi_<floor>_<store_slug>_door` | `poi_f2_luckin_door` |
| `entrance_id` | `entrance_<floor>_<direction_or_gate_slug>` | `entrance_f1_west_gate` |
| `connector_id` | `connector_<type>_<from_floor>_<to_floor>_<nn>` | `connector_escalator_f1_f2_01` |
| `route_node_id` | `node_<floor>_<role>_<slug_or_nnn>` | `node_f1_entry_west_gate` |
| `edge_id` | `edge_<floor_or_cross>_<from_slug>_to_<to_slug>` | `edge_f1_entry_to_escalator_01` |
| `keyframe_id` | `kf_<floor>_<nnnn>` | `kf_f1_0001` |

说明：

- 表格中的 `<floor>` 使用小写形式，如 `f1 / b1`。
- `route_node_id` 的 `role` 建议只用 `entry / poi / connector / walk / turn`。
- 目标店铺门口节点建议用 `node_<floor>_poi_<store_slug>`，并在 `pois[].route_node_id` 中引用。
- 扶梯上下口节点建议分别建立，例如 `node_f1_connector_escalator_01_up` 和 `node_f2_connector_escalator_01_down`。

### 4.3 keyframe 文件命名

| 文件 | 规则 | 示例 |
| --- | --- | --- |
| RGB 图片 | `localization/images/<keyframe_id>.jpg` | `localization/images/kf_f1_0001.jpg` |
| 局部特征 | `localization/features/superpoint/<keyframe_id>.npz` | `localization/features/superpoint/kf_f1_0001.npz` |
| `image_ref` | 相对 `localization/` 的路径 | `images/kf_f1_0001.jpg` |
| `feature_ref` | 相对 `localization/` 的路径 | `features/superpoint/kf_f1_0001.npz` |

## 5. 真实场馆包装配清单

### 5.1 装配前决策清单

| 项目 | 必填 | 填写内容 |
| --- | --- | --- |
| 场馆基础信息 | 是 | 场馆名、城市、地址、场馆类型 |
| Demo 楼层范围 | 是 | 只列主路线涉及楼层 |
| 目标店铺 | 是 | 店铺名、门口位置、所在楼层 |
| 最优入场口 | 是 | 室外导航落点、室内入口点 |
| 跨层方式 | 视路线而定 | 扶梯、电梯或楼梯；首版优先扶梯 |
| 主路线 | 是 | 入场口到目标 POI 的节点顺序 |
| 坐标原点 | 是 | 场馆局部坐标原点、方向、单位 |
| RGB 补采策略 | 是 | 采图设备、路线、间距、补采时间段 |

### 5.2 文件装配清单

| 步骤 | 输出文件 | 完成标准 |
| --- | --- | --- |
| 1 | `venue.json` | 场馆 ID 和名称稳定，`venue_id` 与 `manifest.venue_id` 一致 |
| 2 | `floors.json` | 主路线涉及楼层全部存在，`floor_index` 排序正确 |
| 3 | `entrances.json` | 最优入口有经纬度、室内坐标和入口节点 |
| 4 | `pois.json` | 目标店铺门口 POI 有室内坐标和 `route_node_id` |
| 5 | `connectors.json` | 跨层点有上下楼层、上下节点和跨层边 |
| 6 | `route_graph.json` | 入口节点到目标节点可连通，跨层边方向正确 |
| 7 | `localization/cameras.json` | 补采设备内参存在并被 keyframe 引用 |
| 8 | `localization/keyframes.jsonl` | 每个 keyframe 有楼层、坐标、图片引用和特征引用 |
| 9 | `localization/images/` | 所有 `image_ref` 文件存在 |
| 10 | `localization/features/superpoint/` | 所有 `feature_ref` 和 `features_index.path` 文件存在 |
| 11 | `localization/retrieval/descriptors.faiss` | 检索索引存在，能随包发布 |
| 12 | `manifest.json` | 必备文件声明完整，最终 checksums 已回填 |

## 6. 真实场馆最小标注清单

### 6.1 Demo 范围模板

| 字段 | 填写规则 |
| --- | --- |
| `demo_scope_id` | `scope_<venue_code>_main_route_01` |
| `target_floors` | 主路线涉及楼层，如 `F1,F2` |
| `primary_entrance_id` | 最推荐入口 ID |
| `target_poi_ids` | 首版目标店铺门口 POI 列表 |
| `connector_ids` | 主路线跨层连接点列表 |
| `main_route_node_ids` | 从入口到目标 POI 的节点顺序 |
| `backup_notes` | 不纳入首版但现场需要知道的风险点 |

### 6.2 楼层标注表

| `floor_id` | `floor_name` | `floor_index` | `z` | 纳入原因 | 确认人 |
| --- | --- | --- | --- | --- | --- |
| 待填 | 待填 | 待填 | 待填 | 入口层 / 目标层 / 过渡层 | 待填 |

### 6.3 入口标注表

| `entrance_id` | `entrance_name` | `floor_id` | `geo_position.lat/lng` | `indoor_position.x/y` | `route_node_id` | 现场照片 |
| --- | --- | --- | --- | --- | --- | --- |
| 待填 | 待填 | 待填 | 待填 | 待填 | 待填 | 待填 |

### 6.4 POI 标注表

| `poi_id` | `poi_name` | `poi_type` | `floor_id` | `position.x/y` | `route_node_id` | `arrival_radius_m` | 现场照片 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 待填 | 待填 | `store_door` | 待填 | 待填 | 待填 | `3.0` | 待填 |

说明：`arrival_radius_m` 当前是样例包中的可选 Demo 字段，不作为 `validate_venue_package.py` 必填项；真实包可以保留，供 App 或云端按需消费。

### 6.5 扶梯与跨层标注表

| `connector_id` | `connector_type` | `from_floor_id` | `to_floor_id` | `from_node_id` | `to_node_id` | `edge_id` | `direction` |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 待填 | `escalator` | 待填 | 待填 | 待填 | 待填 | 待填 | `up/down/bidirectional` |

### 6.6 路网标注表

| 类型 | 必填字段 | 标注要求 |
| --- | --- | --- |
| `route_graph.nodes` | `node_id / floor_id / x / y / node_type` | 覆盖入口、转角、扶梯上下口、目标 POI 门口 |
| `route_graph.edges` | `edge_id / from_node_id / to_node_id / distance / travel_mode / bidirectional` | 保证主路线可连通；跨层边 `travel_mode` 用 `escalator` |

### 6.7 keyframe 标注表

| `keyframe_id` | `floor_id` | `venue_xy.x/y` | `heading` | `route_edge_id` | `image_ref` | `feature_ref` | 质量 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 待填 | 待填 | 待填 | 待填 | 待填 | 待填 | 待填 | 清晰 / 模糊 / 遮挡 |

最小采样要求：

- 入场口至少 2 张 keyframe。
- 每个转角至少 1-2 张 keyframe。
- 每个扶梯上下口各至少 2 张 keyframe。
- 目标店铺门口至少 2 张 keyframe。
- 连续走廊按约 `1-2m` 间隔补采，最终可筛出较稀疏 keyframe。

## 7. 校验与发布收口

### 7.1 装配期校验

```bash
python mapping/tools/validate_venue_package.py <真实场馆包目录> --json
```

装配期允许的情况：

- `manifest.checksums is empty` warning 可以暂时存在。
- 定位资产可用占位文件，但所有被引用路径必须存在。

必须立即修复的报错：

| 报错类型 | 处理方式 |
| --- | --- |
| `missing required file` | 补齐必备文件或修正目录 |
| `unknown floor_id` | 在 `floors.json` 补楼层，或修正对象楼层 |
| `unknown route_node_id` | 在 `route_graph.nodes` 补节点，或修正引用 |
| `no route_graph edge found` | 补对应边，保证 connector 两端节点连通 |
| `referenced file not found` | 补图片/特征文件，或修正 `image_ref / feature_ref` |
| `manifest.checksums mismatch` | 内容变更后重新生成并回填 checksums |

### 7.2 发布前校验

```bash
python mapping/tools/validate_venue_package.py <真实场馆包目录> --json
python mapping/tools/publish_venue_package.py <真实场馆包目录> --output-dir dist --strict-warnings
```

发布前必须满足：

- `ok=true`
- `errors=[]`
- `warnings=[]`
- 发布产物包含 `*.zip`、`*.report.json`、`*.checksums.json`

说明：当前发布脚本实际输出名为 `<venue_id>_<package_version>.zip`；如需按 `venue_code` 命名，可显式传入 `--archive-name`。

## 8. 离线重定位评估输入模板

模板分为两类：

- Mapping baseline fixture：`mapping/algorithms/relocalization/templates/real_venue_eval_fixture_template.json`
- Cloud 图片评估查询：`mapping/algorithms/relocalization/templates/cloud_image_eval_queries_template.jsonl`

当前 `evaluate_offline.py` 使用 fixture 内的 `payload_token` 构造 baseline 描述子，用于验证评估链路、指标输出和失败样本导出；它不是生产级真实图片评估格式。

Cloud 图片评估查询模板由 `cloud/tools/evaluate_relocalization.py` 消费，使用 `image_path` 读取真实查询图片；该模板不使用 `payload_token`。

### 8.1 baseline fixture 必填结构

| 字段 | 说明 |
| --- | --- |
| `venue_id` | 对应真实包 `manifest.venue_id` |
| `floors[].floor_id` | 涉及评估的楼层 |
| `keyframes[].keyframe_id` | 与真实包 `keyframes.jsonl` 对齐 |
| `keyframes[].floor_id` | keyframe 所在楼层 |
| `keyframes[].venue_xy` | keyframe 的场馆坐标 |
| `keyframes[].route_edge_id` | 可选，建议与真实包一致 |
| `keyframes[].payload_token` | 当前 baseline 用的代表性 token |
| `queries[].query_id` | 查询样本 ID |
| `queries[].capture_mode` | 与接口枚举一致 |
| `queries[].payload_token` | 当前 baseline 查询 token |
| `queries[].expected_keyframe_id` | 期望命中的 keyframe，可选 |
| `queries[].expected_floor_id` | 期望楼层，可选 |
| `queries[].expected_position` | 期望位置，可选 |

### 8.2 Cloud 图片评估查询结构

| 字段 | 说明 |
| --- | --- |
| `query_id` | 查询样本 ID，必填 |
| `image_path` | 查询图片路径，必填；相对路径基于 `--query-root` |
| `venue_id` | 对应真实包 `manifest.venue_id`，必填 |
| `capture_mode` | 采集来源，可选；默认可用 `offline_eval` |
| `candidate_floor_id` | 候选楼层，可选 |
| `expected_keyframe_id` | 期望命中的 keyframe，可选 |
| `expected_floor_id` | 期望楼层，可选 |
| `route_prior` | 路线先验，可选，包含 `route_id / edge_ids / corridor_window_m` |

### 8.3 输出要求

```bash
.\.venv\Scripts\python mapping/algorithms/relocalization/evaluate_offline.py <fixture文件或目录> --report-json dist/relocalization-report.json --query-jsonl dist/relocalization-queries.jsonl --failure-dir dist/relocalization-failures
```

必须查看：

- `top1_accuracy`
- `floor_accuracy`
- `avg_position_error_m`
- `status_counts`
- `failure_count`
- `relocalization-failures/` 中的失败样本

## 9. 人工待提供清单

| 类别 | 必须提供内容 | 交付形式 |
| --- | --- | --- |
| 场馆信息 | 场馆名、城市、地址、场馆类型 | 文本表 |
| 楼层范围 | Demo 涉及楼层、楼层展示名、楼层顺序 | 文本表 |
| 目标店铺 | 店铺名、门口位置、所在楼层、现场照片 | 文本 + 照片 |
| 最优入场口 | 入口名、室外经纬度、室内落点、入口照片 | 文本 + 坐标 + 照片 |
| 跨层方式 | 扶梯/楼梯/电梯位置、上下楼层、方向 | 文本 + 标注点 |
| 主路线 | 从入口到目标店铺的节点顺序和转角 | 标注图或节点表 |
| S20 数据 | 原始采集、后处理点云、楼层切片、俯视底图 | 文件目录 |
| 坐标约定 | 原点、x/y 方向、单位、比例关系 | 文本 + 示例点 |
| RGB 补采 | 原始图片、采集路线、采集设备、采集时间 | 图片目录 + 说明 |
| keyframe 确认 | 筛选后的 keyframe、楼层、坐标、朝向 | JSONL 或表格 |
| 特征资产 | `descriptors.faiss`、`features_index.json`、`*.npz` | 文件目录 |
| 点位复核 | 入口、POI、扶梯上下口、关键转角确认 | 复核表 |

## 10. 需 Cloud AI 配合项

- 确认云端加载真实包时的 `AI_GLASSES_VENUE_PACKAGE_ROOT` 指向发布后的真实包目录。
- 确认路径规划只依赖 `route_graph.nodes / route_graph.edges / pois[].route_node_id`，不维护独立 POI 或路网副本。
- 确认视觉定位返回的 `floor_id / position / matched_keyframe_id / route_snap.edge_id` 与真实包字段一致。
- 确认是否需要把 `arrival_radius_m` 纳入云端到达判定；如果需要，后续再进入正式契约变更。
- 真实图片离线评估使用 Cloud 图片评估查询模板和 `cloud/tools/evaluate_relocalization.py`；`evaluate_offline.py` 保持 Mapping baseline fixture 口径。
