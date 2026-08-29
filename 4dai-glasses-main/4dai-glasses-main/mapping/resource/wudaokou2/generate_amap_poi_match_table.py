import argparse
import csv
import difflib
import json
import re
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path


ROOT_DIR = Path(__file__).resolve().parent
INPUT_PATH = (
    ROOT_DIR
    / "processed"
    / "app_indoor_map"
    / "wudaokou_all_floors_poi_resolver.json"
)
OUTPUT_DIR = ROOT_DIR / "processed" / "app_indoor_map"
RAW_RESULTS_PATH = OUTPUT_DIR / "amap_poi_search_results.json"
CSV_PATH = OUTPUT_DIR / "amap_poi_match_review.csv"
MARKDOWN_PATH = OUTPUT_DIR / "AMAP_POI_MATCH_REVIEW.md"

AMAP_HOME_URL = "https://www.amap.com/"
AMAP_SEARCH_URL = "https://www.amap.com/_AMapService/v3/place/text"
VENUE_NAME = "五道口购物中心"
VENUE_ADDRESS_HINT = "成府路28号"
VENUE_AMAP_POI_ID = "B000A80D2Q"
CITY = "北京"
FLOOR_ORDER = ["B3", "B2", "B1", "F1", "F2", "F3", "F4", "F5", "F6"]
NON_STORE_NAMES = {"wc", "卫生间"}


def has_chinese(text):
    return any("\u4e00" <= char <= "\u9fff" for char in str(text))


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


def markdown_escape(text):
    return str(text if text is not None else "").replace("|", "\\|").replace("\n", " ")


def load_items():
    data = json.loads(INPUT_PATH.read_text(encoding="utf-8"))
    return [
        item
        for item in data["items"]
        if str(item.get("name", "")).lower() not in NON_STORE_NAMES
        and str(item.get("display_name", "")) not in NON_STORE_NAMES
    ]


