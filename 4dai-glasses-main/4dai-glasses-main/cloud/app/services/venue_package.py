from __future__ import annotations

import json
from collections.abc import Iterable
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Any

from cloud.app.core.settings import settings


@dataclass(frozen=True)
class KeyframeRecord:
    keyframe_id: str
    floor_id: str
    venue_xy: dict[str, float]
    intrinsics_id: str | None
    heading: float | None
    route_edge_id: str | None
    image_ref: str | None
    feature_ref: str | None


@dataclass(frozen=True)
class VenueBundle:
    root: Path
    manifest: dict
    venue: dict
    floors: list[dict]
    pois: list[dict]
    entrances: list[dict]
    connectors: list[dict]
    route_graph: dict
    cameras: list[dict]
    keyframes: list[KeyframeRecord]


class VenuePackageError(Exception):
    def __init__(
        self,
        message: str,
        *,
        package_root: Path,
        stage: str,
        details: dict[str, Any] | None = None,
    ) -> None:
        super().__init__(message)
        self.message = message
        self.package_root = package_root
        self.stage = stage
        self.details = details or {}

    def to_details(self) -> dict[str, Any]:
        return {
            "error_type": "venue_package_error",
            "stage": self.stage,
            "package_root": str(self.package_root),
            **self.details,
        }


def append_reference_file_error(errors: list[str], root: Path, prefix: str, ref: str) -> None:
    try:
        resolved = ensure_package_relative(root, ref)
    except VenuePackageError as exc:
        for detail in exc.details.get("validation_errors", []):
            errors.append(f"{prefix}: {detail}")
        return
    if not resolved.exists():
        errors.append(f"{prefix}: missing file '{ref}'")


def relative_path(value: str) -> str:
    return Path(value).as_posix()


def ensure_package_relative(root: Path, path_value: str) -> Path:
    root = root.resolve()
    path = Path(path_value)
    if path.is_absolute():
        raise VenuePackageError(
            "venue package validation failed",
            package_root=root,
            stage="validation",
            details={"validation_errors": [f"{path_value}: path must be relative"]},
        )
    resolved = (root / path).resolve()
    if not resolved.is_relative_to(root):
        raise VenuePackageError(
            "venue package validation failed",
            package_root=root,
            stage="validation",
            details={"validation_errors": [f"{path_value}: path escapes package root"]},
        )
    return resolved


def read_text(root: Path, path_value: str) -> str:
    path = ensure_package_relative(root, path_value)
    try:
        return path.read_text(encoding="utf-8")
    except FileNotFoundError as exc:
        raise VenuePackageError(
            "venue package required file missing",
            package_root=root,
            stage="missing_file",
            details={"missing_files": [relative_path(path_value)]},
        ) from exc
    except OSError as exc:
        raise VenuePackageError(
            "venue package file read failed",
            package_root=root,
            stage="file_read",
            details={"file": relative_path(path_value), "reason": str(exc)},
        ) from exc


def load_json(root: Path, path_value: str) -> dict:
    raw = read_text(root, path_value)
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise VenuePackageError(
            "venue package json parse failed",
            package_root=root,
            stage="json_parse",
            details={"file": relative_path(path_value), "line": exc.lineno, "column": exc.colno},
        ) from exc
    if not isinstance(payload, dict):
        raise VenuePackageError(
            "venue package validation failed",
            package_root=root,
            stage="validation",
            details={"validation_errors": [f"{relative_path(path_value)}: json root must be object"]},
        )
    return payload


def load_jsonl(root: Path, path_value: str) -> list[dict]:
    rows: list[dict] = []
    for line_number, raw in enumerate(read_text(root, path_value).splitlines(), start=1):
        stripped = raw.strip()
        if stripped:
            try:
                payload = json.loads(stripped)
            except json.JSONDecodeError as exc:
                raise VenuePackageError(
                    "venue package jsonl parse failed",
                    package_root=root,
                    stage="json_parse",
                    details={
                        "file": relative_path(path_value),
                        "line": line_number,
                        "column": exc.colno,
                    },
                ) from exc
            if not isinstance(payload, dict):
                raise VenuePackageError(
                    "venue package validation failed",
                    package_root=root,
                    stage="validation",
                    details={"validation_errors": [f"{relative_path(path_value)}:{line_number}: row must be object"]},
                )
            rows.append(payload)
    return rows


