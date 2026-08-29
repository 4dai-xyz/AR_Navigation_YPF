#!/usr/bin/env python3
"""Build the conference walk graph from the green walkable-road overlay.

Input:
  OCR/huichang_road.jpg                         green walkable route markup
  android/ai-glasses-poc/.../huichang_app_nav_graph.json
  android/ai-glasses-poc/.../huichang_poi_resolver_app_ready.json

Output:
  Updated App nav graph/resolver with real skeleton-derived walk edges.
  OCR/_demo_out/huichang_road_network_preview.jpg

This script intentionally uses only Pillow + NumPy so it can run in the
current lightweight Windows Python environment without installing OpenCV.
"""
from __future__ import annotations

import argparse
import json
import math
from collections import deque
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, List, Sequence, Tuple

import numpy as np
from PIL import Image, ImageDraw, ImageFont

Point = Tuple[int, int]  # x, y
Lin = int


@dataclass
class Component:
    pixels: List[Lin]
    min_x: int
    min_y: int
    max_x: int
    max_y: int

    @property
    def width(self) -> int:
        return self.max_x - self.min_x + 1

    @property
    def height(self) -> int:
        return self.max_y - self.min_y + 1

    @property
    def area(self) -> int:
        return len(self.pixels)

    @property
    def density(self) -> float:
        return self.area / max(1, self.width * self.height)


@dataclass
class RoadNode:
    node_id: str
    x: float
    y: float
    node_type: str
    source: str


@dataclass
class RoadEdge:
    from_node_id: str
    to_node_id: str
    distance: float


def repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def neighbors8(y: int, x: int, height: int, width: int) -> Iterable[Tuple[int, int]]:
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            if dy == 0 and dx == 0:
                continue
            ny = y + dy
            nx = x + dx
            if 0 <= ny < height and 0 <= nx < width:
                yield ny, nx


def binary_dilate(mask: np.ndarray, iterations: int = 1) -> np.ndarray:
    current = mask.astype(bool)
    for _ in range(iterations):
        padded = np.pad(current, 1, mode="constant", constant_values=False)
        out = np.zeros_like(current)
        for dy in range(3):
            for dx in range(3):
                out |= padded[dy : dy + current.shape[0], dx : dx + current.shape[1]]
        current = out
    return current


def binary_erode(mask: np.ndarray, iterations: int = 1) -> np.ndarray:
    current = mask.astype(bool)
    for _ in range(iterations):
        padded = np.pad(current, 1, mode="constant", constant_values=False)
        out = np.ones_like(current)
        for dy in range(3):
            for dx in range(3):
                out &= padded[dy : dy + current.shape[0], dx : dx + current.shape[1]]
        current = out
    return current


def connected_components(mask: np.ndarray) -> List[Component]:
    height, width = mask.shape
    ys, xs = np.nonzero(mask)
    remaining = set((ys.astype(np.int64) * width + xs.astype(np.int64)).tolist())
    components: List[Component] = []
    while remaining:
        start = remaining.pop()
        queue = deque([start])
        pixels = [start]
        min_x = max_x = start % width
        min_y = max_y = start // width
        while queue:
            lin = queue.popleft()
            y = lin // width
            x = lin % width
            for ny, nx in neighbors8(y, x, height, width):
                nlin = ny * width + nx
                if nlin not in remaining:
                    continue
                remaining.remove(nlin)
                queue.append(nlin)
                pixels.append(nlin)
                min_x = min(min_x, nx)
                max_x = max(max_x, nx)
                min_y = min(min_y, ny)
                max_y = max(max_y, ny)
        components.append(Component(pixels, min_x, min_y, max_x, max_y))
    return components


def filter_components(mask: np.ndarray, min_area: int = 40) -> Tuple[np.ndarray, List[Component], List[Component]]:
    kept = np.zeros_like(mask, dtype=bool)
    kept_components: List[Component] = []
    removed_components: List[Component] = []
    for component in connected_components(mask):
        # Drop tiny JPEG speckles and small filled green labels/icons that are not walk paths.
        small_filled_label = (
            component.area >= min_area
            and component.width < 180
            and component.height < 90
            and component.density > 0.35
        )
        if component.area < min_area or small_filled_label:
            removed_components.append(component)
            continue
        for lin in component.pixels:
            kept.flat[lin] = True
        kept_components.append(component)
    return kept, kept_components, removed_components


