#!/usr/bin/env python3
"""
Detector post-processing cross-check (diagnostic only). Reference = upstream PaddleOCR v3.7.0
ppocr/postprocess/db_postprocess.py (DBPostProcess.boxes_from_bitmap, quad path) re-implemented here from the
source, plus tools/infer/predict_det.py (order_points_clockwise, clip_det_res, filter_tag_det_res) and
tools/infer/predict_system.py (sorted_boxes). Inputs = the Java detector probability map and the Java stage dump
(`crossCheckDump` -> det_prob.f32, det_stages.txt), so every stage is compared on the IDENTICAL probability map.

Parameters = models/det.yml pairing used by Java: thresh 0.2, box_thresh 0.45, max_candidates 3000, unclip 1.4,
min_size 3. score_mode: Java uses SLOW (documented deviation); both modes are run on the reference.

Tolerances (fixed before running):
  mask      exact pixel count
  contours  same SET of outer components (matched by bbox); vertex sets compared as polygons (start vertex /
            direction / SIMPLE-compression differences are equivalent): identical filled area
  minAreaRect (mini box)  corner distance <= 0.51 px after canonical ordering (rotating calipers vs cv2.minAreaRect)
  score     |d| <= 0.005
  unclip    expanded min box corners <= 1.0 px (pyclipper integer quantisation vs exact offset)
  final     scaled int corners: |d| <= 1 px, exact-equality count reported
"""
import math, os, sys
import numpy as np
import cv2
import pyclipper
from shapely.geometry import Polygon

THRESH, BOX_THRESH, MAXC, UNCLIP, MIN_SIZE = 0.2, 0.45, 3000, 1.4, 3


def get_mini_boxes(contour):
    bb = cv2.minAreaRect(contour)
    pts = sorted(list(cv2.boxPoints(bb)), key=lambda x: x[0])
    i1, i2, i3, i4 = 0, 1, 2, 3
    if pts[1][1] > pts[0][1]:
        i1, i4 = 0, 1
    else:
        i1, i4 = 1, 0
    if pts[3][1] > pts[2][1]:
        i2, i3 = 2, 3
    else:
        i2, i3 = 3, 2
    return [pts[i1], pts[i2], pts[i3], pts[i4]], min(bb[1])


def box_score_fast(bitmap, box):
    h, w = bitmap.shape[:2]
    box = box.copy()
    xmin = np.clip(np.floor(box[:, 0].min()).astype("int32"), 0, w - 1)
    xmax = np.clip(np.ceil(box[:, 0].max()).astype("int32"), 0, w - 1)
    ymin = np.clip(np.floor(box[:, 1].min()).astype("int32"), 0, h - 1)
    ymax = np.clip(np.ceil(box[:, 1].max()).astype("int32"), 0, h - 1)
    mask = np.zeros((ymax - ymin + 1, xmax - xmin + 1), dtype=np.uint8)
    box[:, 0] -= xmin
    box[:, 1] -= ymin
    cv2.fillPoly(mask, box.reshape(1, -1, 2).astype("int32"), 1)
    return cv2.mean(bitmap[ymin:ymax + 1, xmin:xmax + 1], mask)[0]


def box_score_slow(bitmap, contour):
    h, w = bitmap.shape[:2]
    contour = np.reshape(contour.copy(), (-1, 2))
    xmin = np.clip(np.min(contour[:, 0]), 0, w - 1)
    xmax = np.clip(np.max(contour[:, 0]), 0, w - 1)
    ymin = np.clip(np.min(contour[:, 1]), 0, h - 1)
    ymax = np.clip(np.max(contour[:, 1]), 0, h - 1)
    mask = np.zeros((ymax - ymin + 1, xmax - xmin + 1), dtype=np.uint8)
    contour[:, 0] -= xmin
    contour[:, 1] -= ymin
    cv2.fillPoly(mask, contour.reshape(1, -1, 2).astype("int32"), 1)
    return cv2.mean(bitmap[ymin:ymax + 1, xmin:xmax + 1], mask)[0]


def unclip(box):
    poly = Polygon(box)
    distance = poly.area * UNCLIP / poly.length
    off = pyclipper.PyclipperOffset()
    off.AddPath(box, pyclipper.JT_ROUND, pyclipper.ET_CLOSEDPOLYGON)
    return off.Execute(distance)


