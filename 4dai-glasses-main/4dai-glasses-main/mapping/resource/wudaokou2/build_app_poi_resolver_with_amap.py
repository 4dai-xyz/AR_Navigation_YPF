import argparse
import copy
import csv
import json
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path


ROOT_DIR = Path(__file__).resolve().parent
REPO_ROOT = ROOT_DIR.parents[2]
APP_OUTPUT_DIR = ROOT_DIR / "processed" / "app_indoor_map"

ALL_FLOORS_RESOLVER_PATH = APP_OUTPUT_DIR / "wudaokou_all_floors_poi_resolver.json"
MAIN_MATCH_CSV_PATH = APP_OUTPUT_DIR / "amap_poi_match_review.csv"
RETRY_MATCH_CSV_PATH = APP_OUTPUT_DIR / "amap_poi_wrong_matches_retry.csv"

ALL_FLOORS_APP_READY_PATH = APP_OUTPUT_DIR / "wudaokou_all_floors_poi_resolver_app_ready.json"
B1_F1_APP_READY_PATH = APP_OUTPUT_DIR / "wudaokou_b1_f1_poi_resolver_app_ready.json"
REPORT_PATH = APP_OUTPUT_DIR / "APP_POI_RESOLVER_AMAP_MAPPING_REPORT.md"

ANDROID_B1_F1_RESOLVER_PATH = (
    REPO_ROOT
    / "android"
    / "ai-glasses-poc"
    / "app"
    / "src"
    / "main"
    / "assets"
    / "mapping"
    / "wudaokou"
    / "wudaokou_b1_f1_poi_resolver.json"
)

VENUE_AMAP_POI_ID = "B000A80D2Q"
VENUE_NAME = "五道口购物中心"
VENUE_ADDRESS = "北京市海淀区成府路28号"
INDOOR_ONLY_STATUS = "indoor_only_no_reliable_amap_poi"

OLD_SPATIAL_ITEM_KEYS = {
    "gcj02",
    "amap_gcj02",
    "venue_xy",
    "image_xy",
    "pixel_xy",
    "app_preview_xy",
    "alignment",
    "map_alignment",
    "calibration",
    "anchor",
    "anchor_gcj02",
    "geo_reference",
    "georeference",
}

OLD_TO_NEW_POI_ID = {
    "poi_b1_qishier_28": "poi_b1_qishierjiang_120",
    "poi_b1_shguopo_44": "poi_b1_shaguopo_44",
    "poi_b1_xiecheng_33": "poi_b1_xiechenglvyou_33",
    "poi_f1_lianxiang_4": "poi_f1_lianxiang_thinkpad_128",
}

CONFLICTING_OLD_ALIASES_BY_POI_ID = {
    "poi_b1_baozhu_102": {"宝珠", "宝珠奶酪"},
}


def read_csv_auto(path):
    last_error = None
    for encoding in ("utf-8-sig", "gb18030"):
        try:
            text = path.read_text(encoding=encoding)
            break
        except UnicodeDecodeError as error:
            last_error = error
    else:
        raise last_error
    delimiter = "\t" if "\t" in text.splitlines()[0] else ","
    return list(csv.DictReader(text.splitlines(), delimiter=delimiter))


def parse_location(value):
    text = str(value or "").strip().strip('"')
    if "," not in text:
        return None
    lng_text, lat_text = text.split(",", 1)
    try:
        return {"lng": float(lng_text), "lat": float(lat_text)}
    except ValueError:
        return None


def unique_values(values):
    result = []
    seen = set()
    for value in values:
        text = str(value or "").strip()
        if not text or text in seen:
            continue
        result.append(text)
        seen.add(text)
    return result


def base_external_refs():
    return {
        "amap_poi_id": None,
        "amap_searchable": False,
        "amap_search_status": INDOOR_ONLY_STATUS,
        "amap_match_source": "manual_review_or_no_reliable_candidate",
        "venue_amap_poi_id": VENUE_AMAP_POI_ID,
        "venue_amap_search_keyword": VENUE_NAME,
    }


