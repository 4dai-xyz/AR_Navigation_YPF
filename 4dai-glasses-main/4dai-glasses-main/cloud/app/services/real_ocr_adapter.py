from __future__ import annotations

import importlib.util
import os
from collections import deque
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class RealOcrCandidate:
    label: str
    class_index: int
    score: float
    detection_confidence: float
    classification_confidence: float
    bbox: tuple[int, int, int, int]


@dataclass(frozen=True)
class RealOcrResult:
    status: str
    failure_stage: str | None
    message: str
    candidates: list[RealOcrCandidate]
    details: dict[str, Any]


REAL_OCR_DEBUGS: deque[tuple[str, RealOcrResult]] = deque(maxlen=20)


def remember_real_ocr_debug(request_id: str, result: RealOcrResult) -> None:
    REAL_OCR_DEBUGS.appendleft((request_id, result))


def get_real_ocr_debug(request_id: str | None) -> RealOcrResult | None:
    if not request_id:
        return None
    for item_request_id, result in REAL_OCR_DEBUGS:
        if item_request_id == request_id:
            return result
    return None


def _default_detector_path(repo_root: Path) -> Path:
    uploaded_detector_path = repo_root / "OCR" / "New" / "huichang_logo_detector_binary.pth"
    if uploaded_detector_path.is_file():
        return uploaded_detector_path
    return repo_root / "OCR" / "huichang_logo_detector_binary.pth"


def _default_classifier_path(repo_root: Path) -> Path:
    uploaded_classifier_path = repo_root / "OCR" / "New" / "huichang_logo_model_final.pth"
    if uploaded_classifier_path.is_file():
        return uploaded_classifier_path
    return repo_root / "OCR" / "huichang_logo_model_final.pth"


def _default_classes_path(repo_root: Path) -> Path:
    uploaded_classes_path = repo_root / "OCR" / "New" / "huichang_logo_classes.txt"
    if uploaded_classes_path.is_file():
        return uploaded_classes_path
    return repo_root / "OCR" / "huichang_logo_classes.txt"


def _uploaded_algorithm_path(repo_root: Path) -> Path:
    return repo_root / "OCR" / "New" / "R-CNN.py"


def real_ocr_backend_status(repo_root: Path) -> dict[str, Any]:
    detector_path = Path(os.getenv("AI_GLASSES_REAL_OCR_DETECTOR_PTH") or _default_detector_path(repo_root))
    classifier_path = Path(os.getenv("AI_GLASSES_REAL_OCR_CLASSIFIER_PTH") or _default_classifier_path(repo_root))
    classes_path = Path(os.getenv("AI_GLASSES_REAL_OCR_CLASSES_TXT") or _default_classes_path(repo_root))
    algorithm_path = _uploaded_algorithm_path(repo_root)
    dependency_status = _dependency_status()
    return {
        "available": (
            dependency_status["torch_available"]
            and dependency_status["torchvision_available"]
            and dependency_status["cv2_available"]
            and detector_path.is_file()
            and classifier_path.is_file()
        ),
        "detector_path": str(detector_path),
        "classifier_path": str(classifier_path),
        "classes_path": str(classes_path),
        "algorithm": os.getenv("AI_GLASSES_REAL_OCR_ALGORITHM", "uploaded_rcnn"),
        "uploaded_algorithm_path": str(algorithm_path),
        "uploaded_algorithm_exists": algorithm_path.is_file(),
        "detector_exists": detector_path.is_file(),
        "classifier_exists": classifier_path.is_file(),
        "classes_exists": classes_path.is_file(),
        **dependency_status,
    }


def _dependency_status() -> dict[str, Any]:
    return {
        "torch_available": importlib.util.find_spec("torch") is not None,
        "torchvision_available": importlib.util.find_spec("torchvision") is not None,
        "cv2_available": importlib.util.find_spec("cv2") is not None,
    }


