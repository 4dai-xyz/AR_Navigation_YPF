import csv
import difflib
import argparse
import json
import re
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path


ROOT_DIR = Path(__file__).resolve().parent
OUTPUT_DIR = ROOT_DIR / "processed" / "app_indoor_map"
SOURCE_CSV_PATH = OUTPUT_DIR / "amap_poi_match_review.csv"
RAW_OUTPUT_PATH = OUTPUT_DIR / "amap_poi_wrong_matches_retry_results.json"
CSV_OUTPUT_PATH = OUTPUT_DIR / "amap_poi_wrong_matches_retry.csv"
TXT_OUTPUT_PATH = OUTPUT_DIR / "amap_poi_wrong_matches_retry.txt"
PINYIN_CSV_OUTPUT_PATH = OUTPUT_DIR / "amap_poi_wrong_matches_pinyin_retry.csv"
PINYIN_TXT_OUTPUT_PATH = OUTPUT_DIR / "amap_poi_wrong_matches_pinyin_retry.txt"

AMAP_MOBILE_HOME_URL = "https://m.amap.com/"
AMAP_MOBILE_SEARCH_URL = "https://m.amap.com/_AMapService/v3/place/text"
VENUE_NAME = "五道口购物中心"
VENUE_ADDRESS_HINT = "成府路28号"
VENUE_AMAP_POI_ID = "B000A80D2Q"
CITY = "北京"
FLOOR_ORDER = ["B3", "B2", "B1", "F1", "F2", "F3", "F4", "F5", "F6"]


def has_chinese(text):
    return any("\u4e00" <= char <= "\u9fff" for char in str(text or ""))


def normalize_text(text):
    return "".join(
        char.lower()
        for char in str(text or "")
        if char.isalnum() or "\u4e00" <= char <= "\u9fff"
    )


def chinese_only(text):
    return "".join(char for char in str(text or "") if "\u4e00" <= char <= "\u9fff")


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


def poi_floor(poi):
    indoor_data = poi.get("indoor_data") or {}
    return indoor_data.get("truefloor") or indoor_data.get("floor") or ""


