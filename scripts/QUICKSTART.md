# `scripts/` Quickstart

This directory contains the repository-level offline vision experiments. Use the `main` branch.

## Clean clone

```powershell
git clone --branch main https://github.com/4dai-xyz/AR_Navigation_YPF.git
cd AR_Navigation_YPF
```

The source stays in the cloned repository; run commands from its root. Python 3.10+ is recommended. Install the dependencies required by the experiment you select (the scripts do not vendor model weights).

## First check

Run this before downloading models or preparing a session:

```powershell
python -m compileall -q scripts
```

Expected result: no output and exit code `0`. There is no checked-in sample video/session, so a full inference run cannot be reproduced from a clone alone.

## First real run

Prepare a local Rokid session outside Git (containing `video.mp4`, `frames.csv`, and `imu.jsonl`), then run the diagnostic:

```powershell
python scripts/diag_session.py "D:\data\session_001"
```

Expected result: frame/IMU counts, timestamp intervals, and static-segment statistics. Use the reported session path as input to the conversion/COLMAP scripts. `inference_bev_pose.py` additionally needs COLMAP/DPVO outputs, model weights, and a CUDA-compatible PyTorch environment.

Do not rely on the old absolute paths embedded in some scripts; pass an explicit session/output path or update the constants at the top of the selected script.
