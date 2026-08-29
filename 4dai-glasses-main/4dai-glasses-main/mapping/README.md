# 建图标注子项目 README

更新时间：2026-05-18

## 子项目目标

建图标注子项目负责把场馆现场采集数据转成当前系统可消费的室内地图包。该子项目覆盖从 S20 采集、点云处理、语义标注、RGB 补采到地图包校验发布的完整数据生产链。

## 仓库位置

- 子项目根目录：`mapping/`
- 流程说明：`docs/s20-to-indoor-map-sop-v0.1.md`
- 地图包规格：`../contracts/venue-package/venue-map-package-spec-v0.1.md`
- 样例数据：`examples/venue-package-example/`
- 校验脚本：`tools/validate_venue_package.py`
- 发布脚本：`tools/publish_venue_package.py`
- 离线评估：`algorithms/relocalization/`

## 当前产物范围

当前仓库中的建图标注相关产物已经具备：

- S20 到室内地图包 SOP 文档
- 地图包字段说明文档
- 真实场馆最小包准备说明
- 真实场馆包生产演练与 QA 清单
- 样例场馆地图包
- 场馆包校验脚本
- 场馆包发布脚本
- 离线重定位评估脚本
- 北京五道口购物中心 TATA route-only DEMO 草案与 App handoff 数据
- 北京五道口购物中心 B1/F1 本地图纸路径规划草案路网与 resolver 交付物
- 北京五道口购物中心 `wudaokou2` 全楼层标注源数据、mask、生成脚本与 `app_indoor_map` 交付资源

当前仓库中的建图标注相关产物暂未包含：

- 自动化标注平台
- 自动生成 keyframe 与特征索引的生产流水线
- 多场馆批量数据生产平台
- 众包采图与审核系统

## 子项目工作内容

建图标注子项目负责以下四类工作：

1. 现场采集
2. 点云处理与楼层切分
3. 路网、入口、POI、扶梯等语义标注
4. 地图包装配、校验、发布

## 常用命令

验证地图包：

```bash
python mapping/tools/validate_venue_package.py mapping/examples/venue-package-example --json
```

发布地图包：

```bash
python mapping/tools/publish_venue_package.py mapping/examples/venue-package-example --output-dir dist
```

离线重定位评估：

```bash
.\.venv\Scripts\python mapping/algorithms/relocalization/evaluate_offline.py mapping/algorithms/relocalization/fixtures --report-json dist/relocalization-report.json --query-jsonl dist/relocalization-queries.jsonl --failure-dir dist/relocalization-failures
```

离线评估模板：

```text
mapping/algorithms/relocalization/templates/real_venue_eval_fixture_template.json
mapping/algorithms/relocalization/templates/cloud_image_eval_queries_template.jsonl
```

五道口 TATA route-only DEMO：

```text
mapping/drafts/wudaokou-route-demo-v0.1/
mapping/drafts/wudaokou-route-demo-v0.1/qgis/route_nodes.geojson
mapping/drafts/wudaokou-route-demo-v0.1/qgis/route_edges.geojson
mapping/drafts/wudaokou-route-demo-v0.1/app_handoff/wudaokou_tata_app_route_demo.json
mapping/drafts/wudaokou-route-demo-v0.1/app_handoff/APP_INTEGRATION.md
```

五道口 B1/F1 本地图纸路径规划交付物当前已接入 Android assets：

```text
android/ai-glasses-poc/app/src/main/assets/mapping/wudaokou/wudaokou_b1_f1_app_nav_graph.json
android/ai-glasses-poc/app/src/main/assets/mapping/wudaokou/wudaokou_b1_f1_poi_resolver.json
android/ai-glasses-poc/app/src/main/assets/mapping/wudaokou/b1.jpg
android/ai-glasses-poc/app/src/main/assets/mapping/wudaokou/f1.jpg
```

五道口 `wudaokou2` 全楼层资源与交付说明：

```text
mapping/resource/wudaokou2/README.md
mapping/resource/wudaokou2/generate_app_indoor_map.py
mapping/resource/wudaokou2/processed/app_indoor_map/
```

真实场馆准备清单：

```text
mapping/docs/real-venue-package-prep-v0.1.md
mapping/docs/real-venue-production-runbook-v0.1.md
```

## 关键依赖工具

- S20 与配套采集软件
- SHARE PointClouds Studio
- CloudCompare
- QGIS
- 手机或眼镜 RGB 补采设备
- 特征提取与检索工具链

## 关联文档

- [建图标注子项目 PRD](./PRD.md)
- [建图标注子项目进度](./PROGRESS.md)
- [S20 采集到室内地图生产 SOP](./docs/s20-to-indoor-map-sop-v0.1.md)
- [真实场馆最小包准备说明](./docs/real-venue-package-prep-v0.1.md)
- [真实场馆包生产演练与 QA 清单](./docs/real-venue-production-runbook-v0.1.md)
- [五道口 TATA route-only DEMO 草案](./drafts/wudaokou-route-demo-v0.1/README.md)
- [五道口 TATA App handoff 说明](./drafts/wudaokou-route-demo-v0.1/app_handoff/APP_INTEGRATION.md)
- [五道口 wudaokou2 标注资产说明](./resource/wudaokou2/README.md)
- [场馆地图包字段规格](../contracts/venue-package/venue-map-package-spec-v0.1.md)
