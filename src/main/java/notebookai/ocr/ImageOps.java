package notebookai.ocr;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * Image loading and geometric primitives.
 *
 * <p>Conventions (matching upstream where noted, deviations documented):
 * <ul>
 *   <li>Pixels are BGR bytes in row-major order, like OpenCV {@code imread}.</li>
 *   <li>EXIF orientation IS applied (upstream {@code cv2.imread} ignores it);
 *       coordinates therefore refer to the displayed-orientation image.</li>
 *   <li>Bilinear sampling follows the OpenCV INTER_LINEAR coordinate mapping
 *       {@code src = (dst + 0.5) * scale - 0.5} with border replication.</li>
 *   <li>Bicubic sampling uses the Keys kernel (a = -0.75, as in OpenCV
 *       INTER_CUBIC) with border replication.</li>
 *   <li>Perspective coefficients solve the same 4-point linear system as
 *       {@code cv2.getPerspectiveTransform}, in double precision.</li>
 * </ul>
 */
public final class ImageOps {

    private ImageOps() {}

    /** Decoded image: BGR bytes plus oriented width/height. */
    public record DecodedImage(byte[] bgr, int width, int height) {}

    /** Loads JPG/JPEG/PNG, applies EXIF orientation, returns BGR bytes. */
    public static DecodedImage decode(Path path) throws OcrException, IOException {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!(name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png"))) {
            throw new OcrException(
                    "Unsupported image type (Phase 1 supports JPG/JPEG and PNG): " + path);
        }
        BufferedImage img;
        try (InputStream in = Files.newInputStream(path)) {
            img = ImageIO.read(in);
        }
        if (img == null) {
            throw new OcrException("Unreadable or corrupt image: " + path);
        }
        int orientation = readExifOrientation(path);
        img = toBgr(img);
        img = applyOrientation(img, orientation);
        int w = img.getWidth();
        int h = img.getHeight();
        byte[] bgr = new byte[w * h * 3];
        img.getRaster().getDataElements(0, 0, w, h, bgr);
        return new DecodedImage(bgr, w, h);
    }

