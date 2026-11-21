#!/usr/bin/env python3
"""
Independent reference cross-check for the notebook-ai OCR pipeline (diagnostic only, never imported by the app).

Reference = upstream PaddleOCR v3.7.0 rules re-implemented here from the upstream sources
(tools/infer/utility.py get_rotate_crop_image, tools/infer/predict_rec.py resize_norm_img + batching,
ppocr/data/imaug/operators.py DetResizeForTest/NormalizeImage/ToCHWImage), using cv2 for the operations upstream
uses cv2 for. It consumes the raw stage dumps written by `./gradlew crossCheckDump` so that EVERY stage is
compared on identical input data (a difference is therefore localized to that stage).

Tolerances (fixed BEFORE looking at results; rationale in HANDOFF.md):
  decode   (JPEG: ImageIO vs libjpeg)   mean|d| <= 1.0 level, p99.9 <= 6 levels
  rotate   (integers)                   exact
  det_in   (resize+normalize, float)    shape exact, mean|d| <= 0.01, max <= 0.10   (1 level = 0.0174)
  det_inf  (same tensor, ORT 1.22/1.24) max|d| <= 5e-3, binarized(>0.2) pixel disagreement <= 0.01%
  warp     (INTER_CUBIC, 8-bit)         shape exact, mean|d| <= 1.5 levels, p99.9 <= 10
  rec_in   (resize+pad+normalize)       shape exact, pad exactly 0, mean|d| <= 0.004, max <= 0.06  (1 level = 0.0078)
  rec_inf  (same tensor)                max|d| <= 1e-3 and identical decoded text
"""
import json, math, os, sys
import numpy as np
import cv2
import onnxruntime as ort
import yaml

MODELS = os.environ.get("XC_MODELS", "models")


def load_u8(path, w, h):
    return np.fromfile(path, dtype=np.uint8).reshape(h, w, 3)


def load_f32(path, shape):
    return np.fromfile(path, dtype="<f4").reshape(shape)


def diff(a, b):
    a = a.astype(np.float64); b = b.astype(np.float64)
    d = np.abs(a - b)
    return dict(shape=list(a.shape), mean=float(d.mean()), p999=float(np.percentile(d, 99.9)), max=float(d.max()),
                frac_diff=float((d > 0).mean()))


def verdict(ok):
    return "PASS" if ok else "FAIL"


# ---------------- upstream re-implementations ----------------
def up_det_input(img):
    h, w = img.shape[:2]
    limit = 736
    ratio = 1.0
    if min(h, w) < limit:
        ratio = float(limit) / h if h < w else float(limit) / w
    rh, rw = int(h * ratio), int(w * ratio)
    if max(rh, rw) > 4000:
        ratio = float(4000) / max(rh, rw)
        rh, rw = int(rh * ratio), int(rw * ratio)
    rh = max(int(round(rh / 32) * 32), 32)   # Python round(): half to even
    rw = max(int(round(rw / 32) * 32), 32)
    r = cv2.resize(img, (int(rw), int(rh)))
    mean = np.array([0.485, 0.456, 0.406]).reshape(1, 1, 3).astype("float32")
    std = np.array([0.229, 0.224, 0.225]).reshape(1, 1, 3).astype("float32")
    x = (r.astype("float32") * np.float32(1.0 / 255.0) - mean) / std
    return x.transpose((2, 0, 1))[None], (rh, rw)


def up_rotate_crop(img, points):
    points = np.array(points, dtype=np.float32)
    cw = int(max(np.linalg.norm(points[0] - points[1]), np.linalg.norm(points[2] - points[3])))
    ch = int(max(np.linalg.norm(points[0] - points[3]), np.linalg.norm(points[1] - points[2])))
    std = np.float32([[0, 0], [cw, 0], [cw, ch], [0, ch]])
    M = cv2.getPerspectiveTransform(points, std)
    dst = cv2.warpPerspective(img, M, (cw, ch), borderMode=cv2.BORDER_REPLICATE, flags=cv2.INTER_CUBIC)
    if dst.shape[0] * 1.0 / dst.shape[1] >= 1.5:
        dst = np.rot90(dst)
    return np.ascontiguousarray(dst)


def up_resize_norm(img, max_wh_ratio, imgH=48, imgC=3):
    imgW = int(imgH * max_wh_ratio)            # ONNX dynamic width: upstream keeps this
    h, w = img.shape[:2]
    ratio = w / float(h)
    resized_w = imgW if math.ceil(imgH * ratio) > imgW else int(math.ceil(imgH * ratio))
    r = cv2.resize(img, (resized_w, imgH)).astype("float32")
    r = r.transpose((2, 0, 1)) / 255
    r -= 0.5
    r /= 0.5
    pad = np.zeros((imgC, imgH, imgW), dtype=np.float32)
    pad[:, :, 0:resized_w] = r
    return pad, resized_w


