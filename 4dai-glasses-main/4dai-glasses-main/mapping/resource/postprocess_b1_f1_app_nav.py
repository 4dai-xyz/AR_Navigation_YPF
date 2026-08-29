import json
import math
import re
from collections import defaultdict, deque
from pathlib import Path

import numpy as np
from PIL import Image


ROOT = Path(__file__).resolve().parent
OUT = ROOT / "processed"
GRID_STEP_PX = 35
MASK_THRESHOLD = 128
MIN_WALKABLE_PIXELS_PER_CELL = 16
METERS_PER_PIXEL = 0.10
VENUE_ID = "venue_bj_wudaokou_shopping_center_demo"
VENUE_NAME = "北京五道口购物中心 DEMO"
FLOORS = {
    "B1": {"labelme": "B1.json", "image": "b1.jpg", "mask": "B1_mask.jpg"},
    "F1": {"labelme": "F1.json", "image": "f1.jpg", "mask": "F1_mask.jpg"},
}
ENTRANCE_LABELS = {"NG": "north_gate", "EG": "east_gate", "WG": "west_gate"}
POI_ALIAS_OVERRIDES = {
    "poi_b1_baozhu_102": ["baozhu", "宝珠", "宝珠奶酪"],
    "poi_b1_coco_62": ["coco", "CoCo", "CoCo都可", "都可", "都可茶饮", "keke", "cocodouke"],
    "poi_b1_dec_27": ["dec", "DEC"],
    "poi_b1_fudi_37": ["fudi", "fudi+", "FUDI", "FUDI+", "fudi精选超市", "FUDI精选超市", "精选超市", "超市", "fudijxcs"],
    "poi_b1_jvewei_25": ["jvewei", "juewei", "jueweiyabo", "绝味", "绝味鸭脖", "鸭脖"],
    "poi_b1_lutaizong_45": ["lutaizong", "卤太宗", "卤太宗大鸡腿饭", "大鸡腿饭", "lutai", "ltz"],
    "poi_b1_qishier_28": ["qishier", "七十二", "柒十二", "七十二匠", "柒十二匠", "72", "qse"],
    "poi_b1_ruixing_luckin_26": ["ruixing(luckin)", "ruixing", "luckin", "luckin coffee", "瑞幸", "瑞幸咖啡", "咖啡", "rxkf"],
    "poi_b1_shengnuoyi_1": ["shengnuoyi", "圣诺伊", "圣诺伊口腔", "口腔", "sny"],
    "poi_b1_shguopo_44": ["shaguopo", "下饭菜", "砂锅", "砂锅婆", "砂锅婆砂锅下饭菜"],
    "poi_b1_shibiman_31": ["shibiman", "诗碧曼", "诗碧曼白发防脱发养发馆", "养发馆"],
    "poi_b1_wanmeishijie_22": ["wanmeishijie", "完美世界", "完美世界影城", "wmsj"],
    "poi_b1_wc_2222": ["wc", "WC", "TOILET", "卫生间", "洗手间", "厕所", "卫生间B1"],
    "poi_b1_wuyutai_29": ["wuyutai", "吴裕泰", "吴裕泰茶庄", "茶庄", "茶叶", "wyt"],
    "poi_b1_xiecheng_33": ["xiecheng", "携程", "携程旅游", "携程旅游五道口门市部", "旅游", "xc"],
    "poi_b1_yimayila_24": ["yimayila", "一麻一辣", "一麻一辣麻辣香锅", "一麻一辣五道口店", "麻辣香锅", "yimayilamalaxiangguo", "ymal"],
    "poi_b1_zhangruhuo_27": ["zhangruhuo", "张如火", "张如火酸辣粉", "酸辣粉", "zrh"],
    "poi_b1_zigulu_47": ["zigulu", "子固路", "子固路拌粉", "拌粉"],
    "poi_f1_huashuo_6": ["huashuo", "华硕", "ASUS", "asus", "华硕电脑", "华硕电脑售后", "华硕数码配件", "数码配件"],
    "poi_f1_huawei_7": ["huawei", "华为", "华为授权", "华为授权体验店", "华为手机", "hw"],
    "poi_f1_jack_7": ["jack", "JACK", "jackjones", "JACK&JONES", "杰克琼斯"],
    "poi_f1_lanxiong_103": ["lanxiong", "兰熊", "兰熊鲜奶", "鲜奶", "lx"],
    "poi_f1_lianxiang_4": ["lianxiang", "联想", "Lenovo", "lenovo", "联想百应", "联想百应电脑维修销售", "电脑维修", "电脑销售", "lx"],
    "poi_f1_maidanglao_0": ["maidanglao", "麦当劳", "McDonald's", "mcdonalds", "M记", "mcd"],
    "poi_f1_manner_coffee_3": ["manner coffee", "manner", "Manner", "Manner Coffee", "MANNER COFFEE", "咖啡"],
    "poi_f1_nike_67": ["nike", "NIKE", "Nike", "耐克", "naike"],
    "poi_f1_popmart_8": ["popmart", "POP MART", "泡泡玛特", "泡泡马特", "盲盒", "pop mart"],
    "poi_f1_starbuck_1": ["starbuck", "starbucks", "Starbucks", "STARBUCKS", "星巴克", "星巴克咖啡", "sbk"],
    "poi_f1_xiaomi_13": ["xiaomi", "小米", "小米之家", "小米手机", "mi"],
    "poi_f1_zhongguohuangjin_5": ["zhongguohuangjin", "中国黄金", "黄金", "zg hj", "zghj"],
    "poi_f1_zhoudafu_2": ["zhoudafu", "周大福", "Chow Tai Fook", "chow tai fook", "ctf", "zdf"],
}


