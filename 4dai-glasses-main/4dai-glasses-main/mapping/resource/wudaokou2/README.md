# 五道口购物中心 wudaokou2 标注资产说明

## 保留内容

- `annotation_points/`：高德底图截图与 LabelMe 点位标注，作为当前同一图纸坐标系的源数据。
- `masks/`：每层可通行区域 mask，白色为可通行区域。
- `generate_app_indoor_map.py`：从底图、mask、LabelMe 点位生成 App 室内路网与 POI resolver。
- `build_app_poi_resolver_with_amap.py`：把人工审核后的高德 POI 匹配结果合入 App resolver，并保留旧 B1/F1 的 `poi_id`、`route_node_id` 与空间上下文。
- `processed/app_indoor_map/`：App 可消费的路网、POI resolver、审核表与交付报告。
- `processed/annotation_overlays/algorithm_g_cell_portal_bridge_pruned_clean_indexed/`：G 方案叠加店铺编号后的最终核对图。
- `processed/walkable_network_previews/results/algorithm_g_cell_portal_bridge_pruned/`：G 方案路网预览图与统计。

## 不再保留的过程内容

- `gaode.zip` / `masks.zip` 与目录内容重复，不进入 Git。
- A-F 方案逐层预览图属于算法探索过程图，不再进入 Git；只保留对应 `summary.json` 用于回看统计。
- 高德搜索原始响应 `amap_poi_search_results.json`、`amap_poi_wrong_matches_retry_results.json` 属于可再生成的抓取缓存，不进入 Git。
- 临时截图、红框核对图、crop 诊断图统一放在 `processed/**/diagnostics/`，不进入 Git。

## 当前 App 交付口径

- 全楼层候选：`processed/app_indoor_map/wudaokou_all_floors_app_nav_graph.json` 与 `processed/app_indoor_map/wudaokou_all_floors_poi_resolver_app_ready.json`。
- Android 当前接入目录：`android/ai-glasses-poc/app/src/main/assets/mapping/wudaokou2/`，App 默认加载全楼层 graph/resolver。
- 旧 B1/F1 兼容：`processed/app_indoor_map/wudaokou_b1_f1_poi_resolver_app_ready.json` 已同步到 Android 资产。
- 店铺级高德坐标只表示高德 POI 搜索结果，不等同于室内底图 overlay 对齐坐标。
- `wudaokou_shared_amap_alignment.json` 与 graph 内 `shared_amap_alignment` 使用西门 + F2 TATA 两个锚点生成同视角共享 `image_pixel -> GCJ-02` 变换，适用于当前 B1/F1/F2/F3/F4/F5/F6 同尺寸同视角截图。
- App 应优先使用人工保存的单层校准点；若某层没有校准点，则可回退到 `shared_amap_alignment` 自动投影该层路网节点。
- 如果某层截图发生平移、缩放、旋转、裁切差异，必须为该层补充 floor-specific anchors，不能继续复用共享映射。
