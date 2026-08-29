import json
import math
import re
from collections import defaultdict, deque
from datetime import date
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path

import numpy as np
from PIL import Image


ROOT_DIR = Path(__file__).resolve().parent
ANNOTATION_DIR = ROOT_DIR / "annotation_points"
MASK_DIR = ROOT_DIR / "masks"
OUTPUT_DIR = ROOT_DIR / "processed" / "app_indoor_map"
BASELINE_NETWORK_SCRIPT = ROOT_DIR.parent / "wudaokou1" / "render_walkable_network_previews.py"

VENUE_ID = "venue_bj_wudaokou_shopping_center_demo"
VENUE_NAME = "五道口购物中心"
VENUE_ADDRESS = "北京市海淀区成府路28号"
VENUE_AMAP_POI_ID = "B000A80D2Q"
VENUE_AMAP_SEARCH_KEYWORD = "五道口购物中心"
PREFERRED_ENTRANCE_ID = "entrance_f1_west_gate"
PREFERRED_ENTRANCE_ROUTE_NODE_ID = "node_entrance_f1_west_gate_access"
PREFERRED_ENTRANCE_FLOOR_ID = "F1"
PREFERRED_ENTRANCE_GCJ02 = {"lat": 39.991583, "lng": 116.338965}
TATA_ROUTE_NODE_ID = "node_f2_tata_70_access"
TATA_GCJ02 = {"lat": 39.991556, "lng": 116.339568}
GRAPH_FILE_NAME = "wudaokou_all_floors_app_nav_graph.json"
RESOLVER_FILE_NAME = "wudaokou_all_floors_poi_resolver.json"
SHARED_AMAP_ALIGNMENT_FILE_NAME = "wudaokou_shared_amap_alignment.json"
ANNOTATION_POINTS_FILE_NAME = "wudaokou_all_floors_annotation_points.json"
ALIAS_AUDIT_FILE_NAME = "wudaokou_all_floors_alias_audit.json"
ALIAS_AUDIT_REPORT_FILE_NAME = "ALIAS_AUDIT_REPORT.md"

MASK_THRESHOLD = 128
METERS_PER_PIXEL = 0.10
FLOOR_ORDER = ["B3", "B2", "B1", "F1", "F2", "F3", "F4", "F5", "F6"]
ROUTING_FLOOR_RANK = {"B1": 0, "F1": 1, "F2": 2, "F3": 3, "F4": 4, "F5": 5, "F6": 6}
ENTRANCE_LABELS = {
    "NG": "north_gate",
    "EG": "east_gate",
    "WG": "west_gate",
    "SG": "south_gate",
}
CONNECTOR_LABEL_PREFIXES = ("escalator_", "elevator", "step", "stairs")
GENERIC_CONNECTOR_ALIASES = {
    "elevator",
    "电梯",
    "直梯",
    "升降梯",
    "dianti",
    "step",
    "楼梯",
    "台阶",
    "wc",
    "卫生间",
    "洗手间",
    "厕所",
    "toilet",
}
POI_PROFILE_OVERRIDES = {
    "velwin": {
        "display_name": "VELWIN",
        "amap_searchable": False,
        "amap_search_status": "amap_store_poi_not_found_user_confirmed",
        "chinese_alias_required": False,
        "badges": ["室内点位", "高德未收录店铺"],
        "result_type": "indoor_only_poi",
    },
    "tata": {
        "display_name": "TATA 鞋店",
        "category_hint": "鞋店",
        "chinese_alias_required": False,
        "badges": ["室内点位"],
        "result_type": "indoor_poi",
    },
    "cdgplay": {
        "display_name": "CDG PLAY",
        "chinese_alias_required": False,
        "badges": ["室内点位"],
        "result_type": "indoor_poi",
    },
    "6ixty": {
        "display_name": "6IXTY 8IGTY(五道口购物中心店)",
        "extra_aliases": ["6IXTY 8IGTY", "6IXTY8IGHT", "6IXTY 8IGHT"],
        "chinese_alias_required": False,
        "badges": ["室内点位"],
        "result_type": "indoor_poi",
    },
    "yimayila": {
        "display_name": "一麻一辣麻辣香锅",
        "extra_aliases": ["一麻一辣", "一麻一辣五道口店", "麻辣香锅", "yimayila"],
        "badges": ["室内点位"],
        "result_type": "indoor_poi",
    },
}