def resolve_mobile_amap_key():
    request = urllib.request.Request(
        AMAP_MOBILE_HOME_URL,
        headers={"User-Agent": "Mozilla/5.0"},
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        html = response.read().decode("utf-8", errors="replace")
    match = re.search(r"key=([0-9a-f]{32})", html)
    if not match:
        raise RuntimeError("Cannot resolve AMap mobile JS search key from m.amap.com")
    return match.group(1)


def parse_jsonp(text):
    start = text.find("(")
    end = text.rfind(")")
    if start < 0 or end <= start:
        raise ValueError("Invalid JSONP response")
    return json.loads(text[start + 1 : end])


def compact_poi(poi):
    return {
        "id": poi.get("id"),
        "parent": poi.get("parent"),
        "childtype": poi.get("childtype"),
        "name": poi.get("name"),
        "type": poi.get("type"),
        "typecode": poi.get("typecode"),
        "address": poi.get("address"),
        "location": poi.get("location"),
        "distance": poi.get("distance"),
        "business_area": poi.get("business_area"),
        "indoor_map": poi.get("indoor_map"),
        "indoor_data": poi.get("indoor_data"),
    }


def search_amap(keyword, amap_key, offset=20):
    callback = f"jsonp_{int(time.time() * 1000)}"
    params = {
        "platform": "JS",
        "s": "rsv3",
        "logversion": "2.0",
        "key": amap_key,
        "sdkversion": "2.3.5.6",
        "appname": "https%3A%2F%2Fm.amap.com%2Fsearch",
        "city": CITY,
        "page": "1",
        "offset": str(offset),
        "citylimit": "true",
        "language": "zh_cn",
        "children": "",
        "type_": "KEYWORD",
        "antiCrab": "true",
        "extensions": "all",
        "keywords": keyword,
        "callback": callback,
    }
    url = f"{AMAP_MOBILE_SEARCH_URL}?{urllib.parse.urlencode(params)}"
    request = urllib.request.Request(
        url,
        headers={
            "Referer": AMAP_MOBILE_HOME_URL,
            "User-Agent": "Mozilla/5.0",
        },
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        data = parse_jsonp(response.read().decode("utf-8", errors="replace"))
    return {
        "keyword": keyword,
        "status": data.get("status"),
        "info": data.get("info"),
        "infocode": data.get("infocode"),
        "count": data.get("count"),
        "pois": [compact_poi(poi) for poi in data.get("pois") or []],
    }


def load_wrong_rows():
    last_error = None
    for encoding in ("utf-8-sig", "gb18030"):
        try:
            text = SOURCE_CSV_PATH.read_text(encoding=encoding)
            first_line = text.splitlines()[0]
            delimiter = "\t" if "\t" in first_line else ","
            rows = list(csv.DictReader(text.splitlines(), delimiter=delimiter))
            break
        except UnicodeDecodeError as error:
            last_error = error
    else:
        raise last_error
    rows = [
        row
        for row in rows
        if str(row.get("manual_review_required", "")).strip().lower() == "true"
    ]
    return sorted(
        rows,
        key=lambda row: (
            FLOOR_ORDER.index(row["floor_id"])
            if row["floor_id"] in FLOOR_ORDER
            else len(FLOOR_ORDER),
            row["mapping_label"],
            row["poi_id"],
        ),
    )


def build_query_terms(row):
    aliases = [
        alias.strip()
        for alias in str(row.get("mapping_aliases") or "").split("；")
        if alias.strip()
    ]
    display_name = row["mapping_display_name"].strip()
    label = row["mapping_label"].strip()
    normalized_label = normalize_text(label)
    non_chinese_aliases = [
        alias
        for alias in aliases
        if not has_chinese(alias)
        and (
            normalize_text(alias) == normalized_label
            or len(normalize_text(alias)) >= 4
        )
    ]
    chinese_aliases = [alias for alias in aliases if has_chinese(alias)]
    names = unique_values(
        [label]
        + non_chinese_aliases
        + [display_name]
        + chinese_aliases
    )
    terms = []
    for name in names:
        terms.extend(
            [
                f"{name} 五道口",
                f"{name} 成府路",
                f"{name} {VENUE_ADDRESS_HINT}",
                f"{name} {VENUE_NAME}",
                f"{name} 北京",
            ]
        )
    return unique_values(terms)[:18]


def name_match_score(row, poi):
    candidate_name = poi.get("name") or ""
    candidate_normalized = normalize_text(candidate_name)
    candidate_chinese = chinese_only(candidate_name)
    candidate_chinese_core = (
        candidate_chinese.replace(VENUE_NAME, "")
        .replace("购物中心", "")
        .replace("北京", "")
        .replace("海淀", "")
        .replace("店", "")
        .replace("层", "")
    )

    aliases = [
        alias.strip()
        for alias in str(row.get("mapping_aliases") or "").split("；")
        if alias.strip()
    ]
    label = row["mapping_label"]
    normalized_label = normalize_text(label)
    filtered_aliases = [
        alias
        for alias in aliases
        if has_chinese(alias)
        or normalize_text(alias) == normalized_label
        or len(normalize_text(alias)) >= 4
    ]
    targets = unique_values([row["mapping_display_name"], label] + filtered_aliases)
    for target in targets:
        normalized_target = normalize_text(target)
        if normalized_target and (
            normalized_target in candidate_normalized
            or candidate_normalized in normalized_target
        ):
            return 100, "name_or_alias_match"

        target_chinese = chinese_only(target)
        if target_chinese and candidate_chinese_core:
            ratio = difflib.SequenceMatcher(
                None, target_chinese, candidate_chinese_core
            ).ratio()
            if (len(target_chinese) >= 4 and ratio >= 0.6) or ratio >= 0.78:
                return int(80 * ratio), f"fuzzy_name_match:{ratio:.2f}"
    return 0, ""


def score_candidate(row, poi):
    score, name_reason = name_match_score(row, poi)
    reasons = []
    if name_reason:
        reasons.append(name_reason)

    parent = poi.get("parent") or (poi.get("indoor_data") or {}).get("cpid")
    address = str(poi.get("address") or "")
    floor = poi_floor(poi).upper()
    floor_id = row["floor_id"].upper()
    at_venue = parent == VENUE_AMAP_POI_ID or VENUE_NAME in address

    if parent == VENUE_AMAP_POI_ID:
        score += 60
        reasons.append("parent=venue")
    if VENUE_NAME in address:
        score += 40
        reasons.append("address_has_venue")
    if VENUE_ADDRESS_HINT in address:
        score += 30
        reasons.append("address_has_venue_address")
    if floor and floor == floor_id:
        score += 10
        reasons.append("floor_match")

    try:
        distance = int(float(poi.get("distance")))
    except (TypeError, ValueError):
        distance = None
    if distance is not None and distance <= 500:
        score += 20
        reasons.append("distance<=500m")
    elif distance is not None and distance <= 3000:
        score += 8
        reasons.append("distance<=3000m")

    if not name_reason:
        score -= 120
        reasons.append("no_name_match_penalty")

    return score, reasons, bool(name_reason), at_venue


def choose_best(row, queries):
    candidates = []
    for query in queries:
        for rank, poi in enumerate(query.get("pois") or [], start=1):
            score, reasons, has_name_match, at_venue = score_candidate(row, poi)
            candidates.append(
                {
                    "query_keyword": query["keyword"],
                    "query_info": query.get("info", ""),
                    "query_count": query.get("count", ""),
                    "query_rank": rank,
                    "score": score,
                    "reasons": reasons,
                    "has_name_match": has_name_match,
                    "at_venue": at_venue,
                    "poi": poi,
                }
            )
    if not candidates:
        return {}
    return sorted(candidates, key=lambda item: (-item["score"], item["query_rank"]))[0]


def result_status(best):
    if not best:
        return "no_candidate"
    if best["has_name_match"] and best["at_venue"]:
        return "name_and_venue_matched"
    if best["has_name_match"]:
        return "name_matched_not_venue"
    return "no_reliable_name_match"


def build_summary_row(row, best):
    poi = best.get("poi", {}) if best else {}
    status = result_status(best)
    return {
        "poi_id": row["poi_id"],
        "floor_id": row["floor_id"],
        "mapping_label": row["mapping_label"],
        "mapping_display_name": row["mapping_display_name"],
        "previous_candidate_name": row["amap_candidate_name"],
        "previous_candidate_poi_id": row["amap_candidate_poi_id"],
        "retry_query_keyword": best.get("query_keyword", ""),
        "retry_status": status,
        "retry_candidate_name": poi.get("name", ""),
        "retry_candidate_poi_id": poi.get("id", ""),
        "retry_candidate_parent_poi_id": poi.get("parent", ""),
        "retry_candidate_floor": poi_floor(poi),
        "retry_candidate_address": poi.get("address", ""),
        "retry_candidate_location": poi.get("location", ""),
        "retry_candidate_type": poi.get("type", ""),
        "retry_match_score": best.get("score", 0) if best else 0,
        "retry_match_notes": ";".join(best.get("reasons", [])) if best else "",
        "recommended_action": "can_use_after_manual_confirm"
        if status == "name_and_venue_matched"
        else "manual_review_or_indoor_only",
    }


def collect_retry_results(rows):
    amap_key = resolve_mobile_amap_key()
    raw_items = []
    summary_rows = []
    for index, row in enumerate(rows, start=1):
        terms = build_query_terms(row)
        print(f"[{index}/{len(rows)}] {row['mapping_display_name']} -> {terms[0]}")
        query_results = []
        for term in terms:
            try:
                query_results.append(search_amap(term, amap_key))
            except Exception as error:
                query_results.append(
                    {
                        "keyword": term,
                        "status": "0",
                        "info": str(error),
                        "infocode": "LOCAL_ERROR",
                        "count": "0",
                        "pois": [],
                    }
                )
            time.sleep(0.12)

        best = choose_best(row, query_results)
        summary_rows.append(build_summary_row(row, best))
        raw_items.append(
            {
                "poi_id": row["poi_id"],
                "floor_id": row["floor_id"],
                "mapping_label": row["mapping_label"],
                "mapping_display_name": row["mapping_display_name"],
                "queries": query_results,
            }
        )

    RAW_OUTPUT_PATH.write_text(
        json.dumps(
            {
                "source": "m_amap_web_js_place_text",
                "generated_at": datetime.now(timezone.utc).isoformat(),
                "input": str(SOURCE_CSV_PATH.relative_to(ROOT_DIR)),
                "filter": "manual_review_required=true",
                "venue_name": VENUE_NAME,
                "venue_amap_poi_id": VENUE_AMAP_POI_ID,
                "items": raw_items,
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    return summary_rows


def build_retry_results_from_raw(rows):
    raw_data = json.loads(RAW_OUTPUT_PATH.read_text(encoding="utf-8"))
    raw_by_poi_id = {item["poi_id"]: item for item in raw_data.get("items", [])}
    retry_rows = []
    for row in rows:
        raw_item = raw_by_poi_id.get(row["poi_id"], {})
        best = choose_best(row, raw_item.get("queries", []))
        retry_rows.append(build_summary_row(row, best))
    return retry_rows


def write_csv(rows):
    output_path = CSV_OUTPUT_PATH
    try:
        file = output_path.open("w", encoding="utf-8-sig", newline="")
    except PermissionError:
        output_path = PINYIN_CSV_OUTPUT_PATH
        file = output_path.open("w", encoding="utf-8-sig", newline="")
    with file:
        writer = csv.DictWriter(file, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)
    return output_path


def write_txt(rows):
    status_counts = {}
    for row in rows:
        status_counts[row["retry_status"]] = status_counts.get(row["retry_status"], 0) + 1

    lines = [
        "五道口购物中心 wudaokou2 高德错误候选重搜结果",
        "",
        f"来源: {SOURCE_CSV_PATH.relative_to(ROOT_DIR)}",
        "筛选条件: manual_review_required=true",
        f"数量: {len(rows)}",
        "说明: 本轮改用 m.amap.com 搜索入口，并把名称匹配优先级置于商场归属之前。",
        "补充: 查询词按 mapping_label / 拼音或英文 alias 优先，再尝试中文展示名和中文 alias。",
        "",
        "状态统计:",
    ]
    for status, count in sorted(status_counts.items()):
        lines.append(f"- {status}: {count}")
    lines.append("")

    current_floor = None
    for index, row in enumerate(rows, start=1):
        if row["floor_id"] != current_floor:
            current_floor = row["floor_id"]
            lines.append(f"[{current_floor}]")
        lines.extend(
            [
                f"{index}. {row['mapping_display_name']} ({row['mapping_label']})",
                f"   poi_id: {row['poi_id']}",
                f"   重搜状态: {row['retry_status']}",
                f"   重搜关键词: {row['retry_query_keyword'] or '-'}",
                f"   高德候选: {row['retry_candidate_name'] or '-'}",
                f"   高德 POI ID: {row['retry_candidate_poi_id'] or '-'}",
                f"   高德楼层: {row['retry_candidate_floor'] or '-'}",
                f"   高德地址: {row['retry_candidate_address'] or '-'}",
                f"   建议动作: {row['recommended_action']}",
                f"   上轮错误候选: {row['previous_candidate_name'] or '-'}",
                "",
            ]
        )

    output_path = TXT_OUTPUT_PATH
    try:
        output_path.write_text("\n".join(lines), encoding="utf-8")
    except PermissionError:
        output_path = PINYIN_TXT_OUTPUT_PATH
        output_path.write_text("\n".join(lines), encoding="utf-8")
    return output_path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--from-raw", action="store_true")
    args = parser.parse_args()

    rows = load_wrong_rows()
    retry_rows = build_retry_results_from_raw(rows) if args.from_raw else collect_retry_results(rows)
    csv_path = write_csv(retry_rows)
    txt_path = write_txt(retry_rows)
    print(f"rows={len(retry_rows)}")
    print(f"raw={RAW_OUTPUT_PATH}")
    print(f"csv={csv_path}")
    print(f"txt={txt_path}")


if __name__ == "__main__":
    main()