def build_verified_match(row, source):
    location = parse_location(row.get("amap_candidate_location"))
    external_refs = {
        "amap_poi_id": row.get("amap_candidate_poi_id") or None,
        "amap_searchable": True,
        "amap_search_status": row.get("amap_search_status") or "matched",
        "amap_match_source": source,
        "amap_poi_name": row.get("amap_candidate_name") or None,
        "amap_poi_address": row.get("amap_candidate_address") or None,
        "amap_poi_floor": row.get("amap_candidate_floor") or None,
        "amap_poi_location": location,
        "amap_poi_type": row.get("amap_candidate_type") or None,
        "venue_amap_poi_id": VENUE_AMAP_POI_ID,
        "venue_amap_search_keyword": VENUE_NAME,
    }
    return {key: value for key, value in external_refs.items() if value is not None}


def build_retry_match(row):
    location = parse_location(row.get("retry_candidate_location"))
    external_refs = {
        "amap_poi_id": row.get("retry_candidate_poi_id") or None,
        "amap_searchable": True,
        "amap_search_status": "retry_name_and_venue_matched",
        "amap_match_source": "m_amap_retry_name_and_venue_matched",
        "amap_poi_name": row.get("retry_candidate_name") or None,
        "amap_poi_address": row.get("retry_candidate_address") or None,
        "amap_poi_floor": row.get("retry_candidate_floor") or None,
        "amap_poi_location": location,
        "amap_poi_type": row.get("retry_candidate_type") or None,
        "venue_amap_poi_id": VENUE_AMAP_POI_ID,
        "venue_amap_search_keyword": VENUE_NAME,
    }
    return {key: value for key, value in external_refs.items() if value is not None}


def collect_amap_refs():
    refs = {}
    main_rows = read_csv_auto(MAIN_MATCH_CSV_PATH)
    for row in main_rows:
        manual_required = str(row.get("manual_review_required") or "").strip().lower()
        manual_result = str(row.get("人工审核结果") or "").strip()
        if manual_required in ("true", "1", "yes") or manual_result == "错误":
            refs[row["poi_id"]] = base_external_refs()
            continue
        refs[row["poi_id"]] = build_verified_match(row, "amap_initial_name_and_venue_matched")

    retry_rows = read_csv_auto(RETRY_MATCH_CSV_PATH)
    for row in retry_rows:
        if row.get("retry_status") == "name_and_venue_matched":
            refs[row["poi_id"]] = build_retry_match(row)
        else:
            refs[row["poi_id"]] = base_external_refs()
    return refs


def apply_external_refs(item, external_refs):
    output = copy.deepcopy(item)
    output["venue_id"] = output.get("venue_id", "venue_bj_wudaokou_shopping_center_demo")
    output["venue_name"] = output.get("venue_name", VENUE_NAME)
    output["venue_address"] = output.get("venue_address", VENUE_ADDRESS)
    output["subtitle"] = output.get(
        "subtitle", f"{VENUE_NAME} · {output['floor_id']} · 室内点位"
    )
    output["address"] = output.get("address", VENUE_ADDRESS)
    output["distance_target"] = output.get("distance_target", "preferred_entrance_gcj02")
    output["badges"] = unique_values(output.get("badges", []) + ["室内点位"])
    if not external_refs.get("amap_searchable"):
        output["badges"] = unique_values(output["badges"] + ["indoor-only"])
    output["external_refs"] = {
        **base_external_refs(),
        **(output.get("external_refs") or {}),
        **external_refs,
    }
    output["outdoor_handoff"] = output.get(
        "outdoor_handoff",
        {
            "venue_id": "venue_bj_wudaokou_shopping_center_demo",
            "venue_name": VENUE_NAME,
            "venue_address": VENUE_ADDRESS,
            "venue_amap_poi_id": VENUE_AMAP_POI_ID,
            "venue_amap_search_keyword": VENUE_NAME,
            "strategy": "navigate_to_venue_entrance_then_indoor_route",
            "preferred_entrance_id": "entrance_f1_west_gate",
            "preferred_entrance_route_node_id": "node_entrance_f1_west_gate_access",
            "preferred_entrance_floor_id": "F1",
            "preferred_entrance_gcj02": {"lat": 39.991583, "lng": 116.338965},
            "distance_target": "preferred_entrance_gcj02",
            "distance_owner": "app_runtime_user_location_to_preferred_entrance",
        },
    )
    return output


