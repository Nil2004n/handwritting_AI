package notebookocr.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OCR result for one image.
 *
 * <p>Coordinates refer to the correctly oriented source image: JPEG EXIF
 * orientation is applied at decode time, so region boxes/polygons are in the
 * same pixel space the user sees. This differs from upstream
 * ({@code cv2.imread} ignores EXIF); the deviation is deliberate and recorded
 * in HANDOFF.md.
 */
public final class OcrResult {

    /** One ordered text region. Detector and recognition scores stay separate. */
    public record TextRegion(
            /** Quadrilateral [4][2] in source pixels, TL/TR/BR/BL order. */
            double[][] polygon,
            /** Axis-aligned box [x0, y0, x1, x1] in source pixels. */
            int[] box,
            String text,
            /** Mean probability-map score of the detected quad, or -1 if unavailable. */
            double detScore,
            /** Mean CTC confidence over kept positions, or 0 for empty decodes. */
            double recScore) {}

    /** Stage timings in milliseconds (inference sub-steps, not nested totals). */
    public record Timings(
            double decodeMs,
            double detPreMs,
            double detInferMs,
            double detPostMs,
            double cropMs,
            double recMs,
            double assembleMs,
            double totalMs) {}

    private final int imageWidth;
    private final int imageHeight;
    private final List<TextRegion> regions;
    private final String combinedText;
    private final Timings timings;

    public OcrResult(
            int imageWidth,
            int imageHeight,
            List<TextRegion> regions,
            String combinedText,
            Timings timings) {
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.regions = Collections.unmodifiableList(new ArrayList<>(regions));
        this.combinedText = combinedText;
        this.timings = timings;
    }

    public int imageWidth() {
        return imageWidth;
    }

    public int imageHeight() {
        return imageHeight;
    }

    public List<TextRegion> regions() {
        return regions;
    }

    public String combinedText() {
        return combinedText;
    }

    public Timings timings() {
        return timings;
    }
}
