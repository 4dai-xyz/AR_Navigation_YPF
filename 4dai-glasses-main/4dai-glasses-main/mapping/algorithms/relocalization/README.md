# Relocalization Baseline

这里存放离线评估脚本和 baseline fixture。

## 运行

```bash
.\.venv\Scripts\python mapping/algorithms/relocalization/evaluate_offline.py
```

批量评估与导出：

```bash
.\.venv\Scripts\python mapping/algorithms/relocalization/evaluate_offline.py mapping/algorithms/relocalization/fixtures --report-json dist/relocalization-report.json --query-jsonl dist/relocalization-queries.jsonl --failure-dir dist/relocalization-failures
```

真实场馆 baseline fixture 模板：

```text
mapping/algorithms/relocalization/templates/real_venue_eval_fixture_template.json
```

Cloud 图片评估查询模板：

```text
mapping/algorithms/relocalization/templates/cloud_image_eval_queries_template.jsonl
```

## 说明

- 当前 baseline 与 `cloud/app/services/relocalization.py` 共用同一套描述子和置信度策略。
- fixture 的目标是验证工程链路和指标输出格式，不代表最终算法精度。
- `--report-json` 用于落聚合报告。
- `--query-jsonl` 用于落每条 query 的明细结果。
- `--failure-dir` 会导出失败样本，便于人工复盘。
- `templates/real_venue_eval_fixture_template.json` 只作为 Mapping baseline fixture 模板，不会被默认 fixture 扫描。
- 真实场馆模板中的 `query_image_ref / expected_status / expected_route_node_id / expected_poi_id` 当前用于记录和导出 ground truth，其中 `expected_status` 会参与失败样本判定。
- `templates/cloud_image_eval_queries_template.jsonl` 是 Cloud 图片评估输入模板，由 `cloud/tools/evaluate_relocalization.py` 消费，使用 `image_path` 读取真实图片，不使用 `payload_token`。