def unique_aliases(values):
    aliases = []
    seen = set()
    for value in values:
        if value is None:
            continue
        alias = str(value).strip()
        if not alias or alias in seen:
            continue
        aliases.append(alias)
        seen.add(alias)
    return aliases


def slugify(value):
    text = re.sub(r"[^a-z0-9]+", "_", str(value).strip().lower())
    return re.sub(r"_+", "_", text).strip("_") or "unnamed"


def distance_px(first_point, second_point):
    return math.hypot(second_point[0] - first_point[0], second_point[1] - first_point[1])


def edge_id(from_node_id, to_node_id):
    return f"edge_{from_node_id.removeprefix('node_')}_to_{to_node_id.removeprefix('node_')}"


def walk_edge(from_node_id, to_node_id, floor_id, pixel_distance, status, line_walkable=True):
    distance_m = round(pixel_distance * METERS_PER_PIXEL, 3)
    return {
        "edge_id": edge_id(from_node_id, to_node_id),
        "from_node_id": from_node_id,
        "to_node_id": to_node_id,
        "floor_id": floor_id,
        "travel_mode": "walk",
        "bidirectional": True,
        "distance": distance_m,
        "distance_unit": "meter_estimate",
        "distance_px": round(pixel_distance, 3),
        "cost_seconds": round(distance_m / 1.2, 1),
        "status": status,
        "line_walkable": line_walkable,
    }


def vertical_edge(from_connector, to_connector, mode, bidirectional, status, seconds):
    return {
        "edge_id": edge_id(from_connector["route_node_id"], to_connector["route_node_id"]),
        "from_node_id": from_connector["route_node_id"],
        "to_node_id": to_connector["route_node_id"],
        "travel_mode": mode,
        "bidirectional": bidirectional,
        "distance": 3.0,
        "distance_unit": "meter_estimate",
        "distance_px": 30.0,
        "cost_seconds": seconds,
        "status": status,
        "connector_group_id": from_connector["group_id"],
        "from_connector_id": from_connector["connector_id"],
        "to_connector_id": to_connector["connector_id"],
    }