def build_all_floors_app_ready(source_resolver, amap_refs):
    items = []
    for item in source_resolver["items"]:
        refs = amap_refs.get(item["poi_id"], base_external_refs())
        if item["name"] == "wc":
            refs = {
                **base_external_refs(),
                "amap_search_status": "facility_indoor_only",
                "amap_match_source": "non_store_facility",
            }
        items.append(apply_external_refs(item, refs))
    output = copy.deepcopy(source_resolver)
    output["schema_version"] = "poi_resolver.v0.1"
    output["generated_at"] = datetime.now(timezone.utc).isoformat()
    output["amap_mapping_policy"] = {
        "matched": "manual_review_required=false or retry_status=name_and_venue_matched",
        "unmatched": "amap_searchable=false and route by indoor poi route_node_id",
        "outdoor_handoff": "venue_amap_poi_id then preferred_entrance_gcj02",
    }
    output["items"] = items
    return output


def resolve_new_item(old_item, new_items_by_id, new_items_by_floor_name):
    mapped_id = OLD_TO_NEW_POI_ID.get(old_item["poi_id"], old_item["poi_id"])
    return new_items_by_id.get(mapped_id) or new_items_by_floor_name.get(
        (old_item["floor_id"], old_item["name"])
    )


def preserve_old_spatial_context(source_item, old_item):
    preserved_keys = []
    for key in sorted(OLD_SPATIAL_ITEM_KEYS):
        if key in old_item and source_item.get(key) != old_item[key]:
            source_item[key] = copy.deepcopy(old_item[key])
            preserved_keys.append(key)
    if old_item.get("outdoor_handoff") and source_item.get("outdoor_handoff") != old_item["outdoor_handoff"]:
        source_item["outdoor_handoff"] = copy.deepcopy(old_item["outdoor_handoff"])
        preserved_keys.append("outdoor_handoff")
    return preserved_keys


def old_spatial_context_keys(old_item):
    keys = [key for key in sorted(OLD_SPATIAL_ITEM_KEYS) if key in old_item]
    if old_item.get("outdoor_handoff"):
        keys.append("outdoor_handoff")
    return keys


