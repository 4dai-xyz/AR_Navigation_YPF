import json
import heapq
import math
from collections import defaultdict, deque
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parent
BASE_DIR = ROOT / "annotation_points"
MASK_DIR = ROOT / "masks"
OUT_DIR = ROOT / "processed" / "walkable_network_previews"
RESULTS_DIR = OUT_DIR / "results"
DIAGNOSTICS_DIR = OUT_DIR / "diagnostics"
EXPERIMENT_OUT_DIR = RESULTS_DIR

ALGORITHM_ID = "A"
ALGORITHM_NAME = "raw_grid_baseline"
ALGORITHM_STATUS = "rollback_baseline"

GRID_STEP_PX = 35
MASK_THRESHOLD = 128
MASK_OVERLAY_COLOR = (0, 180, 255)
MASK_OVERLAY_ALPHA = 72
MIN_WHITE_PIXELS_PER_CELL = 4
CLEARANCE_RADIUS_PX = 8
MAX_VISIBLE_EDGE_PX = 49.5
MAX_REPAIR_EDGE_PX = 92
MAX_STRAIGHT_GAP_AXIS_DEVIATION_PX = 3
MAX_EXPERIMENT_NODES_PER_FLOOR = 28
MAX_ALGORITHM_C_VISUAL_GAP_NODES = 12
MAX_ALGORITHM_C_COMPONENT_BRIDGE_PX = 140
MAX_ALGORITHM_C_TERMINAL_EDGES = 24
MAX_ALGORITHM_C_TERMINAL_PATH_NODES = 8
MAX_ALGORITHM_C_TERMINAL_PATH_PX = 120
MAX_ALGORITHM_C_TERMINAL_PATH_WAYPOINTS = 3
MAX_ALGORITHM_C_TERMINAL_PATH_RATIO = 1.35
MAX_ALGORITHM_E_PATH_EDGES_PER_FLOOR = 72
MAX_ALGORITHM_E_PATH_EDGE_PX = 90
MAX_ALGORITHM_E_PATH_LENGTH_PX = 105
MAX_ALGORITHM_E_PATH_RATIO = 1.8
MIN_ALGORITHM_E_GRAPH_DETOUR = 1.35
MAX_ALGORITHM_E_GRAPH_SEARCH_PX = 260
MAX_ALGORITHM_F_PORTAL_NODES_PER_FLOOR = 48
MAX_ALGORITHM_F_PORTAL_EDGES_PER_FLOOR = 96
MAX_ALGORITHM_F_PORTAL_EDGE_PX = 120
MAX_ALGORITHM_F_PORTAL_PATH_PX = 160
MAX_ALGORITHM_F_PORTAL_PATH_RATIO = 2.2
MIN_ALGORITHM_F_GRAPH_DETOUR = 1.2
MAX_ALGORITHM_F_GRAPH_SEARCH_PX = 320
MAX_ALGORITHM_G_PORTAL_NODES_PER_FLOOR = 24
MAX_ALGORITHM_G_PORTAL_EDGES_PER_FLOOR = 48
MIN_ALGORITHM_G_GRAPH_DETOUR = 1.55
MIN_ALGORITHM_G_PORTAL_SPACING_PX = 42
MAX_EXPERIMENT_EDGES_PER_NEW_NODE = 4
LOCAL_PATH_MARGIN_PX = 18
MAX_LOCAL_PATH_VISITED = 12000

EDGE_CELL_OFFSETS = ((1, 0), (0, 1), (1, 1), (1, -1))
REPAIR_CELL_OFFSETS = (
    (1, 0),
    (0, 1),
    (1, 1),
    (1, -1),
    (2, 0),
    (0, 2),
    (2, 1),
    (1, 2),
    (2, -1),
    (1, -2),
    (-1, 0),
    (0, -1),
    (-1, -1),
    (-1, 1),
    (-2, 0),
    (0, -2),
    (-2, -1),
    (-1, -2),
    (-2, 1),
    (-1, 2),
)

RAW_EDGE_TARGETS = {
    "b1": 1812,
    "f1": 2245,
    "f2": 1895,
    "f3": 2107,
    "f4": 1834,
    "f5": 1892,
    "f6": 1044,
}
RAW_COMPONENT_TARGETS = {
    "b1": 6,
    "f1": 3,
    "f2": 1,
    "f3": 2,
    "f4": 1,
    "f5": 1,
    "f6": 6,
}

EXPERIMENT_ALGORITHMS = {
    "B": {
        "name": "straight_gap_sparse",
        "description": "补直线/近直线白色窄通道中超过普通连边阈值的缺口",
    },
    "C": {
        "name": "component_bridge_sparse",
        "description": "优先尝试用局部 mask 路径连接 Algorithm A 的小型断开组件",
    },
    "D": {
        "name": "cell_component_sparse",
        "description": "在单个 grid cell 内补未被 Algorithm A 代表的独立白色连通片",
    },
    "E": {
        "name": "mask_path_polyline_bridge",
        "description": "Use local mask paths to add sparse polyline edges between adjacent grid-cell nodes.",
    },
    "F": {
        "name": "cell_portal_bridge",
        "description": "Detect shared walkable cell-boundary portals and bridge them with sparse local mask paths.",
    },
    "G": {
        "name": "cell_portal_bridge_pruned",
        "description": "Prune Algorithm F portal bridges by detour gain and spatial spacing to reduce redundant edges.",
    },
}

def load_font(size):
    for path in ("C:/Windows/Fonts/msyh.ttc", "C:/Windows/Fonts/simhei.ttf", "C:/Windows/Fonts/arial.ttf"):
        if Path(path).exists():
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def algorithm_output_dir(algorithm_id, algorithm_name):
    return RESULTS_DIR / f"algorithm_{algorithm_id.lower()}_{algorithm_name}"


def line_walkable(mask, first_point, second_point, sample_step=1):
    first_x, first_y = first_point
    second_x, second_y = second_point
    distance = math.hypot(second_x - first_x, second_y - first_y)
    sample_count = max(1, int(distance / sample_step))
    height, width = mask.shape
    for index in range(sample_count + 1):
        ratio = index / sample_count
        x = round(first_x + (second_x - first_x) * ratio)
        y = round(first_y + (second_y - first_y) * ratio)
        if x < 0 or y < 0 or x >= width or y >= height or not mask[y, x]:
            return False
    return True


def box_count_map(mask, radius):
    mask_int = mask.astype(np.uint16)
    integral = np.pad(mask_int, ((1, 0), (1, 0)), mode="constant").cumsum(axis=0).cumsum(axis=1)
    height, width = mask.shape
    y_indexes = np.arange(height)
    x_indexes = np.arange(width)
    y0 = np.maximum(y_indexes - radius, 0)
    y1 = np.minimum(y_indexes + radius + 1, height)
    x0 = np.maximum(x_indexes - radius, 0)
    x1 = np.minimum(x_indexes + radius + 1, width)
    return (
        integral[y1[:, None], x1[None, :]]
        - integral[y0[:, None], x1[None, :]]
        - integral[y1[:, None], x0[None, :]]
        + integral[y0[:, None], x0[None, :]]
    )


def best_walkable_pixel(cell, cell_clearance, origin_x, origin_y):
    walkable_y, walkable_x = np.nonzero(cell)
    if len(walkable_x) == 0:
        return None
    center_x = (cell.shape[1] - 1) / 2
    center_y = (cell.shape[0] - 1) / 2
    clearance = cell_clearance[walkable_y, walkable_x]
    center_distances = (walkable_x - center_x) ** 2 + (walkable_y - center_y) ** 2
    score = (clearance.max() - clearance) * 100000 + center_distances
    best = int(np.argmin(score))
    return int(walkable_x[best] + origin_x), int(walkable_y[best] + origin_y)


