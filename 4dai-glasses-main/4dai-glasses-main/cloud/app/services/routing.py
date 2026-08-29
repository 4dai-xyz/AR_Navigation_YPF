from __future__ import annotations

import heapq
import math
from dataclasses import dataclass

from cloud.app.core.error_codes import ApiError, BusinessCode
from cloud.app.models.api import IndoorRouteData, IndoorRouteRequest
from cloud.app.services.venue_package import VenueBundle, find_poi


@dataclass(frozen=True)
class RouteNode:
    node_id: str
    floor_id: str
    x: float
    y: float
    node_type: str
    ref_id: str | None


@dataclass(frozen=True)
class RouteEdge:
    edge_id: str
    from_node_id: str
    to_node_id: str
    distance: float
    travel_mode: str
    bidirectional: bool


def build_nodes(bundle: VenueBundle) -> dict[str, RouteNode]:
    return {
        item["node_id"]: RouteNode(
            node_id=item["node_id"],
            floor_id=item["floor_id"],
            x=float(item["x"]),
            y=float(item["y"]),
            node_type=item["node_type"],
            ref_id=item.get("ref_id"),
        )
        for item in bundle.route_graph.get("nodes", [])
    }


def build_edges(bundle: VenueBundle) -> list[RouteEdge]:
    return [
        RouteEdge(
            edge_id=item["edge_id"],
            from_node_id=item["from_node_id"],
            to_node_id=item["to_node_id"],
            distance=float(item["distance"]),
            travel_mode=item["travel_mode"],
            bidirectional=bool(item["bidirectional"]),
        )
        for item in bundle.route_graph.get("edges", [])
    ]


def build_floor_index_map(bundle: VenueBundle) -> dict[str, int]:
    return {
        item["floor_id"]: int(item.get("floor_index", index))
        for index, item in enumerate(bundle.floors)
    }


def nearest_node_id(bundle: VenueBundle, floor_id: str, x: float, y: float) -> str:
    nodes = build_nodes(bundle)
    candidates = [node for node in nodes.values() if node.floor_id == floor_id]
    if not candidates:
        raise ValueError(f"no route nodes found on floor {floor_id}")
    return min(candidates, key=lambda node: math.dist((x, y), (node.x, node.y))).node_id


def dijkstra(bundle: VenueBundle, start_node_id: str, end_node_id: str) -> tuple[list[str], float]:
    adjacency: dict[str, list[tuple[str, float]]] = {}
    for edge in build_edges(bundle):
        adjacency.setdefault(edge.from_node_id, []).append((edge.to_node_id, edge.distance))
        if edge.bidirectional:
            adjacency.setdefault(edge.to_node_id, []).append((edge.from_node_id, edge.distance))

    heap: list[tuple[float, str]] = [(0.0, start_node_id)]
    distance_map = {start_node_id: 0.0}
    previous: dict[str, str | None] = {start_node_id: None}

    while heap:
        current_distance, node_id = heapq.heappop(heap)
        if node_id == end_node_id:
            break
        if current_distance > distance_map.get(node_id, float("inf")):
            continue
        for neighbor_id, weight in adjacency.get(node_id, []):
            candidate = current_distance + weight
            if candidate < distance_map.get(neighbor_id, float("inf")):
                distance_map[neighbor_id] = candidate
                previous[neighbor_id] = node_id
                heapq.heappush(heap, (candidate, neighbor_id))

    if end_node_id not in previous and end_node_id != start_node_id:
        raise ValueError("no available path")

    path: list[str] = [end_node_id]
    while path[-1] != start_node_id:
        predecessor = previous.get(path[-1])
        if predecessor is None:
            break
        path.append(predecessor)
    path.reverse()
    return path, float(distance_map.get(end_node_id, 0.0))


def classify_next_turn(bundle: VenueBundle, path_nodes: list[str]) -> str:
    nodes = build_nodes(bundle)
    if len(path_nodes) <= 1:
        return "arrive"
    if len(path_nodes) == 2:
        return "go_straight"

    first = nodes[path_nodes[0]]
    second = nodes[path_nodes[1]]
    third = nodes[path_nodes[2]]
    floor_index_map = build_floor_index_map(bundle)
    if first.floor_id != second.floor_id:
        return "take_escalator_up" if floor_index_map[second.floor_id] > floor_index_map[first.floor_id] else "take_escalator_down"
    if second.floor_id != third.floor_id:
        return "take_escalator_up" if floor_index_map[third.floor_id] > floor_index_map[second.floor_id] else "take_escalator_down"

    ax = second.x - first.x
    ay = second.y - first.y
    bx = third.x - second.x
    by = third.y - second.y
    cross = ax * by - ay * bx
    if abs(cross) < 1.0:
        return "go_straight"
    return "turn_left" if cross > 0 else "turn_right"


def plan_route(bundle: VenueBundle, request: IndoorRouteRequest) -> IndoorRouteData:
    poi = find_poi(bundle, request.target_poi_id)
    if poi is None:
        raise ApiError(
            code=BusinessCode.TARGET_POI_NOT_FOUND,
            request_id=request.request_id,
            details={"target_poi_id": request.target_poi_id},
        )

    try:
        start_node_id = nearest_node_id(
            bundle,
            request.floor_id,
            request.start_position.x,
            request.start_position.y,
        )
    except ValueError as exc:
        raise ApiError(
            code=BusinessCode.ROUTE_PLANNING_FAILED,
            request_id=request.request_id,
            message=str(exc),
        ) from exc
    end_node_id = poi["route_node_id"]
    try:
        path_nodes, distance_to_target = dijkstra(bundle, start_node_id, end_node_id)
    except ValueError as exc:
        raise ApiError(
            code=BusinessCode.ROUTE_PLANNING_FAILED,
            request_id=request.request_id,
            message=str(exc),
        ) from exc

    nodes = build_nodes(bundle)
    next_turn = classify_next_turn(bundle, path_nodes)
    if len(path_nodes) > 1:
        next_node = nodes[path_nodes[1]]
        distance_to_next_turn = math.dist(
            (request.start_position.x, request.start_position.y),
            (next_node.x, next_node.y),
        )
    else:
        distance_to_next_turn = 0.0
    cross_floor_required = any(
        nodes[path_nodes[index]].floor_id != nodes[path_nodes[index + 1]].floor_id
        for index in range(len(path_nodes) - 1)
    )
    return IndoorRouteData(
        route_id=f"route_{request.request_id}",
        target_poi_id=request.target_poi_id,
        path_nodes=path_nodes,
        next_turn=next_turn,
        distance_to_next_turn=round(distance_to_next_turn, 2),
        distance_to_target=round(distance_to_target, 2),
        cross_floor_required=cross_floor_required,
    )