def reference_boxes(pred, dest_w, dest_h, score_mode):
    bitmap = pred > THRESH
    height, width = bitmap.shape
    contours, _ = cv2.findContours((bitmap * 255).astype(np.uint8), cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    recs = []
    for index in range(min(len(contours), MAXC)):
        contour = contours[index]
        rec = dict(index=index, contour=contour)
        points, sside = get_mini_boxes(contour)
        rec["mini"], rec["side"] = np.array(points), sside
        if sside < MIN_SIZE:
            rec["status"] = "min_size"; recs.append(rec); continue
        pts = np.array(points)
        score = box_score_fast(pred, pts.reshape(-1, 2)) if score_mode == "fast" else box_score_slow(pred, contour)
        rec["score"] = score
        if BOX_THRESH > score:
            rec["status"] = "box_thresh"; recs.append(rec); continue
        box = unclip(pts)
        if len(box) > 1:
            rec["status"] = "unclip_multi"; recs.append(rec); continue
        box = np.array(box).reshape(-1, 1, 2)
        mini2, side2 = get_mini_boxes(box)
        rec["mini2"] = np.array(mini2)
        if side2 < MIN_SIZE + 2:
            rec["status"] = "min_size_after"; recs.append(rec); continue
        b = np.array(mini2)
        b[:, 0] = np.clip(np.round(b[:, 0] / width * dest_w), 0, dest_w)   # np.round: half to even
        b[:, 1] = np.clip(np.round(b[:, 1] / height * dest_h), 0, dest_h)
        rec["final_raw"] = b.astype("int32")
        rec["status"] = "accepted"
        recs.append(rec)
    return recs


def order_points_clockwise(pts):
    rect = np.zeros((4, 2), dtype="float32")
    s = pts.sum(axis=1)
    rect[0] = pts[np.argmin(s)]
    rect[2] = pts[np.argmax(s)]
    tmp = np.delete(pts, (np.argmin(s), np.argmax(s)), axis=0)
    diff = np.diff(np.array(tmp), axis=1)
    rect[1] = tmp[np.argmin(diff)]
    rect[3] = tmp[np.argmax(diff)]
    return rect


def filter_tag_det_res(boxes, h, w):
    out = []
    for box in boxes:
        box = order_points_clockwise(np.array(box))
        for p in range(box.shape[0]):
            box[p, 0] = int(min(max(box[p, 0], 0), w - 1))
            box[p, 1] = int(min(max(box[p, 1], 0), h - 1))
        if int(np.linalg.norm(box[0] - box[1])) <= 3 or int(np.linalg.norm(box[0] - box[3])) <= 3:
            continue
        out.append(box)
    return out


def sorted_boxes(boxes):
    sb = sorted(boxes, key=lambda x: (x[0][1], x[0][0]))
    b = list(sb)
    for i in range(len(b) - 1):
        for j in range(i, -1, -1):
            if abs(b[j + 1][0][1] - b[j][0][1]) < 10 and b[j + 1][0][0] < b[j][0][0]:
                b[j], b[j + 1] = b[j + 1], b[j]
            else:
                break
    return b


def parse_pts(s):
    return np.array([[float(v) for v in p.split(",")] for p in s.strip().strip(";").split(";")])


def parse_stages(path):
    mask_on, contours, cands, finals = 0, [], {}, []
    for line in open(path):
        if line.startswith("MASK_ON"):
            mask_on = int(line.split()[1])
        elif line.startswith("CONTOUR"):
            t = line.split()
            n = int(t[1]); v = np.array([int(x) for x in t[2:]]).reshape(n, 2)
            contours.append(v)
        elif line.startswith("CAND"):
            t = line.split()
            c = dict(index=int(t[1]), status=t[2])
            for kv in t[3:]:
                k, _, v = kv.partition("=")
                c[k] = parse_pts(v) if k in ("mini", "mini2", "quad") else float(v)
            cands[c["index"]] = c
        elif line.startswith("FINAL_TRIMMED"):
            finals.append(parse_pts(line.split(" ", 1)[1]))
    return mask_on, contours, cands, finals


def canon(box):
    """canonical corner order independent of start vertex/direction: sort corners by (angle around centroid)."""
    b = np.array(box, dtype=float)
    c = b.mean(axis=0)
    idx = np.argsort(np.arctan2(b[:, 1] - c[1], b[:, 0] - c[0]))
    return b[idx]


def corner_dist(a, b):
    """max over corners of min distance after trying the 4 cyclic shifts of canonical order."""
    ca, cb = canon(a), canon(b)
    best = 1e9
    for k in range(4):
        best = min(best, np.abs(ca - np.roll(cb, k, axis=0)).max())
    return best


def main(dump_dir, tag):
    meta = {}
    for line in open(os.path.join(dump_dir, "meta.txt")):
        k, _, v = line.rstrip("\n").partition("=")
        meta[k] = v
    ph, pw = [int(x) for x in meta["det_prob"].split(",")]
    ww, wh = [int(x) for x in meta["working"].split("x")]
    pred = np.fromfile(os.path.join(dump_dir, "det_prob.f32"), dtype="<f4").reshape(ph, pw)
    mask_on, jcont, jcand, jfinal = parse_stages(os.path.join(dump_dir, "det_stages.txt"))
    print(f"== {tag}: prob map {ph}x{pw}, working {ww}x{wh}")

    # mask
    ref_on = int((pred > THRESH).sum())
    print(f"S-mask   thresholded pixels: reference {ref_on}  java {mask_on}  {'PASS' if ref_on == mask_on else 'FAIL'}")

    ref = {m: reference_boxes(pred, ww, wh, m) for m in ("slow", "fast")}
    rcont = ref["slow"]
    holes = 0
    _, hier = cv2.findContours(((pred > THRESH) * 255).astype(np.uint8), cv2.RETR_CCOMP, cv2.CHAIN_APPROX_SIMPLE)
    holes = int((hier[0][:, 3] >= 0).sum()) if hier is not None else 0
    print(f"S-contour reference RETR_LIST contours={len(rcont)} (of which {holes} are holes: Java traces outer boundaries only); "
          f"java contours={len(jcont)}")

    # match contours by bbox
    def bbox(v):
        v = v.reshape(-1, 2)
        return (v[:, 0].min(), v[:, 1].min(), v[:, 0].max(), v[:, 1].max())
    rmap = {bbox(r["contour"]): r for r in rcont}
    matched, unmatched_j = 0, []
    area_bad = 0
    pair = {}
    for ji, jc in enumerate(jcont):
        r = rmap.get(bbox(jc))
        if r is None:
            unmatched_j.append(ji); continue
        matched += 1
        pair[ji] = r
        a_ref = abs(cv2.contourArea(r["contour"]))
        a_j = abs(cv2.contourArea(jc.reshape(-1, 1, 2).astype(np.int32)))
        if abs(a_ref - a_j) > 1e-6:
            area_bad += 1
    print(f"S-contour matched by bbox: {matched}/{len(jcont)} java contours; area-equal (polygon equivalence) "
          f"{matched - area_bad}/{matched}; java contours without reference match: {len(unmatched_j)}")

    # candidates: java accepted vs reference accepted (slow, same score mode as Java)
    for mode in ("slow", "fast"):
        accepted = [r for r in ref[mode] if r["status"] == "accepted"]
        print(f"S-cands  reference({mode}) accepted={len(accepted)}  statuses=" +
              str({s: sum(1 for r in ref[mode] if r['status'] == s) for s in set(r['status'] for r in ref[mode])}))
    jacc = [c for c in jcand.values() if c["status"] == "accepted"]
    print(f"         java(SLOW) accepted={len(jacc)} statuses=" +
          str({s: sum(1 for c in jcand.values() if c['status'] == s) for s in set(c['status'] for c in jcand.values())}))

    # per-candidate numeric stages vs reference(slow), matched by bbox of the contour
    md = {"mini": [], "score": [], "mini2": [], "final": []}
    exact_final = 0
    nfinal = 0
    retained_mismatch = []
    ref_by_bbox = {bbox(r["contour"]): r for r in ref["slow"]}
    # java candidate index == java contour index
    for ji, jc in enumerate(jcont):
        r = ref_by_bbox.get(bbox(jc))
        c = jcand.get(ji)
        if r is None or c is None:
            continue
        if (c["status"] == "accepted") != (r["status"] == "accepted"):
            retained_mismatch.append((ji, c["status"], r["status"]))
        if "mini" in c and "mini" in r:
            md["mini"].append(corner_dist(c["mini"], r["mini"]))
        if "score" in r and "slow" in c and c["status"] not in ("rejected:_min_size_(<3_on_map)",):
            md["score"].append(abs(c["slow"] - r["score"]))
        if c["status"] == "accepted" and r["status"] == "accepted":
            md["mini2"].append(corner_dist(c["mini2"], r["mini2"]))
            nfinal += 1
            d = corner_dist(c["quad"], r["final_raw"])
            md["final"].append(d)
            exact_final += int(d == 0)
    def rep(name, vals, tol):
        if not vals:
            print(f"S-{name:<8} no comparable candidates"); return
        v = np.array(vals)
        print(f"S-{name:<8} n={len(v)} max={v.max():.4f} mean={v.mean():.4f}  tol={tol}  {'PASS' if v.max() <= tol else 'FAIL'}")
    rep("mini", md["mini"], 0.51)
    rep("score", md["score"], 0.005)
    rep("unclip", md["mini2"], 1.0)
    rep("final", md["final"], 1.0)
    print(f"         final quads exactly equal: {exact_final}/{nfinal}; retained-set mismatches vs reference(slow): {retained_mismatch}")

    # point ordering + clip + filter (predict_det.filter_tag_det_res) on the reference's retained boxes
    rb = [r["final_raw"] for r in ref["slow"] if r["status"] == "accepted"]
    flt = filter_tag_det_res(rb, wh, ww)
    print(f"S-order  upstream filter_tag_det_res keeps {len(flt)}/{len(rb)} boxes (java keeps {len(jacc)})")
    for k, (box, raw) in enumerate(zip(flt, rb)):
        same_order = np.array_equal(box.astype(int), np.array(raw).astype(int))
        print(f"         box {k}: order_points_clockwise reorders corners vs get_mini_boxes order: {not same_order}; "
              f"clip to W-1/H-1 changed: {not np.array_equal(np.array(raw), np.clip(raw, 0, [ww, wh]))}")
    # reading order (upstream sorted_boxes) vs java final grouping order is reported by the caller
    sb = sorted_boxes(flt)
    print("S-sort   upstream sorted_boxes order (first-corner y,x): " + " ".join(f"({b[0][0]:.0f},{b[0][1]:.0f})" for b in sb))
    return dict(ref=ref, java_acc=jacc, flt=flt)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