def line_is_walkable(mask, first_point, second_point):
    first_x, first_y = first_point
    second_x, second_y = second_point
    sample_count = max(1, int(distance_px(first_point, second_point) / 6))
    height, width = mask.shape
    for sample_index in range(sample_count + 1):
        ratio = sample_index / sample_count
        pixel_x = round(first_x + (second_x - first_x) * ratio)
        pixel_y = round(first_y + (second_y - first_y) * ratio)
        if pixel_x < 0 or pixel_y < 0 or pixel_x >= width or pixel_y >= height:
            return False
        if not mask[pixel_y, pixel_x]:
            return False
    return True


def nearest_walkable(mask, point):
    point_x, point_y = point
    walkable_y, walkable_x = np.nonzero(mask)
    best_point = None
    best_distance = None
    for start_index in range(0, len(walkable_x), 200_000):
        chunk_x = walkable_x[start_index : start_index + 200_000]
        chunk_y = walkable_y[start_index : start_index + 200_000]
        distances = (chunk_x - point_x) ** 2 + (chunk_y - point_y) ** 2
        chunk_index = int(np.argmin(distances))
        chunk_distance = float(distances[chunk_index])
        if best_distance is None or chunk_distance < best_distance:
            best_distance = chunk_distance
            best_point = (int(chunk_x[chunk_index]), int(chunk_y[chunk_index]))
    return best_point, math.sqrt(best_distance or 0.0)


def nearest_grid(nodes, floor_id, point, mask):
    point_x, point_y = point
    candidates = sorted(
        [node for node in nodes if node["floor_id"] == floor_id and node["node_type"] == "walkable_grid"],
        key=lambda node: (node["source_pixel"]["x"] - point_x) ** 2 + (node["source_pixel"]["y"] - point_y) ** 2,
    )
    for node in candidates[:80]:
        node_point = (node["source_pixel"]["x"], node["source_pixel"]["y"])
        if line_is_walkable(mask, point, node_point):
            return node, distance_px(point, node_point), True
    node = candidates[0]
    node_point = (node["source_pixel"]["x"], node["source_pixel"]["y"])
    return node, distance_px(point, node_point), False


def classify(label):
    if label in ENTRANCE_LABELS:
        return "entrance"
    if label.startswith("escalator") or label in {"elevator", "step"}:
        return "connector"
    if label == "wc":
        return "facility"
    return "poi_internal_sample"


def load_inputs():
    floors = {}
    qa = []
    for floor_id, config in FLOORS.items():
        labelme = json.loads((ROOT / config["labelme"]).read_text(encoding="utf-8"))
        image = Image.open(ROOT / config["image"])
        mask_image = Image.open(ROOT / config["mask"]).convert("L")
        mask = np.array(mask_image) >= MASK_THRESHOLD
        if image.size != mask_image.size:
            qa.append({"severity": "error", "code": "image_mask_size_mismatch", "floor_id": floor_id})
        if [labelme.get("imageWidth"), labelme.get("imageHeight")] != list(image.size):
            qa.append({"severity": "error", "code": "labelme_image_size_mismatch", "floor_id": floor_id})
        floors[floor_id] = {"labelme": labelme, "image_size": image.size, "mask": mask}
    return floors, qa