def extract_green_walk_mask(image: Image.Image) -> Tuple[np.ndarray, Dict[str, int]]:
    rgb = np.asarray(image.convert("RGB"))
    red = rgb[:, :, 0].astype(np.int16)
    green = rgb[:, :, 1].astype(np.int16)
    blue = rgb[:, :, 2].astype(np.int16)
    height, width = green.shape
    yy, xx = np.indices((height, width))

    # The walkable roads are manually drawn in saturated dark green. The right-side legend
    # and cyan exhibition regions are excluded by color and map ROI constraints.
    mask = (
        (green > 70)
        & (green > red + 35)
        & (green > blue + 18)
        & (red < 135)
        & (blue < 175)
        & (xx < 2550)
        & (yy > 450)
        & (yy < 3975)
    )
    raw_pixels = int(mask.sum())
    mask = binary_dilate(mask, iterations=1)
    mask = binary_erode(mask, iterations=1)
    mask, kept, removed = filter_components(mask, min_area=45)
    return mask, {
        "raw_pixels": raw_pixels,
        "filtered_pixels": int(mask.sum()),
        "kept_components": len(kept),
        "removed_components": len(removed),
    }


def zhang_suen_thinning(mask: np.ndarray) -> np.ndarray:
    if not mask.any():
        return mask.copy()
    ys, xs = np.nonzero(mask)
    min_y = max(0, int(ys.min()) - 2)
    max_y = min(mask.shape[0], int(ys.max()) + 3)
    min_x = max(0, int(xs.min()) - 2)
    max_x = min(mask.shape[1], int(xs.max()) + 3)
    work = mask[min_y:max_y, min_x:max_x].copy().astype(np.uint8)

    changed = True
    while changed:
        changed = False
        for step in (0, 1):
            padded = np.pad(work, 1, mode="constant", constant_values=0)
            p2 = padded[0:-2, 1:-1]
            p3 = padded[0:-2, 2:]
            p4 = padded[1:-1, 2:]
            p5 = padded[2:, 2:]
            p6 = padded[2:, 1:-1]
            p7 = padded[2:, 0:-2]
            p8 = padded[1:-1, 0:-2]
            p9 = padded[0:-2, 0:-2]
            neighbors = p2 + p3 + p4 + p5 + p6 + p7 + p8 + p9
            sequence = [p2, p3, p4, p5, p6, p7, p8, p9, p2]
            transitions = np.zeros_like(work)
            for a, b in zip(sequence, sequence[1:]):
                transitions += ((a == 0) & (b == 1)).astype(np.uint8)
            if step == 0:
                marker = (
                    (work == 1)
                    & (neighbors >= 2)
                    & (neighbors <= 6)
                    & (transitions == 1)
                    & ~((p2 == 1) & (p4 == 1) & (p6 == 1))
                    & ~((p4 == 1) & (p6 == 1) & (p8 == 1))
                )
            else:
                marker = (
                    (work == 1)
                    & (neighbors >= 2)
                    & (neighbors <= 6)
                    & (transitions == 1)
                    & ~((p2 == 1) & (p4 == 1) & (p8 == 1))
                    & ~((p2 == 1) & (p6 == 1) & (p8 == 1))
                )
            if marker.any():
                work[marker] = 0
                changed = True
    out = np.zeros_like(mask, dtype=bool)
    out[min_y:max_y, min_x:max_x] = work.astype(bool)
    return out


def neighbor_count(mask: np.ndarray) -> np.ndarray:
    padded = np.pad(mask.astype(np.uint8), 1, mode="constant", constant_values=0)
    count = np.zeros_like(mask, dtype=np.uint8)
    for dy in range(3):
        for dx in range(3):
            if dy == 1 and dx == 1:
                continue
            count += padded[dy : dy + mask.shape[0], dx : dx + mask.shape[1]]
    return count


def skeleton_neighbors(lin: Lin, skeleton: np.ndarray) -> List[Lin]:
    height, width = skeleton.shape
    y = lin // width
    x = lin % width
    out: List[Lin] = []
    for ny, nx in neighbors8(y, x, height, width):
        if skeleton[ny, nx]:
            out.append(ny * width + nx)
    return out