def load_baseline_network_module():
    spec = spec_from_file_location("wudaokou_g_network_baseline", BASELINE_NETWORK_SCRIPT)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Cannot load baseline network script: {BASELINE_NETWORK_SCRIPT}")
    module = module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def write_json(path: Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def relative_asset_path(path: Path) -> str:
    return path.relative_to(ROOT_DIR).as_posix()


def slugify(value) -> str:
    text = re.sub(r"[^a-z0-9]+", "_", str(value).strip().lower())
    return re.sub(r"_+", "_", text).strip("_") or "unnamed"


def normalize_search_text(value) -> str:
    return "".join(char.lower() for char in str(value) if char.isalnum() or ord(char) > 127)


def unique_values(values) -> list[str]:
    result = []
    seen = set()
    for value in values:
        if value is None:
            continue
        text = str(value).strip()
        if not text or text in seen:
            continue
        result.append(text)
        seen.add(text)
    return result


def has_chinese(value: str) -> bool:
    return any("\u4e00" <= char <= "\u9fff" for char in value)


def poi_profile(label: str) -> dict:
    return POI_PROFILE_OVERRIDES.get(str(label).strip().lower(), {})


def venue_context() -> dict:
    return {
        "venue_id": VENUE_ID,
        "venue_name": VENUE_NAME,
        "venue_address": VENUE_ADDRESS,
        "venue_amap_poi_id": VENUE_AMAP_POI_ID,
        "venue_amap_search_keyword": VENUE_AMAP_SEARCH_KEYWORD,
    }


def outdoor_handoff_context() -> dict:
    return {
        "strategy": "navigate_to_venue_entrance_then_indoor_route",
        "preferred_entrance_id": PREFERRED_ENTRANCE_ID,
        "preferred_entrance_route_node_id": PREFERRED_ENTRANCE_ROUTE_NODE_ID,
        "preferred_entrance_floor_id": PREFERRED_ENTRANCE_FLOOR_ID,
        "preferred_entrance_gcj02": PREFERRED_ENTRANCE_GCJ02,
        "distance_target": "preferred_entrance_gcj02",
        "distance_owner": "app_runtime_user_location_to_preferred_entrance",
    }


def build_shared_amap_alignment(floors, nodes) -> tuple[dict | None, list[dict]]:
    node_by_id = {node["node_id"]: node for node in nodes}
    anchor_a = node_by_id.get(PREFERRED_ENTRANCE_ROUTE_NODE_ID)
    anchor_b = node_by_id.get(TATA_ROUTE_NODE_ID)
    if anchor_a is None or anchor_b is None:
        missing = [
            node_id
            for node_id, node in [
                (PREFERRED_ENTRANCE_ROUTE_NODE_ID, anchor_a),
                (TATA_ROUTE_NODE_ID, anchor_b),
            ]
            if node is None
        ]
        return None, [
            {
                "severity": "error",
                "code": "shared_amap_alignment_anchor_missing",
                "missing_node_ids": missing,
            }
        ]

    meters_per_degree_lat = 111_320.0
    meters_per_degree_lng = meters_per_degree_lat * math.cos(math.radians(PREFERRED_ENTRANCE_GCJ02["lat"]))
    anchor_b_east_m = (TATA_GCJ02["lng"] - PREFERRED_ENTRANCE_GCJ02["lng"]) * meters_per_degree_lng
    anchor_b_north_m = (TATA_GCJ02["lat"] - PREFERRED_ENTRANCE_GCJ02["lat"]) * meters_per_degree_lat
    dx = anchor_b["x"] - anchor_a["x"]
    dy = anchor_b["y"] - anchor_a["y"]
    denominator = dx * dx + dy * dy
    if denominator == 0:
        return None, [
            {
                "severity": "error",
                "code": "shared_amap_alignment_anchor_degenerate",
                "anchor_node_ids": [PREFERRED_ENTRANCE_ROUTE_NODE_ID, TATA_ROUTE_NODE_ID],
            }
        ]

    transform_a = (dx * anchor_b_east_m + dy * anchor_b_north_m) / denominator
    transform_b = (dx * anchor_b_north_m - dy * anchor_b_east_m) / denominator
    scale_m_per_pixel = math.hypot(transform_a, transform_b)
    rotation_degrees = math.degrees(math.atan2(transform_b, transform_a))

    alignment = {
        "schema_version": "shared_floor_amap_alignment.v0.1",
        "mode": "shared_same_viewport_all_floors",
        "source_coordinate": "image_pixel",
        "target_coordinate": "gcj02",
        "applies_to_floors": list(floors.keys()),
        "assumption": "All floor screenshots use the same GaoDe camera, zoom, center, rotation, crop and pixel size.",
        "anchors": [
            {
                "node_id": PREFERRED_ENTRANCE_ROUTE_NODE_ID,
                "floor_id": anchor_a["floor_id"],
                "alignment_role": "anchor",
                "anchor_source": "user_confirmed_amap_gcj02",
                "pixel": {"x": anchor_a["x"], "y": anchor_a["y"]},
                "gcj02": PREFERRED_ENTRANCE_GCJ02,
            },
            {
                "node_id": TATA_ROUTE_NODE_ID,
                "floor_id": anchor_b["floor_id"],
                "alignment_role": "anchor",
                "anchor_source": "user_confirmed_tata_target_reused_for_nearest_route_node",
                "pixel": {"x": anchor_b["x"], "y": anchor_b["y"]},
                "gcj02": TATA_GCJ02,
            },
        ],
        "parameters": {
            "meters_per_degree_lat": meters_per_degree_lat,
            "meters_per_degree_lng_at_origin": meters_per_degree_lng,
            "origin_node_id": PREFERRED_ENTRANCE_ROUTE_NODE_ID,
            "origin_floor_id": anchor_a["floor_id"],
            "origin_pixel": {"x": anchor_a["x"], "y": anchor_a["y"]},
            "origin_gcj02": PREFERRED_ENTRANCE_GCJ02,
            "transform_a": transform_a,
            "transform_b": transform_b,
            "scale_m_per_image_pixel": scale_m_per_pixel,
            "rotation_degrees": rotation_degrees,
        },
        "known_limitations": [
            "This is a two-anchor same-viewport demo alignment, not floor-specific survey calibration.",
            "It is valid only while B1/F1/F2/F3/F4/F5/F6 screenshots keep identical GaoDe viewport, crop, size and rotation.",
            "If a floor still drifts on GaoDe indoor basemap, add floor-specific anchors and generate a floor-specific transform.",
        ],
    }
    return alignment, []


def display_name_for_poi(poi, aliases) -> str:
    profile = poi_profile(poi["poi_name"])
    if profile.get("display_name"):
        return profile["display_name"]
    return next((alias for alias in aliases if has_chinese(alias)), aliases[0] if aliases else poi["poi_name"])


def aliases_with_profile(label: str, aliases) -> list[str]:
    profile = poi_profile(label)
    return unique_values(list(aliases) + profile.get("extra_aliases", []))


def distance_px(first_point, second_point) -> float:
    return math.hypot(second_point[0] - first_point[0], second_point[1] - first_point[1])


def line_is_walkable(mask, first_point, second_point, sample_step=4) -> bool:
    first_x, first_y = first_point
    second_x, second_y = second_point
    sample_count = max(1, int(distance_px(first_point, second_point) / sample_step))
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


def nearest_walkable_pixel(floor_data, source_point) -> tuple[tuple[int, int], float]:
    point_x, point_y = source_point
    walkable_flat = floor_data["walkable_flat"]
    width = floor_data["image_size"][0]
    best_point = None
    best_distance_square = None
    for start_index in range(0, len(walkable_flat), 200_000):
        chunk_flat = walkable_flat[start_index : start_index + 200_000]
        chunk_y = chunk_flat // width
        chunk_x = chunk_flat - chunk_y * width
        distances = (chunk_x - point_x) ** 2 + (chunk_y - point_y) ** 2
        chunk_best_index = int(np.argmin(distances))
        chunk_distance_square = float(distances[chunk_best_index])
        if best_distance_square is None or chunk_distance_square < best_distance_square:
            best_distance_square = chunk_distance_square
            best_point = (int(chunk_x[chunk_best_index]), int(chunk_y[chunk_best_index]))
    return best_point, math.sqrt(best_distance_square or 0.0)


def classify_label(label: str) -> str:
    if label in ENTRANCE_LABELS:
        return "entrance"
    if label == "wc":
        return "facility"
    if label.startswith(CONNECTOR_LABEL_PREFIXES):
        return "connector"
    return "poi_internal_sample"


def connector_type_for_label(label: str) -> str:
    if label.startswith("escalator_"):
        return "escalator"
    if label == "step" or label == "stairs":
        return "stairs"
    return label


def load_floor_inputs():
    floors = {}
    qa_items = []
    for mask_path in sorted(MASK_DIR.glob("*.jpg")):
        floor_key = mask_path.stem.lower()
        floor_id = floor_key.upper()
        labelme_path = ANNOTATION_DIR / f"{floor_key}.json"
        image_path = ANNOTATION_DIR / f"{floor_key}.jpg"
        if not labelme_path.exists() or not image_path.exists():
            qa_items.append(
                {
                    "severity": "warning",
                    "code": "missing_annotation_or_image_for_mask_floor",
                    "floor_id": floor_id,
                    "mask": str(mask_path),
                }
            )
            continue
        labelme = json.loads(labelme_path.read_text(encoding="utf-8"))
        image = Image.open(image_path)
        mask_image = Image.open(mask_path).convert("L")
        mask = np.array(mask_image) >= MASK_THRESHOLD
        if image.size != mask_image.size:
            qa_items.append(
                {
                    "severity": "error",
                    "code": "image_mask_size_mismatch",
                    "floor_id": floor_id,
                    "image_size": image.size,
                    "mask_size": mask_image.size,
                }
            )
        if [labelme.get("imageWidth"), labelme.get("imageHeight")] != list(image.size):
            qa_items.append(
                {
                    "severity": "error",
                    "code": "labelme_image_size_mismatch",
                    "floor_id": floor_id,
                    "labelme_size": [labelme.get("imageWidth"), labelme.get("imageHeight")],
                    "image_size": image.size,
                }
            )
        walkable_flat = np.flatnonzero(mask.ravel()).astype(np.int32, copy=False)
        floors[floor_id] = {
            "floor_id": floor_id,
            "floor_key": floor_key,
            "labelme": labelme,
            "labelme_path": labelme_path,
            "image_path": image_path,
            "mask_path": mask_path,
            "image_size": image.size,
            "mask": mask,
            "walkable_flat": walkable_flat,
        }
    return dict(sorted(floors.items(), key=lambda item: FLOOR_ORDER.index(item[0]))), qa_items


def convert_g_node_id(floor_id: str, raw_node_id: str) -> str:
    return f"node_{floor_id.lower()}_{raw_node_id.removeprefix('node_')}"


def app_walk_edge(edge_id, from_node_id, to_node_id, floor_id, distance_pixels, status, line_walkable=True, edge_type="walk"):
    distance_meters = round(distance_pixels * METERS_PER_PIXEL, 3)
    return {
        "edge_id": edge_id,
        "from_node_id": from_node_id,
        "to_node_id": to_node_id,
        "floor_id": floor_id,
        "travel_mode": "walk",
        "bidirectional": True,
        "distance": distance_meters,
        "distance_unit": "meter_estimate",
        "distance_px": round(distance_pixels, 3),
        "cost_seconds": round(distance_meters / 1.2, 1),
        "status": status,
        "line_walkable": line_walkable,
        "edge_type": edge_type,
    }


def path_length(path_points) -> float:
    if not path_points or len(path_points) == 1:
        return 0.0
    total = 0.0
    for point_index in range(len(path_points) - 1):
        total += distance_px(path_points[point_index], path_points[point_index + 1])
    return total


def build_g_route_graph(floors):
    network_module = load_baseline_network_module()
    nodes = []
    edges = []
    grid_nodes_by_floor = defaultdict(list)
    qa_items = []
    network_stats = []
    edge_ids = set()

    for floor_id, floor_data in floors.items():
        floor_key = floor_data["floor_key"]
        raw_nodes, raw_edges, _raw_index = network_module.build_raw_grid_network(floor_data["mask"])
        raw_edges = network_module.prune_edges_to_legacy_raw_stats(floor_key, raw_nodes, raw_edges)
        g_nodes, g_edges, g_stats = network_module.build_algorithm_g(floor_data["mask"], raw_nodes, raw_edges)
        components = network_module.connected_components([node["node_id"] for node in g_nodes], g_edges)
        network_stats.append(
            {
                "floor_id": floor_id,
                "nodes": len(g_nodes),
                "edges": len(g_edges),
                "components": len(components),
                "largest_components": components[:5],
                "added_nodes": g_stats["added_nodes"],
                "added_edges": g_stats["added_edges"],
            }
        )
        if len(components) > 1:
            qa_items.append(
                {
                    "severity": "warning",
                    "code": "floor_g_graph_not_fully_connected",
                    "floor_id": floor_id,
                    "component_count": len(components),
                    "largest_components": components[:5],
                }
            )

        for raw_node in g_nodes:
            node_id = convert_g_node_id(floor_id, raw_node["node_id"])
            node = {
                "node_id": node_id,
                "floor_id": floor_id,
                "node_type": "walkable_grid",
                "x": raw_node["x"],
                "y": raw_node["y"],
                "source_pixel": {"x": raw_node["x"], "y": raw_node["y"]},
                "grid_cell": {"x": raw_node["cell_x"], "y": raw_node["cell_y"]},
                "cell_walkable_pixels": raw_node["white_pixels"],
                "status": f"generated_from_algorithm_g_{raw_node.get('node_source', 'unknown')}",
            }
            nodes.append(node)
            grid_nodes_by_floor[floor_id].append(node)

        for raw_edge in g_edges:
            from_node_id = convert_g_node_id(floor_id, raw_edge["from_node_id"])
            to_node_id = convert_g_node_id(floor_id, raw_edge["to_node_id"])
            edge_type = raw_edge.get("edge_type", "algorithm_g")
            edge_id = f"edge_{from_node_id.removeprefix('node_')}_to_{to_node_id.removeprefix('node_')}"
            if edge_id in edge_ids:
                continue
            edge_ids.add(edge_id)
            edge_path = raw_edge.get("path") or []
            distance_pixels = path_length(edge_path) or distance_px(
                (raw_edge["path"][0][0], raw_edge["path"][0][1]),
                (raw_edge["path"][-1][0], raw_edge["path"][-1][1]),
            )
            edges.append(
                app_walk_edge(
                    edge_id=edge_id,
                    from_node_id=from_node_id,
                    to_node_id=to_node_id,
                    floor_id=floor_id,
                    distance_pixels=distance_pixels,
                    status=f"generated_from_algorithm_g_{edge_type}",
                    line_walkable=True,
                    edge_type=edge_type,
                )
            )
    return nodes, edges, grid_nodes_by_floor, network_stats, qa_items


def nearest_grid_node(grid_nodes_by_floor, floor_data, floor_id, access_point):
    candidates = sorted(
        grid_nodes_by_floor[floor_id],
        key=lambda node: (node["x"] - access_point[0]) ** 2 + (node["y"] - access_point[1]) ** 2,
    )
    for candidate in candidates[:100]:
        candidate_point = (candidate["x"], candidate["y"])
        if line_is_walkable(floor_data["mask"], access_point, candidate_point):
            return candidate, distance_px(access_point, candidate_point), True
    fallback = candidates[0]
    return fallback, distance_px(access_point, (fallback["x"], fallback["y"])), False


def extract_annotation_points(floors):
    points = []
    qa_items = []
    for floor_id, floor_data in floors.items():
        height, width = floor_data["mask"].shape
        occurrences = defaultdict(int)
        for shape in floor_data["labelme"].get("shapes", []):
            label = str(shape.get("label", "")).strip()
            label_slug = slugify(label)
            occurrences[label_slug] += 1
            if shape.get("shape_type") != "point" or not shape.get("points"):
                qa_items.append({"severity": "warning", "code": "unsupported_shape", "floor_id": floor_id, "label": label})
                continue
            source_point = (float(shape["points"][0][0]), float(shape["points"][0][1]))
            source_pixel = {"x": round(source_point[0], 3), "y": round(source_point[1], 3)}
            if source_point[0] < 0 or source_point[1] < 0 or source_point[0] >= width or source_point[1] >= height:
                qa_items.append(
                    {
                        "severity": "error",
                        "code": "annotation_out_of_bounds",
                        "floor_id": floor_id,
                        "label": label,
                        "source_pixel": source_pixel,
                    }
                )
            access_point, access_distance = nearest_walkable_pixel(floor_data, source_point)
            if access_distance > 60:
                qa_items.append(
                    {
                        "severity": "warning",
                        "code": "annotation_far_from_walkable_mask",
                        "floor_id": floor_id,
                        "label": label,
                        "group_id": shape.get("group_id"),
                        "distance_px": round(access_distance, 1),
                    }
                )
            points.append(
                {
                    "floor_id": floor_id,
                    "label": label,
                    "label_slug": label_slug,
                    "group_id": shape.get("group_id"),
                    "occurrence": occurrences[label_slug],
                    "object_type": classify_label(label),
                    "source_pixel": source_pixel,
                    "nearest_walkable_pixel": {"x": access_point[0], "y": access_point[1]},
                    "distance_to_walkable_px": round(access_distance, 3),
                    "aliases": unique_values(shape.get("aliases", [])),
                    "description": str(shape.get("description") or "").strip(),
                }
            )
    return points, qa_items


def access_node_id(ref_id: str) -> str:
    if ref_id.startswith("poi_"):
        return f"node_{ref_id.removeprefix('poi_')}_access"
    return f"node_{ref_id}_access"


def add_access_node(nodes, edges, grid_nodes_by_floor, floors, semantic_node, edge_ids, qa_items):
    nodes.append(semantic_node)
    floor_id = semantic_node["floor_id"]
    access_point = (semantic_node["access_pixel"]["x"], semantic_node["access_pixel"]["y"])
    grid_node, grid_distance, direct = nearest_grid_node(grid_nodes_by_floor, floors[floor_id], floor_id, access_point)
    edge_id = f"edge_{semantic_node['node_id'].removeprefix('node_')}_to_{grid_node['node_id'].removeprefix('node_')}"
    if edge_id not in edge_ids:
        edge_ids.add(edge_id)
        edges.append(
            app_walk_edge(
                edge_id=edge_id,
                from_node_id=semantic_node["node_id"],
                to_node_id=grid_node["node_id"],
                floor_id=floor_id,
                distance_pixels=grid_distance,
                status="generated_semantic_access_link",
                line_walkable=direct,
                edge_type="semantic_access",
            )
        )
    if not direct:
        qa_items.append(
            {
                "severity": "warning",
                "code": "semantic_access_link_not_directly_walkable",
                "floor_id": floor_id,
                "ref_id": semantic_node["ref_id"],
                "route_node_id": semantic_node["node_id"],
                "nearest_grid_node_id": grid_node["node_id"],
                "distance_px": round(grid_distance, 1),
            }
        )


def build_aliases_for_samples(samples) -> list[str]:
    aliases = []
    for sample in samples:
        aliases.extend(sample.get("aliases", []))
    aliases.extend([samples[0]["label"], samples[0]["label"].lower()])
    return unique_values(aliases)


def add_semantic_annotations(nodes, edges, grid_nodes_by_floor, floors, annotation_points):
    pois = []
    connectors = []
    entrances = []
    qa_items = []
    edge_ids = {edge["edge_id"] for edge in edges}
    poi_groups = defaultdict(list)

    for point in annotation_points:
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

        node_id = access_node_id(ref_id)
        semantic_node = {
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
            "status": "generated_from_labelme_annotation",
        }
        add_access_node(nodes, edges, grid_nodes_by_floor, floors, semantic_node, edge_ids, qa_items)

        if point["object_type"] == "connector":
            connectors.append(
                {
                    "connector_id": ref_id,
                    "floor_id": floor_id,
                    "connector_type": connector_type_for_label(point["label"]),
                    "group_id": point["group_id"],
                    "direction_hint": point["label"],
                    "route_node_id": node_id,
                    "source_pixel": point["source_pixel"],
                    "access_pixel": point["nearest_walkable_pixel"],
                    "aliases": point["aliases"],
                    "status": "generated_from_labelme_annotation",
                }
            )
        elif point["object_type"] == "entrance":
            entrances.append(
                {
                    "entrance_id": ref_id,
                    "floor_id": floor_id,
                    "entrance_type": ENTRANCE_LABELS[point["label"]],
                    "route_node_id": node_id,
                    "source_pixel": point["source_pixel"],
                    "access_pixel": point["nearest_walkable_pixel"],
                    "aliases": point["aliases"],
                    "status": "generated_from_labelme_annotation",
                }
            )
        else:
            pois.append(
                {
                    "poi_id": ref_id,
                    "poi_type": "facility",
                    "poi_name": point["label"],
                    "venue_id": VENUE_ID,
                    "floor_id": floor_id,
                    "position": point["source_pixel"],
                    "route_node_id": node_id,
                    "source_samples": [point["source_pixel"]],
                    "access_pixel": point["nearest_walkable_pixel"],
                    "access_strategy": "nearest_walkable_from_annotation",
                    "distance_to_walkable_px": point["distance_to_walkable_px"],
                    "aliases": build_aliases_for_samples([point]),
                    "tags": ["facility"],
                    "status": "draft",
                }
            )

    for (floor_id, label_slug, group_id), samples in sorted(poi_groups.items()):
        best_sample = min(samples, key=lambda sample: sample["distance_to_walkable_px"])
        group_suffix = f"_{group_id}" if group_id is not None else ""
        poi_id = f"poi_{floor_id.lower()}_{label_slug}{group_suffix}"
        node_id = f"node_{floor_id.lower()}_{label_slug}{group_suffix}_access"
        semantic_node = {
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
            "status": "generated_from_labelme_annotation",
        }
        add_access_node(nodes, edges, grid_nodes_by_floor, floors, semantic_node, edge_ids, qa_items)
        pois.append(
            {
                "poi_id": poi_id,
                "poi_type": "store_internal_sample",
                "poi_name": samples[0]["label"],
                "venue_id": VENUE_ID,
                "floor_id": floor_id,
                "position": best_sample["source_pixel"],
                "route_node_id": node_id,
                "source_samples": [sample["source_pixel"] for sample in samples],
                "access_pixel": best_sample["nearest_walkable_pixel"],
                "access_strategy": "nearest_walkable_from_internal_samples",
                "distance_to_walkable_px": best_sample["distance_to_walkable_px"],
                "aliases": build_aliases_for_samples(samples),
                "tags": ["store", "internal_point"],
                "status": "draft",
            }
        )
    return (
        sorted(pois, key=lambda item: item["poi_id"]),
        sorted(connectors, key=lambda item: item["connector_id"]),
        sorted(entrances, key=lambda item: item["entrance_id"]),
        qa_items,
    )


def vertical_edge(from_connector, to_connector, mode, bidirectional, status, seconds):
    from_floor = from_connector["floor_id"]
    to_floor = to_connector["floor_id"]
    return {
        "edge_id": f"edge_{from_connector['route_node_id'].removeprefix('node_')}_to_{to_connector['route_node_id'].removeprefix('node_')}",
        "from_node_id": from_connector["route_node_id"],
        "to_node_id": to_connector["route_node_id"],
        "floor_id": f"{from_floor}->{to_floor}",
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


def first_connector(connectors, direction_hints):
    for direction_hint in direction_hints:
        for connector in connectors:
            if connector["direction_hint"] == direction_hint:
                return connector
    return None


def add_vertical_edges(edges, connectors):
    qa_items = []
    edge_ids = {edge["edge_id"] for edge in edges}
    connectors_by_group = defaultdict(list)
    for connector in connectors:
        connectors_by_group[str(connector.get("group_id"))].append(connector)

    for group_id, group_connectors in sorted(connectors_by_group.items()):
        connectors_by_floor = defaultdict(list)
        for connector in group_connectors:
            connectors_by_floor[connector["floor_id"]].append(connector)
        available_floors = [floor_id for floor_id in ROUTING_FLOOR_RANK if floor_id in connectors_by_floor]
        for floor_index in range(len(available_floors) - 1):
            lower_floor = available_floors[floor_index]
            upper_floor = available_floors[floor_index + 1]
            if ROUTING_FLOOR_RANK[upper_floor] - ROUTING_FLOOR_RANK[lower_floor] != 1:
                qa_items.append(
                    {
                        "severity": "warning",
                        "code": "connector_group_missing_intermediate_floor",
                        "group_id": group_id,
                        "from_floor_id": lower_floor,
                        "to_floor_id": upper_floor,
                    }
                )
                continue
            lower_connectors = connectors_by_floor[lower_floor]
            upper_connectors = connectors_by_floor[upper_floor]

            lower_elevator = first_connector(lower_connectors, ["elevator"])
            upper_elevator = first_connector(upper_connectors, ["elevator"])
            if lower_elevator and upper_elevator:
                edge = vertical_edge(lower_elevator, upper_elevator, "elevator", True, "generated_vertical_link", 20.0)
                if edge["edge_id"] not in edge_ids:
                    edge_ids.add(edge["edge_id"])
                    edges.append(edge)

            lower_stairs = first_connector(lower_connectors, ["step", "stairs"])
            upper_stairs = first_connector(upper_connectors, ["step", "stairs"])
            if lower_stairs and upper_stairs:
                edge = vertical_edge(lower_stairs, upper_stairs, "stairs", True, "generated_vertical_link", 35.0)
                if edge["edge_id"] not in edge_ids:
                    edge_ids.add(edge["edge_id"])
                    edges.append(edge)

            lower_up = first_connector(lower_connectors, ["escalator_up_from", "escalator_up_to"])
            upper_up = first_connector(upper_connectors, ["escalator_up_to", "escalator_up_from"])
            if lower_up and upper_up:
                edge = vertical_edge(lower_up, upper_up, "escalator", False, "generated_vertical_link_inferred", 25.0)
                if edge["edge_id"] not in edge_ids:
                    edge_ids.add(edge["edge_id"])
                    edges.append(edge)

            upper_down = first_connector(upper_connectors, ["escalator_down_to", "escalator_down_from"])
            lower_down = first_connector(lower_connectors, ["escalator_down_from", "escalator_down_to"])
            if upper_down and lower_down:
                edge = vertical_edge(upper_down, lower_down, "escalator", False, "generated_vertical_link_inferred", 25.0)
                if edge["edge_id"] not in edge_ids:
                    edge_ids.add(edge["edge_id"])
                    edges.append(edge)

        if len(available_floors) <= 1:
            qa_items.append(
                {
                    "severity": "warning",
                    "code": "unpaired_connector_group",
                    "group_id": group_id,
                    "connectors": [connector["connector_id"] for connector in group_connectors],
                }
            )
    return qa_items


def build_poi_resolver(pois):
    resolver = []
    for poi in sorted(pois, key=lambda item: (item["floor_id"], item["poi_name"], item["poi_id"])):
        aliases = aliases_with_profile(poi["poi_name"], poi.get("aliases", []) or [poi["poi_name"], poi["poi_name"].lower()])
        profile = poi_profile(poi["poi_name"])
        display_name = display_name_for_poi(poi, aliases)
        amap_searchable = profile.get("amap_searchable")
        result_type = profile.get("result_type", "indoor_poi")
        badges = profile.get("badges", ["室内点位"])
        resolver.append(
            {
                "poi_id": poi["poi_id"],
                "name": poi["poi_name"],
                "display_name": display_name,
                "floor_id": poi["floor_id"],
                "aliases": aliases,
                "route_node_id": poi["route_node_id"],
                "match_policy": "manual_label_alias",
                "result_type": result_type,
                "venue_id": VENUE_ID,
                "venue_name": VENUE_NAME,
                "venue_address": VENUE_ADDRESS,
                "subtitle": f"{VENUE_NAME} · {poi['floor_id']} · 室内点位",
                "address": VENUE_ADDRESS,
                "distance_target": "preferred_entrance_gcj02",
                "badges": badges,
                "external_refs": {
                    "amap_poi_id": None,
                    "amap_searchable": amap_searchable,
                    "amap_search_status": profile.get("amap_search_status", "unknown"),
                    "venue_amap_poi_id": VENUE_AMAP_POI_ID,
                    "venue_amap_search_keyword": VENUE_AMAP_SEARCH_KEYWORD,
                },
                "outdoor_handoff": {
                    **venue_context(),
                    **outdoor_handoff_context(),
                },
            }
        )
    return resolver


def enrich_pois_for_app(pois):
    for poi in pois:
        aliases = aliases_with_profile(poi["poi_name"], poi.get("aliases", []))
        profile = poi_profile(poi["poi_name"])
        poi["aliases"] = aliases
        poi["display_name"] = display_name_for_poi(poi, aliases)
        poi["venue_name"] = VENUE_NAME
        poi["venue_address"] = VENUE_ADDRESS
        poi["result_type"] = profile.get("result_type", "indoor_poi")
        poi["badges"] = profile.get("badges", ["室内点位"])
        poi["category_hint"] = profile.get("category_hint")
        poi["external_refs"] = {
            "amap_poi_id": None,
            "amap_searchable": profile.get("amap_searchable"),
            "amap_search_status": profile.get("amap_search_status", "unknown"),
            "venue_amap_poi_id": VENUE_AMAP_POI_ID,
            "venue_amap_search_keyword": VENUE_AMAP_SEARCH_KEYWORD,
        }
        poi["outdoor_handoff"] = {
            **venue_context(),
            **outdoor_handoff_context(),
        }
    return pois


def graph_component_qa(floors, nodes, edges):
    qa_items = []
    for floor_id in floors:
        node_ids = {node["node_id"] for node in nodes if node["floor_id"] == floor_id}
        adjacency = defaultdict(list)
        for edge in edges:
            if edge.get("travel_mode") != "walk" or edge.get("floor_id") != floor_id:
                continue
            adjacency[edge["from_node_id"]].append(edge["to_node_id"])
            if edge.get("bidirectional"):
                adjacency[edge["to_node_id"]].append(edge["from_node_id"])
        seen = set()
        sizes = []
        for node_id in node_ids:
            if node_id in seen:
                continue
            queue = deque([node_id])
            seen.add(node_id)
            size = 0
            while queue:
                current_node_id = queue.popleft()
                size += 1
                for next_node_id in adjacency[current_node_id]:
                    if next_node_id not in seen:
                        seen.add(next_node_id)
                        queue.append(next_node_id)
            sizes.append(size)
        qa_items.append(
            {
                "severity": "info",
                "code": "floor_walk_graph_components",
                "floor_id": floor_id,
                "component_count": len(sizes),
                "largest_components": sorted(sizes, reverse=True)[:5],
            }
        )
    return qa_items


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
        current_node_id = queue.popleft()
        for next_node_id in adjacency[current_node_id]:
            if next_node_id not in seen:
                seen.add(next_node_id)
                queue.append(next_node_id)

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
    return {
        "severity": "warning" if unreachable_pois else "info",
        "code": "poi_reachability_from_entrances",
        "start_nodes": start_nodes,
        "reachable_nodes": len(seen),
        "total_nodes": len(node_ids),
        "reachable_pois": len(graph["pois"]) - len(unreachable_pois),
        "total_pois": len(graph["pois"]),
        "unreachable_pois": unreachable_pois,
    }


def reference_integrity_qa(graph):
    qa_items = []
    node_ids = {node["node_id"] for node in graph["nodes"]}
    edge_ids = set()
    for edge in graph["edges"]:
        if edge["edge_id"] in edge_ids:
            qa_items.append({"severity": "error", "code": "duplicate_edge_id", "edge_id": edge["edge_id"]})
        edge_ids.add(edge["edge_id"])
        if edge["from_node_id"] not in node_ids or edge["to_node_id"] not in node_ids:
            qa_items.append({"severity": "error", "code": "edge_missing_node", "edge_id": edge["edge_id"]})
        if edge["from_node_id"] == edge["to_node_id"]:
            qa_items.append({"severity": "error", "code": "edge_self_loop", "edge_id": edge["edge_id"]})
    for poi in graph["pois"]:
        if poi["route_node_id"] not in node_ids:
            qa_items.append({"severity": "error", "code": "poi_missing_route_node", "poi_id": poi["poi_id"]})
    for resolver_item in graph["poi_resolver"]:
        if resolver_item["route_node_id"] not in node_ids:
            qa_items.append({"severity": "error", "code": "resolver_missing_route_node", "poi_id": resolver_item["poi_id"]})
    return qa_items


def audit_aliases(poi_resolver):
    issues = []
    normalized_alias_owners = defaultdict(list)
    for item in poi_resolver:
        aliases = item.get("aliases", [])
        profile = poi_profile(item["name"])
        if item["name"] not in aliases:
            issues.append({"severity": "warning", "code": "alias_missing_label_name", "poi_id": item["poi_id"], "name": item["name"]})
        if not any(has_chinese(alias) for alias in aliases):
            if profile.get("chinese_alias_required") is False:
                issues.append({"severity": "info", "code": "english_only_brand_without_chinese_alias", "poi_id": item["poi_id"], "name": item["name"], "aliases": aliases})
            else:
                issues.append({"severity": "warning", "code": "alias_missing_chinese_search_term", "poi_id": item["poi_id"], "name": item["name"], "aliases": aliases})
        if len(aliases) < 3:
            issues.append({"severity": "info", "code": "alias_count_low", "poi_id": item["poi_id"], "name": item["name"], "alias_count": len(aliases)})
        for alias in aliases:
            normalized = normalize_search_text(alias)
            if not normalized:
                continue
            normalized_alias_owners[normalized].append({"poi_id": item["poi_id"], "name": item["name"], "floor_id": item["floor_id"], "alias": alias})
            if alias in GENERIC_CONNECTOR_ALIASES:
                continue
            if alias.isascii() and len(normalized) <= 2:
                issues.append({"severity": "info", "code": "short_ascii_alias_may_be_ambiguous", "poi_id": item["poi_id"], "name": item["name"], "alias": alias})

    for normalized, owners in sorted(normalized_alias_owners.items()):
        unique_poi_ids = sorted({owner["poi_id"] for owner in owners})
        if len(unique_poi_ids) <= 1:
            continue
        severity = "info" if normalized in {normalize_search_text(alias) for alias in GENERIC_CONNECTOR_ALIASES} else "warning"
        issues.append({"severity": severity, "code": "duplicate_normalized_alias", "normalized_alias": normalized, "owners": owners})

    return {
        "schema_version": "mapping_alias_audit.v0.1",
        "venue_id": VENUE_ID,
        "items": issues,
    }


def description_review_qa(annotation_points):
    qa_items = []
    for point in annotation_points:
        description = point.get("description") or ""
        if "需确认" in description:
            qa_items.append(
                {
                    "severity": "warning",
                    "code": "annotation_alias_or_translation_needs_manual_review",
                    "floor_id": point["floor_id"],
                    "label": point["label"],
                    "group_id": point["group_id"],
                    "description": description,
                    "aliases": point.get("aliases", []),
                }
            )
    return qa_items


def build_graph():
    floors, qa_items = load_floor_inputs()
    nodes, edges, grid_nodes_by_floor, network_stats, network_qa = build_g_route_graph(floors)
    qa_items.extend(network_qa)
    annotation_points, annotation_qa = extract_annotation_points(floors)
    qa_items.extend(annotation_qa)
    qa_items.extend(description_review_qa(annotation_points))
    pois, connectors, entrances, semantic_qa = add_semantic_annotations(nodes, edges, grid_nodes_by_floor, floors, annotation_points)
    pois = enrich_pois_for_app(pois)
    qa_items.extend(semantic_qa)
    qa_items.extend(add_vertical_edges(edges, connectors))
    resolver = build_poi_resolver(pois)
    shared_amap_alignment, alignment_qa = build_shared_amap_alignment(floors, nodes)
    qa_items.extend(alignment_qa)

    graph = {
        "schema_version": "app_indoor_nav_graph.v0.1",
        "status": "draft_generated_from_wudaokou2_labelme_mask_algorithm_g",
        "generated_at": date.today().isoformat(),
        "venue": {
            "venue_id": VENUE_ID,
            "venue_name": VENUE_NAME,
            "venue_address": VENUE_ADDRESS,
            "venue_amap_poi_id": VENUE_AMAP_POI_ID,
            "venue_amap_search_keyword": VENUE_AMAP_SEARCH_KEYWORD,
            "preferred_entrance": {
                "entrance_id": PREFERRED_ENTRANCE_ID,
                "route_node_id": PREFERRED_ENTRANCE_ROUTE_NODE_ID,
                "floor_id": PREFERRED_ENTRANCE_FLOOR_ID,
                "gcj02": PREFERRED_ENTRANCE_GCJ02,
            },
            "enabled_floors": list(floors.keys()),
        },
        "coordinate_system": {
            "type": "image_pixel",
            "origin": "top_left",
            "x_axis": "right",
            "y_axis": "down",
            "unit": "pixel",
            "meter_per_pixel_estimate": METERS_PER_PIXEL,
            "meter_scale_status": "demo_estimate_not_surveyed",
        },
        "shared_amap_alignment_ref": SHARED_AMAP_ALIGNMENT_FILE_NAME,
        "source_files": {
            floor_id: {
                "image": relative_asset_path(data["image_path"]),
                "mask": relative_asset_path(data["mask_path"]),
                "labelme": relative_asset_path(data["labelme_path"]),
            }
            for floor_id, data in floors.items()
        },
        "generation": {
            "walkable_mask_threshold": MASK_THRESHOLD,
            "route_network_algorithm": "G cell_portal_bridge_pruned",
            "poi_access_strategy": "nearest_walkable_from_internal_samples",
            "vertical_link_strategy": "infer_adjacent_floor_links_from_connector_group_id_and_direction_hint",
        },
        "network_stats": network_stats,
        "floors": [
            {
                "floor_id": floor_id,
                "image": relative_asset_path(floors[floor_id]["image_path"]),
                "mask": relative_asset_path(floors[floor_id]["mask_path"]),
                "labelme": relative_asset_path(floors[floor_id]["labelme_path"]),
                "width": floors[floor_id]["image_size"][0],
                "height": floors[floor_id]["image_size"][1],
            }
            for floor_id in floors
        ],
        "nodes": nodes,
        "edges": edges,
        "pois": pois,
        "entrances": entrances,
        "connectors": connectors,
        "poi_resolver": resolver,
        "qa": qa_items,
        "known_limitations": [
            "Store annotations are internal samples; route_node_id points to nearest walkable mask access point, not confirmed shop doors.",
            "Multiple internal samples of the same store are merged into one POI; App v0.1 supports one route_node_id per resolver item.",
            "Indoor-only POIs such as VELWIN use venue-level outdoor handoff because GaoDe may not return a store-level POI.",
            "GCJ-02 overlay uses two-anchor shared same-viewport alignment; floor-specific drift still needs per-floor anchors.",
            "Route graph uses Algorithm G generated from JPG masks; production should prefer lossless masks and manual QA of centerline quality.",
            "F6 Algorithm G currently has multiple walk graph components; some F6 POIs may be unreachable from F1 entrances.",
            "B2/B3 are not included because matching masks or complete annotations are missing.",
            "Escalator/elevator/stair cross-floor links are inferred from label/group_id and need manual QA before demo freeze.",
        ],
    }
    if shared_amap_alignment is not None:
        graph["shared_amap_alignment"] = shared_amap_alignment
    graph["qa"].extend(graph_component_qa(floors, nodes, edges))
    graph["qa"].extend(reference_integrity_qa(graph))
    graph["qa"].append(reachability_qa(graph))
    return graph, annotation_points


def write_alias_report(alias_audit):
    severity_counts = defaultdict(int)
    for item in alias_audit["items"]:
        severity_counts[item["severity"]] += 1
    lines = [
        "# 五道口 wudaokou2 中文搜索词审核报告",
        "",
        "## 结论",
        "",
        "- 已将 LabelMe `aliases` 写入 App `poi_resolver.items[].aliases`。",
        "- `warning` 表示建议人工复核后再作为稳定 DEMO 搜索词。",
        "- `info` 多为短拼音/简称或同名设施，能用但可能出现多个候选。",
        "",
        "## 统计",
        "",
        f"- issues: `{dict(sorted(severity_counts.items()))}`",
        "",
        "## 需重点复核",
        "",
    ]
    warnings = [item for item in alias_audit["items"] if item["severity"] == "warning"]
    if not warnings:
        lines.append("- 暂无 warning。")
    for item in warnings[:120]:
        lines.append(f"- `{item['code']}`: `{json.dumps(item, ensure_ascii=False)}`")
    if len(warnings) > 120:
        lines.append(f"- 另有 `{len(warnings) - 120}` 条 warning，详见 `{ALIAS_AUDIT_FILE_NAME}`。")
    (OUTPUT_DIR / ALIAS_AUDIT_REPORT_FILE_NAME).write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_postprocess_report(graph, alias_audit):
    severity_counts = defaultdict(int)
    for item in graph["qa"]:
        severity_counts[item["severity"]] += 1
    alias_severity_counts = defaultdict(int)
    for item in alias_audit["items"]:
        alias_severity_counts[item["severity"]] += 1
    lines = [
        "# 五道口 wudaokou2 App 室内地图生成报告",
        "",
        "## 输出文件",
        "",
        f"- `{GRAPH_FILE_NAME}`：App `ImageIndoorNavigation` 可读取的室内路网。",
        f"- `{RESOLVER_FILE_NAME}`：App 搜索词到 `route_node_id` 的映射。",
        f"- `{SHARED_AMAP_ALIGNMENT_FILE_NAME}`：同视角全楼层复用的 `image_pixel -> GCJ-02` 高德 overlay 变换。",
        f"- `{ANNOTATION_POINTS_FILE_NAME}`：LabelMe 点位吸附到可通行 mask 的中间结果。",
        f"- `{ALIAS_AUDIT_FILE_NAME}` / `{ALIAS_AUDIT_REPORT_FILE_NAME}`：中文搜索词审核结果。",
        "",
        "## 统计",
        "",
        f"- floors: `{graph['venue']['enabled_floors']}`",
        f"- nodes: `{len(graph['nodes'])}`",
        f"- edges: `{len(graph['edges'])}`",
        f"- pois: `{len(graph['pois'])}`",
        f"- entrances: `{len(graph['entrances'])}`",
        f"- connectors: `{len(graph['connectors'])}`",
        f"- graph_qa: `{dict(sorted(severity_counts.items()))}`",
        f"- alias_audit: `{dict(sorted(alias_severity_counts.items()))}`",
        "",
        "## 接入注意",
        "",
        "- 当前产物先放在 Mapping 输出目录，未直接覆盖 Android assets。",
        "- App 若要使用全楼层新图，需要把 graph/resolver 放入 assets 并切换加载文件名。",
        "- App 可优先读取 graph 内的 `shared_amap_alignment`；独立 alignment JSON 仅作为交付核对与后续拆分资产。",
        "- 共享映射成立前提：B1/F1/F2/F3/F4/F5/F6 截图必须保持同一高德视角、同尺寸、同裁切、同旋转。",
        "- `poi_resolver.items[]` 已包含 `venue_name`、`venue_address`、`subtitle`、`badges`、`external_refs`、`outdoor_handoff`，用于展示商场、地址、距离目标和高德未收录店铺状态。",
        "- `VELWIN` 已标记为 `result_type=indoor_only_poi`、`external_refs.amap_searchable=false`，室外段应交接到五道口购物中心西门，室内段再到店铺点。",
        "- B2/B3 未进入本次图，因为没有完整 mask/路网输入。",
        "- 如果某层仍和高德室内底图错位，需要为该楼层补 2 个以上锚点并生成 floor-specific transform。",
    ]
    (OUTPUT_DIR / "POSTPROCESS_REPORT.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    graph, annotation_points = build_graph()
    resolver = {
        "schema_version": "poi_resolver.v0.1",
        "venue_id": VENUE_ID,
        "source_graph": GRAPH_FILE_NAME,
        "items": graph["poi_resolver"],
    }
    annotation_export = {
        "schema_version": "mapping_annotation_points.v0.1",
        "coordinate_system": graph["coordinate_system"],
        "items": annotation_points,
    }
    alias_audit = audit_aliases(graph["poi_resolver"])

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    write_json(OUTPUT_DIR / GRAPH_FILE_NAME, graph)
    write_json(OUTPUT_DIR / RESOLVER_FILE_NAME, resolver)
    if graph.get("shared_amap_alignment"):
        write_json(OUTPUT_DIR / SHARED_AMAP_ALIGNMENT_FILE_NAME, graph["shared_amap_alignment"])
    write_json(OUTPUT_DIR / ANNOTATION_POINTS_FILE_NAME, annotation_export)
    write_json(OUTPUT_DIR / ALIAS_AUDIT_FILE_NAME, alias_audit)
    write_alias_report(alias_audit)
    write_postprocess_report(graph, alias_audit)

    print(f"generated={OUTPUT_DIR}")
    print(f"floors={','.join(graph['venue']['enabled_floors'])}")
    print(f"nodes={len(graph['nodes'])}")
    print(f"edges={len(graph['edges'])}")
    print(f"pois={len(graph['pois'])}")
    print(f"entrances={len(graph['entrances'])}")
    print(f"connectors={len(graph['connectors'])}")
    print(f"qa_errors={sum(1 for item in graph['qa'] if item['severity'] == 'error')}")
    print(f"qa_warnings={sum(1 for item in graph['qa'] if item['severity'] == 'warning')}")
    print(f"alias_warnings={sum(1 for item in alias_audit['items'] if item['severity'] == 'warning')}")


if __name__ == "__main__":
    main()
