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
