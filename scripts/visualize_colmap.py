"""可视化 COLMAP SfM 结果：3D 轨迹 + 稀疏点云 + BEV 顶视图。

读取 sparse_txt/ 下的 cameras.txt / images.txt / points3D.txt
"""
import os
import numpy as np
import matplotlib.pyplot as plt
from mpl_toolkits.mplot3d import Axes3D  # noqa: F401

SESSION    = r"G:\ARProjects\AR_Navigation\4dai-glasses-main\4dai-glasses-main\android\session_20260707_163809_054"
TXT_DIR    = os.path.join(SESSION, "colmap", "sparse_txt")
OUT_FIG    = os.path.join(SESSION, "colmap", "trajectory.png")


def parse_cameras(path):
    cams = {}
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            if line.startswith("#") or not line.strip(): continue
            toks = line.split()
            cams[int(toks[0])] = {
                "model": toks[1],
                "width": int(toks[2]),
                "height": int(toks[3]),
                "params": [float(x) for x in toks[4:]],
            }
    return cams


def parse_images(path):
    """返回 list of dict: {name, qvec, tvec, cam_center}. images.txt 每 2 行一条."""
    entries = []
    with open(path, "r", encoding="utf-8") as f:
        lines = [l for l in f if not l.startswith("#") and l.strip()]
    it = iter(lines)
    for hdr in it:
        toks = hdr.split()
        image_id = int(toks[0])
        qw, qx, qy, qz = map(float, toks[1:5])
        tx, ty, tz     = map(float, toks[5:8])
        cam_id         = int(toks[8])
        name           = toks[9]
        try:
            next(it)     # 跳过 POINTS2D 行
        except StopIteration:
            pass
        # world->camera: R_wc, t_wc；相机中心 c = -R_wc^T @ t_wc
        # qvec 是 world->camera 四元数 (w, x, y, z)
        R = qvec_to_R(qw, qx, qy, qz)
        t = np.array([tx, ty, tz])
        cam_center = -R.T @ t
        entries.append({
            "id": image_id, "name": name, "cam_id": cam_id,
            "R_wc": R, "t_wc": t, "center": cam_center,
            "quat": (qw, qx, qy, qz),
        })
    return entries


def qvec_to_R(qw, qx, qy, qz):
    R = np.array([
        [1-2*qy*qy-2*qz*qz, 2*qx*qy-2*qz*qw,   2*qx*qz+2*qy*qw],
        [2*qx*qy+2*qz*qw,   1-2*qx*qx-2*qz*qz, 2*qy*qz-2*qx*qw],
        [2*qx*qz-2*qy*qw,   2*qy*qz+2*qx*qw,   1-2*qx*qx-2*qy*qy],
    ])
    return R


def parse_points3D(path):
    ids, xyz, rgb, err = [], [], [], []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            if line.startswith("#") or not line.strip(): continue
            toks = line.split()
            ids.append(int(toks[0]))
            xyz.append([float(toks[1]), float(toks[2]), float(toks[3])])
            rgb.append([int(toks[4]), int(toks[5]), int(toks[6])])
            err.append(float(toks[7]))
    return np.array(ids), np.array(xyz), np.array(rgb), np.array(err)


def main():
    cams   = parse_cameras(os.path.join(TXT_DIR, "cameras.txt"))
    images = parse_images(os.path.join(TXT_DIR, "images.txt"))
    ids, xyz, rgb, err = parse_points3D(os.path.join(TXT_DIR, "points3D.txt"))

    print(f"cameras: {len(cams)}")
    for k, c in cams.items():
        print(f"  cam {k}: {c['model']} {c['width']}x{c['height']}  params={c['params']}")
    print(f"images registered: {len(images)} / {len([f for f in os.listdir(os.path.join(SESSION,'colmap','images')) if f.endswith('.jpg')])}")
    print(f"3D points: {len(xyz)}")
    if len(err):
        print(f"reproj_error: mean={err.mean():.3f}px  median={np.median(err):.3f}px  p95={np.percentile(err,95):.3f}px")

    # 按 image name 排序（时间顺序）
    images.sort(key=lambda x: x["name"])
    centers = np.array([im["center"] for im in images])
    if len(centers) < 2:
        print("轨迹点不足，跳过绘图")
        return

    # 过滤离群点（用中位距离 5x 筛）
    med = np.median(np.linalg.norm(xyz - xyz.mean(0), axis=1))
    dist = np.linalg.norm(xyz - xyz.mean(0), axis=1)
    keep = dist < 5 * med
    pts  = xyz[keep]
    cols = rgb[keep] / 255.0

    fig = plt.figure(figsize=(15, 6))

    # 3D 视图
    ax1 = fig.add_subplot(1, 2, 1, projection="3d")
    ax1.scatter(pts[:, 0], pts[:, 2], -pts[:, 1], c=cols, s=1, alpha=0.4)
    ax1.plot(centers[:, 0], centers[:, 2], -centers[:, 1], "r-", lw=2, label="camera trajectory")
    ax1.scatter(centers[0, 0], centers[0, 2], -centers[0, 1], c="green", s=80, label="start", zorder=5)
    ax1.scatter(centers[-1, 0], centers[-1, 2], -centers[-1, 1], c="black", s=80, label="end", zorder=5)
    ax1.set_xlabel("X"); ax1.set_ylabel("Z (forward)"); ax1.set_zlabel("-Y (up)")
    ax1.set_title("3D sparse + camera trajectory")
    ax1.legend()

    # BEV 顶视图 (X-Z 平面)
    ax2 = fig.add_subplot(1, 2, 2)
    ax2.scatter(pts[:, 0], pts[:, 2], c=cols, s=1, alpha=0.4)
    ax2.plot(centers[:, 0], centers[:, 2], "r-", lw=2, label="camera trajectory")
    ax2.scatter(centers[0, 0], centers[0, 2], c="green", s=80, label="start", zorder=5)
    ax2.scatter(centers[-1, 0], centers[-1, 2], c="black", s=80, label="end", zorder=5)
    ax2.set_xlabel("X (right)"); ax2.set_ylabel("Z (forward)")
    ax2.set_title("BEV (top-down)")
    ax2.set_aspect("equal")
    ax2.grid(alpha=0.3); ax2.legend()

    plt.tight_layout()
    plt.savefig(OUT_FIG, dpi=140)
    print(f"\n已保存: {OUT_FIG}")


if __name__ == "__main__":
    main()