    private static BufferedImage toBgr(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_3BYTE_BGR) {
            return img;
        }
        BufferedImage bgr = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        bgr.getGraphics().drawImage(img, 0, 0, null);
        return bgr;
    }

    /** EXIF orientation tag (1-8); 1 (normal) when absent or unreadable. */
    static int readExifOrientation(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            Metadata metadata = ImageMetadataReader.readMetadata(in);
            ExifIFD0Directory dir = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (dir != null && dir.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return dir.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }
        } catch (Exception ignored) {
            // Fall through to "normal".
        }
        return 1;
    }

    /**
     * Applies the EXIF orientation transform. Mapping follows the EXIF 2.3
     * specification (2/4/5/7 mirror, 3/4/6/8 rotate).
     */
    static BufferedImage applyOrientation(BufferedImage img, int orientation) {
        int w = img.getWidth();
        int h = img.getHeight();
        return switch (orientation) {
            case 2 -> flip(img, w, h, true, false);
            case 3 -> rotate180(img, w, h);
            case 4 -> flip(img, w, h, false, true);
            case 5 -> transpose(img, w, h);
            case 6 -> rotateCw(img, w, h);
            case 7 -> transverse(img, w, h);
            case 8 -> rotateCcw(img, w, h);
            default -> img;
        };
    }

    private static BufferedImage blank(int w, int h) {
        return new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
    }

    private static BufferedImage flip(BufferedImage src, int w, int h, boolean horizontal, boolean vertical) {
        BufferedImage dst = blank(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sx = horizontal ? w - 1 - x : x;
                int sy = vertical ? h - 1 - y : y;
                dst.setRGB(x, y, src.getRGB(sx, sy));
            }
        }
        return dst;
    }

    private static BufferedImage rotate180(BufferedImage src, int w, int h) {
        return flip(src, w, h, true, true);
    }

    private static BufferedImage transpose(BufferedImage src, int w, int h) {
        BufferedImage dst = blank(h, w);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                dst.setRGB(y, x, src.getRGB(x, y));
            }
        }
        return dst;
    }

    private static BufferedImage rotateCw(BufferedImage src, int w, int h) {
        BufferedImage dst = blank(h, w);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                dst.setRGB(h - 1 - y, x, src.getRGB(x, y));
            }
        }
        return dst;
    }

    private static BufferedImage rotateCcw(BufferedImage src, int w, int h) {
        BufferedImage dst = blank(h, w);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                dst.setRGB(y, w - 1 - x, src.getRGB(x, y));
            }
        }
        return dst;
    }

    private static BufferedImage transverse(BufferedImage src, int w, int h) {
        BufferedImage dst = blank(h, w);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                dst.setRGB(h - 1 - y, w - 1 - x, src.getRGB(x, y));
            }
        }
        return dst;
    }

    /** Clamp helper for border replication. */
    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    /**
     * Bilinear resize of BGR bytes (OpenCV INTER_LINEAR coordinate mapping).
     */
    public static byte[] resizeBilinear(byte[] src, int srcW, int srcH, int dstW, int dstH) {
        byte[] dst = new byte[dstW * dstH * 3];
        double scaleX = (double) srcW / dstW;
        double scaleY = (double) srcH / dstH;
        for (int y = 0; y < dstH; y++) {
            double fy = (y + 0.5) * scaleY - 0.5;
            int y0 = (int) Math.floor(fy);
            double dy = fy - y0;
            int ya = clamp(y0, 0, srcH - 1);
            int yb = clamp(y0 + 1, 0, srcH - 1);
            for (int x = 0; x < dstW; x++) {
                double fx = (x + 0.5) * scaleX - 0.5;
                int x0 = (int) Math.floor(fx);
                double dx = fx - x0;
                int xa = clamp(x0, 0, srcW - 1);
                int xb = clamp(x0 + 1, 0, srcW - 1);
                int out = (y * dstW + x) * 3;
                for (int c = 0; c < 3; c++) {
                    double v00 = src[(ya * srcW + xa) * 3 + c] & 0xFF;
                    double v10 = src[(ya * srcW + xb) * 3 + c] & 0xFF;
                    double v01 = src[(yb * srcW + xa) * 3 + c] & 0xFF;
                    double v11 = src[(yb * srcW + xb) * 3 + c] & 0xFF;
                    double v = (v00 * (1 - dx) + v10 * dx) * (1 - dy)
                            + (v01 * (1 - dx) + v11 * dx) * dy;
                    dst[out + c] = (byte) clamp((int) Math.round(v), 0, 255);
                }
            }
        }
        return dst;
    }

    /** Keys bicubic kernel with a = -0.75 (OpenCV INTER_CUBIC). */
    private static double cubic(double x) {
        x = Math.abs(x);
        double x2 = x * x;
        double x3 = x2 * x;
        if (x <= 1.0) {
            return 1.5 * x3 - 2.5 * x2 + 1.0;
        }
        if (x <= 2.0) {
            return -0.75 * x3 + 3.75 * x2 - 6.0 * x + 3.0;
        }
        return 0.0;
    }

    /** Bicubic resize of BGR bytes (INTER_CUBIC mapping + replicate border). */
    public static byte[] resizeBicubic(byte[] src, int srcW, int srcH, int dstW, int dstH) {
        byte[] dst = new byte[dstW * dstH * 3];
        double scaleX = (double) srcW / dstW;
        double scaleY = (double) srcH / dstH;
        for (int y = 0; y < dstH; y++) {
            double fy = (y + 0.5) * scaleY - 0.5;
            int sy = (int) Math.floor(fy);
            for (int x = 0; x < dstW; x++) {
                double fx = (x + 0.5) * scaleX - 0.5;
                int sx = (int) Math.floor(fx);
                int out = (y * dstW + x) * 3;
                for (int c = 0; c < 3; c++) {
                    double v = 0.0;
                    for (int j = -1; j <= 2; j++) {
                        double wy = cubic(fy - (sy + j));
                        int yy = clamp(sy + j, 0, srcH - 1);
                        for (int i = -1; i <= 2; i++) {
                            double wx = cubic(fx - (sx + i));
                            int xx = clamp(sx + i, 0, srcW - 1);
                            v += wx * wy * (src[(yy * srcW + xx) * 3 + c] & 0xFF);
                        }
                    }
                    dst[out + c] = (byte) clamp((int) Math.round(v), 0, 255);
                }
            }
        }
        return dst;
    }

    /**
     * Solves the 4-point perspective system (same equations as
     * {@code cv2.getPerspectiveTransform}), returning the 3x3 matrix
     * row-major. Solved in double precision with partial pivoting.
     */
    public static double[] perspectiveCoeffs(double[][] src, double[][] dst) throws OcrException {
        double[][] a = new double[8][9];
        for (int i = 0; i < 4; i++) {
            double x = src[i][0];
            double y = src[i][1];
            double u = dst[i][0];
            double v = dst[i][1];
            a[2 * i] = new double[] {-x, -y, -1, 0, 0, 0, u * x, u * y, -u};
            a[2 * i + 1] = new double[] {0, 0, 0, -x, -y, -1, v * x, v * y, -v};
        }
        for (int col = 0; col < 8; col++) {
            int pivot = col;
            for (int row = col + 1; row < 8; row++) {
                if (Math.abs(a[row][col]) > Math.abs(a[pivot][col])) {
                    pivot = row;
                }
            }
            if (Math.abs(a[pivot][col]) < 1e-12) {
                throw new OcrException("Degenerate quad: cannot solve perspective transform.");
            }
            double[] tmp = a[col];
            a[col] = a[pivot];
            a[pivot] = tmp;
            for (int row = 0; row < 8; row++) {
                if (row != col) {
                    double factor = a[row][col] / a[col][col];
                    for (int k = col; k < 9; k++) {
                        a[row][k] -= factor * a[col][k];
                    }
                }
            }
        }
        double[] m = new double[9];
        for (int i = 0; i < 8; i++) {
            m[i] = a[i][8] / a[i][i];
        }
        m[8] = 1.0;
        return m;
    }

    /** Applies a 3x3 perspective matrix to one point. */
    public static double[] applyPerspective(double[] m, double x, double y) {
        double w = m[6] * x + m[7] * y + m[8];
        return new double[] {(m[0] * x + m[1] * y + m[2]) / w, (m[3] * x + m[4] * y + m[5]) / w};
    }

    /** Inverts a 3x3 matrix. */
    static double[] invert3x3(double[] m) throws OcrException {
        double det = m[0] * (m[4] * m[8] - m[5] * m[7])
                - m[1] * (m[3] * m[8] - m[5] * m[6])
                + m[2] * (m[3] * m[7] - m[4] * m[6]);
        if (Math.abs(det) < 1e-12) {
            throw new OcrException("Degenerate perspective transform (not invertible).");
        }
        double inv = 1.0 / det;
        return new double[] {
            (m[4] * m[8] - m[5] * m[7]) * inv,
            (m[2] * m[7] - m[1] * m[8]) * inv,
            (m[1] * m[5] - m[2] * m[4]) * inv,
            (m[5] * m[6] - m[3] * m[8]) * inv,
            (m[0] * m[8] - m[2] * m[6]) * inv,
            (m[2] * m[3] - m[0] * m[5]) * inv,
            (m[3] * m[7] - m[4] * m[6]) * inv,
            (m[1] * m[6] - m[0] * m[7]) * inv,
            (m[0] * m[4] - m[1] * m[3]) * inv,
        };
    }

    /**
     * Warps a BGR image through a quad-to-rect perspective map (bicubic with
     * border replication, like {@code cv2.warpPerspective} with
     * BORDER_REPLICATE + INTER_CUBIC). {@code quad} is TL/TR/BR/BL.
     */
    public static byte[] warpQuadBicubic(
            byte[] src, int srcW, int srcH, double[][] quad, int dstW, int dstH)
            throws OcrException {
        double[][] rect = {{0, 0}, {dstW, 0}, {dstW, dstH}, {0, dstH}};
        double[] forward = perspectiveCoeffs(quad, rect);
        double[] inv = invert3x3(forward);
        byte[] dst = new byte[dstW * dstH * 3];
        for (int y = 0; y < dstH; y++) {
            for (int x = 0; x < dstW; x++) {
                double[] p = applyPerspective(inv, x + 0.0, y + 0.0);
                double fx = p[0];
                double fy = p[1];
                int sx = (int) Math.floor(fx);
                int sy = (int) Math.floor(fy);
                int out = (y * dstW + x) * 3;
                for (int c = 0; c < 3; c++) {
                    double v = 0.0;
                    for (int j = -1; j <= 2; j++) {
                        double wy = cubic(fy - (sy + j));
                        int yy = clamp(sy + j, 0, srcH - 1);
                        for (int i = -1; i <= 2; i++) {
                            double wx = cubic(fx - (sx + i));
                            int xx = clamp(sx + i, 0, srcW - 1);
                            v += wx * wy * (src[(yy * srcW + xx) * 3 + c] & 0xFF);
                        }
                    }
                    dst[out + c] = (byte) clamp((int) Math.round(v), 0, 255);
                }
            }
        }
        return dst;
    }

    /** Counterclockwise 90-degree rotation (matches {@code np.rot90}). */
    public static byte[] rot90(byte[] src, int w, int h) {
        byte[] dst = new byte[src.length];
        int nw = h;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int nx = y;
                int ny = w - 1 - x;
                System.arraycopy(src, (y * w + x) * 3, dst, (ny * nw + nx) * 3, 3);
            }
        }
        return dst;
    }
}
