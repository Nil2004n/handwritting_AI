package notebookai.model;

import java.util.Arrays;
import java.util.Optional;
import notebookai.ocr.ImageOps;
import notebookai.ocr.OcrException;

/**
 * Image preparation state: the immutable oriented source plus an explicit,
 * redoable working image (90-degree steps, fine straightening, optional
 * rectangular crop). Every mutation bumps {@link #revision}, letting callers
 * discard stale background results without locking.
 *
 * <p>Coordinate convention: working-image pixels, origin top-left.
 * Display mapping ({@link #mapDisplayToWorking}) inverts a "contain" fit:
 * uniform scale, centered image, letterbox bars excluded. Selection boxes
 * are clipped to bounds and rejected when empty or tiny.
 */
public final class WorkingImage {

    /** Minimum selection side in working pixels; smaller is rejected as a slip. */
    public static final int MIN_SELECT = 8;

    /** Fine-rotation slider range in degrees. */
    public static final double MAX_STRAIGHTEN_DEG = 10.0;

    /** Rendered working image. */
    public record Rendered(byte[] bgr, int width, int height) {}

    private final byte[] original;
    private final int ow;
    private final int oh;
    private int quarterTurnsCw;
    private double fineDegrees;
    private int[] crop;
    private int revision;

    public WorkingImage(byte[] originalBgr, int width, int height) {
        this.original = Arrays.copyOf(originalBgr, originalBgr.length);
        this.ow = width;
        this.oh = height;
    }

    /**
     * Rebuilds the working image from the original plus saved preparation, applying the operations
     * in the only order the UI can produce them: 90-degree turns, then straighten, then crop (a
     * turn or straighten clears any crop, so a persisted crop always postdates them).
     */
    public static WorkingImage restore(byte[] originalBgr, int width, int height, int quarterTurnsCw,
            double fineDegrees, int[] crop) {
        WorkingImage w = new WorkingImage(originalBgr, width, height);
        w.rotateSteps(quarterTurnsCw);
        if (fineDegrees != 0) {
            w.setStraighten(fineDegrees);
        }
        if (crop != null) {
            w.setCrop(crop);
        }
        return w;
    }

    /** Size of the rotated/straightened frame (before any crop), computed exactly as {@link #render()} does. */
    public static int[] frameSize(int width, int height, int quarterTurnsCw, double fineDegrees) {
        int w = width;
        int h = height;
        if (Math.floorMod(quarterTurnsCw, 4) % 2 == 1) {
            w = height;
            h = width;
        }
        if (fineDegrees != 0) {
            double theta = Math.toRadians(fineDegrees);
            double cos = Math.cos(theta);
            double sin = Math.sin(theta);
            int nw = Math.max((int) Math.ceil(Math.abs(w * cos) + Math.abs(h * sin)), 1);
            int nh = Math.max((int) Math.ceil(Math.abs(w * sin) + Math.abs(h * cos)), 1);
            return new int[] {nw, nh};
        }
        return new int[] {w, h};
    }

    public int revision() {
        return revision;
    }

    public int quarterTurnsCw() {
        return quarterTurnsCw;
    }

    public double fineDegrees() {
        return fineDegrees;
    }

    /** Crop rect [x0,y0,x1,y1] in rotated-frame pixels, or null. */
    public int[] crop() {
        return crop == null ? null : crop.clone();
    }

    public boolean hasCrop() {
        return crop != null;
    }

    /** Rotate 90 degrees; positive steps are clockwise. */
    public void rotateSteps(int stepsCw) {
        quarterTurnsCw = Math.floorMod(quarterTurnsCw + stepsCw, 4);
        crop = null; // The crop rect lives in the previous rotated frame; it would hit a different area.
        revision++;
    }

    /** Straightening angle in degrees, clamped to [-10, +10]. */
    public void setStraighten(double degrees) {
        fineDegrees = Math.max(-MAX_STRAIGHTEN_DEG, Math.min(MAX_STRAIGHTEN_DEG, degrees));
        crop = null; // Same reason as rotateSteps: the crop was defined on the pre-change frame.
        revision++;
    }

    public void setCrop(int[] rectX0Y0X1Y1) {
        crop = rectX0Y0X1Y1 == null ? null : rectX0Y0X1Y1.clone();
        revision++;
    }

    public void clearCrop() {
        setCrop(null);
    }

    public void reset() {
        quarterTurnsCw = 0;
        fineDegrees = 0;
        crop = null;
        revision++;
    }

    /** Copies a working-image rectangle as standalone BGR bytes (row-major). */
    public static byte[] cropBytes(byte[] bgr, int imgW, int imgH, int[] rect) {
        int w = rect[2] - rect[0];
        int h = rect[3] - rect[1];
        byte[] out = new byte[w * h * 3];
        for (int y = 0; y < h; y++) {
            System.arraycopy(bgr, ((rect[1] + y) * imgW + rect[0]) * 3, out, (y * w) * 3, w * 3);
        }
        return out;
    }