def build_b1_f1_app_ready(old_resolver, all_floors_resolver, amap_refs):
    new_items_by_id = {item["poi_id"]: item for item in all_floors_resolver["items"]}
    new_items_by_floor_name = {
        (item["floor_id"], item["name"]): item for item in all_floors_resolver["items"]
    }
    items = []
    mapping_rows = []
    for old_item in old_resolver["items"]:
        new_item = resolve_new_item(old_item, new_items_by_id, new_items_by_floor_name)
        source_item = copy.deepcopy(new_item or old_item)
        preserved_spatial_keys = preserve_old_spatial_context(source_item, old_item)
        source_item["poi_id"] = old_item["poi_id"]
        source_item["name"] = old_item["name"]
        source_item["floor_id"] = old_item["floor_id"]
        source_item["route_node_id"] = old_item["route_node_id"]
        source_item["match_policy"] = old_item.get("match_policy", "manual_label_alias")
        old_aliases = [
            alias
            for alias in old_item.get("aliases", [])
            if alias not in CONFLICTING_OLD_ALIASES_BY_POI_ID.get(old_item["poi_id"], set())
        ]
        source_item["aliases"] = unique_values(
            old_aliases
            + source_item.get("aliases", [])
            + [source_item.get("display_name"), old_item.get("name")]
        )

        if old_item["name"] == "wc":
            refs = {
                **base_external_refs(),
                "amap_search_status": "facility_indoor_only",
                "amap_match_source": "non_store_facility",
            }
        elif new_item:
            refs = amap_refs.get(new_item["poi_id"], base_external_refs())
        else:
            refs = base_external_refs()

        items.append(apply_external_refs(source_item, refs))
        mapping_rows.append(
            {
                "old_poi_id": old_item["poi_id"],
                "new_poi_id": new_item["poi_id"] if new_item else None,
                "floor_id": old_item["floor_id"],
                "display_name": source_item.get("display_name", old_item["name"]),
                "amap_search_status": refs.get("amap_search_status"),
                "amap_poi_id": refs.get("amap_poi_id"),
                "amap_searchable": refs.get("amap_searchable"),
                "old_spatial_keys": old_spatial_context_keys(old_item),
                "preserved_spatial_keys": preserved_spatial_keys,
            }
        )

    output = copy.deepcopy(old_resolver)
    output["generated_at"] = datetime.now(timezone.utc).isoformat()
    output["source_resolver"] = str(ALL_FLOORS_APP_READY_PATH.relative_to(ROOT_DIR))
    output["amap_mapping_policy"] = {
        "matched": "uses mapped wudaokou2 POI if available; keeps original B1/F1 poi_id and route_node_id",
        "unmatched": "indoor-only; App routes to route_node_id without relying on shop-level AMap POI",
    }
    output["items"] = items
    return output, mapping_rows