def build_grid(floors):
    nodes = []
    edges = []
    for floor_id, floor_data in floors.items():
        mask = floor_data["mask"]
        height, width = mask.shape
        index = {}
        for cell_y, pixel_y in enumerate(range(0, height, GRID_STEP_PX)):
            for cell_x, pixel_x in enumerate(range(0, width, GRID_STEP_PX)):
                cell = mask[pixel_y : min(pixel_y + GRID_STEP_PX, height), pixel_x : min(pixel_x + GRID_STEP_PX, width)]
                walkable_y, walkable_x = np.nonzero(cell)
                if len(walkable_x) < MIN_WALKABLE_PIXELS_PER_CELL:
                    continue
                center_x = pixel_x + (cell.shape[1] - 1) / 2
                center_y = pixel_y + (cell.shape[0] - 1) / 2
                distances = (walkable_x + pixel_x - center_x) ** 2 + (walkable_y + pixel_y - center_y) ** 2
                nearest_index = int(np.argmin(distances))
                node_x = int(walkable_x[nearest_index] + pixel_x)
                node_y = int(walkable_y[nearest_index] + pixel_y)
                node_id = f"node_{floor_id.lower()}_grid_{len(index) + 1:04d}"
                index[(cell_x, cell_y)] = {"node_id": node_id, "point": (node_x, node_y)}
                nodes.append({
                    "node_id": node_id,
                    "floor_id": floor_id,
                    "node_type": "walkable_grid",
                    "x": node_x,
                    "y": node_y,
                    "source_pixel": {"x": node_x, "y": node_y},
                    "grid_cell": {"x": cell_x, "y": cell_y},
                    "cell_walkable_pixels": int(len(walkable_x)),
                    "status": "generated_from_mask_cell",
                })
        for (cell_x, cell_y), from_item in index.items():
            from_point = from_item["point"]
            for offset_x, offset_y in ((1, 0), (0, 1), (1, 1), (1, -1)):
                neighbor = (cell_x + offset_x, cell_y + offset_y)
                if neighbor in index and line_is_walkable(mask, from_point, index[neighbor]["point"]):
                    to_point = index[neighbor]["point"]
                    edges.append(walk_edge(from_item["node_id"], index[neighbor]["node_id"], floor_id, distance_px(from_point, to_point), "generated_from_mask_cell"))
    return nodes, edges


def extract_annotations(floors):
    points = []
    qa = []
    for floor_id, floor_data in floors.items():
        mask = floor_data["mask"]
        height, width = mask.shape
        occurrences = defaultdict(int)
        for shape in floor_data["labelme"].get("shapes", []):
            label = str(shape.get("label", "")).strip()
            label_slug = slugify(label)
            occurrences[label_slug] += 1
            if shape.get("shape_type") != "point" or not shape.get("points"):
                qa.append({"severity": "warning", "code": "unsupported_shape", "floor_id": floor_id, "label": label})
                continue
            source_pixel = {"x": round(float(shape["points"][0][0]), 3), "y": round(float(shape["points"][0][1]), 3)}
            if source_pixel["x"] < 0 or source_pixel["y"] < 0 or source_pixel["x"] >= width or source_pixel["y"] >= height:
                qa.append({"severity": "error", "code": "annotation_out_of_bounds", "floor_id": floor_id, "label": label, "source_pixel": source_pixel})
            access_point, access_distance = nearest_walkable(mask, (source_pixel["x"], source_pixel["y"]))
            if access_distance > 60:
                qa.append({"severity": "warning", "code": "annotation_far_from_walkable_mask", "floor_id": floor_id, "label": label, "group_id": shape.get("group_id"), "distance_px": round(access_distance, 1)})
            points.append({
                "floor_id": floor_id,
                "label": label,
                "label_slug": label_slug,
                "group_id": shape.get("group_id"),
                "occurrence": occurrences[label_slug],
                "object_type": classify(label),
                "source_pixel": source_pixel,
                "nearest_walkable_pixel": {"x": access_point[0], "y": access_point[1]},
                "distance_to_walkable_px": round(access_distance, 3),
            })
    return points, qa


def add_access_node(nodes, edges, floors, node):
    nodes.append(node)
    floor_id = node["floor_id"]
    access_point = (node["access_pixel"]["x"], node["access_pixel"]["y"])
    grid_node, grid_distance, direct = nearest_grid(nodes, floor_id, access_point, floors[floor_id]["mask"])
    edges.append(walk_edge(node["node_id"], grid_node["node_id"], floor_id, grid_distance, "generated_access_link", direct))


