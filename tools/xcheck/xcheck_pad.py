#!/usr/bin/env python3
"""
Deterministic padding-width diagnostic (compatibility study, not a tuning tool). For IDENTICAL crop bytes (the production
crops exported by crossCheckDump) the recognizer input is built at several padded widths using the upstream
resize_norm_img rule, and the same rec.onnx is run each time:
  - own     : int(48*max(320/48, w/h))   = what the app does (batch of 1; upstream `--rec_batch_num 1`)
  - batched : width implied by upstream's default batching (rec_batch_num=6, crops sorted by aspect ratio, each batch
              padded to its max wh ratio)
  - fixed   : 320, 480, 640, 960 (reference grid, independent of any expected text)
Reported per crop: tensor shape, resized width, zero-pad columns, decoded text, and the max |logit diff| and number of
argmax changes over the frames that cover the VALID (non-padded) columns versus the 'own' run. Nothing here selects a
width: no expected text and no confidence is used.
Pad value: after normalization the pad is exactly 0.0 (= source gray level 127.5), not white.
"""
import math, os, sys
import numpy as np
sys.path.insert(0, os.path.dirname(__file__))
import xcheck as X


def run(sess, crop, imgW):
    mw = imgW / 48.0
    x, rw = X.up_resize_norm(crop, mw)
    assert x.shape[2] == imgW, (x.shape, imgW)
    return X.run_rec(sess, x[None])[0], rw


def main(dump_dir):
    meta = {}
    lines = open(os.path.join(dump_dir, "meta.txt")).read().splitlines()
    n = int([l for l in lines if l.startswith("regions=")][0].split("=")[1])
    crops = []
    for i in range(n):
        m = [l for l in lines if l.startswith(f"r{i}=")][0]
        cw, ch = [int(v) for v in m.split("crop ")[1].split(" ")[0].split("x")]
        raw = np.fromfile(os.path.join(dump_dir, f"r{i}_crop.u8"), dtype=np.uint8).reshape(ch, cw, 3)
        crops.append(raw)
    table = X.load_table()
    sess = X.rec_session()
    order = sorted(range(n), key=lambda k: crops[k].shape[1] / crops[k].shape[0])
    batched_w = {}
    for b in range(0, n, 6):
        grp = order[b:b + 6]
        mw = 320 / 48
        for k in grp:
            mw = max(mw, crops[k].shape[1] / crops[k].shape[0])
        for k in grp:
            batched_w[k] = int(48 * mw)
    print(f"{n} crops; upstream batching groups: {[order[b:b+6] for b in range(0, n, 6)]}")
    for i, c in enumerate(crops):
        h, w = c.shape[:2]
        own = int(48 * max(320 / 48, w / h))
        widths = sorted({own, batched_w[i], 320, 480, 640, 960})
        base, rw = run(sess, c, own)
        txt0 = X.ctc_decode(base, table)[0]
        valid_frames = int(math.ceil(rw * base.shape[0] / own))
        print(f"R{i} crop {w}x{h}  resized_w={rw}  own_pad_width={own}  batched_pad_width={batched_w[i]}  own text='{txt0}'")
        for W in widths:
            lg, _ = run(sess, c, W)
            t = X.ctc_decode(lg, table)[0]
            vf = min(valid_frames, lg.shape[0], base.shape[0])
            dmax = float(np.abs(lg[:vf] - base[:vf]).max())
            ch_ = int((lg[:vf].argmax(1) != base[:vf].argmax(1)).sum())
            tag = ("own" if W == own else "") + ("+batched" if W == batched_w[i] and W != own else ("/batched" if W == batched_w[i] else ""))
            print(f"     W={W:<4} tensor=[1,3,48,{W}] pad_cols={W - rw:<4} {tag:<9} max|dlogit| on {vf} valid frames={dmax:.4f}  "
                  f"argmax-changes={ch_:<2} text='{t}'  same-as-own={t == txt0}")


if __name__ == "__main__":
    main(sys.argv[1])