def require_fields(errors: list[str], prefix: str, item: dict, fields: Iterable[str]) -> None:
    for field in fields:
        if field not in item:
            errors.append(f"{prefix}: missing required field '{field}'")


def collect_manifest_file_errors(root: Path, manifest: dict) -> tuple[list[str], list[str]]:
    errors: list[str] = []
    missing_files: list[str] = []
    files = manifest.get("files", [])
    if not isinstance(files, list):
        return ["manifest.files: must be an array"], missing_files

    for index, item in enumerate(files):
        if not isinstance(item, dict):
            errors.append(f"manifest.files[{index}]: must be object")
            continue
        if not item.get("required", False):
            continue
        path_value = item.get("path")
        if not isinstance(path_value, str) or not path_value:
            errors.append(f"manifest.files[{index}].path: required file path missing")
            continue
        try:
            resolved = ensure_package_relative(root, path_value)
        except VenuePackageError as exc:
            errors.extend(exc.details.get("validation_errors", []))
            continue
        if not resolved.exists():
            missing_files.append(relative_path(path_value))
    return errors, missing_files


def validate_bundle(bundle: VenueBundle) -> None:
    errors: list[str] = []
    require_fields(errors, "manifest", bundle.manifest, ["package_id", "package_version", "venue_id", "default_floor_id"])
    require_fields(errors, "venue", bundle.venue, ["venue_id", "venue_name"])

    floor_ids_set = {item.get("floor_id") for item in bundle.floors if isinstance(item, dict)}
    if not floor_ids_set:
        errors.append("floors: at least one floor is required")
    if bundle.manifest.get("default_floor_id") not in floor_ids_set:
        errors.append("manifest.default_floor_id: must exist in floors")
    if bundle.manifest.get("venue_id") != bundle.venue.get("venue_id"):
        errors.append("manifest.venue_id: must match venue.venue_id")

    nodes = bundle.route_graph.get("nodes", [])
    edges = bundle.route_graph.get("edges", [])
    if not isinstance(nodes, list) or not nodes:
        errors.append("route_graph.nodes: at least one node is required")
        nodes = []
    if not isinstance(edges, list) or not edges:
        errors.append("route_graph.edges: at least one edge is required")
        edges = []

    node_ids: set[str] = set()
    for index, node in enumerate(nodes):
        if not isinstance(node, dict):
            errors.append(f"route_graph.nodes[{index}]: must be object")
            continue
        require_fields(errors, f"route_graph.nodes[{index}]", node, ["node_id", "floor_id", "x", "y", "node_type"])
        node_id = node.get("node_id")
        if isinstance(node_id, str):
            node_ids.add(node_id)
        if node.get("floor_id") not in floor_ids_set:
            errors.append(f"route_graph.nodes[{index}].floor_id: unknown floor_id '{node.get('floor_id')}'")

    edge_ids: set[str] = set()
    for index, edge in enumerate(edges):
        if not isinstance(edge, dict):
            errors.append(f"route_graph.edges[{index}]: must be object")
            continue
        require_fields(
            errors,
            f"route_graph.edges[{index}]",
            edge,
            ["edge_id", "from_node_id", "to_node_id", "distance", "travel_mode", "bidirectional"],
        )
        edge_id = edge.get("edge_id")
        if isinstance(edge_id, str):
            edge_ids.add(edge_id)
        if edge.get("from_node_id") not in node_ids:
            errors.append(f"route_graph.edges[{index}].from_node_id: unknown node '{edge.get('from_node_id')}'")
        if edge.get("to_node_id") not in node_ids:
            errors.append(f"route_graph.edges[{index}].to_node_id: unknown node '{edge.get('to_node_id')}'")

    camera_ids: set[str] = set()
    for index, camera in enumerate(bundle.cameras):
        if not isinstance(camera, dict):
            errors.append(f"cameras[{index}]: must be object")
            continue
        require_fields(errors, f"cameras[{index}]", camera, ["intrinsics_id", "model", "width", "height", "fx", "fy", "cx", "cy"])
        intrinsics_id = camera.get("intrinsics_id")
        if isinstance(intrinsics_id, str):
            camera_ids.add(intrinsics_id)

    for index, entrance in enumerate(bundle.entrances):
        if not isinstance(entrance, dict):
            errors.append(f"entrances[{index}]: must be object")
            continue
        require_fields(errors, f"entrances[{index}]", entrance, ["entrance_id", "entrance_name", "venue_id", "floor_id"])
        if entrance.get("venue_id") != bundle.venue.get("venue_id"):
            errors.append(f"entrances[{index}].venue_id: must match venue.venue_id")
        if entrance.get("floor_id") not in floor_ids_set:
            errors.append(f"entrances[{index}].floor_id: unknown floor_id '{entrance.get('floor_id')}'")
        route_node_id = entrance.get("route_node_id")
        if route_node_id is not None:
            if route_node_id not in node_ids:
                errors.append(f"entrances[{index}].route_node_id: unknown node '{route_node_id}'")
            else:
                node = next((item for item in nodes if isinstance(item, dict) and item.get("node_id") == route_node_id), None)
                if node and node.get("floor_id") != entrance.get("floor_id"):
                    errors.append(f"entrances[{index}].route_node_id: node floor does not match entrance floor")

    for index, connector in enumerate(bundle.connectors):
        if not isinstance(connector, dict):
            errors.append(f"connectors[{index}]: must be object")
            continue
        require_fields(
            errors,
            f"connectors[{index}]",
            connector,
            ["connector_id", "connector_type", "from_floor_id", "to_floor_id", "from_node_id", "to_node_id"],
        )
        if connector.get("from_floor_id") not in floor_ids_set:
            errors.append(f"connectors[{index}].from_floor_id: unknown floor_id '{connector.get('from_floor_id')}'")
        if connector.get("to_floor_id") not in floor_ids_set:
            errors.append(f"connectors[{index}].to_floor_id: unknown floor_id '{connector.get('to_floor_id')}'")
        if connector.get("from_node_id") not in node_ids:
            errors.append(f"connectors[{index}].from_node_id: unknown node '{connector.get('from_node_id')}'")
        if connector.get("to_node_id") not in node_ids:
            errors.append(f"connectors[{index}].to_node_id: unknown node '{connector.get('to_node_id')}'")
        edge_id = connector.get("edge_id")
        if edge_id is not None and edge_id not in edge_ids:
            errors.append(f"connectors[{index}].edge_id: unknown edge '{edge_id}'")

    for index, poi in enumerate(bundle.pois):
        if not isinstance(poi, dict):
            errors.append(f"pois[{index}]: must be object")
            continue
        require_fields(errors, f"pois[{index}]", poi, ["poi_id", "floor_id", "route_node_id"])
        if poi.get("floor_id") not in floor_ids_set:
            errors.append(f"pois[{index}].floor_id: unknown floor_id '{poi.get('floor_id')}'")
        if poi.get("route_node_id") not in node_ids:
            errors.append(f"pois[{index}].route_node_id: unknown node '{poi.get('route_node_id')}'")

    if not bundle.keyframes:
        errors.append("localization/keyframes.jsonl: at least one keyframe is required")
    for index, keyframe in enumerate(bundle.keyframes):
        if keyframe.floor_id not in floor_ids_set:
            errors.append(f"keyframes[{index}].floor_id: unknown floor_id '{keyframe.floor_id}'")
        if keyframe.intrinsics_id and keyframe.intrinsics_id not in camera_ids:
            errors.append(f"keyframes[{index}].intrinsics_id: unknown camera '{keyframe.intrinsics_id}'")
        if keyframe.route_edge_id and keyframe.route_edge_id not in edge_ids:
            errors.append(f"keyframes[{index}].route_edge_id: unknown edge '{keyframe.route_edge_id}'")
        for field_name, ref in (("image_ref", keyframe.image_ref), ("feature_ref", keyframe.feature_ref)):
            if ref:
                append_reference_file_error(errors, bundle.root / "localization", f"keyframes[{index}].{field_name}", ref)

    if errors:
        raise VenuePackageError(
            "venue package validation failed",
            package_root=bundle.root,
            stage="validation",
            details={"validation_errors": errors[:50]},
        )