def _load_state_dict(torch_module, checkpoint_path: Path, map_location: str):
    try:
        checkpoint = torch_module.load(checkpoint_path, map_location=map_location, weights_only=True)
    except TypeError:
        checkpoint = torch_module.load(checkpoint_path, map_location=map_location)
    if isinstance(checkpoint, dict) and isinstance(checkpoint.get("state_dict"), dict):
        checkpoint = checkpoint["state_dict"]
    return {key.removeprefix("module."): value for key, value in checkpoint.items()}


def _infer_fc_out_features(state_dict: dict[str, Any]) -> int:
    if "fc.weight" not in state_dict:
        raise ValueError("fc.weight not found in checkpoint")
    return int(state_dict["fc.weight"].shape[0])


def _load_class_names(classes_path: Path, expected_count: int) -> tuple[list[str], str, bool]:
    if classes_path.is_file():
        names = [
            line.strip()
            for line in classes_path.read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.strip().startswith("#")
        ]
        if len(names) == expected_count:
            return names, str(classes_path), True
        return [f"class_{index:03d}" for index in range(expected_count)], (
            f"{classes_path} has {len(names)} names, expected {expected_count}"
        ), False
    return [f"class_{index:03d}" for index in range(expected_count)], "generated", False


@lru_cache(maxsize=2)
def _load_models(
    detector_path_text: str,
    classifier_path_text: str,
    classes_path_text: str,
    device: str,
):
    import torch
    import torch.nn as nn
    from torchvision import models

    detector_path = Path(detector_path_text)
    classifier_path = Path(classifier_path_text)
    classes_path = Path(classes_path_text)
    detector_state = _load_state_dict(torch, detector_path, device)
    classifier_state = _load_state_dict(torch, classifier_path, device)
    num_classes = _infer_fc_out_features(classifier_state)
    class_names, class_source, labels_configured = _load_class_names(classes_path, num_classes)

    detector = models.resnet18(weights=None)
    detector.fc = nn.Linear(detector.fc.in_features, 2)
    detector.load_state_dict(detector_state)
    detector.to(device).eval()

    classifier = models.resnet18(weights=None)
    classifier.fc = nn.Linear(classifier.fc.in_features, num_classes)
    classifier.load_state_dict(classifier_state)
    classifier.to(device).eval()

    return torch, detector, classifier, class_names, class_source, labels_configured


