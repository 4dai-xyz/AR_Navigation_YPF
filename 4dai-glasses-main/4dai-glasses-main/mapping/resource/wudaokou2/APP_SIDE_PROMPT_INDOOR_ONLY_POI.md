# App 侧接入 Prompt：五道口 indoor-only POI 与室外+室内组合导航

你在仓库 `F:/hz/codex/AI_Glasses` 中工作，角色是 Android App 负责人。

## 背景

Mapping 侧已在以下产物中新增全楼层室内地图与 indoor-only POI 展示字段：

- `mapping/resource/wudaokou2/processed/app_indoor_map/wudaokou_all_floors_app_nav_graph.json`
- `mapping/resource/wudaokou2/processed/app_indoor_map/wudaokou_all_floors_poi_resolver.json`

其中 `VELWIN` 这类店铺在真实商场内存在，但高德店铺 POI 搜不到。Mapping 已将其标记为：

- `result_type`: `indoor_only_poi`
- `external_refs.amap_searchable`: `false`
- `badges`: `["室内点位", "高德未收录店铺"]`
- `venue_name`: `五道口购物中心`
- `venue_address`: `北京市海淀区成府路28号`
- `outdoor_handoff.strategy`: `navigate_to_venue_entrance_then_indoor_route`
- `outdoor_handoff.preferred_entrance_route_node_id`: `node_entrance_f1_west_gate_access`
- `outdoor_handoff.preferred_entrance_gcj02`: `{ "lat": 39.991583, "lng": 116.338965 }`

## 任务

请修改 Android App，使搜索 `VELWIN` 等本地室内 POI 时，即使高德店铺搜索不到，也能展示并完成“室外到商场入口 + 室内到店铺点”的组合导航。

## 具体要求

1. 切换或新增加载 Mapping 新产物：
   - graph: `wudaokou_all_floors_app_nav_graph.json`
   - resolver: `wudaokou_all_floors_poi_resolver.json`
   - 不要破坏旧 B1/F1 demo，可用 feature flag 或常量切换。

2. 扩展 `ImageIndoorPoiResolverItem` 解析字段，至少兼容：
   - `display_name`
   - `result_type`
   - `venue_name`
   - `venue_address`
   - `subtitle`
   - `badges`
   - `external_refs.amap_searchable`
   - `outdoor_handoff.preferred_entrance_gcj02`
   - 这些字段必须可选，缺失时回退旧逻辑。

3. 搜索结果展示：
   - 主标题优先用 `display_name`，否则用 `name`。
   - 副标题优先用 `subtitle`，例如：`五道口购物中心 · F2 · 室内点位`。
   - 地址显示 `venue_address`。
   - 距离由 App 用当前位置到 `outdoor_handoff.preferred_entrance_gcj02` 动态计算，展示为 `距你约 xxx m/km`。
   - 若 `external_refs.amap_searchable == false`，展示标签：`高德未收录店铺`。

4. 组合导航逻辑：
   - 对 `result_type == indoor_only_poi` 的目标，不要求高德能搜到店铺 POI。
   - 室外段：导航到 `outdoor_handoff.preferred_entrance_gcj02` 或高德“五道口购物中心”场馆 POI。
   - 室内段：使用 resolver 的 `route_node_id` 规划室内路线。
   - 到达商场入口后切换到室内导航。

5. 楼层支持：
   - 新 graph 包含 `B1/F1/F2/F3/F4/F5/F6`。
   - UI 若只支持 B1/F1，需要补充楼层切换或根据路线自动切换显示。

6. 验证用例：
   - 搜索 `VELWIN`：应显示 `VELWIN`、`五道口购物中心 · F2 · 室内点位`、商场地址、距离、高德未收录标签，并能规划到 `poi_f2_velwin_923`。
   - 搜索 `TATA`：应显示 `TATA 鞋店`，可规划到 F2。
   - 搜索 `小米`：应显示 F1 小店并能规划。
   - 搜索 `厕所`：允许出现多个楼层候选。
   - 从 F1 西门到 VELWIN/TATA 应能生成室内路线。

## 不要做

- 不要把 `VELWIN` 伪造成高德店铺 POI。
- 不要因为高德搜索不到店铺就隐藏本地室内结果。
- 不要修改 Mapping 产物字段名。
