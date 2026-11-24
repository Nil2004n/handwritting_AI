# Model manifest — PaddleOCR PP-OCRv6_small for local Java inference

## Selected models
PP-OCRv6_small was verifiable (not merely planned): PaddlePaddle publishes
official ONNX exports on Hugging Face, released with PaddleOCR 3.7.0
(2026-06-11). Small tier: detector 2.48M params, recognizer 5.2M params,
50 languages, Apache-2.0. No alternative or fallback model was needed.

| Role | HF repo | Pinned commit |
|------|---------|---------------|
| Detector (DB, LCNetV4 + RepLKFPN) | `PaddlePaddle/PP-OCRv6_small_det_onnx` | `28fe5895…bfc62` (full SHA in `tools/download-models.sh`) |
| Recognizer (CTC head, EncoderWithLightSVTR) | `PaddlePaddle/PP-OCRv6_small_rec_onnx` | `b8f84f0b…3a513` (full SHA in `tools/download-models.sh`) |

Paper: arXiv 2606.13108 (`arxiv:2606.13108` tag on both repos).

## Required files and pairing
| Local file | Source blob | Purpose |
|------------|-------------|---------|
| `models/det.onnx` (9,880,512 B) | `inference.onnx` (det repo) | DB detector graph; input BGR image NCHW, output probability map |
| `models/det.yml` (885 B) | `inference.yml` (det repo) | DB postprocess params (`box_thresh`, `thresh`, `unclip_ratio`, normalize) |
| `models/rec.onnx` (21,159,378 B) | `inference.onnx` (rec repo) | CTC recognizer graph; input 3×48×W crop, output char logits |
| `models/rec.yml` (150,579 B) | `inference.yml` (rec repo) | Preprocess (`RecResizeImg` 3×48×320) + `CTCLabelDecode` + embedded `character_dict` |
| `models/checksums.sha256` (committed) | — | SHA-256 manifest for the four files above |

`inference.json` (Paddle graph dump, not needed for ONNX Runtime) was
deliberately NOT downloaded.

## SHA-256 checksums
Recorded in `models/checksums.sha256` (verified 2026-11-20):
- `det.onnx`: `d73e0058…9410e`
- `det.yml`: `193f4352…b1516`
- `rec.onnx`: `5435fd74…a24634`
- `rec.yml`: `ab078671…512c77d1`
(Full values in the manifest file; `sha256sum -c` and `verifyOcrSetup`
both enforce them.)

## License
Apache License 2.0. Declared per-repo as the `license:apache-2.0` tag;
upstream text preserved at `third-party/PaddleOCR-LICENSE`
(PaddlePaddle/PaddleOCR, Apache-2.0). No separate LICENSE blob ships in
the model repos.

## Compatibility: verified vs untested
- VERIFIED (`./gradlew verifyOcrSetup`, exit 0, 2026-11-20): both `.onnx`
  files load in ONNX Runtime 1.22.0 Java (CPU); checksums enforced;
  recognizer `character_dict` readable (18,708 entries). Observed I/O:
  - detector: input `x` shape=[-1, 3, -1, -1] type=FLOAT;
    output `fetch_name_0` shape=[-1, 1, -1, -1] type=FLOAT
  - recognizer: input `x` shape=[-1, 3, 48, -1] type=FLOAT (matches the
    `RecResizeImg` 3x48x320 pipeline config);
    output `fetch_name_0` shape=[-1, -1, 18710] type=FLOAT
  All native resources closed after the check; failure paths (missing
  files, bad checksums) exit 1 with actionable messages, covered by unit
  tests and by a direct missing-assets run.
- VERIFIED (Phase 1, 2026-11-21): full decode table is
  `["blank"] + 18,708 dict entries + [" "]` = 18,710 classes, matching the
  upstream `use_space_char=True` inference default. The extra class is the
  space character (index 18709), confirmed firing at inter-word gaps with
  ~1.0 confidence on rendered English text — not an unknown token.
