"""快速验证 CLAHE 暗光增强。对几个典型帧并排显示 原图 | CLAHE。"""
import os, sys, cv2, numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import inference_bev as ib

TARGETS = [
    (r"H:\ForPengfei\04142024\Front\1\GX010027.MP4",                    500),
    (r"H:\ForPengfei\04142024\Front\1\GX010027.MP4",                   5000),
    (r"H:\ForPengfei\04142024\Front\1\GX010027.MP4",                  12000),
    (r"H:\ForPengfei\04142024\Front\3\VID_20240414_205527_00_001.mp4",  500),
    (r"H:\ForPengfei\04142024\Front\3\VID_20240414_205527_00_001.mp4", 5000),
]

OUT = r"H:\ForPengfei\04142024\Front\output\_smoke_clahe"


def grab(p, idx):
    c = cv2.VideoCapture(p); c.set(cv2.CAP_PROP_POS_FRAMES, idx)
    ok, f = c.read(); c.release()
    if not ok or f is None: return None
    h0, w0 = f.shape[:2]
    if max(w0, h0) > ib.OUTPUT_MAX_LONG_SIDE:
        s = ib.OUTPUT_MAX_LONG_SIDE / max(w0, h0)
        f = cv2.resize(f, (int(round(w0*s)/2)*2, int(round(h0*s)/2)*2),
                       interpolation=cv2.INTER_AREA)
    return f


def label(img, text):
    o = img.copy()
    cv2.rectangle(o, (0, 0), (640, 60), (0, 0, 0), -1)
    cv2.putText(o, text, (10, 45), cv2.FONT_HERSHEY_SIMPLEX, 1.1, (0,255,255), 3)
    return o


def main():
    os.makedirs(OUT, exist_ok=True)
    for vp, idx in TARGETS:
        f = grab(vp, idx)
        if f is None: print(f"skip {vp}:{idx}"); continue
        lab = cv2.cvtColor(f, cv2.COLOR_BGR2LAB)
        L_mean = float(lab[..., 0].mean())
        enhanced = ib.maybe_enhance_for_model(f)
        triggered = enhanced is not f and not np.array_equal(enhanced, f)
        # 始终也强制跑一次 CLAHE 看看效果
        l2 = cv2.cvtColor(f, cv2.COLOR_BGR2LAB)
        L,A,B = cv2.split(l2)
        L_force = ib._clahe.apply(L)
        forced = cv2.cvtColor(cv2.merge([L_force, A, B]), cv2.COLOR_LAB2BGR)

        h = f.shape[0]; sep = np.full((h, 6, 3), 255, np.uint8)
        name = os.path.basename(vp).rsplit('.',1)[0]
        row = np.hstack([
            label(f, f"{name}@{idx}  L_mean={L_mean:.0f}  auto={'ON' if triggered else 'OFF'}"),
            sep, label(enhanced, "AUTO"), sep, label(forced, "FORCED")
        ])
        out = os.path.join(OUT, f"{name}_f{idx:05d}.jpg")
        cv2.imwrite(out, row); print("写入:", out, " L_mean=", f"{L_mean:.1f}", " triggered=", triggered)


if __name__ == "__main__":
    main()