@lru_cache(maxsize=1)
def _load_uploaded_algorithm(algorithm_path_text: str):
    algorithm_path = Path(algorithm_path_text)
    if not algorithm_path.is_file():
        raise FileNotFoundError(str(algorithm_path))
    spec = importlib.util.spec_from_file_location("ai_glasses_uploaded_ocr_rcnn", algorithm_path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load uploaded OCR algorithm: {algorithm_path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _configure_uploaded_algorithm(module) -> None:
    env_mapping = {
        "DETECTOR_THRESHOLD": "AI_GLASSES_REAL_OCR_DETECTION_THRESHOLD",
        "CLASSIFIER_THRESHOLD": "AI_GLASSES_REAL_OCR_CLASSIFICATION_THRESHOLD",
        "MIN_BOX_SIZE": "AI_GLASSES_REAL_OCR_MIN_BOX_SIZE",
        "MIN_BOX_AREA_RATIO": "AI_GLASSES_REAL_OCR_MIN_BOX_AREA_RATIO",
        "MAX_BOX_AREA_RATIO": "AI_GLASSES_REAL_OCR_MAX_BOX_AREA_RATIO",
        "BLUE_SIGN_MIN_AREA_RATIO": "AI_GLASSES_REAL_OCR_BLUE_SIGN_MIN_AREA_RATIO",
        "BLUE_SIGN_MIN_TALL_ASPECT": "AI_GLASSES_REAL_OCR_BLUE_SIGN_MIN_TALL_ASPECT",
        "BLUE_SIGN_MAX_WIDTH_RATIO": "AI_GLASSES_REAL_OCR_BLUE_SIGN_MAX_WIDTH_RATIO",
        "NMS_IOU_THRESHOLD": "AI_GLASSES_REAL_OCR_NMS_IOU_THRESHOLD",
        "NMS_MIN_OVERLAP_THRESHOLD": "AI_GLASSES_REAL_OCR_NMS_MIN_OVERLAP_THRESHOLD",
    }
    for attr_name, env_name in env_mapping.items():
        if env_name not in os.environ or not hasattr(module, attr_name):
            continue
        current_value = getattr(module, attr_name)
        raw_value = os.environ[env_name]
        if isinstance(current_value, int):
            setattr(module, attr_name, int(raw_value))
        else:
            setattr(module, attr_name, float(raw_value))


def _get_edge_proposals(cv2_module, image):
    gray = cv2_module.cvtColor(image, cv2_module.COLOR_BGR2GRAY)
    blurred = cv2_module.GaussianBlur(gray, (5, 5), 0)
    edges = cv2_module.Canny(blurred, 50, 150)
    contours, _ = cv2_module.findContours(edges, cv2_module.RETR_EXTERNAL, cv2_module.CHAIN_APPROX_SIMPLE)
    frame_h, frame_w = image.shape[:2]
    min_box_size = int(os.getenv("AI_GLASSES_REAL_OCR_MIN_BOX_SIZE", "40"))
    min_area_ratio = float(os.getenv("AI_GLASSES_REAL_OCR_MIN_BOX_AREA_RATIO", "0.0015"))
    max_box_width_ratio = float(os.getenv("AI_GLASSES_REAL_OCR_MAX_BOX_WIDTH_RATIO", "0.65"))
    min_area = max(int(frame_h * frame_w * min_area_ratio), min_box_size * min_box_size)
    proposals = []
    for cnt in contours:
        x, y, width, height = cv2_module.boundingRect(cnt)
        area = width * height
        if width < min_box_size or height < min_box_size:
            continue
        if area < min_area or width > frame_w * max_box_width_ratio:
            continue
        proposals.append((x, y, width, height))
    proposals.sort(key=lambda item: item[2] * item[3], reverse=True)
    max_proposals = int(os.getenv("AI_GLASSES_REAL_OCR_MAX_PROPOSALS", "80"))
    return proposals[:max(1, max_proposals)]


def _get_proposals(*, repo_root: Path, cv2_module, image) -> tuple[list[tuple[int, int, int, int]], list[tuple[int, int, int, int]], str]:
    algorithm = os.getenv("AI_GLASSES_REAL_OCR_ALGORITHM", "uploaded_rcnn")
    if algorithm == "edge":
        return _get_edge_proposals(cv2_module, image), [], "edge"
    if algorithm != "uploaded_rcnn":
        raise ValueError(f"unsupported real OCR algorithm: {algorithm}")
    uploaded_module = _load_uploaded_algorithm(str(_uploaded_algorithm_path(repo_root)))
    _configure_uploaded_algorithm(uploaded_module)
    proposals, blue_sign_boxes = uploaded_module.get_proposals(image)
    max_proposals = int(os.getenv("AI_GLASSES_REAL_OCR_MAX_PROPOSALS", "120"))
    return proposals[:max(1, max_proposals)], blue_sign_boxes, "uploaded_rcnn"


def _get_classifier_crops(*, repo_root: Path, crop):
    if os.getenv("AI_GLASSES_REAL_OCR_ALGORITHM", "uploaded_rcnn") != "uploaded_rcnn":
        return [crop]
    uploaded_module = _load_uploaded_algorithm(str(_uploaded_algorithm_path(repo_root)))
    _configure_uploaded_algorithm(uploaded_module)
    return uploaded_module.get_classifier_crops(crop)


def _nms_uploaded_detections(*, repo_root: Path, detections: list[tuple[Any, ...]]) -> list[tuple[Any, ...]]:
    if os.getenv("AI_GLASSES_REAL_OCR_ALGORITHM", "uploaded_rcnn") != "uploaded_rcnn":
        return detections
    uploaded_module = _load_uploaded_algorithm(str(_uploaded_algorithm_path(repo_root)))
    _configure_uploaded_algorithm(uploaded_module)
    return uploaded_module.nms(detections, iou_threshold=uploaded_module.NMS_IOU_THRESHOLD)


def _build_transform():
    from torchvision import transforms

    return transforms.Compose(
        [
            transforms.Resize((224, 224)),
            transforms.ToTensor(),
            transforms.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225]),
        ]
    )