    /**
     * Stale-job check: a background result is current only if no newer job
     * started ({@code jobGen == currentGen}) and the working image has not
     * changed since ({@code jobRev == currentRev}).
     */
    public static boolean isCurrentJob(int jobGen, int currentGen, int jobRev, int currentRev) {
        return jobGen == currentGen && jobRev == currentRev;
    }

    /**
     * Renders the current working image: 90-degree steps (lossless), then
     * fine rotation about the center (white fill), then the crop window.
     */
    public Rendered render() {
        byte[] img = original;
        int w = ow;
        int h = oh;
        int ccw = (4 - quarterTurnsCw) % 4;
        for (int i = 0; i < ccw; i++) {
            img = ImageOps.rot90(img, w, h);
            int t = w;
            w = h;
            h = t;
        }
        if (fineDegrees != 0) {
            ImageOps.Rotated r = ImageOps.rotateBicubic(img, w, h, fineDegrees);
            img = r.bgr();
            w = r.width();
            h = r.height();
        }
        if (crop != null) {
            int x0 = Math.max(0, crop[0]);
            int y0 = Math.max(0, crop[1]);
            int x1 = Math.min(w, crop[2]);
            int y1 = Math.min(h, crop[3]);
            if (x1 > x0 && y1 > y0) {
                byte[] cut = new byte[(x1 - x0) * (y1 - y0) * 3];
                for (int y = y0; y < y1; y++) {
                    System.arraycopy(
                            img, (y * w + x0) * 3, cut, ((y - y0) * (x1 - x0)) * 3, (x1 - x0) * 3);
                }
                img = cut;
                w = x1 - x0;
                h = y1 - y0;
            }
        }
        return new Rendered(img, w, h);
    }

    /**
     * Maps a point in display space (the fixed fit box) to working-image
     * pixels, inverting the contain fit. Empty when the point falls on
     * letterboxing rather than the image.
     */
    public static Optional<double[]> mapDisplayToWorking(
            double mx, double my, double fitW, double fitH, int imgW, int imgH) {
        double[] p = displayToWorking(mx, my, fitW, fitH, imgW, imgH);
        if (p == null) {
            return Optional.empty();
        }
        double x = p[0];
        double y = p[1];
        if (x < 0 || y < 0 || x >= imgW || y >= imgH) {
            return Optional.empty();
        }
        return Optional.of(new double[] {x, y});
    }

    /**
     * Unchecked variant used for drag corners, which callers clip afterwards.
     * Returns null for degenerate inputs.
     */
    public static double[] displayToWorking(
            double mx, double my, double fitW, double fitH, int imgW, int imgH) {
        if (!(fitW > 0) || !(fitH > 0) || !(imgW > 0) || !(imgH > 0)) {
            return null;
        }
        double scale = Math.min(fitW / imgW, fitH / imgH);
        double dispW = imgW * scale;
        double dispH = imgH * scale;
        double ox = (fitW - dispW) / 2;
        double oy = (fitH - dispH) / 2;
        return new double[] {(mx - ox) / scale, (my - oy) / scale};
    }

    /** Orders two corners into [x0,y0,x1,y1]. */
    public static int[] normalizeRect(double x0, double y0, double x1, double y1) {
        int ax = (int) Math.round(Math.min(x0, x1));
        int ay = (int) Math.round(Math.min(y0, y1));
        int bx = (int) Math.round(Math.max(x0, x1));
        int by = (int) Math.round(Math.max(y0, y1));
        return new int[] {ax, ay, bx, by};
    }

    /**
     * Clips a rect to image bounds. Empty unless at least MIN_SELECT pixels
     * survive on both axes — smaller drags are treated as clicks, not regions.
     */
    public static Optional<int[]> clipRect(int[] rect, int imgW, int imgH) {
        int x0 = Math.max(0, rect[0]);
        int y0 = Math.max(0, rect[1]);
        int x1 = Math.min(imgW, rect[2]);
        int y1 = Math.min(imgH, rect[3]);
        if (x1 - x0 < MIN_SELECT || y1 - y0 < MIN_SELECT) {
            return Optional.empty();
        }
        return Optional.of(new int[] {x0, y0, x1, y1});
    }

    /** Even-odd point-in-polygon test for quad hit-testing. */
    public static boolean pointInQuad(double[] pt, double[][] quad) {        boolean inside = false;
        for (int i = 0, j = quad.length - 1; i < quad.length; j = i++) {
            double xi = quad[i][0];
            double yi = quad[i][1];
            double xj = quad[j][0];
            double yj = quad[j][1];
            if ((yi > pt[1]) != (yj > pt[1])
                    && pt[0] < (xj - xi) * (pt[1] - yi) / (yj - yi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }
}
