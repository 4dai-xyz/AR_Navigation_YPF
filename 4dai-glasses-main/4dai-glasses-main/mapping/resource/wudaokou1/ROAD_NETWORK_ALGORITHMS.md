# Wudaokou Road Network Algorithms

## Algorithm A: `raw_grid_baseline`

Algorithm A is the current rollback baseline for Wudaokou Shopping Center walkable road-network previews.

### Scope

- Input: `masks/*.jpg`
- Output: `processed/walkable_network_previews/results/algorithm_a_raw_grid_baseline/`
- Summary: `processed/walkable_network_previews/results/algorithm_a_raw_grid_baseline/summary.json`
- Visual style: blue nodes and blue edges only

### Rules

- `mask` threshold: pixels `>=128` are walkable.
- Grid step: `35px`.
- Each grid cell with enough white pixels creates one node inside the walkable region.
- Edges are generated only between neighboring grid-cell nodes.
- An edge is valid only when the sampled line stays inside the white walkable mask.
- Legacy raw-grid statistics are kept stable so future algorithms can compare against this baseline.

### Baseline Statistics

| Floor | Nodes | Edges | Components |
| --- | ---: | ---: | ---: |
| B1 | 699 | 1812 | 6 |
| F1 | 796 | 2245 | 3 |
| F2 | 665 | 1895 | 1 |
| F3 | 725 | 2107 | 2 |
| F4 | 664 | 1834 | 1 |
| F5 | 696 | 1892 | 1 |
| F6 | 379 | 1044 | 6 |

### Rollback Rule

Future algorithms must not overwrite Algorithm A semantics directly. If a new approach is tested, create a separate algorithm label such as Algorithm B and keep its output separate until manually accepted.

To roll back, regenerate Algorithm A with:

```powershell
python mapping/resource/wudaokou1/render_walkable_network_previews.py
```

## Experimental Algorithms

All experimental algorithms must use Algorithm A as input and write outputs under:

`processed/walkable_network_previews/results/algorithm_<id>_<name>/`

They must not overwrite Algorithm A preview files or change Algorithm A baseline statistics.

For Git handoff, keep only final accepted preview images and compact `summary.json` statistics. Large per-floor preview images from rejected or intermediate algorithms should stay local or be regenerated from the script when needed.

Temporary crops, node-id screenshots, and visual debugging files must be kept under:

`processed/walkable_network_previews/diagnostics/`

### Algorithm B: `straight_gap_sparse`

- Goal: detect straight or near-straight narrow corridor gaps.
- Method: find two existing Algorithm A nodes where the connecting line is fully walkable, but the distance is slightly longer than the normal edge threshold.
- Output: insert only the minimum waypoint nodes needed to split that gap into valid edges.
- Redundancy control: per floor added nodes are capped and each gap uses sparse midpoint-style waypoints.

### Algorithm C: `component_bridge_sparse`

- Goal: reduce small disconnected components caused by narrow passages.
- Method: search local mask paths from small components back to the largest Algorithm A component.
- Output: add compressed waypoints if the local path is short and close to the direct distance; then add a small number of visual gap waypoints, terminal edges, and terminal local-path waypoints for narrow corridors that still look broken.
- Redundancy control: ignores large components, rejects paths with too many waypoints, and caps the extra visual gap nodes and terminal edges per floor.

### Algorithm D: `cell_component_sparse`

- Goal: detect walkable fragments inside a grid cell that Algorithm A does not represent.
- Method: split each grid cell's white mask area into local connected components and add one node only for boundary-touching unrepresented components.
- Output: connect each new node only to a small number of nearest valid neighbors.
- Redundancy control: per floor added nodes are capped and each new node has limited edges.

### Algorithm E: `mask_path_polyline_bridge`

- Goal: test whether visually broken narrow corridors are caused by straight-line edge rendering rather than missing mask connectivity.
- Method: start from Algorithm C output, then find adjacent grid-cell node pairs where the direct line is blocked but a short local path exists inside the white mask and the current graph route is a detour.
- Output: add sparse `algorithm_e` edges with `path` polylines that follow the local mask path; no extra nodes are added.
- Redundancy control: caps added polyline edges per floor and rejects long or highly curved local paths.

### Algorithm F: `cell_portal_bridge`

- Goal: detect narrow corridor seams from the mask itself rather than from manually circled screenshots.
- Method: find shared white boundary runs between adjacent grid cells, place one portal node at the shared walkable seam, and connect both cell nodes to that portal through local mask paths.
- Output: add sparse `algorithm_f` portal nodes and red polyline edges through the detected seam.
- Redundancy control: requires the existing graph route to be a detour and caps portal nodes and portal edges per floor.

### Algorithm G: `cell_portal_bridge_pruned`

- Goal: keep Algorithm F's successful narrow-corridor repairs while reducing redundant nearby portal nodes and edges.
- Method: reuse F's portal candidates, require a stronger graph-detour gain, and suppress new portals that are too close to already selected portals.
- Output: add fewer `algorithm_g` portal nodes and magenta polyline edges.
- Redundancy control: prevents self-loop edges, applies portal spacing, and uses lower node/edge caps than Algorithm F.
