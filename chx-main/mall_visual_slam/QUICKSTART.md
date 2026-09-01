# `mall_visual_slam` Quickstart

This is the visual-SLAM/BEV source tree on branch `main`. The tracked code is under `config/`, `launch/`, `scripts/`, and `src/`.

## Clean clone

```bash
git clone --branch main https://github.com/4dai-xyz/AR_Navigation_YPF.git
cd AR_Navigation_YPF/chx-main/mall_visual_slam
```

The clone does **not** contain the external DPVO, ORB-SLAM3, KV-Tracker, SAM2, or Pi3 source trees. It also does not contain the large `resources/` videos or generated `output/` trajectories mentioned by historical notes. Keep those assets in a separate local directory.

## First check

```bash
python -m compileall -q src scripts
```

Expected result: exit code `0`. For the people-BEV package, install `src/people_bev_tracker/requirements.txt` (and Ultralytics) before executing Python imports.

## First successful BEV run

Provide three local inputs: a video, a camera YAML, and a TUM-format pose file. Then run a short 30-frame job from this directory:

```bash
python src/people_bev_tracker/scripts/offline_pipeline.py \
  --video /data/input_video.mp4 \
  --calib config/KannalaBrandt8_1280x720.yaml \
  --pose /data/trajectory_tum.txt \
  --output-dir /data/people_bev_smoke \
  --max-frames 30
```

Expected result: `bev_tracking.mp4`, `debug_overlay.mp4`, `people_tracks.json`, and `camera_trajectory.json` in `/data/people_bev_smoke`. The complete video path has the same command without `--max-frames`; metric scale and accurate tracking still depend on the external pose, weights, and calibration.

ROS2 launch files are integration entry points, not a clone-only smoke test; they require a separately installed ROS2/ORB-SLAM3 workspace.