def prune_short_spurs(skeleton: np.ndarray, max_spur_length: int = 28) -> np.ndarray:
    work = skeleton.copy()
    height, width = work.shape
    while True:
        degree = neighbor_count(work)
        endpoints = np.flatnonzero(work & (degree == 1))
        to_remove: set[Lin] = set()
        for endpoint in endpoints:
            path = [int(endpoint)]
            previous = -1
            current = int(endpoint)
            while True:
                neighbors = [n for n in skeleton_neighbors(current, work) if n != previous]
                if not neighbors:
                    break
                nxt = neighbors[0]
                previous = current
                current = nxt
                if degree.flat[current] != 2:
                    break
                path.append(current)
                if len(path) > max_spur_length:
                    break
            if len(path) <= max_spur_length and degree.flat[current] >= 3:
                to_remove.update(path)
        if not to_remove:
            return work
        for lin in to_remove:
            work.flat[lin] = False


def nearest_skeleton_points(nodes: Sequence[dict], skeleton: np.ndarray) -> Dict[str, Tuple[Lin, float]]:
    height, width = skeleton.shape
    ys, xs = np.nonzero(skeleton)
    if len(xs) == 0:
        raise RuntimeError("No skeleton pixels extracted from huichang_road.jpg")
    coords = np.column_stack([xs.astype(np.float64), ys.astype(np.float64)])
    result: Dict[str, Tuple[Lin, float]] = {}
    for node in nodes:
        point = np.array([float(node["x"]), float(node["y"])], dtype=np.float64)
        distances_sq = np.sum((coords - point) ** 2, axis=1)
        index = int(np.argmin(distances_sq))
        x = int(xs[index])
        y = int(ys[index])
        result[node["node_id"]] = (y * width + x, float(math.sqrt(distances_sq[index])))
    return result


