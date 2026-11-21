package notebookai.ocr;

/**
 * Detector preprocessing replicating upstream {@code DetResizeForTest}
 * (limit_side_len=736, limit_type=min, max_side_limit=4000, as paired in
 * {@code models/det.yml}) followed by ImageNet normalization and CHW layout.
 *
 * <p>Note: the tools-CLI defaults (960/max) differ; the yml pairing is the
 * stable model contract and is what the pipeline class uses, so it wins here.
 */
public final class DetPreprocess {

    /** Preprocessed input: NCHW float tensor plus original-size shape info. */
    public record DetInput(float[] chw, int n, int c, int h, int w, int srcH, int srcW) {}

    private DetPreprocess() {}

    public static DetInput preprocess(byte[] bgr, int srcW, int srcH) throws OcrException {
        // DetResizeForTest type 0, limit_type "min", limit_side_len 736.
        double ratio = 1.0;
        int minSide = Math.min(srcW, srcH);
        if (minSide < 736) {
            ratio = 736.0 / minSide;
        }
        int resizeH = (int) (srcH * ratio);
        int resizeW = (int) (srcW * ratio);
        if (Math.max(resizeH, resizeW) > 4000) {
            double cap = 4000.0 / Math.max(resizeH, resizeW);
            resizeH = (int) (resizeH * cap);
            resizeW = (int) (resizeW * cap);
        }
        resizeH = Math.max((int) Math.rint(resizeH / 32.0) * 32, 32);
        resizeW = Math.max((int) Math.rint(resizeW / 32.0) * 32, 32);
        if (resizeW <= 0 || resizeH <= 0) {
            throw new OcrException("Detector resize produced an empty image.");
        }
        byte[] resized = ImageOps.resizeBilinear(bgr, srcW, srcH, resizeW, resizeH);

        // NormalizeImage: (v * 1/255 - mean[c]) / std[c], BGR channels as stored.
        double[] mean = {0.485, 0.456, 0.406};
        double[] std = {0.229, 0.224, 0.225};
        float[] chw = new float[3 * resizeH * resizeW];
        for (int y = 0; y < resizeH; y++) {
            for (int x = 0; x < resizeW; x++) {
                int px = (y * resizeW + x) * 3;
                for (int c = 0; c < 3; c++) {
                    double v = (resized[px + c] & 0xFF) * (1.0 / 255.0);
                    chw[(c * resizeH + y) * resizeW + x] = (float) ((v - mean[c]) / std[c]);
                }
            }
        }
        return new DetInput(chw, 1, 3, resizeH, resizeW, srcH, srcW);
    }

    /* Package-visible hooks for unit tests. */

    /** Upstream uses Python round(): half to EVEN (Math.rint), not half-up (Math.round). */
    static int[] snap32(int w, int h) {
        return new int[] {
            Math.max((int) Math.rint(w / 32.0) * 32, 32),
            Math.max((int) Math.rint(h / 32.0) * 32, 32)
        };
    }

    static double minResizeRatio(int w, int h) {
        int minSide = Math.min(w, h);
        return minSide < 736 ? 736.0 / minSide : 1.0;
    }

    static float[] normalize(byte[] bgr, int w, int h) {
        double[] mean = {0.485, 0.456, 0.406};
        double[] std = {0.229, 0.224, 0.225};
        float[] out = new float[3 * w * h];
        for (int i = 0; i < w * h; i++) {
            for (int c = 0; c < 3; c++) {
                double v = (bgr[i * 3 + c] & 0xFF) * (1.0 / 255.0);
                out[(c * w * h) + i] = (float) ((v - mean[c]) / std[c]);
            }
        }
        return out;
    }
}
