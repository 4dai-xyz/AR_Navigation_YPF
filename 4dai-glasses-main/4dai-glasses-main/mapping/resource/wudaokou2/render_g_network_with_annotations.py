from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path


ROOT = Path(__file__).resolve().parent
BASELINE_SCRIPT = ROOT.parent / "wudaokou1" / "render_g_network_with_annotations.py"


def load_baseline_module():
    spec = spec_from_file_location("wudaokou1_g_network_with_annotations", BASELINE_SCRIPT)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Cannot load baseline script: {BASELINE_SCRIPT}")
    module = module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def configure_paths(module):
    module.ROOT_DIR = ROOT
    module.ANNOTATION_DIR = ROOT / "annotation_points"
    module.G_NETWORK_DIR = (
        ROOT
        / "processed"
        / "walkable_network_previews"
        / "results"
        / "algorithm_g_cell_portal_bridge_pruned"
    )
    module.OUTPUT_DIR = (
        ROOT
        / "processed"
        / "annotation_overlays"
        / "algorithm_g_cell_portal_bridge_pruned_clean_indexed"
    )


def main():
    module = load_baseline_module()
    configure_paths(module)
    module.main()


if __name__ == "__main__":
    main()
