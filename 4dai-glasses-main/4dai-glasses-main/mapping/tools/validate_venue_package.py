#!/usr/bin/env python3
"""Validate a venue package against the MVP package conventions."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable


TOP_LEVEL_REQUIRED = [
    "manifest.json",
    "venue.json",
    "floors.json",
    "pois.json",
    "entrances.json",
    "connectors.json",
    "route_graph.json",
    "localization/cameras.json",
    "localization/keyframes.jsonl",
    "localization/retrieval/descriptors.faiss",
    "localization/features/superpoint/features_index.json",
]


@dataclass
class ValidationResult:
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    summary: dict[str, Any] = field(default_factory=dict)

    @property
    def ok(self) -> bool:
        return not self.errors


def load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line_number, raw in enumerate(handle, start=1):
            stripped = raw.strip()
            if not stripped:
                continue
            try:
                rows.append(json.loads(stripped))
            except json.JSONDecodeError as exc:
                raise ValueError(f"{path}: line {line_number}: invalid JSONL: {exc}") from exc
    return rows


def require_keys(obj: dict[str, Any], keys: Iterable[str], label: str, result: ValidationResult) -> None:
    for key in keys:
        if key not in obj:
            result.errors.append(f"{label}: missing required field '{key}'")


def add_unique_id(value: Any, field_name: str, label: str, seen: set[str], result: ValidationResult) -> None:
    if not isinstance(value, str) or not value:
        return
    if value in seen:
        result.errors.append(f"{label}: duplicate {field_name} '{value}'")
    else:
        seen.add(value)


def resolve_ref_path(package_root: Path, localization_root: Path, raw_ref: str) -> Path | None:
    direct = package_root / raw_ref
    if direct.exists():
        return direct
    local = localization_root / raw_ref
    if local.exists():
        return local
    return None


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def normalize_manifest_file_path(package_dir: Path, raw_path: Any) -> tuple[str | None, Path | None]:
    if not isinstance(raw_path, str):
        return None, None
    stripped_path = raw_path.strip()
    if not stripped_path:
        raise ValueError("path must be a non-empty relative path inside package")
    candidate = Path(stripped_path)
    if candidate.is_absolute() or candidate.anchor:
        raise ValueError("path must be a relative path inside package")
    resolved_path = (package_dir / candidate).resolve()
    try:
        relative_path = resolved_path.relative_to(package_dir)
    except ValueError as exc:
        raise ValueError("path must not resolve outside package") from exc
    return relative_path.as_posix(), resolved_path


def build_declared_checksums(package_dir: Path, declared_files: list[dict[str, Any]]) -> dict[str, str]:
    checksums: dict[str, str] = {}
    for file_decl in declared_files:
        try:
            normalized_path, file_path = normalize_manifest_file_path(package_dir, file_decl.get("path"))
        except ValueError:
            continue
        if normalized_path is None or file_path is None:
            continue
        if file_path.exists() and file_path.is_file():
            checksums[normalized_path] = sha256_file(file_path)
    return checksums


def validate_package(package_dir: Path) -> ValidationResult:
    result = ValidationResult()
    package_dir = package_dir.resolve()
    localization_dir = package_dir / "localization"

    if not package_dir.is_dir():
        result.errors.append(f"package_dir is not a directory: {package_dir}")
        return result

    for rel in TOP_LEVEL_REQUIRED:
        if not (package_dir / rel).exists():
            result.errors.append(f"missing required file: {rel}")

    manifest_path = package_dir / "manifest.json"
    if not manifest_path.exists():
        return result
    if result.errors:
        return result

    manifest = load_json(manifest_path)
    require_keys(
        manifest,
        [
            "package_id",
            "package_version",
            "venue_id",
            "venue_code",
            "created_at",
            "schema_version",
            "coordinate_system",
            "unit",
            "default_floor_id",
            "files",
        ],
        "manifest",
        result,
    )

    declared_files = manifest.get("files", [])
    if not isinstance(declared_files, list):
        result.errors.append("manifest.files must be an array")
        declared_files = []

    manifest_file_names: set[str] = set()
    manifest_file_paths: set[str] = set()
    for index, file_decl in enumerate(declared_files):
        label = f"manifest.files[{index}]"
        if not isinstance(file_decl, dict):
            result.errors.append(f"{label}: must be an object")
            continue
        require_keys(file_decl, ["name", "path", "type", "required"], label, result)
        name = file_decl.get("name")
        path_value = file_decl.get("path")
        add_unique_id(name, "name", label, manifest_file_names, result)
        required = file_decl.get("required", False)
        normalized_path: str | None = None
        resolved_path: Path | None = None
        if isinstance(path_value, str):
            try:
                normalized_path, resolved_path = normalize_manifest_file_path(package_dir, path_value)
            except ValueError as exc:
                result.errors.append(f"{label}: {exc}")
        if normalized_path is not None:
            add_unique_id(normalized_path, "path", label, manifest_file_paths, result)
        if required and normalized_path is not None and resolved_path is not None and not resolved_path.exists():
            result.errors.append(f"{label}: required file not found: {normalized_path}")

    for rel in TOP_LEVEL_REQUIRED:
        if rel != "manifest.json" and rel not in manifest_file_paths:
            result.warnings.append(f"manifest.files does not declare recommended control file: {rel}")

    manifest_checksums = manifest.get("checksums", {})
    if not isinstance(manifest_checksums, dict):
        result.errors.append("manifest.checksums must be an object when provided")
        manifest_checksums = {}
    computed_checksums = build_declared_checksums(package_dir, declared_files)
    if not manifest_checksums:
        result.warnings.append("manifest.checksums is empty")
    else:
        for rel_path, digest in computed_checksums.items():
            manifest_digest = manifest_checksums.get(rel_path)
            if manifest_digest is None:
                result.warnings.append(f"manifest.checksums missing digest for {rel_path}")
            elif manifest_digest != digest:
                result.errors.append(f"manifest.checksums mismatch for {rel_path}")

    venue = load_json(package_dir / "venue.json")
    require_keys(venue, ["venue_id", "venue_name", "venue_type"], "venue", result)
    manifest_venue_id = manifest.get("venue_id")
    venue_venue_id = venue.get("venue_id")
    if isinstance(manifest_venue_id, str) and isinstance(venue_venue_id, str) and manifest_venue_id != venue_venue_id:
        result.errors.append(
            f"venue: venue_id '{venue_venue_id}' does not match manifest.venue_id '{manifest_venue_id}'"
        )

    floors_doc = load_json(package_dir / "floors.json")
    floors = floors_doc.get("floors", [])
    if not isinstance(floors, list):
        result.errors.append("floors.floors must be an array")
        floors = []
    floor_ids: set[str] = set()
    floor_indices: set[int] = set()
    floor_index_map: dict[str, int] = {}
    for index, floor in enumerate(floors):
        label = f"floors[{index}]"
        if not isinstance(floor, dict):
            result.errors.append(f"{label}: must be an object")
            continue
        require_keys(floor, ["floor_id", "floor_name", "floor_index"], label, result)
        floor_id = floor.get("floor_id")
        add_unique_id(floor_id, "floor_id", label, floor_ids, result)
        floor_index = floor.get("floor_index")
        if isinstance(floor_index, int):
            if floor_index in floor_indices:
                result.errors.append(f"{label}: duplicate floor_index '{floor_index}'")
            else:
                floor_indices.add(floor_index)
        if isinstance(floor_id, str) and isinstance(floor_index, int):
            floor_index_map[floor_id] = floor_index
    default_floor_id = manifest.get("default_floor_id")
    if not isinstance(default_floor_id, str) or not default_floor_id:
        result.errors.append("manifest.default_floor_id must be a non-empty string")
    elif default_floor_id not in floor_ids:
        result.errors.append(f"manifest.default_floor_id references unknown floor_id '{default_floor_id}'")

    pois_doc = load_json(package_dir / "pois.json")
    pois = pois_doc.get("pois", [])
    if not isinstance(pois, list):
        result.errors.append("pois.pois must be an array")
        pois = []
    poi_ids: set[str] = set()

    entrances_doc = load_json(package_dir / "entrances.json")
    entrances = entrances_doc.get("entrances", [])
    if not isinstance(entrances, list):
        result.errors.append("entrances.entrances must be an array")
        entrances = []
    entrance_ids: set[str] = set()

    connectors_doc = load_json(package_dir / "connectors.json")
    connectors = connectors_doc.get("connectors", [])
    if not isinstance(connectors, list):
        result.errors.append("connectors.connectors must be an array")
        connectors = []
    connector_ids: set[str] = set()

    route_graph = load_json(package_dir / "route_graph.json")
    nodes = route_graph.get("nodes", [])
    edges = route_graph.get("edges", [])
    if not isinstance(nodes, list):
        result.errors.append("route_graph.nodes must be an array")
        nodes = []
    if not isinstance(edges, list):
        result.errors.append("route_graph.edges must be an array")
        edges = []

    node_ids: set[str] = set()
    node_refs: dict[str, str] = {}
    node_by_id: dict[str, dict[str, Any]] = {}
    for index, node in enumerate(nodes):
        label = f"route_graph.nodes[{index}]"
        if not isinstance(node, dict):
            result.errors.append(f"{label}: must be an object")
            continue
        require_keys(node, ["node_id", "floor_id", "x", "y", "node_type"], label, result)
        node_id = node.get("node_id")
        floor_id = node.get("floor_id")
        add_unique_id(node_id, "node_id", label, node_ids, result)
        if isinstance(node_id, str):
            node_by_id[node_id] = node
            if isinstance(node.get("ref_id"), str):
                node_refs[node_id] = node["ref_id"]
        if isinstance(floor_id, str) and floor_id not in floor_ids:
            result.errors.append(f"{label}: unknown floor_id '{floor_id}'")

    edge_ids: set[str] = set()
    edge_by_id: dict[str, dict[str, Any]] = {}
    edge_pairs: set[tuple[str, str]] = set()
    for index, edge in enumerate(edges):
        label = f"route_graph.edges[{index}]"
        if not isinstance(edge, dict):
            result.errors.append(f"{label}: must be an object")
            continue
        require_keys(
            edge,
            ["edge_id", "from_node_id", "to_node_id", "distance", "travel_mode", "bidirectional"],
            label,
            result,
        )
        edge_id = edge.get("edge_id")
        add_unique_id(edge_id, "edge_id", label, edge_ids, result)
        if isinstance(edge_id, str):
            edge_by_id[edge_id] = edge
        from_node_id = edge.get("from_node_id")
        to_node_id = edge.get("to_node_id")
        if isinstance(from_node_id, str) and isinstance(to_node_id, str):
            edge_pairs.add((from_node_id, to_node_id))
        for key in ("from_node_id", "to_node_id"):
            node_id = edge.get(key)
            if isinstance(node_id, str) and node_id not in node_ids:
                result.errors.append(f"{label}: unknown {key} '{node_id}'")

    for index, poi in enumerate(pois):
        label = f"pois[{index}]"
        if not isinstance(poi, dict):
            result.errors.append(f"{label}: must be an object")
            continue
        require_keys(
            label=label,
            obj=poi,
            keys=["poi_id", "poi_type", "poi_name", "venue_id", "floor_id", "position", "route_node_id"],
            result=result,
        )
        poi_id = poi.get("poi_id")
        floor_id = poi.get("floor_id")
        route_node_id = poi.get("route_node_id")
        add_unique_id(poi_id, "poi_id", label, poi_ids, result)
        venue_id = poi.get("venue_id")
        if isinstance(manifest_venue_id, str) and isinstance(venue_id, str) and venue_id != manifest_venue_id:
            result.errors.append(f"{label}: venue_id '{venue_id}' does not match manifest.venue_id '{manifest_venue_id}'")
        if isinstance(floor_id, str) and floor_id not in floor_ids:
            result.errors.append(f"{label}: unknown floor_id '{floor_id}'")
        if isinstance(route_node_id, str) and route_node_id not in node_ids:
            result.errors.append(f"{label}: unknown route_node_id '{route_node_id}'")
        if isinstance(route_node_id, str) and route_node_id in node_by_id:
            node_floor_id = node_by_id[route_node_id].get("floor_id")
            if isinstance(floor_id, str) and node_floor_id != floor_id:
                result.errors.append(f"{label}: route_node_id '{route_node_id}' is on floor '{node_floor_id}', not '{floor_id}'")
        position = poi.get("position")
        if not isinstance(position, dict) or "x" not in position or "y" not in position:
            result.errors.append(f"{label}: position must contain x and y")

    for index, entrance in enumerate(entrances):
        label = f"entrances[{index}]"
        if not isinstance(entrance, dict):
            result.errors.append(f"{label}: must be an object")
            continue
        require_keys(
            entrance,
            ["entrance_id", "entrance_name", "venue_id", "floor_id", "geo_position", "indoor_position"],
            label,
            result,
        )
        entrance_id = entrance.get("entrance_id")
        floor_id = entrance.get("floor_id")
        add_unique_id(entrance_id, "entrance_id", label, entrance_ids, result)
        venue_id = entrance.get("venue_id")
        if isinstance(manifest_venue_id, str) and isinstance(venue_id, str) and venue_id != manifest_venue_id:
            result.errors.append(f"{label}: venue_id '{venue_id}' does not match manifest.venue_id '{manifest_venue_id}'")
        if isinstance(floor_id, str) and floor_id not in floor_ids:
            result.errors.append(f"{label}: unknown floor_id '{floor_id}'")
        route_node_id = entrance.get("route_node_id")
        if route_node_id is not None and route_node_id not in node_ids:
            result.errors.append(f"{label}: unknown optional route_node_id '{route_node_id}'")
        if isinstance(route_node_id, str) and route_node_id in node_by_id:
            node_floor_id = node_by_id[route_node_id].get("floor_id")
            if isinstance(floor_id, str) and node_floor_id != floor_id:
                result.errors.append(f"{label}: route_node_id '{route_node_id}' is on floor '{node_floor_id}', not '{floor_id}'")

    for index, connector in enumerate(connectors):
        label = f"connectors[{index}]"
        if not isinstance(connector, dict):
            result.errors.append(f"{label}: must be an object")
            continue
        require_keys(
            connector,
            ["connector_id", "connector_type", "from_floor_id", "to_floor_id", "from_node_id", "to_node_id"],
            label,
            result,
        )
        connector_id = connector.get("connector_id")
        add_unique_id(connector_id, "connector_id", label, connector_ids, result)
        from_floor_id = connector.get("from_floor_id")
        to_floor_id = connector.get("to_floor_id")
        for key in ("from_floor_id", "to_floor_id"):
            floor_id = connector.get(key)
            if isinstance(floor_id, str) and floor_id not in floor_ids:
                result.errors.append(f"{label}: unknown {key} '{floor_id}'")
        from_node_id = connector.get("from_node_id")
        to_node_id = connector.get("to_node_id")
        for key in ("from_node_id", "to_node_id"):
            node_id = connector.get(key)
            if isinstance(node_id, str) and node_id not in node_ids:
                result.errors.append(f"{label}: unknown {key} '{node_id}'")
        edge_id = connector.get("edge_id")
        if edge_id is not None and edge_id not in edge_ids:
            result.errors.append(f"{label}: unknown optional edge_id '{edge_id}'")
        if (
            isinstance(from_node_id, str)
            and isinstance(to_node_id, str)
            and (from_node_id, to_node_id) not in edge_pairs
            and (to_node_id, from_node_id) not in edge_pairs
        ):
            result.errors.append(f"{label}: no route_graph edge found between '{from_node_id}' and '{to_node_id}'")
        direction = connector.get("direction")
        if (
            isinstance(direction, str)
            and isinstance(from_floor_id, str)
            and isinstance(to_floor_id, str)
            and from_floor_id in floor_index_map
            and to_floor_id in floor_index_map
        ):
            from_index = floor_index_map[from_floor_id]
            to_index = floor_index_map[to_floor_id]
            if direction == "up" and from_index >= to_index:
                result.errors.append(f"{label}: direction 'up' conflicts with floor_index ordering")
            if direction == "down" and from_index <= to_index:
                result.errors.append(f"{label}: direction 'down' conflicts with floor_index ordering")

    for node_id, ref_id in node_refs.items():
        if ref_id not in poi_ids and ref_id not in entrance_ids and ref_id not in connector_ids:
            result.errors.append(f"route_graph node '{node_id}' references unknown ref_id '{ref_id}'")

    cameras_doc = load_json(localization_dir / "cameras.json")
    cameras = cameras_doc.get("cameras", [])
    if not isinstance(cameras, list):
        result.errors.append("localization.cameras.cameras must be an array")
        cameras = []
    camera_ids: set[str] = set()
    for index, camera in enumerate(cameras):
        label = f"cameras[{index}]"
        if not isinstance(camera, dict):
            result.errors.append(f"{label}: must be an object")
            continue
        require_keys(camera, ["intrinsics_id", "model", "width", "height", "fx", "fy", "cx", "cy"], label, result)
        intrinsics_id = camera.get("intrinsics_id")
        add_unique_id(intrinsics_id, "intrinsics_id", label, camera_ids, result)

    keyframes = load_jsonl(localization_dir / "keyframes.jsonl")
    keyframe_ids: set[str] = set()
    for index, keyframe in enumerate(keyframes):
        label = f"keyframes[{index}]"
        if not isinstance(keyframe, dict):
            result.errors.append(f"{label}: must be an object")
            continue
        require_keys(keyframe, ["keyframe_id", "floor_id", "venue_xy"], label, result)
        add_unique_id(keyframe.get("keyframe_id"), "keyframe_id", label, keyframe_ids, result)
        floor_id = keyframe.get("floor_id")
        if isinstance(floor_id, str) and floor_id not in floor_ids:
            result.errors.append(f"{label}: unknown floor_id '{floor_id}'")
        intrinsics_id = keyframe.get("intrinsics_id")
        if intrinsics_id is not None and intrinsics_id not in camera_ids:
            result.errors.append(f"{label}: unknown intrinsics_id '{intrinsics_id}'")
        route_edge_id = keyframe.get("route_edge_id")
        if route_edge_id is not None and route_edge_id not in edge_ids:
            result.errors.append(f"{label}: unknown route_edge_id '{route_edge_id}'")
        venue_xy = keyframe.get("venue_xy")
        if not isinstance(venue_xy, dict) or "x" not in venue_xy or "y" not in venue_xy:
            result.errors.append(f"{label}: venue_xy must contain x and y")
        quality_score = keyframe.get("quality_score")
        if quality_score is not None and not (isinstance(quality_score, (int, float)) and 0.0 <= quality_score <= 1.0):
            result.errors.append(f"{label}: quality_score must be between 0.0 and 1.0")
        for ref_key in ("image_ref", "feature_ref"):
            raw_ref = keyframe.get(ref_key)
            if isinstance(raw_ref, str) and resolve_ref_path(package_dir, localization_dir, raw_ref) is None:
                result.errors.append(f"{label}: referenced file not found for {ref_key}: {raw_ref}")

    features_index_path = localization_dir / "features" / "superpoint" / "features_index.json"
    if features_index_path.exists():
        features_index = load_json(features_index_path)
        entries = features_index.get("feature_files", [])
        if not isinstance(entries, list):
            result.errors.append("features_index.feature_files must be an array")
        else:
            indexed_keyframe_ids: set[str] = set()
            for index, entry in enumerate(entries):
                label = f"features_index[{index}]"
                if not isinstance(entry, dict):
                    result.errors.append(f"{label}: must be an object")
                    continue
                require_keys(entry, ["keyframe_id", "path"], label, result)
                keyframe_id = entry.get("keyframe_id")
                if isinstance(keyframe_id, str) and keyframe_id not in keyframe_ids:
                    result.errors.append(f"{label}: unknown keyframe_id '{keyframe_id}'")
                add_unique_id(keyframe_id, "keyframe_id", label, indexed_keyframe_ids, result)
                path_value = entry.get("path")
                if isinstance(path_value, str) and resolve_ref_path(package_dir, localization_dir, path_value) is None:
                    result.errors.append(f"{label}: feature file not found: {path_value}")

    result.summary = summarize_package(
        manifest=manifest,
        floors=floors,
        pois=pois,
        entrances=entrances,
        connectors=connectors,
        nodes=nodes,
        edges=edges,
        keyframes=keyframes,
        declared_files=declared_files,
        computed_checksums=computed_checksums,
        warning_count=len(result.warnings),
    )
    return result


def summarize_package(
    manifest: dict[str, Any],
    floors: list[dict[str, Any]],
    pois: list[dict[str, Any]],
    entrances: list[dict[str, Any]],
    connectors: list[dict[str, Any]],
    nodes: list[dict[str, Any]],
    edges: list[dict[str, Any]],
    keyframes: list[dict[str, Any]],
    declared_files: list[dict[str, Any]],
    computed_checksums: dict[str, str],
    warning_count: int,
) -> dict[str, Any]:
    return {
        "package_id": manifest.get("package_id"),
        "package_version": manifest.get("package_version"),
        "venue_id": manifest.get("venue_id"),
        "floor_count": len(floors),
        "poi_count": len(pois),
        "entrance_count": len(entrances),
        "connector_count": len(connectors),
        "route_node_count": len(nodes),
        "route_edge_count": len(edges),
        "keyframe_count": len(keyframes),
        "declared_file_count": len(declared_files),
        "computed_checksum_count": len(computed_checksums),
        "warning_count": warning_count,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate a venue package.")
    parser.add_argument("package_dir", help="Path to venue package directory")
    parser.add_argument("--json", action="store_true", help="Print machine-readable summary")
    args = parser.parse_args()

    package_dir = Path(args.package_dir)
    try:
        result = validate_package(package_dir)
    except (OSError, ValueError) as exc:
        result = ValidationResult(errors=[str(exc)])

    if args.json:
        payload = {
            "ok": result.ok,
            "errors": result.errors,
            "warnings": result.warnings,
            "summary": result.summary,
        }
        print(json.dumps(payload, indent=2, ensure_ascii=True))
    else:
        print(f"Package: {package_dir}")
        print(f"Status: {'OK' if result.ok else 'FAILED'}")
        if result.summary:
            print(json.dumps(result.summary, indent=2, ensure_ascii=True))
        if result.warnings:
            print("Warnings:")
            for warning in result.warnings:
                print(f"  - {warning}")
        if result.errors:
            print("Errors:")
            for error in result.errors:
                print(f"  - {error}")

    return 0 if result.ok else 1


if __name__ == "__main__":
    sys.exit(main())