def add_semantic_points(nodes, edges, floors, points):
    pois = []
    connectors = []
    entrances = []
    poi_groups = defaultdict(list)
    for point in points:
        if point["object_type"] == "poi_internal_sample":
            poi_groups[(point["floor_id"], point["label_slug"], point["group_id"])].append(point)
            continue
        floor_id = point["floor_id"]
        group_id = point["group_id"] if point["group_id"] is not None else point["occurrence"]
        if point["object_type"] == "entrance":
            ref_id = f"entrance_{floor_id.lower()}_{ENTRANCE_LABELS[point['label']]}"
            node_type = "entrance"
        elif point["object_type"] == "facility":
            ref_id = f"poi_{floor_id.lower()}_{point['label_slug']}_{group_id}"
            node_type = "poi_anchor"
        else:
            ref_id = f"connector_{floor_id.lower()}_{point['label_slug']}_{group_id}"
            node_type = "connector"
        node_id = f"node_{ref_id}_access"
        node = {
            "node_id": node_id,
            "floor_id": floor_id,
            "node_type": node_type,
            "ref_id": ref_id,
            "label": point["label"],
            "x": point["nearest_walkable_pixel"]["x"],
            "y": point["nearest_walkable_pixel"]["y"],
            "source_pixel": point["source_pixel"],
            "access_pixel": point["nearest_walkable_pixel"],
            "access_strategy": "nearest_walkable_from_annotation",
            "status": "generated_from_annotation",
        }
        add_access_node(nodes, edges, floors, node)
        if point["object_type"] == "connector":
            connectors.append({"connector_id": ref_id, "floor_id": floor_id, "connector_type": "escalator" if point["label"].startswith("escalator") else point["label"], "group_id": point["group_id"], "direction_hint": point["label"], "route_node_id": node_id, "source_pixel": point["source_pixel"], "access_pixel": point["nearest_walkable_pixel"], "status": "generated_from_annotation"})
        elif point["object_type"] == "entrance":
            entrances.append({"entrance_id": ref_id, "floor_id": floor_id, "entrance_type": ENTRANCE_LABELS[point["label"]], "route_node_id": node_id, "source_pixel": point["source_pixel"], "access_pixel": point["nearest_walkable_pixel"], "status": "generated_from_annotation"})
        else:
            pois.append({"poi_id": ref_id, "poi_type": "facility", "poi_name": point["label"], "venue_id": VENUE_ID, "floor_id": floor_id, "position": point["source_pixel"], "route_node_id": node_id, "source_samples": [point["source_pixel"]], "access_pixel": point["nearest_walkable_pixel"], "access_strategy": "nearest_walkable_from_annotation", "tags": ["facility"], "status": "draft"})
    for (floor_id, label_slug, group_id), samples in sorted(poi_groups.items()):
        best_sample = min(samples, key=lambda sample: sample["distance_to_walkable_px"])
        group_suffix = f"_{group_id}" if group_id is not None else ""
        poi_id = f"poi_{floor_id.lower()}_{label_slug}{group_suffix}"
        node_id = f"node_{floor_id.lower()}_{label_slug}{group_suffix}_access"
        node = {
            "node_id": node_id,
            "floor_id": floor_id,
            "node_type": "poi_anchor",
            "ref_id": poi_id,
            "label": samples[0]["label"],
            "x": best_sample["nearest_walkable_pixel"]["x"],
            "y": best_sample["nearest_walkable_pixel"]["y"],
            "source_pixel": best_sample["source_pixel"],
            "access_pixel": best_sample["nearest_walkable_pixel"],
            "access_strategy": "nearest_walkable_from_internal_samples",
            "status": "generated_from_annotation",
        }
        add_access_node(nodes, edges, floors, node)
        pois.append({"poi_id": poi_id, "poi_type": "store_internal_sample", "poi_name": samples[0]["label"], "venue_id": VENUE_ID, "floor_id": floor_id, "position": best_sample["source_pixel"], "route_node_id": node_id, "source_samples": [sample["source_pixel"] for sample in samples], "access_pixel": best_sample["nearest_walkable_pixel"], "access_strategy": "nearest_walkable_from_internal_samples", "distance_to_walkable_px": best_sample["distance_to_walkable_px"], "tags": ["store", "internal_point"], "status": "draft"})
    return sorted(pois, key=lambda item: item["poi_id"]), connectors, entrances