def cluster_node_pixels(node_mask: np.ndarray) -> Tuple[List[Tuple[Lin, Point, List[Lin]]], Dict[Lin, int]]:
    clusters = []
    pixel_to_cluster: Dict[Lin, int] = {}
    for component in connected_components(node_mask):
        xs = np.array([lin % node_mask.shape[1] for lin in component.pixels], dtype=np.float64)
        ys = np.array([lin // node_mask.shape[1] for lin in component.pixels], dtype=np.float64)
        centroid = np.array([xs.mean(), ys.mean()])
        best = min(
            component.pixels,
            key=lambda lin: (lin % node_mask.shape[1] - centroid[0]) ** 2
            + (lin // node_mask.shape[1] - centroid[1]) ** 2,
        )
        clusters.append((best, (int(best % node_mask.shape[1]), int(best // node_mask.shape[1])), component.pixels))
    clusters.sort(key=lambda item: (item[1][1], item[1][0]))
    for idx, (_, _, pixels) in enumerate(clusters):
        for lin in pixels:
            pixel_to_cluster[lin] = idx
    return clusters, pixel_to_cluster


def trace_skeleton_edges(
    skeleton: np.ndarray,
    clusters: List[Tuple[Lin, Point, List[Lin]]],
    pixel_to_cluster: Dict[Lin, int],
) -> List[List[Lin]]:
    visited_pairs: set[Tuple[Lin, Lin]] = set()
    paths: List[List[Lin]] = []

    def mark_pair(a: Lin, b: Lin) -> None:
        visited_pairs.add((a, b) if a < b else (b, a))

    def has_pair(a: Lin, b: Lin) -> bool:
        return ((a, b) if a < b else (b, a)) in visited_pairs

    for cluster_index, (rep_lin, _, pixels) in enumerate(clusters):
        for start_pixel in pixels:
            for neighbor in skeleton_neighbors(start_pixel, skeleton):
                if pixel_to_cluster.get(neighbor) == cluster_index:
                    continue
                if has_pair(start_pixel, neighbor):
                    continue
                path = [rep_lin]
                previous = start_pixel
                current = neighbor
                mark_pair(start_pixel, neighbor)
                while current not in pixel_to_cluster:
                    path.append(current)
                    next_pixels = [n for n in skeleton_neighbors(current, skeleton) if n != previous]
                    if not next_pixels:
                        break
                    if len(next_pixels) > 1:
                        break
                    nxt = next_pixels[0]
                    mark_pair(current, nxt)
                    previous, current = current, nxt
                if current in pixel_to_cluster:
                    end_cluster = pixel_to_cluster[current]
                    if end_cluster != cluster_index:
                        path.append(clusters[end_cluster][0])
                        paths.append(path)
    return paths


def perpendicular_distance(point: np.ndarray, start: np.ndarray, end: np.ndarray) -> float:
    line = end - start
    denom = float(np.dot(line, line))
    if denom == 0.0:
        return float(np.linalg.norm(point - start))
    t = max(0.0, min(1.0, float(np.dot(point - start, line) / denom)))
    projection = start + t * line
    return float(np.linalg.norm(point - projection))


def rdp_indices(points: np.ndarray, epsilon: float) -> List[int]:
    if len(points) <= 2:
        return list(range(len(points)))
    start = points[0]
    end = points[-1]
    max_distance = -1.0
    index = -1
    for i in range(1, len(points) - 1):
        distance = perpendicular_distance(points[i], start, end)
        if distance > max_distance:
            max_distance = distance
            index = i
    if max_distance > epsilon:
        left = rdp_indices(points[: index + 1], epsilon)
        right = rdp_indices(points[index:], epsilon)
        return left[:-1] + [i + index for i in right]
    return [0, len(points) - 1]


def simplify_path(path: List[Lin], width: int, epsilon: float = 10.0, max_segment_px: float = 260.0) -> List[Point]:
    coords = np.array([(lin % width, lin // width) for lin in path], dtype=np.float64)
    if len(coords) <= 2:
        return [(int(round(x)), int(round(y))) for x, y in coords]
    keep = sorted(set(rdp_indices(coords, epsilon)))
    expanded = [keep[0]]
    for start_index, end_index in zip(keep, keep[1:]):
        segment = coords[start_index : end_index + 1]
        if len(segment) > 1:
            diffs = np.diff(segment, axis=0)
            lengths = np.sqrt(np.sum(diffs * diffs, axis=1))
            total = float(lengths.sum())
            if total > max_segment_px:
                cumulative = np.concatenate([[0.0], np.cumsum(lengths)])
                split_count = int(math.ceil(total / max_segment_px))
                for split in range(1, split_count):
                    target = total * split / split_count
                    local_index = int(np.searchsorted(cumulative, target, side="left"))
                    expanded.append(start_index + min(local_index, len(segment) - 1))
        expanded.append(end_index)
    expanded = sorted(set(expanded))
    return [(int(round(coords[i, 0])), int(round(coords[i, 1]))) for i in expanded]


def euclidean(a: RoadNode, b: RoadNode) -> float:
    return math.hypot(a.x - b.x, a.y - b.y)


def build_road_graph(
    graph: dict,
    skeleton: np.ndarray,
    snap_points: Dict[str, Tuple[Lin, float]],
) -> Tuple[List[RoadNode], List[RoadEdge], Dict[str, str], Dict[str, float], Dict[str, int]]:
    degree = neighbor_count(skeleton)
    node_mask = skeleton & ((degree == 1) | (degree >= 3))
    for lin, _ in snap_points.values():
        node_mask.flat[lin] = True
    # Slightly expand node pixels so nearby branch clusters and forced snap pixels collapse together.
    node_mask = skeleton & binary_dilate(node_mask, iterations=1)
    clusters, pixel_to_cluster = cluster_node_pixels(node_mask)
    cluster_id_by_lin = {lin: idx for idx, (_, _, pixels) in enumerate(clusters) for lin in pixels}

    cluster_node_ids = [f"road_node_{index:03d}" for index in range(len(clusters))]
    road_nodes: List[RoadNode] = []
    for index, (_, point, _) in enumerate(clusters):
        road_nodes.append(
            RoadNode(
                node_id=cluster_node_ids[index],
                x=float(point[0]),
                y=float(point[1]),
                node_type="walk_junction",
                source="huichang_road_skeleton",
            )
        )

    snap_route_node_by_original: Dict[str, str] = {}
    snap_distance_by_original: Dict[str, float] = {}
    for original_node_id, (lin, distance) in snap_points.items():
        cluster_index = cluster_id_by_lin.get(lin)
        if cluster_index is None:
            # A forced point can merge into a neighboring node cluster after dilation; find nearest cluster rep.
            x = lin % skeleton.shape[1]
            y = lin // skeleton.shape[1]
            cluster_index = min(
                range(len(clusters)),
                key=lambda idx: (clusters[idx][1][0] - x) ** 2 + (clusters[idx][1][1] - y) ** 2,
            )
        snap_route_node_by_original[original_node_id] = cluster_node_ids[cluster_index]
        snap_distance_by_original[original_node_id] = distance

    paths = trace_skeleton_edges(skeleton, clusters, pixel_to_cluster)
    road_node_by_id: Dict[str, RoadNode] = {node.node_id: node for node in road_nodes}
    road_edges: List[RoadEdge] = []
    waypoint_counter = 0
    edge_pairs: set[Tuple[str, str]] = set()

    def add_node(point: Point, node_type: str = "walk_waypoint") -> str:
        nonlocal waypoint_counter
        node_id = f"road_wp_{waypoint_counter:04d}"
        waypoint_counter += 1
        node = RoadNode(
            node_id=node_id,
            x=float(point[0]),
            y=float(point[1]),
            node_type=node_type,
            source="huichang_road_skeleton",
        )
        road_nodes.append(node)
        road_node_by_id[node_id] = node
        return node_id

    cluster_rep_to_node_id = {rep_lin: cluster_node_ids[index] for index, (rep_lin, _, _) in enumerate(clusters)}
    for path in paths:
        simplified = simplify_path(path, skeleton.shape[1])
        if len(simplified) < 2:
            continue
        start_id = cluster_rep_to_node_id[path[0]]
        end_id = cluster_rep_to_node_id[path[-1]]
        node_ids = [start_id]
        for point in simplified[1:-1]:
            node_ids.append(add_node(point))
        node_ids.append(end_id)
        for from_id, to_id in zip(node_ids, node_ids[1:]):
            if from_id == to_id:
                continue
            pair = (from_id, to_id) if from_id < to_id else (to_id, from_id)
            if pair in edge_pairs:
                continue
            edge_pairs.add(pair)
            road_edges.append(
                RoadEdge(
                    from_node_id=from_id,
                    to_node_id=to_id,
                    distance=round(euclidean(road_node_by_id[from_id], road_node_by_id[to_id]), 3),
                )
            )

    stats = {
        "junction_nodes": len(clusters),
        "waypoint_nodes": waypoint_counter,
        "raw_skeleton_edges": len(paths),
    }
    return road_nodes, road_edges, snap_route_node_by_original, snap_distance_by_original, stats


def graph_components(nodes: Sequence[RoadNode], edges: Sequence[RoadEdge]) -> List[List[str]]:
    adjacency: Dict[str, List[str]] = {node.node_id: [] for node in nodes}
    for edge in edges:
        adjacency.setdefault(edge.from_node_id, []).append(edge.to_node_id)
        adjacency.setdefault(edge.to_node_id, []).append(edge.from_node_id)
    remaining = set(adjacency)
    components = []
    while remaining:
        start = remaining.pop()
        queue = deque([start])
        component = [start]
        while queue:
            current = queue.popleft()
            for nxt in adjacency.get(current, []):
                if nxt not in remaining:
                    continue
                remaining.remove(nxt)
                queue.append(nxt)
                component.append(nxt)
        components.append(component)
    components.sort(key=len, reverse=True)
    return components


def route_reachability(
    start_node_id: str,
    target_node_ids: Iterable[str],
    edges: Sequence[RoadEdge],
) -> Tuple[int, int]:
    adjacency: Dict[str, List[str]] = {}
    for edge in edges:
        adjacency.setdefault(edge.from_node_id, []).append(edge.to_node_id)
        adjacency.setdefault(edge.to_node_id, []).append(edge.from_node_id)
    queue = deque([start_node_id])
    visited = {start_node_id}
    while queue:
        current = queue.popleft()
        for nxt in adjacency.get(current, []):
            if nxt in visited:
                continue
            visited.add(nxt)
            queue.append(nxt)
    targets = list(target_node_ids)
    reachable = sum(1 for node_id in targets if node_id in visited)
    return reachable, len(targets)


def is_generated_road_node(node: dict) -> bool:
    return str(node.get("node_id", "")).startswith("road_") or node.get("source") == "huichang_road_skeleton"


def annotation_nodes(graph: dict) -> List[dict]:
    return [node for node in graph.get("nodes", []) if not is_generated_road_node(node)]


def update_assets(
    graph_path: Path,
    resolver_path: Path,
    road_nodes: Sequence[RoadNode],
    road_edges: Sequence[RoadEdge],
    snap_route_node_by_original: Dict[str, str],
    snap_distance_by_original: Dict[str, float],
    extraction_stats: Dict[str, int],
    graph_stats: Dict[str, int],
) -> dict:
    graph = json.loads(graph_path.read_text(encoding="utf-8"))
    resolver = json.loads(resolver_path.read_text(encoding="utf-8"))

    original_nodes = annotation_nodes(graph)
    original_node_ids = {node["node_id"] for node in original_nodes}
    converted_nodes = []
    for node in road_nodes:
        converted_nodes.append(
            {
                "node_id": node.node_id,
                "floor_id": "F1",
                "node_type": node.node_type,
                "x": round(node.x, 3),
                "y": round(node.y, 3),
                "source": node.source,
            }
        )

    converted_edges = []
    for index, edge in enumerate(road_edges):
        converted_edges.append(
            {
                "edge_id": f"road_edge_{index:04d}",
                "from_node_id": edge.from_node_id,
                "to_node_id": edge.to_node_id,
                "floor_id": "F1",
                "travel_mode": "walk",
                "bidirectional": True,
                "distance": edge.distance,
                "cost_seconds": round(edge.distance / 90.0, 3),
                "source": "huichang_road_skeleton",
            }
        )

    def remap_route_node_id(route_node_id: str) -> str:
        return snap_route_node_by_original.get(route_node_id, route_node_id)

    for poi in graph.get("pois", []):
        old_route = poi.get("annotation_node_id") or poi.get("route_node_id")
        if old_route in original_node_ids:
            poi["annotation_node_id"] = old_route
            poi["route_node_id"] = remap_route_node_id(old_route)
            poi["route_snap_distance_px"] = round(snap_distance_by_original.get(old_route, 0.0), 3)
    for entrance in graph.get("entrances", []):
        old_route = entrance.get("annotation_node_id") or entrance.get("route_node_id")
        if old_route in original_node_ids:
            entrance["annotation_node_id"] = old_route
            entrance["route_node_id"] = remap_route_node_id(old_route)
            entrance["route_snap_distance_px"] = round(snap_distance_by_original.get(old_route, 0.0), 3)
    for connector in graph.get("connectors", []):
        old_route = connector.get("annotation_node_id") or connector.get("route_node_id")
        if old_route in original_node_ids:
            connector["annotation_node_id"] = old_route
            connector["route_node_id"] = remap_route_node_id(old_route)
            connector["route_snap_distance_px"] = round(snap_distance_by_original.get(old_route, 0.0), 3)

    for item in resolver.get("items", []):
        old_route = item.get("annotation_node_id") or item.get("route_node_id")
        if old_route in original_node_ids:
            item["annotation_node_id"] = old_route
            item["route_node_id"] = remap_route_node_id(old_route)
            item["route_snap_distance_px"] = round(snap_distance_by_original.get(old_route, 0.0), 3)

    graph["nodes"] = original_nodes + converted_nodes
    graph["edges"] = converted_edges
    graph["generation_note"] = {
        "source": "OCR/huichang_road.jpg",
        "method": "green_path_mask_zhang_suen_skeleton_snap_pois_to_walk_graph",
        "replaces": "previous fully-connected demo edges",
        "coordinate_system": "image_pixel_top_left_y_down",
        "bidirectional_walk_edges": True,
        "extraction_stats": extraction_stats,
        "graph_stats": graph_stats,
    }

    graph_path.write_text(json.dumps(graph, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    resolver_path.write_text(json.dumps(resolver, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return graph


def draw_preview(
    base_image: Image.Image,
    skeleton: np.ndarray,
    road_nodes: Sequence[RoadNode],
    road_edges: Sequence[RoadEdge],
    snap_route_node_by_original: Dict[str, str],
    graph: dict,
    output_path: Path,
) -> None:
    image = base_image.convert("RGB").copy()
    draw = ImageDraw.Draw(image, "RGBA")
    node_by_id = {node.node_id: node for node in road_nodes}
    for edge in road_edges:
        a = node_by_id[edge.from_node_id]
        b = node_by_id[edge.to_node_id]
        draw.line([(a.x, a.y), (b.x, b.y)], fill=(20, 190, 70, 210), width=5)
    # Draw skeleton pixels lightly for visual QA.
    ys, xs = np.nonzero(skeleton)
    for x, y in zip(xs[::2], ys[::2]):
        draw.point((int(x), int(y)), fill=(0, 80, 255, 150))
    for node in road_nodes:
        radius = 3 if node.node_type == "walk_waypoint" else 6
        color = (255, 128, 0, 220) if node.node_type == "walk_junction" else (255, 255, 255, 180)
        draw.ellipse((node.x - radius, node.y - radius, node.x + radius, node.y + radius), fill=color, outline=(0, 0, 0, 180))
    for source in graph.get("nodes", []):
        snapped = snap_route_node_by_original.get(source.get("node_id"))
        if not snapped:
            continue
        road = node_by_id.get(snapped)
        if not road:
            continue
        sx = float(source["x"])
        sy = float(source["y"])
        draw.line([(sx, sy), (road.x, road.y)], fill=(255, 0, 0, 100), width=2)
        draw.ellipse((sx - 4, sy - 4, sx + 4, sy + 4), fill=(255, 0, 0, 210))
    output_path.parent.mkdir(parents=True, exist_ok=True)
    image.save(output_path, quality=92)


def main() -> None:
    parser = argparse.ArgumentParser()
    root = repo_root()
    parser.add_argument("--road-image", type=Path, default=root / "OCR" / "huichang_road.jpg")
    parser.add_argument("--base-image", type=Path, default=root / "android" / "ai-glasses-poc" / "app" / "src" / "main" / "assets" / "mapping" / "conference" / "huichang.jpg")
    parser.add_argument("--graph", type=Path, default=root / "android" / "ai-glasses-poc" / "app" / "src" / "main" / "assets" / "mapping" / "conference" / "huichang_app_nav_graph.json")
    parser.add_argument("--resolver", type=Path, default=root / "android" / "ai-glasses-poc" / "app" / "src" / "main" / "assets" / "mapping" / "conference" / "huichang_poi_resolver_app_ready.json")
    parser.add_argument("--preview", type=Path, default=root / "OCR" / "_demo_out" / "huichang_road_network_preview.jpg")
    args = parser.parse_args()

    road_image = Image.open(args.road_image)
    mask, extraction_stats = extract_green_walk_mask(road_image)
    skeleton = zhang_suen_thinning(mask)
    skeleton = prune_short_spurs(skeleton)
    extraction_stats["skeleton_pixels"] = int(skeleton.sum())

    graph_before = json.loads(args.graph.read_text(encoding="utf-8"))
    source_nodes = annotation_nodes(graph_before)
    snap_points = nearest_skeleton_points(source_nodes, skeleton)
    road_nodes, road_edges, snap_map, snap_distances, graph_stats = build_road_graph(graph_before, skeleton, snap_points)
    components = graph_components(road_nodes, road_edges)
    graph_stats["road_nodes"] = len(road_nodes)
    graph_stats["road_edges"] = len(road_edges)
    graph_stats["connected_components"] = len(components)
    graph_stats["largest_component_nodes"] = len(components[0]) if components else 0

    default_entrance = graph_before.get("entrances", [{}])[0]
    entrance_node_id = default_entrance.get("annotation_node_id") or default_entrance.get("route_node_id", "node_0")
    start = snap_map.get(entrance_node_id)
    targets = [snap_map.get(node["node_id"]) for node in source_nodes]
    targets = [target for target in targets if target]
    if start:
        reachable, total = route_reachability(start, targets, road_edges)
        graph_stats["reachable_snapped_targets_from_default_entrance"] = reachable
        graph_stats["snapped_targets"] = total

    max_snap = max(snap_distances.values()) if snap_distances else 0.0
    avg_snap = sum(snap_distances.values()) / max(1, len(snap_distances))
    graph_stats["max_snap_distance_px"] = round(max_snap, 3)
    graph_stats["avg_snap_distance_px"] = round(avg_snap, 3)

    graph_after = update_assets(
        graph_path=args.graph,
        resolver_path=args.resolver,
        road_nodes=road_nodes,
        road_edges=road_edges,
        snap_route_node_by_original=snap_map,
        snap_distance_by_original=snap_distances,
        extraction_stats=extraction_stats,
        graph_stats=graph_stats,
    )
    preview_graph = dict(graph_before)
    preview_graph["nodes"] = source_nodes
    draw_preview(Image.open(args.base_image), skeleton, road_nodes, road_edges, snap_map, preview_graph, args.preview)

    print(json.dumps({"extraction_stats": extraction_stats, "graph_stats": graph_stats, "preview": str(args.preview)}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
