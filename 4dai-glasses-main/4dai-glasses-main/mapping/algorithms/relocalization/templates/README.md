# Offline Relocalization Templates

这里存放两类离线评估输入模板，二者口径不同，不能混用。

## 模板类型

| 文件 | 用途 | 消费方 | 是否读取真实图片 |
| --- | --- | --- | --- |
| `real_venue_eval_fixture_template.json` | baseline fixture 模板，用于验证 Mapping 侧 baseline 评估链路 | `mapping/algorithms/relocalization/evaluate_offline.py` | 否，使用 `payload_token` |
| `cloud_image_eval_queries_template.jsonl` | Cloud 图片评估查询模板，用于真实图片离线评估输入 | `cloud/tools/evaluate_relocalization.py` | 是，读取 `image_path` |

## baseline fixture 模板

使用方式：

1. 复制 `real_venue_eval_fixture_template.json` 到临时工作目录。
2. 用真实场馆包中的 `venue_id`、`floor_id`、`keyframe_id`、`venue_xy` 和 `route_edge_id` 替换模板值。
3. 为每条 query 填写期望命中的 keyframe、楼层、位置、最近路网节点和目标 POI。
4. 运行：

```bash
.\.venv\Scripts\python mapping/algorithms/relocalization/evaluate_offline.py <fixture文件> --report-json dist/relocalization-report.json --query-jsonl dist/relocalization-queries.jsonl --failure-dir dist/relocalization-failures
```

注意：不要把未替换的模板直接放到 `fixtures/` 目录；默认评估会扫描 `fixtures/*.json`。

baseline fixture 字段口径：

- `query_image_ref`：真实评估素材中的查询图片相对路径；当前 baseline 脚本只透传该字段，不直接读取图片。
- `ground_truth_source`：人工标定来源，例如 `manual_anchor` 或 `surveyed_point`。
- `expected_status`：期望定位状态；负样本可填 `not_found`，避免被误判为异常失败。
- `expected_route_node_id`：人工确认的最近路网节点。
- `expected_poi_id`：查询点对应目标 POI 时填写；非 POI 查询可省略。

## Cloud 图片评估模板

使用方式：

1. 复制 `cloud_image_eval_queries_template.jsonl` 到真实评估工作目录。
2. 把 `image_path` 替换为真实查询图片相对路径，并通过 `--query-root` 指向图片根目录。
3. 把 `venue_id` 替换为真实场馆包 `manifest.venue_id`。
4. 按人工 ground truth 填写 `expected_keyframe_id` 和 `expected_floor_id`；未知时可以省略。
5. 如需缩小检索范围，填写 `route_prior.edge_ids` 和 `route_prior.corridor_window_m`。

运行方式：

```bash
.\.venv\Scripts\python cloud/tools/evaluate_relocalization.py --queries <queries.jsonl> --query-root <图片根目录> --venue-package-root <真实场馆包目录> --report-json dist/cloud-relocalization-report.json --failure-json dist/cloud-relocalization-failures.json
```

Cloud 图片评估字段口径以 `cloud/tools/evaluate_relocalization.py` 和 `cloud/app/services/offline_relocalization_eval.py` 为准：

- 必填：`query_id`、`image_path`、`venue_id`
- 可选：`expected_keyframe_id`、`expected_floor_id`、`capture_mode`、`candidate_floor_id`、`route_prior`
- `route_prior` 可包含 `route_id`、`edge_ids`、`corridor_window_m`
- 当前 Cloud 图片评估工具不消费 `expected_route_node_id` 或 `expected_poi_id`；这两个字段只保留在 baseline fixture / QA 表中记录人工语义锚点。
