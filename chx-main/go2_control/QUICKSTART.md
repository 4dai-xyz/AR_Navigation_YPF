# `go2_control` Quickstart

This repository contains Go2 scripts, demos, and documentation. The Unitree SDK2, MuJoCo installation, Isaac Sim/Lab, ROS2 workspace, robot assets, and environments are external dependencies; they are not part of this clone. Use branch `main`.

## Clean clone

```bash
git clone --branch main https://github.com/4dai-xyz/AR_Navigation_YPF.git
cd AR_Navigation_YPF/chx-main/go2_control
```

Keep the checkout at the path supplied through `UNITREE_DEV_ROOT` (or update that variable) when using the shell wrappers. Do not assume the historical `/home/ros/unitree_dev` path exists.

## First check

```bash
python -m compileall -q projects scripts
bash -n scripts/*.sh
```

Expected result: both commands exit `0`. This is the only clone-only test because simulator/SDK assets are external.

## First simulator run

After installing Unitree MuJoCo/SDK2 and creating its Python environment, set the workspace root and run the included headless stability test:

```bash
export UNITREE_DEV_ROOT=/path/to/unitree_dev
bash scripts/run_go2_cmd_vel_smoke_test.sh
```

If your Unitree sources live elsewhere, point `UNITREE_DEV_ROOT` there and run the corresponding scripts from that workspace. A successful test reports the Go2 control loop completing without a physics/runtime error; it does not move a real robot. Start real-robot commands only after verifying the network and reviewing the safety notes in `notes/go2_quickstart.md`.