def choose_connector(connectors, labels):
    for label in labels:
        for connector in connectors:
            if connector["direction_hint"] == label:
                return connector
    return connectors[0] if connectors else None


def add_vertical_edges(edges, connectors):
    qa = []
    by_group = defaultdict(list)
    for connector in connectors:
        by_group[str(connector.get("group_id"))].append(connector)
    for group_id, group in sorted(by_group.items()):
        by_floor = defaultdict(list)
        for connector in group:
            by_floor[connector["floor_id"]].append(connector)
        lower = by_floor.get("B1", [])
        upper = by_floor.get("F1", [])
        if not lower or not upper:
            qa.append({"severity": "warning", "code": "unpaired_connector_group", "group_id": group_id, "connectors": [connector["connector_id"] for connector in group]})
            continue
        if lower[0]["connector_type"] == "elevator" and upper[0]["connector_type"] == "elevator":
            edges.append(vertical_edge(lower[0], upper[0], "elevator", True, "generated_vertical_link", 20.0))
        elif lower[0]["connector_type"] == "step" and upper[0]["connector_type"] == "step":
            edges.append(vertical_edge(lower[0], upper[0], "stairs", True, "generated_vertical_link", 35.0))
        elif lower[0]["connector_type"] == "escalator" and upper[0]["connector_type"] == "escalator":
            lower_down = choose_connector(lower, ["escalator_down_from"])
            upper_down = choose_connector(upper, ["escalator_down_to"])
            lower_up = choose_connector(lower, ["escalator_up_from", "escalator_up_to"])
            upper_up = choose_connector(upper, ["escalator_up_from", "escalator_up_to"])
            if lower_down and upper_down and ("down" in lower_down["direction_hint"] or "down" in upper_down["direction_hint"]):
                edges.append(vertical_edge(upper_down, lower_down, "escalator", False, "generated_vertical_link_inferred", 25.0))
            if lower_up and upper_up and ("up" in lower_up["direction_hint"] or "up" in upper_up["direction_hint"]):
                edges.append(vertical_edge(lower_up, upper_up, "escalator", False, "generated_vertical_link_inferred", 25.0))
            if len(lower) > 1 or len(upper) > 1:
                qa.append({"severity": "warning", "code": "multiple_escalator_candidates_same_group", "group_id": group_id, "connectors": [connector["connector_id"] for connector in group]})
    return qa


def component_qa(nodes, edges):
    qa = []
    for floor_id in FLOORS:
        floor_node_ids = {node["node_id"] for node in nodes if node["floor_id"] == floor_id}
        adjacency = defaultdict(list)
        for edge in edges:
            if edge.get("floor_id") != floor_id:
                continue
            adjacency[edge["from_node_id"]].append(edge["to_node_id"])
            if edge.get("bidirectional"):
                adjacency[edge["to_node_id"]].append(edge["from_node_id"])
        seen = set()
        sizes = []
        for node_id in floor_node_ids:
            if node_id in seen:
                continue
            queue = deque([node_id])
            seen.add(node_id)
            size = 0
            while queue:
                current = queue.popleft()
                size += 1
                for next_node in adjacency[current]:
                    if next_node not in seen:
                        seen.add(next_node)
                        queue.append(next_node)
            sizes.append(size)
        qa.append({"severity": "info", "code": "floor_graph_components", "floor_id": floor_id, "component_count": len(sizes), "largest_components": sorted(sizes, reverse=True)[:5]})
    return qa


