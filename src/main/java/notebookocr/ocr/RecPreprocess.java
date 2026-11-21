package notebookocr.ocr;

/**
 * Recognizer preprocessing replicating upstream {@code resize_norm_img}
 * (standard CTC path, v3.7.0): BGR kept as-is, bilinear resize to height 48
 * with aspect-preserving width, per-pixel {@code (v/255 - 0.5) / 0.5}, right
 * zero-padding to {@code int(48 * max_wh_ratio)}.
 *
 * <p>Upstream batches crops and pads to the batch max width; Phase 1 runs
 * crops sequentially (batch size 1), so each crop pads to its own width.
 * Numerics on the valid region are identical; only trailing blank columns
 * differ, and those decode to nothing. No rec_image_shape width is
 * hard-coded: the width follows the crop aspect ratio like upstream.
 */
public final class RecPreprocess {

    /** Preprocessed crop: CHW floats plus the padded width. */
    public record RecInput(float[] chw, int width) {}

    public static final int REC_HEIGHT = 48;
    public static final double BASE_WH_RATIO = 320.0 / 48.0;

    private RecPreprocess() {}

    public static RecInput preprocess(byte[] bgr, int w, int h) throws OcrException {
        if (w <= 0 || h <= 0) {
            throw new OcrException("Empty crop image.");
        }
        double ratio = (double) w / h;
        double maxWhRatio = Math.max(BASE_WH_RATIO, ratio);
        int imgW = (int) (REC_HEIGHT * maxWhRatio);
        int resizedW = Math.ceil(REC_HEIGHT * ratio) > imgW ? imgW : (int) Math.ceil(REC_HEIGHT * ratio);
        if (resizedW <= 0) {
            throw new OcrException("Crop too narrow to resize.");
        }
        byte[] resized = ImageOps.resizeBilinear(bgr, w, h, resizedW, REC_HEIGHT);
        float[] chw = new float[3 * REC_HEIGHT * imgW];
        for (int y = 0; y < REC_HEIGHT; y++) {
            for (int x = 0; x < resizedW; x++) {
                int px = (y * resizedW + x) * 3;
                for (int c = 0; c < 3; c++) {
                    float v = ((resized[px + c] & 0xFF) / 255.0f - 0.5f) / 0.5f;
                    chw[(c * REC_HEIGHT + y) * imgW + x] = v;
                }
            }
        }
        return new RecInput(chw, imgW);
    }
}
