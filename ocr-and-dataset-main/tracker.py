import cv2
import json
import os
import re


# ================= Config =================
# Single image-sequence labeling mode.
IMG_DIR = r"G:\kejicompany\tracker\huichang_images_10fps\video_003"
SAVE_PATH = "huichang_slam_data_10fps_video_003.json"
MAP_PATH = "huichang_label_id_mapping_10fps_video_003.txt"
AUTO_SAVE_INTERVAL = 100
DISPLAY_TARGET_H = 800
# ==========================================


def natural_sort_key(value):
    """Sort strings with embedded numbers in human order."""
    return [int(text) if text.isdigit() else text.lower()
            for text in re.split(r"([0-9]+)", value)]


def create_single_tracker():
    if hasattr(cv2, "legacy") and hasattr(cv2.legacy, "TrackerCSRT_create"):
        return cv2.legacy.TrackerCSRT_create()
    if hasattr(cv2, "TrackerCSRT_create"):
        return cv2.TrackerCSRT_create()
    if hasattr(cv2, "legacy") and hasattr(cv2.legacy, "TrackerKCF_create"):
        return cv2.legacy.TrackerKCF_create()
    if hasattr(cv2, "TrackerKCF_create"):
        return cv2.TrackerKCF_create()
    raise RuntimeError(
        "This OpenCV build has no CSRT/KCF tracker. "
        "Install opencv-contrib-python or a conda OpenCV build with legacy trackers."
    )


def update_trackers(trackers, frame):
    boxes = []
    for tracker in trackers:
        success, box = tracker.update(frame)
        if not success:
            return False, []
        boxes.append(box)
    return True, boxes


def save_all_data(results, mapping, data_path, map_path):
    """Save frame annotations and the global label mapping."""
    output = {"info": "SLAM Logo Ground Truth", "frames": results}
    with open(data_path, "w", encoding="utf-8") as f:
        json.dump(output, f, ensure_ascii=False, indent=4)

    with open(map_path, "w", encoding="utf-8") as f:
        f.write("=== SLAM Logo Label & ID Mapping ===\n")
        f.write("format: label_id\n")
        f.write("-" * 35 + "\n")
        for label in sorted(list(mapping), key=natural_sort_key):
            f.write(f"{label}\n")

    print(f">>> Progress saved: {data_path} & {map_path}")


def parse_label(user_input):
    label = user_input.strip()
    if not label:
        return "unknown"
    return re.sub(r"\s+", "_", label)


def load_progress():
    final_results = {}
    unique_labels = set()

    if not os.path.exists(SAVE_PATH):
        return final_results, unique_labels

    try:
        with open(SAVE_PATH, "r", encoding="utf-8") as f:
            temp_data = json.load(f).get("frames", {})
        final_results = temp_data
        for frame_content in temp_data.values():
            for label in frame_content.keys():
                unique_labels.add(label)
        print(f">>> Resumed old progress: {len(final_results)} frames")
    except Exception as e:
        print(f">>> Failed to resume progress: {e}")

    return final_results, unique_labels


def get_display_size(frame):
    if frame is None:
        return 450, DISPLAY_TARGET_H
    img_h, img_w = frame.shape[:2]
    target_w = int(img_w * (DISPLAY_TARGET_H / max(img_h, 1)))
    return max(320, target_w), DISPLAY_TARGET_H


def run_interactive_harvest():
    img_names = sorted(
        [f for f in os.listdir(IMG_DIR) if f.lower().endswith((".jpg", ".jpeg", ".png"))],
        key=natural_sort_key,
    )
    if not img_names:
        print(f">>> No images found. Check IMG_DIR: {IMG_DIR}")
        return

    final_results, unique_labels = load_progress()
    trackers = []
    tracker_full_names = []
    is_tracking = False
    frame_idx = len(final_results)

    print("\n" + "=" * 45)
    print(">>> SLAM image-sequence labeling started")
    print(">>> SPACE: select ROI and assign label")
    print(">>> N: mark current frame empty and skip")
    print(">>> Q: save and quit")
    print(">>> Input example: starbuck")
    print("=" * 45 + "\n")

    first_frame = cv2.imread(os.path.join(IMG_DIR, img_names[min(frame_idx, len(img_names) - 1)]))
    target_w, target_h = get_display_size(first_frame)

    cv2.namedWindow("SLAM Harvest Pro", cv2.WINDOW_NORMAL | cv2.WINDOW_KEEPRATIO)
    cv2.resizeWindow("SLAM Harvest Pro", target_w, target_h)

    while frame_idx < len(img_names):
        frame_name = img_names[frame_idx]
        frame = cv2.imread(os.path.join(IMG_DIR, frame_name))
        if frame is None:
            break

        display_frame = frame.copy()

        if is_tracking and trackers:
            success, boxes = update_trackers(trackers, frame)
            frame_data = {}
            if success:
                for j, box in enumerate(boxes):
                    x, y, w, h = [int(v) for v in box]
                    full_name = tracker_full_names[j]
                    frame_data[full_name] = {
                        "bbox": [x, y, w, h],
                        "center": [x + w / 2, y + h / 2],
                    }

                    cv2.rectangle(display_frame, (x, y), (x + w, y + h), (0, 255, 0), 2)
                    cv2.putText(display_frame, full_name, (x, y - 10), 0, 0.6, (0, 255, 0), 2)

                final_results[frame_name] = frame_data
                frame_idx += 1

                if frame_idx % AUTO_SAVE_INTERVAL == 0:
                    save_all_data(final_results, unique_labels, SAVE_PATH, MAP_PATH)
            else:
                print(">>> Target lost. Press SPACE to select again.")
                is_tracking = False
        else:
            cv2.putText(
                display_frame,
                "PAUSED: SPACE select / N skip / Q quit",
                (20, 50),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.7,
                (0, 0, 255),
                2,
            )

        cv2.imshow("SLAM Harvest Pro", display_frame)
        key = cv2.waitKey(1) & 0xFF

        if key == ord(" "):
            cv2.namedWindow("Select Logo", cv2.WINDOW_NORMAL | cv2.WINDOW_KEEPRATIO)
            cv2.resizeWindow("Select Logo", target_w, target_h)
            roi = cv2.selectROI("Select Logo", frame, False)
            cv2.destroyWindow("Select Logo")

            if roi != (0, 0, 0, 0):
                print(f"\n>>> ROI: {roi}")
                assigned_name = parse_label(input("Input label name (example: starbuck): "))

                tracker = create_single_tracker()
                ok = tracker.init(frame, roi)
                if ok is False:
                    print(">>> Failed to initialize tracker for this ROI.")
                    trackers = []
                    tracker_full_names = []
                    is_tracking = False
                else:
                    trackers = [tracker]
                    tracker_full_names = [assigned_name]
                    unique_labels.add(assigned_name)
                    is_tracking = True
                    print(f">>> Start tracking: {assigned_name}")
            else:
                print(">>> ROI selection cancelled.")
                trackers = []
                tracker_full_names = []
                is_tracking = False

        elif key == ord("n"):
            final_results[frame_name] = {}
            frame_idx += 1
            if frame_idx % AUTO_SAVE_INTERVAL == 0:
                save_all_data(final_results, unique_labels, SAVE_PATH, MAP_PATH)

        elif key == ord("q"):
            break

    save_all_data(final_results, unique_labels, SAVE_PATH, MAP_PATH)
    cv2.destroyAllWindows()
    print(f"\n>>> Finished. Saved {len(final_results)} frames.")


if __name__ == "__main__":
    run_interactive_harvest()