def reachability_qa(graph):
    node_ids = {node["node_id"] for node in graph["nodes"]}
    adjacency = {node_id: [] for node_id in node_ids}
    for edge in graph["edges"]:
        adjacency[edge["from_node_id"]].append(edge["to_node_id"])
        if edge.get("bidirectional"):
            adjacency[edge["to_node_id"]].append(edge["from_node_id"])

    start_nodes = [entrance["route_node_id"] for entrance in graph["entrances"]]
    seen = set(start_nodes)
    queue = deque(start_nodes)
    while queue:
        current = queue.popleft()
        for next_node in adjacency[current]:
            if next_node not in seen:
                seen.add(next_node)
                queue.append(next_node)

    unreachable_pois = [
        {
            "poi_id": poi["poi_id"],
            "floor_id": poi["floor_id"],
            "poi_name": poi["poi_name"],
            "route_node_id": poi["route_node_id"],
        }
        for poi in graph["pois"]
        if poi["route_node_id"] not in seen
    ]
    severity = "warning" if unreachable_pois else "info"
    return {
        "severity": severity,
        "code": "poi_reachability_from_entrances",
        "start_nodes": start_nodes,
        "reachable_nodes": len(seen),
        "total_nodes": len(node_ids),
        "reachable_pois": len(graph["pois"]) - len(unreachable_pois),
        "total_pois": len(graph["pois"]),
        "unreachable_pois": unreachable_pois,
    }


