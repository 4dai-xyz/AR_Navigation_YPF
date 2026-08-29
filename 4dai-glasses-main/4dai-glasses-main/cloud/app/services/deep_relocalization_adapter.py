from __future__ import annotations

import importlib.util
from dataclasses import dataclass
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class DeepRelocalizationConfig:
    model_root: Path | None = None
    retrieval_index_path: Path | None = None
    device: str = "cpu"


class DeepRelocalizationUnavailable(RuntimeError):
    pass


class DeepRelocalizationAdapter:
    required_modules = ("torch", "lightglue", "hloc")

    def __init__(self, config: DeepRelocalizationConfig | None = None) -> None:
        self.config = config or DeepRelocalizationConfig()

    def availability(self) -> dict[str, Any]:
        missing_dependencies = [name for name in self.required_modules if importlib.util.find_spec(name) is None]
        missing_files: list[str] = []
        if self.config.model_root is None:
            missing_files.append("model_root")
        elif not self.config.model_root.exists():
            missing_files.append(str(self.config.model_root))
        if self.config.retrieval_index_path is None:
            missing_files.append("retrieval_index_path")
        elif not self.config.retrieval_index_path.exists():
            missing_files.append(str(self.config.retrieval_index_path))

        if missing_dependencies:
            reason = "missing_optional_dependencies"
        elif missing_files:
            reason = "not_configured"
        else:
            reason = "available"

        return {
            "available": not missing_dependencies and not missing_files,
            "reason": reason,
            "missing_dependencies": missing_dependencies,
            "missing_files": missing_files,
            "device": self.config.device,
        }

    def is_available(self) -> bool:
        return bool(self.availability()["available"])

    def localize(self, *_args: Any, **_kwargs: Any) -> Any:
        availability = self.availability()
        if not availability["available"]:
            raise DeepRelocalizationUnavailable(
                "Track B deep relocalization is unavailable: "
                f"{availability['reason']}; "
                f"missing_dependencies={availability['missing_dependencies']}; "
                f"missing_files={availability['missing_files']}"
            )
        raise NotImplementedError("Track B adapter skeleton is ready, but deep relocalization is not implemented yet.")
