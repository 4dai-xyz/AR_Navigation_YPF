import csv
import json
from pathlib import Path


ROOT_DIR = Path(__file__).resolve().parent
ANNOTATION_DIR = ROOT_DIR / "annotation_points"
OUTPUT_DIR = ROOT_DIR / "processed" / "app_indoor_map"
MARKDOWN_PATH = OUTPUT_DIR / "LABEL_ALIAS_REVIEW.md"
CSV_PATH = OUTPUT_DIR / "label_alias_review.csv"

CONNECTOR_PREFIXES = ("escalator_", "elevator", "step", "stairs")
NON_STORE_LABELS = {"NG", "EG", "WG", "SG", "wc"}
FLOOR_ORDER = ["B3", "B2", "B1", "F1", "F2", "F3", "F4", "F5", "F6"]

KNOWN_USER_NOTES = {
    "6ixty": {
        "suggested_display": "6IXTY 8IGTY(五道口购物中心店)",
        "review_status": "已由用户确认；建议再确认英文拼写是否为 6IXTY8IGHT",
    },
    "tata": {
        "suggested_display": "TATA 鞋店",
        "review_status": "已由用户确认：Tata 是鞋店",
    },
    "cdgplay": {
        "suggested_display": "CDG PLAY",
        "review_status": "已由用户确认：英文品牌，可无中文",
    },
    "velwin": {
        "suggested_display": "VELWIN",
        "review_status": "已按 indoor-only POI 处理：高德店铺未收录，室外交接到商场入口",
    },
}


def has_chinese(text: str) -> bool:
    return any("\u4e00" <= char <= "\u9fff" for char in text)


def unique_values(values):
    result = []
    seen = set()
    for value in values:
        text = str(value).strip()
        if not text or text in seen:
            continue
        result.append(text)
        seen.add(text)
    return result


def markdown_escape(text) -> str:
    return str(text).replace("|", "\\|").replace("\n", " ")


def row_sort_key(row):
    return (FLOOR_ORDER.index(row["floor_id"]), row["label"], str(row["group_id"]))


def collect_rows():
    rows_by_key = {}
    for labelme_path in sorted(ANNOTATION_DIR.glob("*.json")):
        floor_id = labelme_path.stem.upper()
        data = json.loads(labelme_path.read_text(encoding="utf-8"))
        for shape in data.get("shapes", []):
            label = str(shape.get("label", "")).strip()
            if (
                not label
                or label in NON_STORE_LABELS
                or label.startswith(CONNECTOR_PREFIXES)
            ):
                continue
            group_id = shape.get("group_id")
            key = (floor_id, label, group_id)
            row = rows_by_key.setdefault(
                key,
                {
                    "floor_id": floor_id,
                    "label": label,
                    "group_id": group_id,
                    "sample_count": 0,
                    "aliases": [],
                    "description": "",
                },
            )
            row["sample_count"] += 1
            row["aliases"] = unique_values(row["aliases"] + (shape.get("aliases") or []))
            description = str(shape.get("description") or "").strip()
            if description and not row["description"]:
                row["description"] = description

    rows = []
    for row in rows_by_key.values():
        aliases = row["aliases"]
        chinese_aliases = [alias for alias in aliases if has_chinese(alias)]
        english_aliases = [alias for alias in aliases if not has_chinese(alias)]
        note = KNOWN_USER_NOTES.get(row["label"], {})
        suggested_display = note.get("suggested_display") or (
            chinese_aliases[0] if chinese_aliases else aliases[0] if aliases else row["label"]
        )
        if note:
            review_status = note["review_status"]
        elif "需确认" in row["description"]:
            review_status = "需人工确认"
        elif not chinese_aliases:
            review_status = "仅英文/拼音；如为英文品牌则可接受"
        else:
            review_status = "初步可用"
        row.update(
            {
                "suggested_display": suggested_display,
                "chinese_aliases": chinese_aliases,
                "english_aliases": english_aliases,
                "review_status": review_status,
            }
        )
        rows.append(row)
    return sorted(rows, key=row_sort_key)


def write_csv(rows):
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    with CSV_PATH.open("w", encoding="utf-8-sig", newline="") as file:
        writer = csv.DictWriter(
            file,
            fieldnames=[
                "floor_id",
                "label",
                "group_id",
                "sample_count",
                "suggested_display",
                "chinese_aliases",
                "english_aliases",
                "review_status",
                "description",
            ],
        )
        writer.writeheader()
        for row in rows:
            writer.writerow(
                {
                    "floor_id": row["floor_id"],
                    "label": row["label"],
                    "group_id": row["group_id"],
                    "sample_count": row["sample_count"],
                    "suggested_display": row["suggested_display"],
                    "chinese_aliases": "；".join(row["chinese_aliases"]),
                    "english_aliases": "；".join(row["english_aliases"]),
                    "review_status": row["review_status"],
                    "description": row["description"],
                }
            )


def write_markdown(rows):
    status_counts = {}
    for row in rows:
        status_counts[row["review_status"]] = status_counts.get(row["review_status"], 0) + 1
    lines = [
        "# 五道口 wudaokou2 店铺 label / 中文搜索词核对表",
        "",
        "## 说明",
        "",
        "- 来源：`annotation_points/*.json` 的 LabelMe `label`、`group_id`、`aliases`、`description`。",
        "- 不包含入口、扶梯、电梯、楼梯、卫生间等非店铺设施点。",
        "- `建议展示名/中文` 是供 App 候选项展示和人工核对的建议，不会自动改原始 LabelMe。",
        "- 用户已确认：`6ixty` 是 `6IXTY 8IGTY(五道口购物中心店)`；`tata` 是鞋店；`cdgplay` 就是 `CDG PLAY`。",
        "",
        "## 状态统计",
        "",
    ]
    for status, count in sorted(status_counts.items()):
        lines.append(f"- {status}: `{count}`")
    lines.extend(
        [
            "",
            "## 核对表",
            "",
            "| floor | label | group_id | samples | 建议展示名/中文 | 当前中文 aliases | 当前英文/拼音 aliases | 状态 | 备注 |",
            "|---|---|---:|---:|---|---|---|---|---|",
        ]
    )
    for row in rows:
        lines.append(
            "| "
            + " | ".join(
                [
                    markdown_escape(row["floor_id"]),
                    markdown_escape(row["label"]),
                    markdown_escape(row["group_id"]),
                    markdown_escape(row["sample_count"]),
                    markdown_escape(row["suggested_display"]),
                    markdown_escape("；".join(row["chinese_aliases"]) or "-"),
                    markdown_escape("；".join(row["english_aliases"]) or "-"),
                    markdown_escape(row["review_status"]),
                    markdown_escape(row["description"] or "-"),
                ]
            )
            + " |"
        )
    MARKDOWN_PATH.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    rows = collect_rows()
    write_markdown(rows)
    write_csv(rows)
    print(f"rows={len(rows)}")
    print(f"markdown={MARKDOWN_PATH}")
    print(f"csv={CSV_PATH}")


if __name__ == "__main__":
    main()