def point_cell(point):
    return int(point[0] // GRID_STEP_PX), int(point[1] // GRID_STEP_PX)


def cell_white_pixels(mask, cell_x, cell_y):
    origin_x = cell_x * GRID_STEP_PX
    origin_y = cell_y * GRID_STEP_PX
    return int(
        np.count_nonzero(
            mask[
                origin_y : min(origin_y + GRID_STEP_PX, mask.shape[0]),
                origin_x : min(origin_x + GRID_STEP_PX, mask.shape[1]),
            ]
        )
    )


def make_node(nodes, index, cell_x, cell_y, point, mask, node_source):
    node_id = f"node_grid_{len(nodes) + 1:05d}"
    node = {
        "node_id": node_id,
        "cell_x": cell_x,
        "cell_y": cell_y,
        "x": point[0],
        "y": point[1],
        "white_pixels": cell_white_pixels(mask, cell_x, cell_y),
        "node_source": node_source,
    }
    nodes.append(node)
    index[(cell_x, cell_y)].append({"node_id": node_id, "point": point})
    return node


def build_edges(mask, index):
    edges = []
    edge_pairs = set()

    def add_edge(first_node, second_node):
        first_point = first_node["point"]
        second_point = second_node["point"]
        if math.hypot(second_point[0] - first_point[0], second_point[1] - first_point[1]) > MAX_VISIBLE_EDGE_PX:
            return
        if not line_walkable(mask, first_point, second_point):
            return
        pair = tuple(sorted((first_node["node_id"], second_node["node_id"])))
        if pair in edge_pairs:
            return
        edge_pairs.add(pair)
        edges.append(
            {
                "edge_id": f"edge_{first_node['node_id']}_to_{second_node['node_id']}",
                "from_node_id": first_node["node_id"],
                "to_node_id": second_node["node_id"],
                "path": [first_point, second_point],
                "edge_type": "raw_grid",
            }
        )

    for cell_nodes in index.values():
        for first_index in range(len(cell_nodes)):
            for second_index in range(first_index + 1, len(cell_nodes)):
                add_edge(cell_nodes[first_index], cell_nodes[second_index])

    for (cell_x, cell_y), cell_nodes in index.items():
        for first_node in cell_nodes:
            for offset_x, offset_y in EDGE_CELL_OFFSETS:
                for second_node in index.get((cell_x + offset_x, cell_y + offset_y), []):
                    add_edge(first_node, second_node)
    return edges, edge_pairs


def build_raw_grid_network(mask):
    nodes = []
    index = defaultdict(list)
    height, width = mask.shape
    clearance = box_count_map(mask, CLEARANCE_RADIUS_PX)
    for cell_y, origin_y in enumerate(range(0, height, GRID_STEP_PX)):
        for cell_x, origin_x in enumerate(range(0, width, GRID_STEP_PX)):
            cell = mask[origin_y : min(origin_y + GRID_STEP_PX, height), origin_x : min(origin_x + GRID_STEP_PX, width)]
            if int(np.count_nonzero(cell)) < MIN_WHITE_PIXELS_PER_CELL:
                continue
            cell_clearance = clearance[
                origin_y : min(origin_y + GRID_STEP_PX, height),
                origin_x : min(origin_x + GRID_STEP_PX, width),
            ]
            point = best_walkable_pixel(cell, cell_clearance, origin_x, origin_y)
            if point is not None:
                make_node(nodes, index, cell_x, cell_y, point, mask, "raw_grid")
    edges, _edge_pairs = build_edges(mask, index)
    return nodes, edges, index


def connected_components(node_ids, edges):
    adjacency = defaultdict(list)
    for edge in edges:
        adjacency[edge["from_node_id"]].append(edge["to_node_id"])
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
            current = queue.popleft()
            size += 1
            for next_node in adjacency[current]:
                if next_node not in seen:
                    seen.add(next_node)
                    queue.append(next_node)
        sizes.append(size)
    return sorted(sizes, reverse=True)


def component_groups(node_ids, edges):
    adjacency = defaultdict(list)
    for edge in edges:
        adjacency[edge["from_node_id"]].append(edge["to_node_id"])
        adjacency[edge["to_node_id"]].append(edge["from_node_id"])
    seen = set()
    groups = []
    for node_id in node_ids:
        if node_id in seen:
            continue
        queue = deque([node_id])
        seen.add(node_id)
        group = []
        while queue:
            current = queue.popleft()
            group.append(current)
            for next_node in adjacency[current]:
                if next_node not in seen:
                    seen.add(next_node)
                    queue.append(next_node)
        groups.append(group)
    return sorted(groups, key=len, reverse=True)


def weighted_adjacency(nodes, edges):
    node_map = {node["node_id"]: node for node in nodes}
    adjacency = defaultdict(list)
    for edge in edges:
        first = node_map[edge["from_node_id"]]
        second = node_map[edge["to_node_id"]]
        distance = math.hypot(second["x"] - first["x"], second["y"] - first["y"])
        adjacency[edge["from_node_id"]].append((edge["to_node_id"], distance))
        adjacency[edge["to_node_id"]].append((edge["from_node_id"], distance))
    return adjacency


def shortest_path_length(adjacency, source_id, target_id, max_distance):
    queue = [(0.0, source_id)]
    distances = {source_id: 0.0}
    while queue:
        current_distance, current_id = heapq.heappop(queue)
        if current_id == target_id:
            return current_distance
        if current_distance != distances[current_id] or current_distance > max_distance:
            continue
        for next_id, edge_distance in adjacency[current_id]:
            next_distance = current_distance + edge_distance
            if next_distance > max_distance or next_distance >= distances.get(next_id, float("inf")):
                continue
            distances[next_id] = next_distance
            heapq.heappush(queue, (next_distance, next_id))
    return None


def node_degree_map(edges):
    degrees = defaultdict(int)
    for edge in edges:
        degrees[edge["from_node_id"]] += 1
        degrees[edge["to_node_id"]] += 1
    return degrees


def cell_has_near_node(cell_nodes, point, max_distance=4):
    return any(math.hypot(node["point"][0] - point[0], node["point"][1] - point[1]) <= max_distance for node in cell_nodes)


def exact_cell_node(cell_nodes, point):
    for node in cell_nodes:
        if node["point"] == point:
            return node
    return None


def clone_network(nodes, edges):
    return [dict(node) for node in nodes], [dict(edge) for edge in edges]


def build_index_from_nodes(nodes):
    index = defaultdict(list)
    for node in nodes:
        index[(node["cell_x"], node["cell_y"])].append({"node_id": node["node_id"], "point": (node["x"], node["y"])})
    return index


def edge_pair_set(edges):
    return {tuple(sorted((edge["from_node_id"], edge["to_node_id"]))) for edge in edges}


def add_experiment_edge(edges, edge_pairs, first_id, second_id, first_point, second_point, edge_type, path=None):
    if first_id == second_id:
        return False
    pair = tuple(sorted((first_id, second_id)))
    if pair in edge_pairs:
        return False
    edge_pairs.add(pair)
    edges.append(
        {
            "edge_id": f"edge_{first_id}_to_{second_id}_{edge_type}",
            "from_node_id": first_id,
            "to_node_id": second_id,
            "path": path or [first_point, second_point],
            "edge_type": edge_type,
        }
    )
    return True


def connect_new_node_to_nearby(mask, nodes, index, edges, edge_pairs, new_node, edge_type):
    candidates = []
    new_point = (new_node["x"], new_node["y"])
    for offset_x in (-1, 0, 1):
        for offset_y in (-1, 0, 1):
            for neighbor in index.get((new_node["cell_x"] + offset_x, new_node["cell_y"] + offset_y), []):
                if neighbor["node_id"] == new_node["node_id"]:
                    continue
                pair = tuple(sorted((new_node["node_id"], neighbor["node_id"])))
                if pair in edge_pairs:
                    continue
                neighbor_point = neighbor["point"]
                distance = math.hypot(neighbor_point[0] - new_point[0], neighbor_point[1] - new_point[1])
                if distance > MAX_VISIBLE_EDGE_PX:
                    continue
                if not line_walkable(mask, new_point, neighbor_point):
                    continue
                candidates.append((distance, neighbor["node_id"], neighbor_point))

    for _distance, neighbor_id, neighbor_point in sorted(candidates)[:MAX_EXPERIMENT_EDGES_PER_NEW_NODE]:
        add_experiment_edge(edges, edge_pairs, new_node["node_id"], neighbor_id, new_point, neighbor_point, edge_type)


def choose_repair_waypoints(mask, first_point, second_point):
    delta_x = second_point[0] - first_point[0]
    delta_y = second_point[1] - first_point[1]
    distance = math.hypot(delta_x, delta_y)
    waypoint_count = int(math.ceil(distance / MAX_VISIBLE_EDGE_PX)) - 1
    if waypoint_count <= 0:
        return []

    selected_points = []
    for waypoint_index in range(1, waypoint_count + 1):
        ratio = waypoint_index / (waypoint_count + 1)
        ideal_x = round(first_point[0] + delta_x * ratio)
        ideal_y = round(first_point[1] + delta_y * ratio)
        best_candidate = None
        for radius in range(0, 4):
            candidates = []
            for offset_y in range(-radius, radius + 1):
                for offset_x in range(-radius, radius + 1):
                    point = (ideal_x + offset_x, ideal_y + offset_y)
                    if point[0] < 0 or point[1] < 0 or point[0] >= mask.shape[1] or point[1] >= mask.shape[0]:
                        continue
                    if not mask[point[1], point[0]]:
                        continue
                    if not line_walkable(mask, first_point, point):
                        continue
                    if not line_walkable(mask, point, second_point):
                        continue
                    candidates.append((abs(offset_x) + abs(offset_y), abs(offset_y), abs(offset_x), point))
            if candidates:
                best_candidate = min(candidates)[-1]
                break
        if best_candidate is None:
            return None
        selected_points.append(best_candidate)
    return selected_points


def local_mask_path(mask, first_point, second_point):
    first_x, first_y = first_point
    second_x, second_y = second_point
    height, width = mask.shape
    margin = max(LOCAL_PATH_MARGIN_PX, GRID_STEP_PX)
    min_x = max(min(first_x, second_x) - margin, 0)
    max_x = min(max(first_x, second_x) + margin, width - 1)
    min_y = max(min(first_y, second_y) - margin, 0)
    max_y = min(max(first_y, second_y) + margin, height - 1)

    local = mask[min_y : max_y + 1, min_x : max_x + 1]
    start = (first_x - min_x, first_y - min_y)
    target = (second_x - min_x, second_y - min_y)
    if not local[start[1], start[0]] or not local[target[1], target[0]]:
        return None

    queue = deque([start])
    previous = {start: None}
    directions = ((1, 0), (-1, 0), (0, 1), (0, -1), (1, 1), (1, -1), (-1, 1), (-1, -1))
    while queue:
        current = queue.popleft()
        if current == target:
            break
        if len(previous) > MAX_LOCAL_PATH_VISITED:
            return None
        for offset_x, offset_y in directions:
            next_x = current[0] + offset_x
            next_y = current[1] + offset_y
            neighbor = (next_x, next_y)
            if next_x < 0 or next_y < 0 or next_x >= local.shape[1] or next_y >= local.shape[0]:
                continue
            if neighbor in previous or not local[next_y, next_x]:
                continue
            previous[neighbor] = current
            queue.append(neighbor)

    if target not in previous:
        return None

    path = []
    current = target
    while current is not None:
        path.append((current[0] + min_x, current[1] + min_y))
        current = previous[current]
    path.reverse()
    return path


def euclidean_path_length(path):
    return sum(
        math.hypot(path[index][0] - path[index - 1][0], path[index][1] - path[index - 1][1])
        for index in range(1, len(path))
    )


def sparse_path_waypoints(mask, path):
    if len(path) <= 2:
        return []
    waypoints = []
    last_point = path[0]
    travelled = 0.0
    for index in range(1, len(path) - 1):
        current = path[index]
        travelled += math.hypot(current[0] - path[index - 1][0], current[1] - path[index - 1][1])
        if travelled < GRID_STEP_PX:
            continue
        if math.hypot(current[0] - last_point[0], current[1] - last_point[1]) > MAX_VISIBLE_EDGE_PX:
            return None
        if not line_walkable(mask, last_point, current):
            return None
        waypoints.append(current)
        last_point = current
        travelled = 0.0
        if len(waypoints) > 4:
            return None
    if math.hypot(path[-1][0] - last_point[0], path[-1][1] - last_point[1]) > MAX_VISIBLE_EDGE_PX:
        return None
    if not line_walkable(mask, last_point, path[-1]):
        return None
    return waypoints


def terminal_path_waypoints(mask, path, max_waypoints):
    if len(path) <= 2:
        return []

    waypoints = []
    start_index = 0
    while start_index < len(path) - 1:
        start_point = path[start_index]
        end_point = path[-1]
        if math.hypot(end_point[0] - start_point[0], end_point[1] - start_point[1]) <= MAX_VISIBLE_EDGE_PX:
            if line_walkable(mask, start_point, end_point):
                return waypoints

        next_index = None
        for candidate_index in range(len(path) - 2, start_index, -1):
            candidate = path[candidate_index]
            if math.hypot(candidate[0] - start_point[0], candidate[1] - start_point[1]) > MAX_VISIBLE_EDGE_PX:
                continue
            if line_walkable(mask, start_point, candidate):
                next_index = candidate_index
                break
        if next_index is None:
            return None

        waypoints.append(path[next_index])
        if len(waypoints) > max_waypoints:
            return None
        start_index = next_index

    return waypoints


def walkable_components(cell):
    seen = np.zeros_like(cell, dtype=bool)
    components = []
    for start_y in range(cell.shape[0]):
        for start_x in range(cell.shape[1]):
            if not cell[start_y, start_x] or seen[start_y, start_x]:
                continue
            queue = deque([(start_x, start_y)])
            seen[start_y, start_x] = True
            component = []
            while queue:
                current_x, current_y = queue.popleft()
                component.append((current_x, current_y))
                for offset_y in (-1, 0, 1):
                    for offset_x in (-1, 0, 1):
                        if offset_x == 0 and offset_y == 0:
                            continue
                        next_x = current_x + offset_x
                        next_y = current_y + offset_y
                        if next_x < 0 or next_y < 0 or next_x >= cell.shape[1] or next_y >= cell.shape[0]:
                            continue
                        if seen[next_y, next_x] or not cell[next_y, next_x]:
                            continue
                        seen[next_y, next_x] = True
                        queue.append((next_x, next_y))
            components.append(component)
    return components


def component_touches_boundary(component, cell_shape):
    height, width = cell_shape
    return any(x == 0 or y == 0 or x == width - 1 or y == height - 1 for x, y in component)


def best_component_pixel(component, origin_x, origin_y):
    component_x = np.array([point[0] for point in component])
    component_y = np.array([point[1] for point in component])
    center_x = (GRID_STEP_PX - 1) / 2
    center_y = (GRID_STEP_PX - 1) / 2
    center_distances = (component_x - center_x) ** 2 + (component_y - center_y) ** 2
    best = int(np.argmin(center_distances))
    return int(component_x[best] + origin_x), int(component_y[best] + origin_y)


def add_sparse_chain(
    mask,
    nodes,
    index,
    edges,
    edge_pairs,
    first_node,
    second_node,
    waypoints,
    node_source,
    edge_type,
    skip_near_waypoints=True,
):
    if not waypoints:
        return 0, 0
    created_nodes = []
    waypoint_nodes = []
    node_map = {node["node_id"]: node for node in nodes}
    for point in waypoints:
        cell = point_cell(point)
        exact_node = exact_cell_node(index.get(cell, []), point)
        if exact_node is not None:
            waypoint_nodes.append(node_map[exact_node["node_id"]])
            continue
        if skip_near_waypoints and cell_has_near_node(index.get(cell, []), point):
            continue
        new_node = make_node(nodes, index, cell[0], cell[1], point, mask, node_source)
        node_map[new_node["node_id"]] = new_node
        created_nodes.append(new_node)
        waypoint_nodes.append(new_node)
    if not waypoint_nodes:
        return 0, 0

    chain = [
        (first_node["node_id"], (first_node["x"], first_node["y"])),
        *[(node["node_id"], (node["x"], node["y"])) for node in waypoint_nodes],
        (second_node["node_id"], (second_node["x"], second_node["y"])),
    ]
    added_edges = 0
    for index_in_chain in range(1, len(chain)):
        first_id, first_point = chain[index_in_chain - 1]
        second_id, second_point = chain[index_in_chain]
        if math.hypot(second_point[0] - first_point[0], second_point[1] - first_point[1]) > MAX_VISIBLE_EDGE_PX:
            continue
        if not line_walkable(mask, first_point, second_point):
            continue
        if add_experiment_edge(edges, edge_pairs, first_id, second_id, first_point, second_point, edge_type):
            added_edges += 1
    return len(created_nodes), added_edges


def build_algorithm_b(mask, base_nodes, base_edges):
    nodes, edges = clone_network(base_nodes, base_edges)
    index = build_index_from_nodes(nodes)
    edge_pairs = edge_pair_set(edges)
    candidate_pairs = set()
    candidates = []

    for (cell_x, cell_y), cell_nodes in list(index.items()):
        for first_ref in list(cell_nodes):
            first_point = first_ref["point"]
            for offset_x, offset_y in REPAIR_CELL_OFFSETS:
                for second_ref in list(index.get((cell_x + offset_x, cell_y + offset_y), [])):
                    pair = tuple(sorted((first_ref["node_id"], second_ref["node_id"])))
                    if pair in edge_pairs or pair in candidate_pairs:
                        continue
                    second_point = second_ref["point"]
                    delta_x = second_point[0] - first_point[0]
                    delta_y = second_point[1] - first_point[1]
                    distance = math.hypot(delta_x, delta_y)
                    if distance <= MAX_VISIBLE_EDGE_PX or distance > MAX_REPAIR_EDGE_PX:
                        continue
                    if min(abs(delta_x), abs(delta_y)) > MAX_STRAIGHT_GAP_AXIS_DEVIATION_PX:
                        continue
                    if not line_walkable(mask, first_point, second_point):
                        continue
                    waypoints = choose_repair_waypoints(mask, first_point, second_point)
                    if not waypoints:
                        continue
                    candidate_pairs.add(pair)
                    candidates.append((distance, first_ref["node_id"], second_ref["node_id"], waypoints))

    node_map = {node["node_id"]: node for node in nodes}
    added_nodes = 0
    added_edges = 0
    for _distance, first_id, second_id, waypoints in sorted(candidates):
        if added_nodes + len(waypoints) > MAX_EXPERIMENT_NODES_PER_FLOOR:
            continue
        new_nodes, new_edges = add_sparse_chain(
            mask,
            nodes,
            index,
            edges,
            edge_pairs,
            node_map[first_id],
            node_map[second_id],
            waypoints,
            "algorithm_b",
            "algorithm_b",
        )
        added_nodes += new_nodes
        added_edges += new_edges
        node_map = {node["node_id"]: node for node in nodes}
    return nodes, edges, {"added_nodes": added_nodes, "added_edges": added_edges}


def add_algorithm_c_visual_gap_nodes(mask, nodes, index, edges, node_budget):
    if node_budget <= 0:
        return 0, 0

    edge_pairs = edge_pair_set(edges)
    degrees = node_degree_map(edges)
    node_map = {node["node_id"]: node for node in nodes}
    candidate_pairs = set()
    candidates = []

    for (cell_x, cell_y), cell_nodes in list(index.items()):
        for first_ref in list(cell_nodes):
            first_node = node_map[first_ref["node_id"]]
            first_point = first_ref["point"]
            for offset_x, offset_y in REPAIR_CELL_OFFSETS:
                for second_ref in list(index.get((cell_x + offset_x, cell_y + offset_y), [])):
                    pair = tuple(sorted((first_ref["node_id"], second_ref["node_id"])))
                    if pair in edge_pairs or pair in candidate_pairs:
                        continue
                    second_node = node_map[second_ref["node_id"]]
                    second_point = second_ref["point"]
                    delta_x = second_point[0] - first_point[0]
                    delta_y = second_point[1] - first_point[1]
                    distance = math.hypot(delta_x, delta_y)
                    if distance <= MAX_VISIBLE_EDGE_PX or distance > MAX_REPAIR_EDGE_PX:
                        continue
                    if min(abs(delta_x), abs(delta_y)) > MAX_STRAIGHT_GAP_AXIS_DEVIATION_PX:
                        continue
                    if min(degrees[first_node["node_id"]], degrees[second_node["node_id"]]) > 5:
                        continue
                    if not line_walkable(mask, first_point, second_point):
                        continue
                    waypoints = choose_repair_waypoints(mask, first_point, second_point)
                    if not waypoints or len(waypoints) > 1:
                        continue
                    waypoint = waypoints[0]
                    waypoint_cell = point_cell(waypoint)
                    if cell_has_near_node(index.get(waypoint_cell, []), waypoint, max_distance=10):
                        continue
                    candidate_pairs.add(pair)
                    source_priority = 0 if "algorithm_c" in (first_node.get("node_source"), second_node.get("node_source")) else 1
                    degree_score = min(degrees[first_node["node_id"]], degrees[second_node["node_id"]])
                    candidates.append(
                        (
                            source_priority,
                            degree_score,
                            -distance,
                            first_node["node_id"],
                            second_node["node_id"],
                            waypoints,
                        )
                    )

    added_nodes = 0
    added_edges = 0
    for _source_priority, _degree_score, _negative_distance, first_id, second_id, waypoints in sorted(candidates):
        if added_nodes + len(waypoints) > node_budget:
            continue
        node_map = {node["node_id"]: node for node in nodes}
        new_nodes, new_edges = add_sparse_chain(
            mask,
            nodes,
            index,
            edges,
            edge_pairs,
            node_map[first_id],
            node_map[second_id],
            waypoints,
            "algorithm_c",
            "algorithm_c",
        )
        added_nodes += new_nodes
        added_edges += new_edges
        if added_nodes >= node_budget:
            break
    return added_nodes, added_edges


def add_algorithm_c_terminal_edges(mask, nodes, index, edges, edge_budget):
    if edge_budget <= 0:
        return 0

    edge_pairs = edge_pair_set(edges)
    degrees = node_degree_map(edges)
    node_map = {node["node_id"]: node for node in nodes}
    candidate_pairs = set()
    candidates = []

    for (cell_x, cell_y), cell_nodes in list(index.items()):
        for first_ref in list(cell_nodes):
            first_node = node_map[first_ref["node_id"]]
            first_point = first_ref["point"]
            for offset_x, offset_y in REPAIR_CELL_OFFSETS:
                for second_ref in list(index.get((cell_x + offset_x, cell_y + offset_y), [])):
                    second_node = node_map[second_ref["node_id"]]
                    pair = tuple(sorted((first_node["node_id"], second_node["node_id"])))
                    if pair in edge_pairs or pair in candidate_pairs:
                        continue
                    second_point = second_ref["point"]
                    distance = math.hypot(second_point[0] - first_point[0], second_point[1] - first_point[1])
                    if distance > MAX_VISIBLE_EDGE_PX:
                        continue
                    if not line_walkable(mask, first_point, second_point):
                        continue

                    first_is_algorithm = first_node.get("node_source") == "algorithm_c"
                    second_is_algorithm = second_node.get("node_source") == "algorithm_c"
                    first_is_terminal = degrees[first_node["node_id"]] <= 2
                    second_is_terminal = degrees[second_node["node_id"]] <= 2
                    if not (first_is_algorithm or second_is_algorithm or first_is_terminal or second_is_terminal):
                        continue

                    candidate_pairs.add(pair)
                    source_priority = 0 if first_is_algorithm or second_is_algorithm else 1
                    degree_score = min(degrees[first_node["node_id"]], degrees[second_node["node_id"]])
                    candidates.append(
                        (
                            degree_score,
                            source_priority,
                            distance,
                            first_node["node_id"],
                            second_node["node_id"],
                            first_point,
                            second_point,
                        )
                    )

    added_edges = 0
    for _degree_score, _source_priority, _distance, first_id, second_id, first_point, second_point in sorted(candidates):
        if added_edges >= edge_budget:
            break
        if add_experiment_edge(edges, edge_pairs, first_id, second_id, first_point, second_point, "algorithm_c"):
            added_edges += 1
    return added_edges


def add_algorithm_c_terminal_path_nodes(mask, nodes, index, edges, node_budget):
    if node_budget <= 0:
        return 0, 0

    edge_pairs = edge_pair_set(edges)
    degrees = node_degree_map(edges)
    node_map = {node["node_id"]: node for node in nodes}
    candidate_pairs = set()
    candidates = []

    for (cell_x, cell_y), cell_nodes in list(index.items()):
        for first_ref in list(cell_nodes):
            first_node = node_map[first_ref["node_id"]]
            first_point = first_ref["point"]
            for offset_x, offset_y in REPAIR_CELL_OFFSETS:
                for second_ref in list(index.get((cell_x + offset_x, cell_y + offset_y), [])):
                    second_node = node_map[second_ref["node_id"]]
                    pair = tuple(sorted((first_node["node_id"], second_node["node_id"])))
                    if pair in edge_pairs or pair in candidate_pairs:
                        continue
                    second_point = second_ref["point"]
                    distance = math.hypot(second_point[0] - first_point[0], second_point[1] - first_point[1])
                    if distance <= MAX_VISIBLE_EDGE_PX or distance > MAX_ALGORITHM_C_TERMINAL_PATH_PX:
                        continue

                    first_is_algorithm = first_node.get("node_source") == "algorithm_c"
                    second_is_algorithm = second_node.get("node_source") == "algorithm_c"
                    first_is_terminal = degrees[first_node["node_id"]] <= 2
                    second_is_terminal = degrees[second_node["node_id"]] <= 2
                    if not (first_is_algorithm or second_is_algorithm or first_is_terminal or second_is_terminal):
                        continue
                    degree_score = min(degrees[first_node["node_id"]], degrees[second_node["node_id"]])
                    if degree_score > 1:
                        continue

                    path = local_mask_path(mask, first_point, second_point)
                    if path is None:
                        continue
                    path_length = euclidean_path_length(path)
                    if path_length > distance * MAX_ALGORITHM_C_TERMINAL_PATH_RATIO:
                        continue
                    waypoints = terminal_path_waypoints(mask, path, MAX_ALGORITHM_C_TERMINAL_PATH_WAYPOINTS)
                    if not waypoints:
                        continue
                    if any(cell_has_near_node(index.get(point_cell(waypoint), []), waypoint) for waypoint in waypoints):
                        continue

                    candidate_pairs.add(pair)
                    source_priority = 0 if first_is_algorithm or second_is_algorithm else 1
                    path_ratio = path_length / distance
                    candidates.append(
                        (
                            degree_score,
                            path_ratio,
                            len(waypoints),
                            source_priority,
                            path_length,
                            distance,
                            first_node["node_id"],
                            second_node["node_id"],
                            waypoints,
                        )
                    )

    added_nodes = 0
    added_edges = 0
    for _degree_score, _path_ratio, waypoint_count, _source_priority, _path_length, _distance, first_id, second_id, waypoints in sorted(candidates):
        if added_nodes + waypoint_count > node_budget:
            continue
        node_map = {node["node_id"]: node for node in nodes}
        new_nodes, new_edges = add_sparse_chain(
            mask,
            nodes,
            index,
            edges,
            edge_pairs,
            node_map[first_id],
            node_map[second_id],
            waypoints,
            "algorithm_c",
            "algorithm_c",
        )
        added_nodes += new_nodes
        added_edges += new_edges
        if added_nodes >= node_budget:
            break
    return added_nodes, added_edges


def add_algorithm_e_polyline_edges(mask, nodes, index, edges, edge_budget):
    if edge_budget <= 0:
        return 0

    edge_pairs = edge_pair_set(edges)
    node_map = {node["node_id"]: node for node in nodes}
    adjacency = weighted_adjacency(nodes, edges)
    candidate_pairs = set()
    candidates = []

    for (cell_x, cell_y), cell_nodes in list(index.items()):
        for first_ref in list(cell_nodes):
            first_node = node_map[first_ref["node_id"]]
            first_point = first_ref["point"]
            for offset_x, offset_y in EDGE_CELL_OFFSETS:
                for second_ref in list(index.get((cell_x + offset_x, cell_y + offset_y), [])):
                    second_node = node_map[second_ref["node_id"]]
                    pair = tuple(sorted((first_node["node_id"], second_node["node_id"])))
                    if pair in edge_pairs or pair in candidate_pairs:
                        continue

                    second_point = second_ref["point"]
                    distance = math.hypot(second_point[0] - first_point[0], second_point[1] - first_point[1])
                    if distance <= MAX_VISIBLE_EDGE_PX or distance > MAX_ALGORITHM_E_PATH_EDGE_PX:
                        continue
                    if line_walkable(mask, first_point, second_point):
                        continue

                    path = local_mask_path(mask, first_point, second_point)
                    if path is None:
                        continue
                    path_length = euclidean_path_length(path)
                    if (
                        path_length > MAX_ALGORITHM_E_PATH_LENGTH_PX
                        or path_length > distance * MAX_ALGORITHM_E_PATH_RATIO
                    ):
                        continue

                    graph_distance = shortest_path_length(
                        adjacency,
                        first_node["node_id"],
                        second_node["node_id"],
                        MAX_ALGORITHM_E_GRAPH_SEARCH_PX,
                    )
                    if graph_distance is None:
                        continue
                    graph_detour = graph_distance / path_length
                    if graph_detour < MIN_ALGORITHM_E_GRAPH_DETOUR:
                        continue

                    candidate_pairs.add(pair)
                    path_ratio = path_length / distance
                    candidates.append(
                        (
                            -(graph_detour * path_ratio),
                            path_length,
                            first_node["node_id"],
                            second_node["node_id"],
                            path,
                        )
                    )

    added_edges = 0
    for _score, _path_length, first_id, second_id, path in sorted(candidates):
        if added_edges >= edge_budget:
            break
        first_node = node_map[first_id]
        second_node = node_map[second_id]
        if add_experiment_edge(
            edges,
            edge_pairs,
            first_id,
            second_id,
            (first_node["x"], first_node["y"]),
            (second_node["x"], second_node["y"]),
            "algorithm_e",
            path=path,
        ):
            added_edges += 1
    return added_edges


def contiguous_true_runs(values):
    runs = []
    start = None
    for index, value in enumerate(values):
        if value and start is None:
            start = index
        elif not value and start is not None:
            runs.append((start, index - 1))
            start = None
    if start is not None:
        runs.append((start, len(values) - 1))
    return runs


def shared_boundary_portals(mask, cell_x, cell_y, offset_x, offset_y):
    height, width = mask.shape
    portals = []
    if offset_x == 1 and offset_y == 0:
        left_x = (cell_x + 1) * GRID_STEP_PX - 1
        right_x = left_x + 1
        if left_x < 0 or right_x >= width:
            return portals
        y0 = cell_y * GRID_STEP_PX
        y1 = min(y0 + GRID_STEP_PX, height)
        shared = mask[y0:y1, left_x] & mask[y0:y1, right_x]
        for run_start, run_end in contiguous_true_runs(shared):
            y = y0 + (run_start + run_end) // 2
            portals.append((right_x, y))
    elif offset_x == 0 and offset_y == 1:
        top_y = (cell_y + 1) * GRID_STEP_PX - 1
        bottom_y = top_y + 1
        if top_y < 0 or bottom_y >= height:
            return portals
        x0 = cell_x * GRID_STEP_PX
        x1 = min(x0 + GRID_STEP_PX, width)
        shared = mask[top_y, x0:x1] & mask[bottom_y, x0:x1]
        for run_start, run_end in contiguous_true_runs(shared):
            x = x0 + (run_start + run_end) // 2
            portals.append((x, bottom_y))
    return portals


def get_or_create_portal_node(mask, nodes, index, portal_nodes, point, node_source):
    if point in portal_nodes:
        return portal_nodes[point], False
    cell = point_cell(point)
    exact_node = exact_cell_node(index.get(cell, []), point)
    if exact_node is not None:
        node_map = {node["node_id"]: node for node in nodes}
        portal_nodes[point] = node_map[exact_node["node_id"]]
        return portal_nodes[point], False
    if cell_has_near_node(index.get(cell, []), point, max_distance=3):
        return None, False
    node = make_node(nodes, index, cell[0], cell[1], point, mask, node_source)
    portal_nodes[point] = node
    return node, True


def add_algorithm_f_portal_bridges(
    mask,
    nodes,
    index,
    edges,
    node_budget,
    edge_budget,
    edge_type="algorithm_f",
    node_source="algorithm_f",
    min_graph_detour=MIN_ALGORITHM_F_GRAPH_DETOUR,
    min_portal_spacing_px=0,
):
    if node_budget <= 0 or edge_budget <= 0:
        return 0, 0

    edge_pairs = edge_pair_set(edges)
    node_map = {node["node_id"]: node for node in nodes}
    adjacency = weighted_adjacency(nodes, edges)
    candidates = []

    for (cell_x, cell_y), cell_nodes in list(index.items()):
        for offset_x, offset_y in ((1, 0), (0, 1)):
            neighbor_nodes = list(index.get((cell_x + offset_x, cell_y + offset_y), []))
            if not neighbor_nodes:
                continue
            for portal in shared_boundary_portals(mask, cell_x, cell_y, offset_x, offset_y):
                best_candidate = None
                for first_ref in list(cell_nodes):
                    first_node = node_map[first_ref["node_id"]]
                    first_point = first_ref["point"]
                    for second_ref in neighbor_nodes:
                        second_node = node_map[second_ref["node_id"]]
                        second_point = second_ref["point"]
                        pair = tuple(sorted((first_node["node_id"], second_node["node_id"])))
                        if pair in edge_pairs or line_walkable(mask, first_point, second_point):
                            continue
                        distance = math.hypot(second_point[0] - first_point[0], second_point[1] - first_point[1])
                        if distance > MAX_ALGORITHM_F_PORTAL_EDGE_PX:
                            continue
                        first_path = local_mask_path(mask, first_point, portal)
                        second_path = local_mask_path(mask, portal, second_point)
                        if first_path is None or second_path is None:
                            continue
                        path_length = euclidean_path_length(first_path) + euclidean_path_length(second_path)
                        if (
                            path_length > MAX_ALGORITHM_F_PORTAL_PATH_PX
                            or path_length > max(distance, 1.0) * MAX_ALGORITHM_F_PORTAL_PATH_RATIO
                        ):
                            continue
                        graph_distance = shortest_path_length(
                            adjacency,
                            first_node["node_id"],
                            second_node["node_id"],
                            MAX_ALGORITHM_F_GRAPH_SEARCH_PX,
                        )
                        if graph_distance is not None and graph_distance / path_length < min_graph_detour:
                            continue
                        graph_detour = (graph_distance / path_length) if graph_distance is not None else 3.0
                        candidate = (
                            -(graph_detour * path_length / max(distance, 1.0)),
                            path_length,
                            first_node["node_id"],
                            second_node["node_id"],
                            portal,
                            first_path,
                            second_path,
                        )
                        if best_candidate is None or candidate < best_candidate:
                            best_candidate = candidate
                if best_candidate is not None:
                    candidates.append(best_candidate)

    added_nodes = 0
    added_edges = 0
    portal_nodes = {}
    selected_portals = []
    for _score, _path_length, first_id, second_id, portal, first_path, second_path in sorted(candidates):
        if added_nodes >= node_budget or added_edges >= edge_budget:
            break
        if min_portal_spacing_px > 0 and any(
            math.hypot(portal[0] - selected[0], portal[1] - selected[1]) < min_portal_spacing_px
            for selected in selected_portals
        ):
            continue
        node_map = {node["node_id"]: node for node in nodes}
        portal_node, created = get_or_create_portal_node(mask, nodes, index, portal_nodes, portal, node_source)
        if portal_node is None:
            continue
        if created:
            added_nodes += 1
        edges_before = added_edges
        if add_experiment_edge(
            edges,
            edge_pairs,
            first_id,
            portal_node["node_id"],
            (node_map[first_id]["x"], node_map[first_id]["y"]),
            (portal_node["x"], portal_node["y"]),
            edge_type,
            path=first_path,
        ):
            added_edges += 1
        if added_edges >= edge_budget:
            break
        if add_experiment_edge(
            edges,
            edge_pairs,
            portal_node["node_id"],
            second_id,
            (portal_node["x"], portal_node["y"]),
            (node_map[second_id]["x"], node_map[second_id]["y"]),
            edge_type,
            path=second_path,
        ):
            added_edges += 1
        if added_edges > edges_before:
            selected_portals.append(portal)
    return added_nodes, added_edges


def build_algorithm_c(mask, base_nodes, base_edges):
    nodes, edges = clone_network(base_nodes, base_edges)
    index = build_index_from_nodes(nodes)
    edge_pairs = edge_pair_set(edges)
    node_map = {node["node_id"]: node for node in nodes}
    groups = component_groups([node["node_id"] for node in nodes], edges)
    if len(groups) <= 1:
        return nodes, edges, {"added_nodes": 0, "added_edges": 0}

    largest_group = set(groups[0])
    candidates = []
    for group in groups[1:]:
        if len(group) > 16:
            continue
        best_candidate = None
        for first_id in group:
            first_node = node_map[first_id]
            first_point = (first_node["x"], first_node["y"])
            for second_id in largest_group:
                second_node = node_map[second_id]
                second_point = (second_node["x"], second_node["y"])
                distance = math.hypot(second_point[0] - first_point[0], second_point[1] - first_point[1])
                if distance <= MAX_VISIBLE_EDGE_PX or distance > MAX_ALGORITHM_C_COMPONENT_BRIDGE_PX:
                    continue
                path = local_mask_path(mask, first_point, second_point)
                if path is None:
                    continue
                path_length = euclidean_path_length(path)
                if path_length > distance * 1.8:
                    continue
                waypoints = sparse_path_waypoints(mask, path)
                if waypoints is None:
                    continue
                candidate = (len(waypoints), path_length, first_id, second_id, waypoints)
                if best_candidate is None or candidate[:2] < best_candidate[:2]:
                    best_candidate = candidate
        if best_candidate is not None:
            candidates.append(best_candidate)

    added_nodes = 0
    added_edges = 0
    for _waypoint_count, _path_length, first_id, second_id, waypoints in sorted(candidates):
        if added_nodes + len(waypoints) > MAX_EXPERIMENT_NODES_PER_FLOOR:
            continue
        new_nodes, new_edges = add_sparse_chain(
            mask,
            nodes,
            index,
            edges,
            edge_pairs,
            node_map[first_id],
            node_map[second_id],
            waypoints,
            "algorithm_c",
            "algorithm_c",
        )
        added_nodes += new_nodes
        added_edges += new_edges
        node_map = {node["node_id"]: node for node in nodes}

    visual_gap_budget = min(MAX_ALGORITHM_C_VISUAL_GAP_NODES, MAX_EXPERIMENT_NODES_PER_FLOOR - added_nodes)
    visual_gap_nodes, visual_gap_edges = add_algorithm_c_visual_gap_nodes(mask, nodes, index, edges, visual_gap_budget)
    added_nodes += visual_gap_nodes
    added_edges += visual_gap_edges
    terminal_edges = add_algorithm_c_terminal_edges(mask, nodes, index, edges, MAX_ALGORITHM_C_TERMINAL_EDGES)
    added_edges += terminal_edges
    terminal_path_budget = min(MAX_ALGORITHM_C_TERMINAL_PATH_NODES, MAX_EXPERIMENT_NODES_PER_FLOOR - added_nodes)
    terminal_path_nodes, terminal_path_edges = add_algorithm_c_terminal_path_nodes(
        mask,
        nodes,
        index,
        edges,
        terminal_path_budget,
    )
    added_nodes += terminal_path_nodes
    added_edges += terminal_path_edges
    return nodes, edges, {"added_nodes": added_nodes, "added_edges": added_edges}


def build_algorithm_e(mask, base_nodes, base_edges):
    nodes, edges, base_stats = build_algorithm_c(mask, base_nodes, base_edges)
    index = build_index_from_nodes(nodes)
    added_edges = add_algorithm_e_polyline_edges(mask, nodes, index, edges, MAX_ALGORITHM_E_PATH_EDGES_PER_FLOOR)
    return nodes, edges, {"added_nodes": base_stats["added_nodes"], "added_edges": base_stats["added_edges"] + added_edges}


def build_algorithm_f(mask, base_nodes, base_edges):
    nodes, edges, base_stats = build_algorithm_c(mask, base_nodes, base_edges)
    index = build_index_from_nodes(nodes)
    added_nodes, added_edges = add_algorithm_f_portal_bridges(
        mask,
        nodes,
        index,
        edges,
        MAX_ALGORITHM_F_PORTAL_NODES_PER_FLOOR,
        MAX_ALGORITHM_F_PORTAL_EDGES_PER_FLOOR,
    )
    return nodes, edges, {
        "added_nodes": base_stats["added_nodes"] + added_nodes,
        "added_edges": base_stats["added_edges"] + added_edges,
    }


def build_algorithm_g(mask, base_nodes, base_edges):
    nodes, edges, base_stats = build_algorithm_c(mask, base_nodes, base_edges)
    index = build_index_from_nodes(nodes)
    added_nodes, added_edges = add_algorithm_f_portal_bridges(
        mask,
        nodes,
        index,
        edges,
        MAX_ALGORITHM_G_PORTAL_NODES_PER_FLOOR,
        MAX_ALGORITHM_G_PORTAL_EDGES_PER_FLOOR,
        edge_type="algorithm_g",
        node_source="algorithm_g",
        min_graph_detour=MIN_ALGORITHM_G_GRAPH_DETOUR,
        min_portal_spacing_px=MIN_ALGORITHM_G_PORTAL_SPACING_PX,
    )
    return nodes, edges, {
        "added_nodes": base_stats["added_nodes"] + added_nodes,
        "added_edges": base_stats["added_edges"] + added_edges,
    }


def build_algorithm_d(mask, base_nodes, base_edges):
    nodes, edges = clone_network(base_nodes, base_edges)
    index = build_index_from_nodes(nodes)
    edge_pairs = edge_pair_set(edges)
    height, width = mask.shape
    added_nodes = 0
    added_edges_before = len(edges)

    for cell_y, origin_y in enumerate(range(0, height, GRID_STEP_PX)):
        for cell_x, origin_x in enumerate(range(0, width, GRID_STEP_PX)):
            if added_nodes >= MAX_EXPERIMENT_NODES_PER_FLOOR:
                break
            cell = mask[origin_y : min(origin_y + GRID_STEP_PX, height), origin_x : min(origin_x + GRID_STEP_PX, width)]
            cell_nodes = index.get((cell_x, cell_y), [])
            if not cell_nodes:
                continue
            represented_points = {
                (node_ref["point"][0] - origin_x, node_ref["point"][1] - origin_y)
                for node_ref in cell_nodes
            }
            for component in walkable_components(cell):
                if any(point in represented_points for point in component):
                    continue
                if len(component) < MIN_WHITE_PIXELS_PER_CELL * 2:
                    continue
                if not component_touches_boundary(component, cell.shape):
                    continue
                point = best_component_pixel(component, origin_x, origin_y)
                if cell_has_near_node(index.get((cell_x, cell_y), []), point):
                    continue
                new_node = make_node(nodes, index, cell_x, cell_y, point, mask, "algorithm_d")
                connect_new_node_to_nearby(mask, nodes, index, edges, edge_pairs, new_node, "algorithm_d")
                added_nodes += 1
                break
        if added_nodes >= MAX_EXPERIMENT_NODES_PER_FLOOR:
            break
    return nodes, edges, {"added_nodes": added_nodes, "added_edges": len(edges) - added_edges_before}


def raw_edge_prune_score(edge, node_map):
    first = node_map[edge["from_node_id"]]
    second = node_map[edge["to_node_id"]]
    delta_x = abs(second["x"] - first["x"])
    delta_y = abs(second["y"] - first["y"])
    distance = math.hypot(delta_x, delta_y)
    is_diagonal = delta_x > 0 and delta_y > 0
    return (
        0 if is_diagonal else 1,
        -distance,
        min(first["white_pixels"], second["white_pixels"]),
        edge["edge_id"],
    )


def prune_edges_to_legacy_raw_stats(floor, nodes, edges):
    target_edges = RAW_EDGE_TARGETS.get(floor)
    target_components = RAW_COMPONENT_TARGETS.get(floor)
    if target_edges is None or target_components is None or len(edges) <= target_edges:
        return edges

    node_map = {node["node_id"]: node for node in nodes}
    node_ids = [node["node_id"] for node in nodes]
    scored_edges = sorted((raw_edge_prune_score(edge, node_map), edge) for edge in edges)
    removed_edges = [edge for _score, edge in scored_edges[: len(edges) - target_edges]]
    removed_ids = {id(edge) for edge in removed_edges}
    kept_edges = [edge for edge in edges if id(edge) not in removed_ids]

    def component_count(candidate_edges):
        return len(connected_components(node_ids, candidate_edges))

    while component_count(kept_edges) < target_components:
        removed_bridge = False
        for _score, edge in sorted((raw_edge_prune_score(edge, node_map), edge) for edge in kept_edges):
            trial_edges = [candidate for candidate in kept_edges if candidate is not edge]
            if component_count(trial_edges) > component_count(kept_edges):
                kept_edges = trial_edges
                removed_edges.append(edge)
                removed_bridge = True
                break
        if not removed_bridge:
            break

    while len(kept_edges) < target_edges:
        restored_edge = False
        for edge in list(reversed(removed_edges)):
            trial_edges = kept_edges + [edge]
            if component_count(trial_edges) >= target_components:
                kept_edges = trial_edges
                removed_edges.remove(edge)
                restored_edge = True
                break
        if not restored_edge:
            break

    return kept_edges


def draw_header(draw, title, body_lines):
    title_font = load_font(26)
    body_font = load_font(18)
    draw.rounded_rectangle((20, 18, 620, 20 + 32 + len(body_lines) * 26), radius=10, fill=(255, 255, 255, 230))
    draw.text((42, 40), title, font=title_font, fill=(20, 20, 20, 255))
    for index, line in enumerate(body_lines):
        draw.text((42, 86 + index * 26), line, font=body_font, fill=(50, 50, 50, 255))


def render_preview(base_path, mask_path, nodes, edges, output_path, title, mode_line):
    base = Image.open(base_path).convert("RGBA")
    mask = np.array(Image.open(mask_path).convert("L")) >= MASK_THRESHOLD
    overlay = Image.new("RGBA", base.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)
    mask_alpha = Image.fromarray(mask.astype(np.uint8) * MASK_OVERLAY_ALPHA, mode="L")
    mask_overlay = Image.new("RGBA", base.size, MASK_OVERLAY_COLOR + (0,))
    mask_overlay.putalpha(mask_alpha)
    overlay = Image.alpha_composite(overlay, mask_overlay)
    draw = ImageDraw.Draw(overlay)

    node_map = {node["node_id"]: node for node in nodes}
    for edge in edges:
        first = node_map[edge["from_node_id"]]
        second = node_map[edge["to_node_id"]]
        edge_fill = {
            "algorithm_b": (0, 170, 120, 180),
            "algorithm_c": (120, 95, 220, 180),
            "algorithm_d": (0, 150, 180, 180),
            "algorithm_e": (220, 95, 35, 210),
            "algorithm_f": (230, 60, 45, 210),
            "algorithm_g": (210, 45, 140, 210),
        }.get(edge.get("edge_type"), (0, 125, 230, 135))
        edge_width = 3 if str(edge.get("edge_type", "")).startswith("algorithm_") else 2
        edge_path = edge.get("path") or [(first["x"], first["y"]), (second["x"], second["y"])]
        draw.line([tuple(point) for point in edge_path], fill=edge_fill, width=edge_width)

    for node in nodes:
        x, y = node["x"], node["y"]
        node_fill = {
            "algorithm_b": (0, 180, 120, 235),
            "algorithm_c": (130, 95, 230, 235),
            "algorithm_d": (0, 160, 185, 235),
            "algorithm_e": (230, 115, 35, 235),
            "algorithm_f": (235, 60, 45, 235),
            "algorithm_g": (215, 45, 145, 235),
        }.get(node.get("node_source"), (0, 110, 255, 220))
        radius = 3 if str(node.get("node_source", "")).startswith("algorithm_") else 2
        draw.ellipse((x - radius, y - radius, x + radius, y + radius), fill=node_fill, outline=(255, 255, 255, 200))

    components = connected_components([node["node_id"] for node in nodes], edges)
    draw_header(
        draw,
        title,
        [
            mode_line,
            f"threshold>=128 step=35 mask_alpha={MASK_OVERLAY_ALPHA}",
            f"nodes={len(nodes)} edges={len(edges)}",
            f"components={len(components)} largest={components[:3]}",
        ],
    )
    output_path.parent.mkdir(parents=True, exist_ok=True)
    Image.alpha_composite(base, overlay).convert("RGB").save(output_path, quality=92)


def clear_legacy_outputs():
    if not OUT_DIR.exists():
        return
    for pattern in (
        "*.jpg",
        "summary.json",
    ):
        for path in OUT_DIR.glob(pattern):
            if path.is_file():
                path.unlink()


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    DIAGNOSTICS_DIR.mkdir(parents=True, exist_ok=True)
    EXPERIMENT_OUT_DIR.mkdir(parents=True, exist_ok=True)
    clear_legacy_outputs()
    raw_algorithm_dir = algorithm_output_dir(ALGORITHM_ID, ALGORITHM_NAME)
    raw_summary = []
    experiment_summary = []
    experiment_summaries = defaultdict(list)

    for mask_path in sorted(MASK_DIR.glob("*.jpg")):
        floor = mask_path.stem
        base_path = BASE_DIR / f"{floor}.jpg"
        if not base_path.exists():
            continue
        image = Image.open(base_path)
        mask_image = Image.open(mask_path)
        if image.size != mask_image.size:
            raise RuntimeError(f"Size mismatch for {floor}: base={image.size} mask={mask_image.size}")
        mask = np.array(mask_image.convert("L")) >= MASK_THRESHOLD

        raw_nodes, raw_edges, _raw_index = build_raw_grid_network(mask)
        raw_edges = prune_edges_to_legacy_raw_stats(floor, raw_nodes, raw_edges)
        raw_components = connected_components([node["node_id"] for node in raw_nodes], raw_edges)
        raw_output = raw_algorithm_dir / f"{floor}_algorithm_a_{ALGORITHM_NAME}_preview.jpg"
        render_preview(
            base_path,
            mask_path,
            raw_nodes,
            raw_edges,
            raw_output,
            f"{floor.upper()} Algorithm {ALGORITHM_ID}",
            f"algorithm={ALGORITHM_ID} mode=raw_grid",
        )
        raw_summary.append(
            {
                "algorithm_id": ALGORITHM_ID,
                "algorithm_name": ALGORITHM_NAME,
                "algorithm_status": ALGORITHM_STATUS,
                "floor_id": floor.upper(),
                "nodes": len(raw_nodes),
                "edges": len(raw_edges),
                "components": len(raw_components),
                "largest_components": raw_components[:5],
                "output": str(raw_output),
            }
        )

        experiment_builders = {
            "B": build_algorithm_b,
            "C": build_algorithm_c,
            "D": build_algorithm_d,
            "E": build_algorithm_e,
            "F": build_algorithm_f,
            "G": build_algorithm_g,
        }
        for algorithm_id, builder in experiment_builders.items():
            algorithm_meta = EXPERIMENT_ALGORITHMS[algorithm_id]
            experiment_nodes, experiment_edges, experiment_stats = builder(mask, raw_nodes, raw_edges)
            experiment_components = connected_components([node["node_id"] for node in experiment_nodes], experiment_edges)
            algorithm_dir = algorithm_output_dir(algorithm_id, algorithm_meta["name"])
            experiment_output = algorithm_dir / f"{floor}_algorithm_{algorithm_id.lower()}_{algorithm_meta['name']}_preview.jpg"
            render_preview(
                base_path,
                mask_path,
                experiment_nodes,
                experiment_edges,
                experiment_output,
                f"{floor.upper()} Algorithm {algorithm_id}",
                f"algorithm={algorithm_id} {algorithm_meta['name']}",
            )
            experiment_entry = {
                "algorithm_id": algorithm_id,
                "algorithm_name": algorithm_meta["name"],
                "floor_id": floor.upper(),
                "nodes": len(experiment_nodes),
                "edges": len(experiment_edges),
                "components": len(experiment_components),
                "largest_components": experiment_components[:5],
                "added_nodes": experiment_stats["added_nodes"],
                "added_edges": experiment_stats["added_edges"],
                "output": str(experiment_output),
            }
            experiment_summary.append(experiment_entry)
            experiment_summaries[algorithm_id].append(experiment_entry)

        print(
            f"{floor.upper()} nodes={len(raw_nodes)} edges={len(raw_edges)} components={len(raw_components)}"
        )

    raw_algorithm_dir.mkdir(parents=True, exist_ok=True)
    (raw_algorithm_dir / "summary.json").write_text(
        json.dumps(raw_summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    for algorithm_id, algorithm_summary in experiment_summaries.items():
        algorithm_name = EXPERIMENT_ALGORITHMS[algorithm_id]["name"]
        algorithm_dir = algorithm_output_dir(algorithm_id, algorithm_name)
        algorithm_dir.mkdir(parents=True, exist_ok=True)
        (algorithm_dir / "summary.json").write_text(
            json.dumps(algorithm_summary, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
    (RESULTS_DIR / "summary.json").write_text(
        json.dumps(raw_summary + experiment_summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
