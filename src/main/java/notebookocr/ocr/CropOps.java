package notebookocr.ocr;

import java.util.ArrayList;
import java.util.List;

/**
 * Crop extraction and reading order, replicating upstream
 * {@code get_rotate_crop_image} and {@code sorted_boxes} (v3.7.0).
 */
public final class CropOps {

    private CropOps() {}

    /**
     * Sorts boxes top-to-bottom, left-to-right: primary key is the first
     * point's y then x, plus a backward pass swapping neighbors on the same
     * line (dy &lt; 10) that are out of x order. Verbatim upstream port.
     */
    public static List<double[][]> sortedBoxes(List<double[][]> boxes) {
        List<double[][]> sorted = new ArrayList<>(boxes);
        sorted.sort((a, b) -> {
            int c = Double.compare(a[0][1], b[0][1]);
            return c != 0 ? c : Double.compare(a[0][0], b[0][0]);
        });
        int n = sorted.size();
        for (int i = 0; i < n - 1; i++) {
            for (int j = i; j >= 0; j--) {
                double[][] cur = sorted.get(j);
                double[][] nxt = sorted.get(j + 1);
                if (Math.abs(nxt[0][1] - cur[0][1]) < 10 && nxt[0][0] < cur[0][0]) {
                    sorted.set(j, nxt);
                    sorted.set(j + 1, cur);
                } else {
                    break;
                }
            }
        }
        return sorted;
    }

    /** Perspective-cropped text line (BGR bytes) with its output size. */
    public record Crop(byte[] bgr, int width, int height) {}

    /**
     * Warps the quad (TL/TR/BR/BL) to an upright rectangle via perspective
     * transform + bicubic sampling with border replication, rotating tall
     * crops 90 degrees CCW when height/width &gt;= 1.5. Upstream port.
     */
    public static Crop rotateCrop(byte[] img, int imgW, int imgH, double[][] points)
            throws OcrException {
        if (points.length != 4) {
            throw new OcrException("Crop quad must have exactly 4 points.");
        }
        int cropW = (int) Math.max(norm(points[0], points[1]), norm(points[2], points[3]));
        int cropH = (int) Math.max(norm(points[0], points[3]), norm(points[1], points[2]));
        if (cropW <= 0 || cropH <= 0) {
            throw new OcrException("Degenerate crop region.");
        }
        byte[] warped = ImageOps.warpQuadBicubic(img, imgW, imgH, points, cropW, cropH);
        int w = cropW;
        int h = cropH;
        if (h * 1.0 / w >= 1.5) {
            warped = ImageOps.rot90(warped, w, h);
            int t = w;
            w = h;
            h = t;
        }
        return new Crop(warped, w, h);
    }

    private static double norm(double[] a, double[] b) {
        return Math.hypot(b[0] - a[0], b[1] - a[1]);
    }
}