def write_json(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_report(all_floors_resolver, b1_f1_resolver, mapping_rows, sync_android):
    all_status = Counter(
        item["external_refs"].get("amap_search_status")
        for item in all_floors_resolver["items"]
    )
    b1_f1_status = Counter(
        item["external_refs"].get("amap_search_status")
        for item in b1_f1_resolver["items"]
    )
    matched_b1_f1 = [
        row
        for row in mapping_rows
        if row["amap_searchable"] and row["amap_poi_id"]
    ]
    indoor_only_b1_f1 = [
        row
        for row in mapping_rows
        if not row["amap_searchable"]
    ]
    lines = [
        "# 五道口 App POI Resolver 高德映射收口报告",
        "",
        "## 处理口径",
        "",
        "- `manual_review_required=false` 的初筛结果写入高德 POI。",
        "- `retry_status=name_and_venue_matched` 的重搜结果写入高德 POI。",
        "- 其余未能可靠匹配的店铺统一按 `indoor-only` 处理：`amap_searchable=false`，导航仍使用室内 `route_node_id`。",
        "- 同步旧 B1/F1 两层地图时保留原 `poi_id` 与 `route_node_id`，避免破坏 App 现有路线。",
        "- 同步旧 B1/F1 两层地图时也保留旧 item 中已有的 `gcj02` / `venue_xy` / `app_preview_xy` / `outdoor_handoff` 等空间对齐字段；店铺级 `external_refs.amap_poi_location` 仍以人工审核后的高德匹配表为准。",
        "",
        "## 输出文件",
        "",
        f"- 全楼层 App-ready resolver：`{ALL_FLOORS_APP_READY_PATH.relative_to(REPO_ROOT)}`",
        f"- 旧 B1/F1 App-ready resolver：`{B1_F1_APP_READY_PATH.relative_to(REPO_ROOT)}`",
    ]
    if sync_android:
        lines.append(f"- 已同步 Android asset：`{ANDROID_B1_F1_RESOLVER_PATH.relative_to(REPO_ROOT)}`")
    lines.extend(["", "## 全楼层状态统计", ""])
    for status, count in sorted(all_status.items()):
        lines.append(f"- `{status}`: {count}")
    lines.extend(["", "## 旧 B1/F1 状态统计", ""])
    for status, count in sorted(b1_f1_status.items()):
        lines.append(f"- `{status}`: {count}")
    old_spatial_count = sum(1 for row in mapping_rows if row["old_spatial_keys"])
    preserved_spatial_count = sum(1 for row in mapping_rows if row["preserved_spatial_keys"])
    lines.extend(
        [
            "",
            "## 旧 B1/F1 空间字段保留",
            "",
            f"- 旧资产中已有空间上下文字段的条目数：`{old_spatial_count}`",
            f"- 本轮需要用旧值覆盖新默认值的条目数：`{preserved_spatial_count}`",
            "- 当前旧 resolver 的 `outdoor_handoff.preferred_entrance_gcj02` 已继续保留；如旧资产后续补入 `gcj02` / `venue_xy` / `app_preview_xy` 等字段，本脚本不会覆盖。",
        ]
    )
    lines.extend(["", "## 旧 B1/F1 已匹配高德 POI", ""])
    if matched_b1_f1:
        for row in matched_b1_f1:
            lines.append(
                f"- `{row['floor_id']}` {row['display_name']}: `{row['amap_poi_id']}` / `{row['amap_search_status']}`"
            )
    else:
        lines.append("- 无")
    lines.extend(["", "## 旧 B1/F1 indoor-only POI", ""])
    for row in indoor_only_b1_f1:
        lines.append(f"- `{row['floor_id']}` {row['display_name']} / `{row['old_poi_id']}`")
    REPORT_PATH.write_text("\n".join(lines) + "\n", encoding="utf-8")


def validate_resolver(resolver):
    assert resolver["items"], "resolver items cannot be empty"
    for item in resolver["items"]:
        for key in ["poi_id", "name", "floor_id", "aliases", "route_node_id", "match_policy"]:
            assert key in item, f"{item.get('poi_id')} missing {key}"
        refs = item.get("external_refs") or {}
        assert "amap_searchable" in refs, f"{item['poi_id']} missing amap_searchable"
        assert "amap_search_status" in refs, f"{item['poi_id']} missing amap_search_status"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--sync-android-b1-f1", action="store_true")
    args = parser.parse_args()

    source_resolver = json.loads(ALL_FLOORS_RESOLVER_PATH.read_text(encoding="utf-8"))
    old_b1_f1_resolver = json.loads(ANDROID_B1_F1_RESOLVER_PATH.read_text(encoding="utf-8"))
    amap_refs = collect_amap_refs()

    all_floors_app_ready = build_all_floors_app_ready(source_resolver, amap_refs)
    b1_f1_app_ready, mapping_rows = build_b1_f1_app_ready(
        old_b1_f1_resolver, source_resolver, amap_refs
    )

    validate_resolver(all_floors_app_ready)
    validate_resolver(b1_f1_app_ready)

    write_json(ALL_FLOORS_APP_READY_PATH, all_floors_app_ready)
    write_json(B1_F1_APP_READY_PATH, b1_f1_app_ready)
    if args.sync_android_b1_f1:
        write_json(ANDROID_B1_F1_RESOLVER_PATH, b1_f1_app_ready)
    write_report(all_floors_app_ready, b1_f1_app_ready, mapping_rows, args.sync_android_b1_f1)

    all_status = Counter(
        item["external_refs"]["amap_search_status"] for item in all_floors_app_ready["items"]
    )
    b1_f1_status = Counter(
        item["external_refs"]["amap_search_status"] for item in b1_f1_app_ready["items"]
    )
    print(f"all_floors_items={len(all_floors_app_ready['items'])}")
    print(f"b1_f1_items={len(b1_f1_app_ready['items'])}")
    print(f"all_status={dict(sorted(all_status.items()))}")
    print(f"b1_f1_status={dict(sorted(b1_f1_status.items()))}")
    print(f"all_floors_output={ALL_FLOORS_APP_READY_PATH}")
    print(f"b1_f1_output={B1_F1_APP_READY_PATH}")
    if args.sync_android_b1_f1:
        print(f"android_synced={ANDROID_B1_F1_RESOLVER_PATH}")
    print(f"report={REPORT_PATH}")


if __name__ == "__main__":
    main()
