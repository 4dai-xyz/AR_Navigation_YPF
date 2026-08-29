from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path


ROOT = Path(__file__).resolve().parent
BASELINE_SCRIPT = ROOT.parent / "wudaokou1" / "render_walkable_network_previews.py"


def load_baseline_module():
    spec = spec_from_file_location("wudaokou1_walkable_network_previews", BASELINE_SCRIPT)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Cannot load baseline script: {BASELINE_SCRIPT}")
    module = module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def configure_paths(module):
    module.ROOT = ROOT
    module.BASE_DIR = ROOT / "annotation_points"
    module.MASK_DIR = ROOT / "masks"
    module.OUT_DIR = ROOT / "processed" / "walkable_network_previews"
    module.RESULTS_DIR = module.OUT_DIR / "results"
    module.DIAGNOSTICS_DIR = module.OUT_DIR / "diagnostics"
    module.EXPERIMENT_OUT_DIR = module.RESULTS_DIR


def main():
    module = load_baseline_module()
    configure_paths(module)
    module.main()


if __name__ == "__main__":
    main()