def run_real_ocr_adapter(*, repo_root: Path, image_bytes: bytes) -> RealOcrResult:
    detector_path = Path(os.getenv("AI_GLASSES_REAL_OCR_DETECTOR_PTH") or _default_detector_path(repo_root))
    classifier_path = Path(os.getenv("AI_GLASSES_REAL_OCR_CLASSIFIER_PTH") or _default_classifier_path(repo_root))
    classes_path = Path(os.getenv("AI_GLASSES_REAL_OCR_CLASSES_TXT") or _default_classes_path(repo_root))
    device = os.getenv("AI_GLASSES_REAL_OCR_DEVICE", "cpu")

    dependencies = _dependency_status()
    if not dependencies["torch_available"] or not dependencies["torchvision_available"] or not dependencies["cv2_available"]:
        return RealOcrResult(
            status="not_found",
            failure_stage="real_ocr_dependency_unavailable",
            message="real OCR dependencies are unavailable",
            candidates=[],
            details=dependencies,
        )
    if not detector_path.is_file() or not classifier_path.is_file():
        return RealOcrResult(
            status="not_found",
            failure_stage="real_ocr_model_missing",
            message="real OCR model checkpoint is missing",
            candidates=[],
            details={
                "detector_path": str(detector_path),
                "classifier_path": str(classifier_path),
                "detector_exists": detector_path.is_file(),
                "classifier_exists": classifier_path.is_file(),
            },
        )

    import cv2
    import numpy as np
    from PIL import Image

    array = np.frombuffer(image_bytes, dtype=np.uint8)
    frame = cv2.imdecode(array, cv2.IMREAD_COLOR)
    if frame is None:
        return RealOcrResult(
            status="not_found",
            failure_stage="real_ocr_image_decode_failed",
            message="real OCR adapter cannot decode image",
            candidates=[],
            details={"image_bytes": len(image_bytes)},
        )

    try:
        torch, detector, classifier, class_names, class_source, labels_configured = _load_models(
            str(detector_path),
            str(classifier_path),
            str(classes_path),
            device,
        )
    except Exception as exc:
        return RealOcrResult(
            status="not_found",
            failure_stage="real_ocr_model_load_failed",
            message="real OCR adapter failed to load model",
            candidates=[],
            details={"error": str(exc), "detector_path": str(detector_path), "classifier_path": str(classifier_path)},
        )

    try:
        proposals, blue_sign_boxes, proposal_algorithm = _get_proposals(repo_root=repo_root, cv2_module=cv2, image=frame)
    except Exception as exc:
        return RealOcrResult(
            status="not_found",
            failure_stage="real_ocr_algorithm_load_failed",
            message="real OCR adapter failed to load uploaded OCR algorithm",
            candidates=[],
            details={"error": str(exc), "algorithm": os.getenv("AI_GLASSES_REAL_OCR_ALGORITHM", "uploaded_rcnn")},
        )
    if not proposals:
        return RealOcrResult(
            status="not_found",
            failure_stage="real_ocr_no_proposals",
            message="real OCR adapter found no candidate regions",
            candidates=[],
            details={
                "image_width": int(frame.shape[1]),
                "image_height": int(frame.shape[0]),
                "class_source": class_source,
                "labels_configured": labels_configured,
                "proposal_algorithm": proposal_algorithm,
                "blue_sign_count": len(blue_sign_boxes),
            },
        )

    transform = _build_transform()
    detection_threshold = float(os.getenv("AI_GLASSES_REAL_OCR_DETECTION_THRESHOLD", "0.65"))
    classification_threshold = float(os.getenv("AI_GLASSES_REAL_OCR_CLASSIFICATION_THRESHOLD", "0.80"))
    classifier_margin_threshold = float(os.getenv("AI_GLASSES_REAL_OCR_CLASSIFIER_MARGIN_THRESHOLD", "0.15"))
    max_candidates = int(os.getenv("AI_GLASSES_REAL_OCR_MAX_CANDIDATES", "8"))
    detections: list[tuple[int, int, int, int, int, float, float, float]] = []

    for x, y, width, height in proposals:
        crop = frame[y : y + height, x : x + width]
        if crop.size == 0:
            continue
        input_tensor = transform(Image.fromarray(cv2.cvtColor(crop, cv2.COLOR_BGR2RGB))).unsqueeze(0).to(device)
        with torch.no_grad():
            det_out = detector(input_tensor)
            det_prob = torch.nn.functional.softmax(det_out, dim=1)
            det_conf, det_class = torch.max(det_prob, 1)
        detection_confidence = float(det_conf.item())
        if int(det_class.item()) != 1 or detection_confidence < detection_threshold:
            continue

        class_index = -1
        classification_confidence = 0.0
        classifier_margin = 0.0
        for classifier_crop in _get_classifier_crops(repo_root=repo_root, crop=crop):
            if classifier_crop.size == 0:
                continue
            cls_tensor = transform(Image.fromarray(cv2.cvtColor(classifier_crop, cv2.COLOR_BGR2RGB))).unsqueeze(0).to(device)
            with torch.no_grad():
                cls_out = classifier(cls_tensor)
                cls_prob = torch.nn.functional.softmax(cls_out, dim=1)
                cls_conf, cls_class = torch.max(cls_prob, 1)
                top_values = torch.topk(cls_prob, k=min(2, cls_prob.shape[1]), dim=1).values
            current_confidence = float(cls_conf.item())
            if current_confidence > classification_confidence:
                class_index = int(cls_class.item())
                classification_confidence = current_confidence
                classifier_margin = float(top_values[0, 0] - top_values[0, 1]) if top_values.shape[1] > 1 else 1.0

        if (
            class_index < 0
            or classification_confidence < classification_threshold
            or classifier_margin < classifier_margin_threshold
        ):
            continue
        score = round(detection_confidence * classification_confidence, 4)
        detections.append(
            (
                int(x),
                int(y),
                int(width),
                int(height),
                int(class_index),
                score,
                round(detection_confidence, 4),
                round(classification_confidence, 4),
            )
        )

    detections = _nms_uploaded_detections(repo_root=repo_root, detections=detections)
    detections = sorted(detections, key=lambda item: item[5], reverse=True)
    candidates: list[RealOcrCandidate] = []
    for x, y, width, height, class_index, score, detection_confidence, classification_confidence in detections:
        label = class_names[class_index] if 0 <= class_index < len(class_names) else f"class_{class_index:03d}"
        candidates.append(
            RealOcrCandidate(
                label=label,
                class_index=class_index,
                score=score,
                detection_confidence=detection_confidence,
                classification_confidence=classification_confidence,
                bbox=(int(x), int(y), int(width), int(height)),
            )
        )

    candidates = candidates[: max(1, max_candidates)]
    if not candidates:
        return RealOcrResult(
            status="not_found",
            failure_stage="real_ocr_no_logo_detected",
            message="real OCR adapter found proposals but no logo passed detector threshold",
            candidates=[],
            details={
                "proposal_count": len(proposals),
                "detection_threshold": detection_threshold,
                "classification_threshold": classification_threshold,
                "classifier_margin_threshold": classifier_margin_threshold,
                "proposal_algorithm": proposal_algorithm,
                "blue_sign_count": len(blue_sign_boxes),
                "class_source": class_source,
                "labels_configured": labels_configured,
            },
        )

    return RealOcrResult(
        status="ok",
        failure_stage=None,
        message="real OCR adapter produced logo candidates",
        candidates=candidates,
        details={
            "proposal_count": len(proposals),
            "proposal_algorithm": proposal_algorithm,
            "blue_sign_count": len(blue_sign_boxes),
            "class_source": class_source,
            "labels_configured": labels_configured,
            "detector_path": str(detector_path),
            "classifier_path": str(classifier_path),
            "classes_path": str(classes_path),
        },
    )