def build_outputs():
    floors, qa = load_inputs()
    nodes, edges = build_grid(floors)
    points, point_qa = extract_annotations(floors)
    qa.extend(point_qa)
    pois, connectors, entrances = add_semantic_points(nodes, edges, floors, points)
    qa.extend(add_vertical_edges(edges, connectors))
    qa.extend(component_qa(nodes, edges))
    resolver = []
    for poi in pois:
        alias_override = POI_ALIAS_OVERRIDES.get(poi["poi_id"])
        aliases = unique_aliases(alias_override or [poi["poi_name"], poi["poi_name"].lower()])
        resolver.append({
            "poi_id": poi["poi_id"],
            "name": poi["poi_name"],
            "floor_id": poi["floor_id"],
            "aliases": aliases,
            "route_node_id": poi["route_node_id"],
            "match_policy": "manual_label_alias",
        })
    node_ids = {node["node_id"] for node in nodes}
    for edge in edges:
        if edge["from_node_id"] not in node_ids or edge["to_node_id"] not in node_ids:
            qa.append({"severity": "error", "code": "edge_missing_node", "edge_id": edge["edge_id"]})
    for poi in pois:
        if poi["route_node_id"] not in node_ids:
            qa.append({"severity": "error", "code": "poi_missing_route_node", "poi_id": poi["poi_id"]})
    graph = {
        "schema_version": "app_indoor_nav_graph.v0.1",
        "status": "draft_generated_from_labelme_and_mask",
        "generated_at": "2026-05-09",
        "venue": {"venue_id": VENUE_ID, "venue_name": VENUE_NAME, "enabled_floors": list(FLOORS.keys())},
        "coordinate_system": {"type": "image_pixel", "origin": "top_left", "x_axis": "right", "y_axis": "down", "unit": "pixel", "meter_per_pixel_estimate": METERS_PER_PIXEL, "meter_scale_status": "demo_estimate_not_surveyed"},
        "source_files": FLOORS,
        "generation": {"walkable_mask_threshold": MASK_THRESHOLD, "grid_step_px": GRID_STEP_PX, "min_walkable_pixels_per_cell": MIN_WALKABLE_PIXELS_PER_CELL, "walkable_color": "white", "blocked_color": "black", "poi_access_strategy": "nearest_walkable_from_internal_samples"},
        "floors": [{"floor_id": floor_id, "image": config["image"], "mask": config["mask"], "labelme": config["labelme"], "width": floors[floor_id]["image_size"][0], "height": floors[floor_id]["image_size"][1]} for floor_id, config in FLOORS.items()],
        "nodes": nodes,
        "edges": edges,
        "pois": pois,
        "entrances": entrances,
        "connectors": connectors,
        "poi_resolver": resolver,
        "qa": qa,
        "known_limitations": [
            "Only B1 and F1 are included.",
            "Store annotations are internal samples; route_node_id points to nearest walkable mask access point, not confirmed shop doors.",
            "Route graph is generated from mask grid, not manually verified centerline.",
            "No GCJ-02 floor-specific anchors are included; GaoDe overlay still needs floor anchors.",
            "Escalator/elevator/stair cross-floor links are inferred from label/group_id and need manual QA before production.",
            "Mask source is JPG; future production should prefer lossless PNG mask.",
        ],
    }
    qa.append(reachability_qa(graph))
    return graph, points


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_report(graph):
    severity_counts = defaultdict(int)
    for item in graph["qa"]:
        severity_counts[item["severity"]] += 1
    lines = [
        "# 五道口 B1/F1 标注后处理报告 v0.1",
        "",
        "## 输出文件",
        "",
        "- `wudaokou_b1_f1_app_nav_graph.json`：App 路径规划草案路网。",
        "- `wudaokou_b1_f1_poi_resolver.json`：搜索名称到 `route_node_id` 的映射表。",
        "- `wudaokou_b1_f1_annotation_points.json`：原始标注点分类与吸附结果。",
        "",
        "## 坐标口径",
        "",
        "- 原点是图片左上角 `(0, 0)`，`x` 向右，`y` 向下。",
        "- `distance_px` 是像素距离，`distance` 是 `0.10 meter / pixel` DEMO 估算值。",
        "",
        "## 统计",
        "",
        f"- nodes: `{len(graph['nodes'])}`",
        f"- edges: `{len(graph['edges'])}`",
        f"- pois: `{len(graph['pois'])}`",
        f"- entrances: `{len(graph['entrances'])}`",
        f"- connectors: `{len(graph['connectors'])}`",
        f"- qa: `{dict(sorted(severity_counts.items()))}`",
        "",
        "## 连通性与可达性",
        "",
    ]
    for item in graph["qa"]:
        if item["code"] in {"floor_graph_components", "poi_reachability_from_entrances"}:
            lines.append(f"- `{item['code']}`: `{item}`")
    lines.extend([
        "",
        "## 主要 QA",
        "",
    ])
    for item in graph["qa"]:
        if item["severity"] != "info":
            lines.append(f"- `{item['severity']}` `{item['code']}`: `{item}`")
    lines.extend(["", "## 接入注意", "", "- 店铺目标当前导航到最近可通行点，不是确认门口点。", "- 高德底图叠加仍需每层 GCJ-02 锚点。", "- 跨层扶梯方向由 label 和 group_id 推断，App 接入前要抽查。"])
    (OUT / "POSTPROCESS_REPORT.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    graph, points = build_outputs()
    OUT.mkdir(parents=True, exist_ok=True)
    write_json(OUT / "wudaokou_b1_f1_app_nav_graph.json", graph)
    write_json(OUT / "wudaokou_b1_f1_poi_resolver.json", {"schema_version": "poi_resolver.v0.1", "venue_id": VENUE_ID, "source_graph": "wudaokou_b1_f1_app_nav_graph.json", "items": graph["poi_resolver"]})
    write_json(OUT / "wudaokou_b1_f1_annotation_points.json", {"schema_version": "mapping_annotation_points.v0.1", "coordinate_system": graph["coordinate_system"], "items": points})
    write_report(graph)
    print("generated", OUT)
    print("nodes", len(graph["nodes"]))
    print("edges", len(graph["edges"]))
    print("pois", len(graph["pois"]))
    print("entrances", len(graph["entrances"]))
    print("connectors", len(graph["connectors"]))
    print("qa_errors", sum(1 for item in graph["qa"] if item["severity"] == "error"))
    print("qa_warnings", sum(1 for item in graph["qa"] if item["severity"] == "warning"))


if __name__ == "__main__":
    main()
