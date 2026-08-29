# 五道口 App POI Resolver 高德映射收口报告

## 处理口径

- `manual_review_required=false` 的初筛结果写入高德 POI。
- `retry_status=name_and_venue_matched` 的重搜结果写入高德 POI。
- 其余未能可靠匹配的店铺统一按 `indoor-only` 处理：`amap_searchable=false`，导航仍使用室内 `route_node_id`。
- 同步旧 B1/F1 两层地图时保留原 `poi_id` 与 `route_node_id`，避免破坏 App 现有路线。
- 同步旧 B1/F1 两层地图时也保留旧 item 中已有的 `gcj02` / `venue_xy` / `app_preview_xy` / `outdoor_handoff` 等空间对齐字段；店铺级 `external_refs.amap_poi_location` 仍以人工审核后的高德匹配表为准。

## 输出文件

- 全楼层 App-ready resolver：`mapping\resource\wudaokou2\processed\app_indoor_map\wudaokou_all_floors_poi_resolver_app_ready.json`
- 旧 B1/F1 App-ready resolver：`mapping\resource\wudaokou2\processed\app_indoor_map\wudaokou_b1_f1_poi_resolver_app_ready.json`

## 全楼层状态统计

- `facility_indoor_only`: 10
- `indoor_only_no_reliable_amap_poi`: 34
- `matched_parent_or_address_and_name`: 101
- `retry_name_and_venue_matched`: 4

## 旧 B1/F1 状态统计

- `facility_indoor_only`: 1
- `indoor_only_no_reliable_amap_poi`: 4
- `matched_parent_or_address_and_name`: 26

## 旧 B1/F1 空间字段保留

- 旧资产中已有空间上下文字段的条目数：`31`
- 本轮需要用旧值覆盖新默认值的条目数：`0`
- 当前旧 resolver 的 `outdoor_handoff.preferred_entrance_gcj02` 已继续保留；如旧资产后续补入 `gcj02` / `venue_xy` / `app_preview_xy` 等字段，本脚本不会覆盖。

## 旧 B1/F1 已匹配高德 POI

- `B1` CoCo都可: `B0FFMGKW5R` / `matched_parent_or_address_and_name`
- `B1` 福地: `B0JAFUCEI8` / `matched_parent_or_address_and_name`
- `B1` 卤太宗: `B0J2L7ZC5A` / `matched_parent_or_address_and_name`
- `B1` 七十二匠: `B0JRXR4KPR` / `matched_parent_or_address_and_name`
- `B1` 瑞幸: `B0J3SU31IF` / `matched_parent_or_address_and_name`
- `B1` 圣诺伊: `B0J3SU31IE` / `matched_parent_or_address_and_name`
- `B1` 砂锅婆: `B0KDURC9BZ` / `matched_parent_or_address_and_name`
- `B1` 诗碧曼: `B0JRTKVLCQ` / `matched_parent_or_address_and_name`
- `B1` 完美世界: `B0J3SU0TTZ` / `matched_parent_or_address_and_name`
- `B1` 吴裕泰: `B000A8ZHIE` / `matched_parent_or_address_and_name`
- `B1` 携程旅游: `B0FFJN6ONW` / `matched_parent_or_address_and_name`
- `B1` 一麻一辣麻辣香锅: `B0J3SUA2QG` / `matched_parent_or_address_and_name`
- `B1` 张如火酸辣粉: `B0JKJNH87H` / `matched_parent_or_address_and_name`
- `F1` 华硕: `B0H3J75QS6` / `matched_parent_or_address_and_name`
- `F1` 华为: `B0FFJN6OOF` / `matched_parent_or_address_and_name`
- `F1` 杰克琼斯: `B0J1SUYQLV` / `matched_parent_or_address_and_name`
- `F1` 兰熊: `B0HG45TEND` / `matched_parent_or_address_and_name`
- `F1` 联想: `B0KR57W25D` / `matched_parent_or_address_and_name`
- `F1` 麦当劳: `B0I2HLAIJ2` / `matched_parent_or_address_and_name`
- `F1` Manner咖啡: `B0H3PR3KMT` / `matched_parent_or_address_and_name`
- `F1` 耐克: `B0HBR643ZT` / `matched_parent_or_address_and_name`
- `F1` 泡泡玛特: `B0HD7HOM8L` / `matched_parent_or_address_and_name`
- `F1` 星巴克: `B0FFJN6OP3` / `matched_parent_or_address_and_name`
- `F1` 小米: `B0KRU11D6J` / `matched_parent_or_address_and_name`
- `F1` 中国黄金: `B0FFJN6OOI` / `matched_parent_or_address_and_name`
- `F1` 周大福: `B0FFGYN54U` / `matched_parent_or_address_and_name`

## 旧 B1/F1 indoor-only POI

- `B1` 包煮 / `poi_b1_baozhu_102`
- `B1` dec / `poi_b1_dec_27`
- `B1` jvewei / `poi_b1_jvewei_25`
- `B1` 卫生间 / `poi_b1_wc_2222`
- `B1` 紫谷庐 / `poi_b1_zigulu_47`
