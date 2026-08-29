# 建图标注子项目进度

更新时间：2026-05-18

## 当前状态

状态：进行中

当前 Mapping 侧已经完成数据结构、流程文档、样例场馆包和校验发布脚本的基础建设，真实场馆的数据生产仍处于待落地阶段。

需要明确的是：

- 当前 Android 室内主演示已经切到手动演示模式
- 这降低了“必须先有真实重定位数据才能演示”的短期压力
- 但 Mapping 仍然是后续恢复真实室内定位、真实室内路径和真实场馆 Demo 的基础依赖

当前准备状态：

- 真实场馆最小可演示包模板已冻结
- 命名规范、装配清单、最小标注清单已冻结
- 生产演练流程与 QA 清单已冻结
- Mapping baseline fixture 模板与 Cloud 图片评估查询模板已区分
- 北京五道口购物中心 route-only DEMO 已确认到 TATA 门口，并已输出 QGIS 标注层与 App handoff 数据
- 北京五道口购物中心 B1/F1 本地图纸路径规划草案路网与 resolver 已输出并接入 Android assets
- 北京五道口购物中心 `wudaokou2` 全楼层标注源数据、mask、生成脚本与 `app_indoor_map` 交付资源已整理

## 已完成

- [x] 明确场馆地图包字段规格
- [x] 明确 S20 到室内地图包的 SOP
- [x] 提供样例场馆地图包
- [x] 提供地图包校验脚本
- [x] 提供地图包发布脚本
- [x] 提供离线重定位评估脚本
- [x] 建立地图包、云端接口、App 规格之间的字段对齐基础
- [x] 冻结真实场馆最小可演示包模板
- [x] 冻结真实场馆 ID 命名规范
- [x] 提供真实场馆包装配清单和最小标注清单
- [x] 提供真实场馆离线评估输入模板
- [x] 提供真实场馆包生产演练与 QA 检查清单
- [x] 收口样例包、校验脚本、发布脚本与规格的轻微口径差异
- [x] 收口 canonical 规格中的默认 zip 命名，与发布脚本默认产物保持一致
- [x] 区分 Mapping baseline fixture 模板与 Cloud 图片评估查询模板
- [x] 完成北京五道口购物中心西门到 2F TATA 门口 route-only DEMO 标注草案
- [x] 输出五道口 TATA 路线的 QGIS 可编辑节点/边图层
- [x] 输出五道口 TATA 路线的 Android App handoff 数据与接入说明
- [x] 完成五道口 B1/F1 本地图纸路径规划草案路网后处理
- [x] 输出五道口 B1/F1 App 可用 nav graph 与 poi resolver
- [x] 将五道口 B1/F1 图片底图、路网和 resolver 接入 Android assets
- [x] 整理五道口 `wudaokou2` 标注源数据、mask 与 App 室内地图生成脚本
- [x] 输出五道口 `wudaokou2` 的 `app_indoor_map` 交付物、POI 审核表与交付报告

## 进行中

- [ ] 将样例数据结构映射到真实场馆生产流程
- [ ] 等待真实 S20 数据、楼层底图和 RGB 补采素材
- [ ] 等待真实 RGB 补采素材与视觉定位资产
- [ ] 基于真实素材装配首个真实场馆包
- [ ] 基于真实失败样本补充数据回流方式
- [ ] 评估五道口 `wudaokou2` 全楼层资源到 Android 正式资产的稳定交付边界

## 待完成

- [ ] 完成 1 到 2 个真实场馆采集
- [ ] 完成真实楼层底图切分
- [ ] 完成真实入口、POI、扶梯和路网标注
- [ ] 完成真实 RGB 补采与关键帧整理
- [ ] 完成真实场馆包校验与发布
- [ ] 完成与云端联调的真实数据回归验证

## 现有产物

- SOP 文档：`docs/s20-to-indoor-map-sop-v0.1.md`
- 字段规格：`../contracts/venue-package/venue-map-package-spec-v0.1.md`
- 样例数据：`examples/venue-package-example/`
- 校验脚本：`tools/validate_venue_package.py`
- 发布脚本：`tools/publish_venue_package.py`
- 离线评估：`algorithms/relocalization/`
- 真实场馆准备说明：`docs/real-venue-package-prep-v0.1.md`
- 真实生产演练与 QA：`docs/real-venue-production-runbook-v0.1.md`
- Mapping baseline fixture 模板：`algorithms/relocalization/templates/real_venue_eval_fixture_template.json`
- Cloud 图片评估查询模板：`algorithms/relocalization/templates/cloud_image_eval_queries_template.jsonl`
- 五道口 TATA route-only DEMO：`drafts/wudaokou-route-demo-v0.1/`
- 五道口 TATA QGIS 标注层：`drafts/wudaokou-route-demo-v0.1/qgis/route_nodes.geojson`、`drafts/wudaokou-route-demo-v0.1/qgis/route_edges.geojson`
- 五道口 TATA App handoff：`drafts/wudaokou-route-demo-v0.1/app_handoff/wudaokou_tata_app_route_demo.json`
- 五道口 B1/F1 App 路径规划资产：`../android/ai-glasses-poc/app/src/main/assets/mapping/wudaokou/`
- 五道口 `wudaokou2` 标注与交付资源：`resource/wudaokou2/`

## 依赖与阻塞

- 真实场馆数据生产依赖现场采集安排
- 真实视觉定位资产依赖 RGB 补采和算法工具链
- 云端定位效果依赖 keyframe 质量、覆盖率和语义锚点准确性
- Mapping baseline 评估继续使用 `payload_token` fixture
- Cloud 图片评估继续使用 `cloud_image_eval_queries_template.jsonl` 和 `cloud/tools/evaluate_relocalization.py`
- 当前 App 虽然可用手动室内演示，但恢复真实室内定位链路仍然依赖 Mapping 的真实场馆资产
- 五道口 TATA route-only DEMO 不包含真实视觉定位资产，不应作为 localization-ready 场馆包发布
- 五道口 `wudaokou2` 当前已整理为标注与 handoff 资源，不等同于已冻结的 Android 正式资产或 localization-ready 场馆包

## 验收口径

当前阶段验收关注以下四项：

1. 地图包结构稳定。
2. 校验和发布脚本可复用。
3. 样例包能支持云端与 App 联调。
4. 真实场馆生产时有清晰的字段和流程依据。