- VERIFIED (Phase 1): end-to-end OCR on rendered print English is
  character-perfect (see HANDOFF.md); synthetic scribble samples produce
  well-formed regions but meaningless text, as expected (they are not
  handwriting). Real handwriting accuracy is UNTESTED.
- Processing rules implemented from upstream v3.7.0 sources
  (tag `b03f4642`): DetResizeForTest 736/min + round-to-32, ImageNet
  normalize, DB postprocess (thresh 0.2, box 0.45, unclip 1.4, quad,
  fast scoring), getRotateCropImage (INTER_CUBIC, replicate, rot90>=1.5),
  resize_norm_img 3x48xW, CTC greedy decode, drop_score 0.5, sorted_boxes
  reading order. See HANDOFF.md for the deviation list.
- No OpenCV: geometry (contour tracing, minAreaRect, warp, unclip) is
  hand-ported pure Java; no OpenCV or polygon-offset dependency added.
- No PaddlePaddle Java SDK methods are used; only ORT Java session APIs.

## Phase 3 pipeline audit (2026-11-22, upstream v3.7.0 tag `b03f4642`)
Checked boundary by boundary against `operators.py`, `rec_img_aug.py`,
`label_ops.py`, `db/rec_postprocess.py`, `predict_{det,rec,system}.py`:
- Decode: `TYPE_3BYTE_BGR` raster is R,G,B band order; the code swaps to true
  BGR like `cv2.imread` (the earlier true-BGR fix stands — verified correct,
  not reverted). EXIF applied deliberately (upstream ignores it).
- Detector input: BGR bytes × 1/255, ImageNet mean/std applied per stored
  channel, CHW layout — matches `NormalizeImage`+`ToCHWImage` (order hwc).
  Resize 736/min + 32-multiple + bilinear matches `DetResizeForTest` type 0.
- Recognition input: BGR kept, bilinear to height 48, `(v/255-0.5)/0.5`,
  right zero-pad to `int(48*max(320/48, ratio))` — matches `resize_norm_img`
  standard path. No width cap (same as upstream ONNX dynamic shapes; the 3200
  cap lives only in TensorRT configs, not the ONNX graph).
- Geometry: baseline image carries an EXIF block but no orientation tag
  (orientation-1 path); `-Protate=3` mapping verified by readable output;
  `-Protate=3` mapping verified by readable output; warp/perspective covered
  by unit tests; quad handling + minAreaRect validated in Phases 1–2.
- Dictionary: 18,708 entries + blank@0 + space@18709 (`use_space_char=True`
  default, verified in code and by space firing at word gaps). CTC decode
  drops class 0 and collapses repeats, matching `CTCLabelDecode.decode`.
- No truncation: full logit sequences decoded; no length caps anywhere.
- Conclusion: no pipeline defect found in the inspected boundaries. CORRECTION (Phase 3b):
  the earlier phrase "all at high model confidence" was wrong; per-frame output shows some
  errors are low-margin (ITSIELF insertion 0.420 vs blank 0.364; b->h 0.414 vs d 0.251) while
  others are confident (o->a 0.990). This audit compared code to upstream by reading; it did not
  numerically cross-check against a reference cv2/onnxruntime stack (not installed). No
  production change made (see HANDOFF Phase 3 / 3b).

## Correction (2026-11-24): recognizer revision pin
`tools/download-models.sh` and `tools/download-models.ps1` carried a recognizer revision of 41 characters (`b8f3c850b80c...`), which is not a valid Git commit, so the pinned URL always 404'd and the script silently fell back
to the `main` branch (the byte identity was always protected by `checksums.sha256`). The correct commit is `b8f84f0b80c529de40b4fbb3544b84fa7233a513` (it matches the abbreviation above); the Hub's LFS metadata shows its `inference.onnx`
is exactly the verified `rec.onnx` (sha256 `5435fd74...`), and the pinned URL now resolves. Likewise the detector commit `28fe5895c24fd108c19eb3e8479f4ab385fbfc62` was confirmed to contain the verified `det.onnx`.