def load_table():
    with open(os.path.join(MODELS, "rec.yml")) as f:
        cfg = yaml.safe_load(f)
    chars = cfg["PostProcess"]["character_dict"]
    return ["blank"] + [str(c) for c in chars] + [" "]


def ctc_decode(logits, table):
    idx = logits.argmax(axis=1)
    prob = logits.max(axis=1)
    out, conf, prev = [], [], -1
    for t, i in enumerate(idx):
        if i != prev and i != 0:
            out.append(table[i]); conf.append(prob[t])
        prev = i
    return "".join(out), (float(np.mean(conf)) if conf else 0.0)


def rec_session():
    so = ort.SessionOptions()
    so.intra_op_num_threads = 4
    return ort.InferenceSession(os.path.join(MODELS, "rec.onnx"), so, providers=["CPUExecutionProvider"])


def run_rec(sess, x):
    return sess.run(None, {sess.get_inputs()[0].name: x})[0]


def main(dump_dir, source_jpg):
    meta = {}
    regions = {}
    for line in open(os.path.join(dump_dir, "meta.txt")):
        k, _, v = line.rstrip("\n").partition("=")
        meta[k] = v
    ww, wh = [int(x) for x in meta["working"].split("x")]
    dw, dh = [int(x) for x in meta["decoded"].split("x")]
    rotate = int(meta["rotate"])
    n = int(meta["regions"])
    out = {"image": meta["image"], "versions": {"onnxruntime": ort.__version__, "cv2": cv2.__version__,
                                                 "numpy": np.__version__}}
    print(f"== {meta['image']} rotate={rotate} decoded={dw}x{dh} working={ww}x{wh} regions={n}")
    print(f"   reference: onnxruntime {ort.__version__}, opencv {cv2.__version__}, numpy {np.__version__}")

    # S0a decode
    jdec = load_u8(os.path.join(dump_dir, "decoded.u8"), dw, dh)
    ref = cv2.imread(source_jpg)
    d = diff(ref, jdec) if ref.shape == jdec.shape else {"shape_mismatch": [list(ref.shape), list(jdec.shape)]}
    ok = ref.shape == jdec.shape and d["mean"] <= 1.0 and d["p999"] <= 6
    print(f"S0a decode  (cv2.imread vs Java ImageIO, BGR) {verdict(ok)} {d}")
    out["decode"] = d
    # S0b rotation (cv2/np reference on Java's own decoded pixels)
    jwork = load_u8(os.path.join(dump_dir, "working.u8"), ww, wh)
    refw = np.rot90(jdec, k=rotate % 4 and (4 - rotate) % 4) if rotate else jdec
    d = diff(refw, jwork) if refw.shape == jwork.shape else {"shape_mismatch": [list(refw.shape), list(jwork.shape)]}
    ok = refw.shape == jwork.shape and d.get("max", 1) == 0
    print(f"S0b rotate  (np.rot90 CCW x{(4-rotate)%4} vs Java WorkingImage) {verdict(ok)} {d}")
    out["rotate"] = d

    # S1 detector input
    jin = load_f32(os.path.join(dump_dir, "det_in.f32"), [int(x) for x in meta["det_in"].split(",")])
    rin, (rh, rw) = up_det_input(jwork)
    ok = rin.shape == jin.shape
    d = diff(rin, jin) if ok else {"shape_mismatch": [list(rin.shape), list(jin.shape)]}
    ok = ok and d["mean"] <= 0.01 and d["max"] <= 0.10
    print(f"S1  det input (upstream resize+normalize vs Java) {verdict(ok)} ref_shape={list(rin.shape)} {d}")
    out["det_in"] = d

    # S2 detector inference on the SAME tensor (low-memory settings)
    so = ort.SessionOptions()
    so.enable_cpu_mem_arena = False
    so.enable_mem_pattern = False
    so.intra_op_num_threads = 4
    dsess = ort.InferenceSession(os.path.join(MODELS, "det.onnx"), so, providers=["CPUExecutionProvider"])
    pr = dsess.run(None, {dsess.get_inputs()[0].name: jin})[0][0, 0]
    jp = load_f32(os.path.join(dump_dir, "det_prob.f32"), [int(x) for x in meta["det_prob"].split(",")])
    dd = np.abs(pr - jp)
    bin_dis = float(((pr > 0.2) != (jp > 0.2)).mean())
    ok = dd.max() <= 5e-3 and bin_dis <= 1e-4
    print(f"S2  det inference same tensor (py ORT vs Java ORT) {verdict(ok)} max|d|={dd.max():.2e} mean|d|={dd.mean():.2e} "
          f"binarized(>0.2) disagreement={bin_dis:.2e}")
    out["det_inf"] = dict(max=float(dd.max()), mean=float(dd.mean()), bin_disagree=bin_dis)
    del dsess, pr, jp, jin, rin

    table = load_table()
    sess = rec_session()
    crops_py = []
    texts_java = []
    for i in range(n):
        m = [l for l in open(os.path.join(dump_dir, "meta.txt")) if l.startswith(f"r{i}=")][0]
        cw, ch = [int(x) for x in m.split("crop ")[1].split(" ")[0].split("x")]
        rin_w = int(m.split("recin_w=")[1].split()[0])
        T, C = [int(x) for x in m.split("logits=")[1].split()[0].split(",")]
        text_java = m.split(" text=")[1].rstrip("\n")
        quad = np.loadtxt(os.path.join(dump_dir, f"r{i}_quad.txt"))
        jcrop_raw = np.fromfile(os.path.join(dump_dir, f"r{i}_crop.u8"), dtype=np.uint8)
        jcrop = jcrop_raw.reshape(-1, 3) if False else None
        # S3 warp: Java crop may be rot90'd; compare against the same upstream function output.
        pyc = up_rotate_crop(jwork, quad)
        jc = jcrop_raw.reshape(pyc.shape[0], pyc.shape[1], 3) if jcrop_raw.size == pyc.size else None
        if jc is None:
            print(f"R{i} S3 warp  FAIL size mismatch java={jcrop_raw.size} ref={pyc.size}")
            continue
        dw_ = diff(pyc, jc)
        ok3 = dw_["mean"] <= 1.5 and dw_["p999"] <= 10
        # S4a rec preprocess on IDENTICAL (Java) crop; S4b on the Python crop
        jrecin = load_f32(os.path.join(dump_dir, f"r{i}_recin.f32"), [3, 48, rin_w])
        mw = max(320 / 48, jc.shape[1] / jc.shape[0])
        ra, rws = up_resize_norm(jc, mw)
        ok4 = ra.shape == jrecin.shape
        d4 = diff(ra, jrecin) if ok4 else {"shape_mismatch": [list(ra.shape), list(jrecin.shape)]}
        pad_exact = ok4 and bool((ra[:, :, rws:] == 0).all() and (jrecin[:, :, rws:] == 0).all())
        ok4 = ok4 and pad_exact and d4["mean"] <= 0.004 and d4["max"] <= 0.06
        # S5 inference on the SAME Java tensor
        jl = load_f32(os.path.join(dump_dir, f"r{i}_logits.f32"), [T, C])
        pl = run_rec(sess, jrecin[None])[0]
        dl = np.abs(pl - jl)
        txt_py_same, _ = ctc_decode(pl, table)
        txt_jv, _ = ctc_decode(jl, table)
        ok5 = pl.shape == jl.shape and dl.max() <= 1e-3 and txt_py_same == txt_jv
        # S6 full Python path (own crop -> own preprocess -> ORT), batch=1
        pr_in, _ = up_resize_norm(pyc, max(320 / 48, pyc.shape[1] / pyc.shape[0]))
        plb1 = run_rec(sess, pr_in[None])[0]
        txt_b1, _ = ctc_decode(plb1, table)
        crops_py.append((i, pyc))
        texts_java.append(text_java)
        print(f"R{i} {cw}x{ch}->recin_w={rin_w} | S3 warp {verdict(ok3)} mean={dw_['mean']:.3f} p99.9={dw_['p999']:.1f} "
              f"max={dw_['max']:.0f} | S4a rec_in {verdict(ok4)} shape={list(ra.shape)} mean={d4.get('mean', -1):.5f} "
              f"max={d4.get('max', -1):.4f} pad0={pad_exact} | S5 rec_inf {verdict(ok5)} max|d|={dl.max():.2e} "
              f"same-text={txt_py_same == txt_jv} | S6 py-bs1='{txt_b1}' java='{text_java}' same={txt_b1 == text_java}")
        out.setdefault("regions", []).append(dict(i=i, warp=dw_, rec_in=d4, rec_inf_max=float(dl.max()),
                                                  text_same_tensor=txt_py_same == txt_jv, py_bs1=txt_b1,
                                                  java=text_java))

    # S7 upstream batching (rec_batch_num=6, sorted by aspect ratio, pad to batch max_wh_ratio)
    order = sorted(range(len(crops_py)), key=lambda k: crops_py[k][1].shape[1] / crops_py[k][1].shape[0])
    batched = {}
    for b in range(0, len(order), 6):
        grp = [crops_py[k] for k in order[b:b + 6]]
        mw = 320 / 48
        for _, c in grp:
            mw = max(mw, c.shape[1] / c.shape[0])
        xs = np.stack([up_resize_norm(c, mw)[0] for _, c in grp])
        res = run_rec(sess, xs)
        for (i, _), lg in zip(grp, res):
            batched[i] = ctc_decode(lg, table)[0]
    print("S7  upstream batching (6/batch, pad to batch max width) vs Java batch=1:")
    for i, (idx, _) in enumerate(crops_py):
        print(f"    R{idx}: batched='{batched[idx]}'  java='{texts_java[i]}'  same={batched[idx] == texts_java[i]}")
    out["batched"] = {str(k): v for k, v in batched.items()}
    json.dump(out, open(os.path.join(dump_dir, "xcheck_summary.json"), "w"), indent=1)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