def resolve_amap_js_key():
    request = urllib.request.Request(
        AMAP_HOME_URL,
        headers={"User-Agent": "Mozilla/5.0"},
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        html = response.read().decode("utf-8", errors="replace")
    match = re.search(r"key=([0-9a-f]{32})", html)
    if not match:
        raise RuntimeError("Cannot resolve AMap JS search key from amap.com")
    return match.group(1)


def build_query_terms(item, max_queries):
    values = [item.get("display_name"), item.get("name")]
    aliases = item.get("aliases") or []
    values.extend(alias for alias in aliases if has_chinese(alias))
    values.extend(aliases[:2])
    terms = []
    for value in unique_values(values):
        if VENUE_NAME in value:
            terms.append(value)
        else:
            terms.append(f"{value} {VENUE_NAME}")
    return unique_values(terms)[:max_queries]


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


def search_amap(keyword, amap_key, offset):
    callback = f"jsonp_{int(time.time() * 1000)}"
    params = {
        "platform": "JS",
        "s": "rsv3",
        "logversion": "2.0",
        "key": amap_key,
        "sdkversion": "2.3.5.6",
        "appname": "https%3A%2F%2Fwww.amap.com%2Fsearch",
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
    url = f"{AMAP_SEARCH_URL}?{urllib.parse.urlencode(params)}"
    request = urllib.request.Request(
        url,
        headers={
            "Referer": "https://www.amap.com/",
            "User-Agent": "Mozilla/5.0",
        },
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        text = response.read().decode("utf-8", errors="replace")
    data = parse_jsonp(text)
    pois = [compact_poi(poi) for poi in data.get("pois") or []]
    return {
        "keyword": keyword,
        "status": data.get("status"),
        "info": data.get("info"),
        "infocode": data.get("infocode"),
        "count": data.get("count"),
        "pois": pois,
    }


def collect_raw_results(items, args):
    amap_key = resolve_amap_js_key()
    results = []
    for index, item in enumerate(items, start=1):
        queries = build_query_terms(item, args.max_queries)
        query_results = []
        print(f"[{index}/{len(items)}] {item['poi_id']} -> {queries[0]}")
        for keyword in queries:
            try:
                query_results.append(search_amap(keyword, amap_key, args.offset))
            except Exception as error:
                query_results.append(
                    {
                        "keyword": keyword,
                        "status": "0",
                        "info": str(error),
                        "infocode": "LOCAL_ERROR",
                        "count": "0",
                        "pois": [],
                    }
                )
            time.sleep(args.sleep_seconds)
        results.append(
            {
                "poi_id": item["poi_id"],
                "floor_id": item["floor_id"],
                "name": item.get("name"),
                "display_name": item.get("display_name"),
                "aliases": item.get("aliases") or [],
                "queries": query_results,
            }
        )

    RAW_RESULTS_PATH.write_text(
        json.dumps(
            {
                "source": "amap_web_js_place_text",
                "generated_at": datetime.now(timezone.utc).isoformat(),
                "input": str(INPUT_PATH.relative_to(ROOT_DIR)),
                "venue_name": VENUE_NAME,
                "venue_amap_poi_id": VENUE_AMAP_POI_ID,
                "items": results,
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    return results


def poi_floor(poi):
    indoor_data = poi.get("indoor_data") or {}
    return indoor_data.get("truefloor") or indoor_data.get("floor") or ""


def score_candidate(item, poi):
    candidate_name = poi.get("name") or ""
    candidate_address = poi.get("address") or ""
    candidate_parent = poi.get("parent") or (poi.get("indoor_data") or {}).get("cpid")
    candidate_floor = poi_floor(poi)
    aliases = item.get("aliases") or []
    name_targets = unique_values(
        [item.get("display_name"), item.get("name")]
        + aliases
        + [alias for alias in aliases if has_chinese(alias)]
    )
    normalized_candidate = normalize_text(candidate_name)
    normalized_targets = [normalize_text(value) for value in name_targets]

    score = 0
    reasons = []
    name_match = False

    if candidate_parent == VENUE_AMAP_POI_ID:
        score += 80
        reasons.append("parent=venue")
    if VENUE_NAME in candidate_address:
        score += 40
        reasons.append("address_has_venue")
    if VENUE_ADDRESS_HINT in candidate_address:
        score += 20
        reasons.append("address_has_venue_address")

    candidate_chinese = chinese_only(candidate_name)
    candidate_chinese_core = (
        candidate_chinese.replace(VENUE_NAME, "").replace("店", "").replace("层", "")
    )
    for value, target in zip(name_targets, normalized_targets):
        target_chinese = chinese_only(value)
        if target and (target in normalized_candidate or normalized_candidate in target):
            score += 50
            reasons.append("name_or_alias_match")
            name_match = True
            break
        if target_chinese and candidate_chinese_core:
            ratio = difflib.SequenceMatcher(
                None, target_chinese, candidate_chinese_core
            ).ratio()
            if ratio >= 0.6:
                score += 40
                reasons.append("fuzzy_name_match")
                name_match = True
                break

    try:
        distance = int(float(poi.get("distance")))
    except (TypeError, ValueError):
        distance = None
    if distance is not None and distance <= 100:
        score += 25
        reasons.append("distance<=100m")
    elif distance is not None and distance <= 300:
        score += 15
        reasons.append("distance<=300m")

    if candidate_floor and candidate_floor.upper() == item.get("floor_id", "").upper():
        score += 10
        reasons.append("floor_match")

    return score, reasons, name_match


def choose_best_candidate(item, raw_item):
    candidates = []
    for query in raw_item.get("queries", []):
        for rank, poi in enumerate(query.get("pois") or [], start=1):
            score, reasons, name_match = score_candidate(item, poi)
            candidates.append(
                {
                    "query_keyword": query.get("keyword"),
                    "query_status": query.get("status"),
                    "query_info": query.get("info"),
                    "query_count": query.get("count"),
                    "query_rank": rank,
                    "score": score,
                    "reasons": reasons,
                    "name_match": name_match,
                    "poi": poi,
                }
            )
    if not candidates:
        first_query = (raw_item.get("queries") or [{}])[0]
        return {
            "query_keyword": first_query.get("keyword", ""),
            "query_status": first_query.get("status", ""),
            "query_info": first_query.get("info", ""),
            "query_count": first_query.get("count", ""),
            "query_rank": "",
            "score": 0,
            "reasons": [],
            "name_match": False,
            "poi": {},
        }
    return sorted(candidates, key=lambda item: (-item["score"], item["query_rank"]))[0]


def match_status(best):
    poi = best["poi"]
    if not poi:
        return "no_candidate"
    parent = poi.get("parent") or (poi.get("indoor_data") or {}).get("cpid")
    at_venue = parent == VENUE_AMAP_POI_ID or VENUE_NAME in str(poi.get("address") or "")
    if best["name_match"] and at_venue:
        return "matched_parent_or_address_and_name"
    if at_venue:
        return "candidate_at_venue_needs_review"
    return "candidate_needs_review"


def build_rows(items, raw_results):
    raw_by_id = {item["poi_id"]: item for item in raw_results}
    rows = []
    for item in items:
        raw_item = raw_by_id.get(item["poi_id"], {})
        best = choose_best_candidate(item, raw_item)
        poi = best["poi"]
        status = match_status(best)
        notes = ";".join(best["reasons"])
        if item.get("name") == "velwin" and status == "no_candidate":
            notes = "高德可能未收录；按 indoor-only POI 人工兜底"
        rows.append(
            {
                "poi_id": item["poi_id"],
                "floor_id": item.get("floor_id"),
                "mapping_label": item.get("name"),
                "mapping_display_name": item.get("display_name"),
                "mapping_aliases": "；".join(item.get("aliases") or []),
                "query_keyword": best.get("query_keyword", ""),
                "amap_search_status": status,
                "amap_query_info": best.get("query_info", ""),
                "amap_query_count": best.get("query_count", ""),
                "amap_candidate_name": poi.get("name", ""),
                "amap_candidate_poi_id": poi.get("id", ""),
                "amap_candidate_parent_poi_id": poi.get("parent", ""),
                "amap_candidate_floor": poi_floor(poi),
                "amap_candidate_address": poi.get("address", ""),
                "amap_candidate_location": poi.get("location", ""),
                "amap_candidate_type": poi.get("type", ""),
                "match_score": best.get("score", 0),
                "match_notes": notes,
                "manual_review_required": "false"
                if status == "matched_parent_or_address_and_name"
                else "true",
            }
        )
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


def write_csv(rows):
    with CSV_PATH.open("w", encoding="utf-8-sig", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)


def write_markdown(rows):
    status_counts = {}
    for row in rows:
        status_counts[row["amap_search_status"]] = (
            status_counts.get(row["amap_search_status"], 0) + 1
        )
    lines = [
        "# 五道口 wudaokou2 店铺高德 POI 对应复核表",
        "",
        "## 说明",
        "",
        f"- 来源：`{INPUT_PATH.relative_to(ROOT_DIR)}`。",
        "- 搜索方式：高德网页 JS `place/text` 搜索；每个室内 POI 使用 `mapping_display_name + 五道口购物中心` 等关键词尝试匹配。",
        "- `matched_parent_or_address_and_name` 表示候选结果的高德父 POI 或地址落在五道口购物中心，且名称/alias 命中；仍建议人工抽检。",
        "- `candidate_at_venue_needs_review` 表示候选落在商场内，但名称命中较弱，需要人工确认是否为同一家。",
        "- `candidate_needs_review` / `no_candidate` 不应直接写回 App resolver，需要人工处理。",
        "",
        "## 状态统计",
        "",
    ]
    for status, count in sorted(status_counts.items()):
        lines.append(f"- `{status}`: {count}")
    lines.extend(
        [
            "",
            "## 表格",
            "",
            "| floor | label | display_name | query | status | 高德候选名 | 高德 POI ID | 高德楼层 | 地址 | score | notes |",
            "|---|---|---|---|---|---|---|---|---|---:|---|",
        ]
    )
    for row in rows:
        lines.append(
            "| "
            + " | ".join(
                [
                    markdown_escape(row["floor_id"]),
                    markdown_escape(row["mapping_label"]),
                    markdown_escape(row["mapping_display_name"]),
                    markdown_escape(row["query_keyword"]),
                    markdown_escape(row["amap_search_status"]),
                    markdown_escape(row["amap_candidate_name"] or "-"),
                    markdown_escape(row["amap_candidate_poi_id"] or "-"),
                    markdown_escape(row["amap_candidate_floor"] or "-"),
                    markdown_escape(row["amap_candidate_address"] or "-"),
                    markdown_escape(row["match_score"]),
                    markdown_escape(row["match_notes"] or "-"),
                ]
            )
            + " |"
        )
    MARKDOWN_PATH.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--fetch", action="store_true", help="fetch live AMap search results")
    parser.add_argument("--max-queries", type=int, default=3)
    parser.add_argument("--offset", type=int, default=5)
    parser.add_argument("--sleep-seconds", type=float, default=0.25)
    args = parser.parse_args()

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    items = load_items()
    if args.fetch:
        raw_results = collect_raw_results(items, args)
    else:
        raw_results = json.loads(RAW_RESULTS_PATH.read_text(encoding="utf-8"))["items"]

    rows = build_rows(items, raw_results)
    write_csv(rows)
    write_markdown(rows)
    print(f"items={len(items)}")
    print(f"raw_results={RAW_RESULTS_PATH}")
    print(f"csv={CSV_PATH}")
    print(f"markdown={MARKDOWN_PATH}")


if __name__ == "__main__":
    main()
