# 五道口 wudaokou2 App 室内地图生成报告

## 输出文件

- `wudaokou_all_floors_app_nav_graph.json`：App `ImageIndoorNavigation` 可读取的室内路网。
- `wudaokou_all_floors_poi_resolver.json`：App 搜索词到 `route_node_id` 的映射。
- `wudaokou_shared_amap_alignment.json`：同视角全楼层复用的 `image_pixel -> GCJ-02` 高德 overlay 变换。
- `wudaokou_all_floors_annotation_points.json`：LabelMe 点位吸附到可通行 mask 的中间结果。
- `wudaokou_all_floors_alias_audit.json` / `ALIAS_AUDIT_REPORT.md`：中文搜索词审核结果。

## 统计

- floors: `['B1', 'F1', 'F2', 'F3', 'F4', 'F5', 'F6']`
- nodes: `5031`
- edges: `13543`
- pois: `149`
- entrances: `3`
- connectors: `87`
- graph_qa: `{'info': 8, 'warning': 123}`
- alias_audit: `{'info': 54, 'warning': 13}`

## 接入注意

- 当前产物先放在 Mapping 输出目录，未直接覆盖 Android assets。
- App 若要使用全楼层新图，需要把 graph/resolver 放入 assets 并切换加载文件名。
- App 可优先读取 graph 内的 `shared_amap_alignment`；独立 alignment JSON 仅作为交付核对与后续拆分资产。
- 共享映射成立前提：B1/F1/F2/F3/F4/F5/F6 截图必须保持同一高德视角、同尺寸、同裁切、同旋转。
- `poi_resolver.items[]` 已包含 `venue_name`、`venue_address`、`subtitle`、`badges`、`external_refs`、`outdoor_handoff`，用于展示商场、地址、距离目标和高德未收录店铺状态。
- `VELWIN` 已标记为 `result_type=indoor_only_poi`、`external_refs.amap_searchable=false`，室外段应交接到五道口购物中心西门，室内段再到店铺点。
- B2/B3 未进入本次图，因为没有完整 mask/路网输入。
- 如果某层仍和高德室内底图错位，需要为该楼层补 2 个以上锚点并生成 floor-specific transform。
