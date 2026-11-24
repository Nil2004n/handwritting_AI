package notebookai.ocr;


/**
 * Pure-Java geometric primitives over packed BGR byte arrays (resize, perspective warp, rotate). No platform imaging APIs;
 * decoding and EXIF handling live in the platform adapters ({@link PixelImage}, {@code DesktopImageIO}).
 *
 * <p>Conventions (matching upstream where noted, deviations documented):
 * <ul>
 *   <li>Pixels are BGR bytes in row-major order, like OpenCV {@code imread}.</li>
 *   <li>Coordinates refer to the display-oriented image (EXIF orientation applied by the adapter; upstream
 *       {@code cv2.imread} ignores it, a deliberate deviation).</li>
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
    static double cubic(double x) {
        x = Math.abs(x);
        double x2 = x * x;
        double x3 = x2 * x;
        if (x <= 1.0) {
            // Keys a = -0.75: (a+2)x^3 - (a+3)x^2 + 1. (The previous 1.5/-2.5 coefficients are the
            // a = -0.5 form; mixed with the a = -0.75 outer branch the 4 tap weights summed to
            // 0.94-0.98 at fractional offsets, darkening every interpolated pixel by up to ~12%.)
            return 1.25 * x3 - 2.25 * x2 + 1.0;
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

    /** Rendered rotated image: BGR bytes plus dimensions. */
    public record Rotated(byte[] bgr, int width, int height) {}

    /**
     * Rotates counter-clockwise by an arbitrary angle about the image center,
     * expanding the canvas to fit (white fill outside the source). Bicubic
     * sampling with border replication inside the source, matching the warp
     * path's kernel. Used for small straightening angles only.
     */
    public static Rotated rotateBicubic(byte[] src, int w, int h, double degreesCcw) {
        double theta = Math.toRadians(degreesCcw);
        double cos = Math.cos(theta);
        double sin = Math.sin(theta);
        int nw = (int) Math.ceil(Math.abs(w * cos) + Math.abs(h * sin));
        int nh = (int) Math.ceil(Math.abs(w * sin) + Math.abs(h * cos));
        nw = Math.max(nw, 1);
        nh = Math.max(nh, 1);
        double cx = (w - 1) / 2.0;
        double cy = (h - 1) / 2.0;
        double ncx = (nw - 1) / 2.0;
        double ncy = (nh - 1) / 2.0;
        byte[] dst = new byte[nw * nh * 3];
        for (int y = 0; y < nh; y++) {
            for (int x = 0; x < nw; x++) {
                double dx = x - ncx;
                double dy = y - ncy;
                // Inverse rotation (dst -> src), clockwise by theta.
                double fx = cx + dx * cos + dy * sin;
                double fy = cy - dx * sin + dy * cos;
                int out = (y * nw + x) * 3;
                if (fx < 0 || fy < 0 || fx > w - 1 || fy > h - 1) {
                    dst[out] = (byte) 255;
                    dst[out + 1] = (byte) 255;
                    dst[out + 2] = (byte) 255;
                    continue;
                }
                int sx = (int) Math.floor(fx);
                int sy = (int) Math.floor(fy);
                for (int c = 0; c < 3; c++) {
                    double v = 0.0;
                    for (int j = -1; j <= 2; j++) {
                        double wy = cubic(fy - (sy + j));
                        int yy = clamp(sy + j, 0, h - 1);
                        for (int i = -1; i <= 2; i++) {
                            double wx = cubic(fx - (sx + i));
                            int xx = clamp(sx + i, 0, w - 1);
                            v += wx * wy * (src[(yy * w + xx) * 3 + c] & 0xFF);
                        }
                    }
                    dst[out + c] = (byte) clamp((int) Math.round(v), 0, 255);
                }
            }
        }
        return new Rotated(dst, nw, nh);
    }
}
