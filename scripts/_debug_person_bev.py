"""诊断为什么 BEV 上看不到行人。对一个已知有人的帧做完整 trace。"""
import os, sys, cv2, numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import inference_bev as ib

TARGETS = [
    (r"H:\ForPengfei\04142024\Front\1\GX010027.MP4",                    12000),
    (r"H:\ForPengfei\04142024\Front\3\VID_20240414_205527_00_001.mp4", 12000),
]


def grab(p, idx):
    cap = cv2.VideoCapture(p)
    cap.set(cv2.CAP_PROP_POS_FRAMES, idx)
    ok, f = cap.read(); cap.release()
    return f if ok else None


def diag_one(INPUT, FRAME_IDX):
    print(f"\n{'='*80}\n  {INPUT}  @ frame {FRAME_IDX}\n{'='*80}")
    frame = grab(INPUT, FRAME_IDX)
    if frame is None:
        print("read failed"); return

    h0, w0 = frame.shape[:2]
    print(f"raw frame: {w0}x{h0}")
    if max(w0, h0) > ib.OUTPUT_MAX_LONG_SIDE:
        s = ib.OUTPUT_MAX_LONG_SIDE / max(w0, h0)
        new_w = int(round(w0 * s) / 2) * 2
        new_h = int(round(h0 * s) / 2) * 2
        frame = cv2.resize(frame, (new_w, new_h), interpolation=cv2.INTER_AREA)
        print(f"resized to: {new_w}x{new_h}")

    pred  = ib.segment_frame(frame)
    depth = ib.estimate_depth(frame)
    print(f"\nraw depth_disp: min={float(depth.min()):.3f}  max={float(depth.max()):.3f}  "
          f"mean={float(depth.mean()):.3f}  median={float(np.median(depth)):.3f}")
    p5, p25, p50, p75, p95 = np.percentile(depth, [5,25,50,75,95])
    print(f"raw disparity percentiles: p5={p5:.3f} p25={p25:.3f} p50={p50:.3f} p75={p75:.3f} p95={p95:.3f}")

    print(f"pred dtype={pred.dtype} shape={pred.shape}")
    person_pixels_full = (pred == 12).sum()
    print(f"person pixels (full res): {person_pixels_full}  ({person_pixels_full/pred.size*100:.2f}%)")

    # 模拟 compute_bev 内的下采样
    DS = ib.BEV_DOWNSAMPLE
    p_ds = pred[::DS, ::DS]
    d_ds = depth[::DS, ::DS]
    print(f"\n--- after downsample (BEV_DOWNSAMPLE={DS}) ---")
    print(f"downsampled shape: {p_ds.shape}")
    person_ds = (p_ds == 12).sum()
    print(f"person pixels (downsampled): {person_ds}")

    # 连通块统计
    pm = np.isin(p_ds, list(ib.PERSON_IDS)).astype(np.uint8)
    num, labels, stats, centroids = cv2.connectedComponentsWithStats(pm, connectivity=8)
    print(f"connected components (含背景): {num}")
    print(f"  阈值 min_area_px=120; BEV_RANGE_Z_M={ib.BEV_RANGE_Z_M} X={ib.BEV_RANGE_X_M}")

    h, w = p_ds.shape
    fx = w / (2.0 * np.tan(np.deg2rad(ib.CAM_FOV_H_DEG)/2.0))
    cx = w / 2.0
    Z = ib._depth_to_metric(d_ds)
    print(f"  Z global min/max/median = {Z.min():.2f} / {Z.max():.2f} / {np.median(Z):.2f}")

    for i in range(1, num):
        area = stats[i, cv2.CC_STAT_AREA]
        cu = centroids[i, 0]; cv_ = centroids[i, 1]
        region = labels == i
        z_region = Z[region]
        z_med = float(np.median(z_region)) if z_region.size else -1
        X = (cu - cx) * z_med / fx
        in_z = 0.1 < z_med <= ib.BEV_RANGE_Z_M
        in_x = abs(X) <= ib.BEV_RANGE_X_M
        passed = (area >= 120) and in_z and in_x
        marker = "OK" if passed else "SKIP"
        print(f"  CC{i:2d}  area={area:5d}  pix_centroid=({cu:6.1f},{cv_:6.1f})  "
              f"z_med={z_med:5.2f}m  X={X:+6.2f}m  in_z={in_z}  in_x={in_x}  [{marker}]")

    # 实际跑 compute_bev 并保存 BEV 大图（命名带 sub/帧）
    bev = ib.compute_bev(pred, depth)
    stem = f"_debug_bev_{os.path.basename(INPUT).rsplit('.',1)[0]}_f{FRAME_IDX:05d}.png"
    out = os.path.join(r"H:\ForPengfei\04142024\Front\output", stem)
    cv2.imwrite(out, cv2.resize(bev, (560, 560), interpolation=cv2.INTER_NEAREST))
    print(f"BEV image saved (zoomed): {out}")


if __name__ == "__main__":
    for p, fi in TARGETS:
        diag_one(p, fi)