def build_keyframes(root: Path, rows: list[dict]) -> list[KeyframeRecord]:
    errors: list[str] = []
    keyframes: list[KeyframeRecord] = []
    for index, row in enumerate(rows):
        require_fields(errors, f"keyframes[{index}]", row, ["keyframe_id", "floor_id", "venue_xy"])
        venue_xy = row.get("venue_xy")
        if not isinstance(venue_xy, dict) or "x" not in venue_xy or "y" not in venue_xy:
            errors.append(f"keyframes[{index}].venue_xy: must include x and y")
            continue
        if "keyframe_id" not in row or "floor_id" not in row:
            continue
        keyframes.append(
            KeyframeRecord(
                keyframe_id=row["keyframe_id"],
                floor_id=row["floor_id"],
                venue_xy=venue_xy,
                intrinsics_id=row.get("intrinsics_id"),
                heading=row.get("heading"),
                route_edge_id=row.get("route_edge_id"),
                image_ref=row.get("image_ref"),
                feature_ref=row.get("feature_ref"),
            )
        )
    if errors:
        raise VenuePackageError(
            "venue package validation failed",
            package_root=root,
            stage="validation",
            details={"validation_errors": errors[:50]},
        )
    return keyframes


@lru_cache(maxsize=4)
def load_bundle(package_root: str | None = None) -> VenueBundle:
    root = Path(package_root or settings.venue_package_root).resolve()
    if not root.exists() or not root.is_dir():
        raise VenuePackageError(
            "venue package root not found",
            package_root=root,
            stage="root_not_found",
            details={"package_root": str(root)},
        )

    manifest = load_json(root, "manifest.json")
    expected_version = settings.venue_package_version
    if expected_version and manifest.get("package_version") != expected_version:
        raise VenuePackageError(
            "venue package version mismatch",
            package_root=root,
            stage="validation",
            details={
                "validation_errors": [
                    f"manifest.package_version: expected '{expected_version}', got '{manifest.get('package_version')}'"
                ],
                "expected_package_version": expected_version,
                "actual_package_version": manifest.get("package_version"),
            },
        )
    manifest_errors, missing_files = collect_manifest_file_errors(root, manifest)
    if missing_files:
        raise VenuePackageError(
            "venue package required file missing",
            package_root=root,
            stage="missing_file",
            details={"missing_files": missing_files},
        )
    if manifest_errors:
        raise VenuePackageError(
            "venue package validation failed",
            package_root=root,
            stage="validation",
            details={"validation_errors": manifest_errors},
        )

    venue = load_json(root, "venue.json")
    floors = load_json(root, "floors.json").get("floors", [])
    pois = load_json(root, "pois.json").get("pois", [])
    entrances = load_json(root, "entrances.json").get("entrances", [])
    connectors = load_json(root, "connectors.json").get("connectors", [])
    route_graph = load_json(root, "route_graph.json")
    cameras = load_json(root, "localization/cameras.json").get("cameras", [])
    keyframes = build_keyframes(root, load_jsonl(root, "localization/keyframes.jsonl"))
    bundle = VenueBundle(
        root=root,
        manifest=manifest,
        venue=venue,
        floors=floors,
        pois=pois,
        entrances=entrances,
        connectors=connectors,
        route_graph=route_graph,
        cameras=cameras,
        keyframes=keyframes,
    )
    validate_bundle(bundle)
    return bundle


def find_poi(bundle: VenueBundle, poi_id: str) -> dict | None:
    return next((poi for poi in bundle.pois if poi.get("poi_id") == poi_id), None)


def floor_ids(bundle: VenueBundle) -> set[str]:
    return {item["floor_id"] for item in bundle.floors}
