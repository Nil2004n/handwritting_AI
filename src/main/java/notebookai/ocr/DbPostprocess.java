package notebookai.ocr;

import java.util.ArrayList;
import java.util.List;

/**
 * DB postprocessing, quad path, replicating upstream
 * {@code DBPostProcess.boxes_from_bitmap} (v3.7.0) with the pairing from
 * {@code models/det.yml}: thresh=0.2, box_thresh=0.45, max_candidates=3000,
 * unclip_ratio=1.4, score_mode=fast, box_type=quad, no dilation.
 *
 * <p>Documented implementation notes (no silent approximations):
 * <ul>
 *   <li>Contours: Moore-neighbor border following with Jacob's stopping rule
 *       per connected component, then collinear-run compression equivalent to
 *       CHAIN_APPROX_SIMPLE. Enumeration is top-left scan order; upstream
 *       order only matters past the 3000-contour cap, which never binds here.</li>
 *   <li>Hole contours are not traced separately: a hole interior scores near
 *       zero in {@code box_score_fast} and is filtered by box_thresh anyway.</li>
 *   <li>{@code minAreaRect}: exact rotating-calipers over the convex hull
 *       (Andrew monotone chain); the subsequent {@code get_mini_boxes}
 *       ordering is a verbatim port, so corner order cannot diverge.</li>
 *   <li>Unclip input is always a convex quadrilateral (output of
 *       {@code get_mini_boxes}). Offset uses exact miter joins on straight
 *       edges plus circular arcs sampled every 2 degrees at convex vertices
 *       (error bound vs Clipper JT_ROUND: d*(1-cos(1deg)) &lt; 0.008px for
 *       d &lt; 50px), followed by an exact minAreaRect. Upstream pyclipper
 *       integer quantization is not replicated (would need the native lib);
 *       effect is &lt;1px pre-scale and absorbed by the final round+clip
 *       except at exact .5 boundaries.</li>
 *   <li>Mask fill for scoring is even-odd scanline fill; 1px edge
 *       rasterization differences vs OpenCV are possible and bounded.</li>
 * </ul>
 */
public final class DbPostprocess {

    /** One scored quad in feature-map coordinates. */
    public record Quad(double[][] points, double score) {}

    private DbPostprocess() {}

    /**
     * @param prob probability map [h][w] from the detector output
     * @param destW destination (original image) width for coordinate scaling
     * @param destH destination (original image) height
     * @return scored quads in destination pixel coordinates (double precision)
     */
    public static List<Quad> boxesFromBitmap(float[][] prob, int destW, int destH) {
        int h = prob.length;
        int w = prob[0].length;
        boolean[][] bitmap = new boolean[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                bitmap[y][x] = prob[y][x] > 0.2;
            }
        }
        List<int[]> contours = traceContours(bitmap);
        List<Quad> out = new ArrayList<>();
        int num = Math.min(contours.size(), 3000);
        for (int i = 0; i < num; i++) {
            double[][] contour = toPoints(contours.get(i));
            double[][] mini = getMiniBoxes(contour);
            if (mini == null) {
                continue;
            }
            double sside = miniSide(mini);
            if (sside < 3) {
                continue;
            }
            double score = boxScoreFast(prob, mini);
            if (0.45 > score) {
                continue;
            }
            double[][] expanded = unclip(mini, 1.4);
            if (expanded == null) {
                continue;
            }
            double[][] mini2 = getMiniBoxes(expanded);
            if (mini2 == null || miniSide(mini2) < 5) {
                continue;
            }
            double[][] scaled = new double[4][2];
            for (int k = 0; k < 4; k++) {
                scaled[k][0] = clip(Math.round(mini2[k][0] / w * destW), 0, destW);
                scaled[k][1] = clip(Math.round(mini2[k][1] / h * destH), 0, destH);
            }
            out.add(new Quad(scaled, score));
        }
        return out;
    }

    private static double clip(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static double[][] toPoints(int[] flat) {
        int n = flat.length / 2;
        double[][] pts = new double[n][2];
        for (int i = 0; i < n; i++) {
            pts[i][0] = flat[2 * i];
            pts[i][1] = flat[2 * i + 1];
        }
        return pts;
    }

    // ------------------------------------------------------------------
    // Contour tracing: Moore neighbor following + SIMPLE compression.
    // ------------------------------------------------------------------

    private static final int[] DX = {1, 1, 0, -1, -1, -1, 0, 1};
    private static final int[] DY = {0, 1, 1, 1, 0, -1, -1, -1};

    /** Traces one contour per connected component (top-left scan order). */
    static List<int[]> traceContours(boolean[][] bitmap) {
        int h = bitmap.length;
        int w = bitmap[0].length;
        boolean[][] visited = new boolean[h][w];
        List<int[]> contours = new ArrayList<>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (bitmap[y][x] && !visited[y][x]) {
                    List<int[]> component = flood(bitmap, visited, x, y);
                    int[] boundary = traceBoundary(bitmap, component);
                    if (boundary.length >= 6) {
                        contours.add(boundary);
                    }
                }
            }
        }
        return contours;
    }

    private static List<int[]> flood(boolean[][] bitmap, boolean[][] visited, int sx, int sy) {
        int h = bitmap.length;
        int w = bitmap[0].length;
        List<int[]> pixels = new ArrayList<>();
        int[] stack = {sx, sy};
        List<int[]> work = new ArrayList<>();
        work.add(new int[] {sx, sy});
        visited[sy][sx] = true;
        int head = 0;
        while (head < work.size()) {
            int[] p = work.get(head++);
            pixels.add(p);
            for (int d = 0; d < 8; d++) {
                int nx = p[0] + DX[d];
                int ny = p[1] + DY[d];
                if (nx >= 0 && ny >= 0 && nx < w && ny < h && bitmap[ny][nx] && !visited[ny][nx]) {
                    visited[ny][nx] = true;
                    work.add(new int[] {nx, ny});
                }
            }
        }
        return pixels;
    }

    /**
     * Moore-neighbor boundary tracing with Jacob's stopping criterion.
     * Starts at the topmost-leftmost pixel of the component.
     */
    private static int[] traceBoundary(boolean[][] bitmap, List<int[]> component) {
        int h = bitmap.length;
        int w = bitmap[0].length;
        boolean[][] inSet = new boolean[h][w];
        int sx = Integer.MAX_VALUE;
        int sy = Integer.MAX_VALUE;
        for (int[] p : component) {
            inSet[p[1]][p[0]] = true;
            if (p[1] < sy || (p[1] == sy && p[0] < sx)) {
                sx = p[0];
                sy = p[1];
            }
        }
        List<int[]> boundary = new ArrayList<>();
        // Backtrack pixel: neighbor to the west of the start.
        int bx = sx - 1;
        int by = sy;
        int px = sx;
        int py = sy;
        int startDir = -1;
        int guard = 0;
        do {
            boundary.add(new int[] {px, py});
            // Clockwise neighbor order starting after the backtrack direction.
            int backDir = direction(bx - px, by - py);
            boolean advanced = false;
            for (int k = 1; k <= 8; k++) {
                int d = (backDir + k) % 8;
                int nx = px + DX[d];
                int ny = py + DY[d];
                if (nx >= 0 && ny >= 0 && nx < w && ny < h && inSet[ny][nx]) {
                    if (startDir < 0) {
                        startDir = d;
                    } else if (px == sx && py == sy && d == startDir && boundary.size() > 1) {
                        return flatten(boundary, compressSimpleList(boundary));
                    }
                    bx = px;
                    by = py;
                    px = nx;
                    py = ny;
                    advanced = true;
                    break;
                }
            }
            if (!advanced) {
                break; // Isolated pixel; no boundary to trace.
            }
            if (++guard > component.size() * 4 + 16) {
                break;
            }
        } while (!(px == sx && py == sy));
        return flatten(boundary, compressSimpleList(boundary));
    }

    private static int[] flatten(List<int[]> boundary, List<Integer> kept) {
        int[] flat = new int[kept.size() * 2];
        for (int i = 0; i < kept.size(); i++) {
            flat[2 * i] = boundary.get(kept.get(i))[0];
            flat[2 * i + 1] = boundary.get(kept.get(i))[1];
        }
        return flat;
    }

    private static List<Integer> compressSimpleList(List<int[]> boundary) {
        int n = boundary.size();
        List<Integer> kept = new ArrayList<>();
        kept.add(0);
        int prevDir = -1;
        for (int i = 0; i < n; i++) {
            int[] cur = boundary.get(i);
            int[] nxt = boundary.get((i + 1) % n);
            int d = direction(nxt[0] - cur[0], nxt[1] - cur[1]);
            if (d != prevDir) {
                if (i != 0) {
                    kept.add(i);
                }
                prevDir = d;
            }
        }
        // Close the loop: drop the last kept vertex if the closing edge
        // continues the first edge's direction.
        if (kept.size() > 1) {
            int[] first = boundary.get(kept.get(0));
            int[] second = boundary.get(kept.get(1));
            int[] last = boundary.get(kept.get(kept.size() - 1));
            int dFirst = direction(second[0] - first[0], second[1] - first[1]);
            int dLast = direction(first[0] - last[0], first[1] - last[1]);
            if (dFirst == dLast) {
                kept.remove(kept.size() - 1);
            }
        }
        return kept;
    }

    private static int direction(int dx, int dy) {
        for (int d = 0; d < 8; d++) {
            if (DX[d] == dx && DY[d] == dy) {
                return d;
            }
        }
        return 0;
    }

    /**
     * CHAIN_APPROX_SIMPLE equivalent: keep a vertex only when the step
     * direction changes (collinear runs collapse to endpoints).
     */
    static int[] compressSimple(List<int[]> boundary) {
        List<Integer> kept = compressSimpleList(boundary);
        int[] flat = new int[kept.size() * 2];
        for (int i = 0; i < kept.size(); i++) {
            flat[2 * i] = boundary.get(kept.get(i))[0];
            flat[2 * i + 1] = boundary.get(kept.get(i))[1];
        }
        return flat;
    }

    // ------------------------------------------------------------------
    // Convex hull (Andrew monotone chain) + rotating-calipers minAreaRect.
    // ------------------------------------------------------------------

    static double[][] convexHull(double[][] pts) {
        int n = pts.length;
        double[][] sorted = pts.clone();
        java.util.Arrays.sort(sorted, (a, b) -> {
            int c = Double.compare(a[0], b[0]);
            return c != 0 ? c : Double.compare(a[1], b[1]);
        });
        List<double[]> lower = new ArrayList<>();
        for (double[] p : sorted) {
            while (lower.size() >= 2
                    && cross(lower.get(lower.size() - 2), lower.get(lower.size() - 1), p) <= 0) {
                lower.remove(lower.size() - 1);
            }
            lower.add(p);
        }
        List<double[]> upper = new ArrayList<>();
        for (int i = n - 1; i >= 0; i--) {
            double[] p = sorted[i];
            while (upper.size() >= 2
                    && cross(upper.get(upper.size() - 2), upper.get(upper.size() - 1), p) <= 0) {
                upper.remove(upper.size() - 1);
            }
            upper.add(p);
        }
        lower.remove(lower.size() - 1);
        upper.remove(upper.size() - 1);
        lower.addAll(upper);
        return lower.toArray(new double[0][]);
    }

    private static double cross(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    /** Exact minimum-area rectangle corners over a point set. */
    static double[][] minAreaRect(double[][] pts) {
        double[][] hull = convexHull(pts);
        if (hull.length == 0) {
            return new double[0][];
        }
        if (hull.length == 1) {
            double x = hull[0][0];
            double y = hull[0][1];
            return new double[][] {{x, y}, {x, y}, {x, y}, {x, y}};
        }
        if (hull.length == 2) {
            double[] a = hull[0];
            double[] b = hull[1];
            return new double[][] {a, b, b, a};
        }
        double bestArea = Double.POSITIVE_INFINITY;
        double[][] best = null;
        int m = hull.length;
        for (int i = 0; i < m; i++) {
            double[] p1 = hull[i];
            double[] p2 = hull[(i + 1) % m];
            double ex = p2[0] - p1[0];
            double ey = p2[1] - p1[1];
            double len = Math.hypot(ex, ey);
            if (len < 1e-12) {
                continue;
            }
            double ux = ex / len;
            double uy = ey / len;
            double vx = -uy;
            double vy = ux;
            double minU = Double.POSITIVE_INFINITY;
            double maxU = Double.NEGATIVE_INFINITY;
            double minV = Double.POSITIVE_INFINITY;
            double maxV = Double.NEGATIVE_INFINITY;
            for (double[] p : hull) {
                double u = p[0] * ux + p[1] * uy;
                double v = p[0] * vx + p[1] * vy;
                minU = Math.min(minU, u);
                maxU = Math.max(maxU, u);
                minV = Math.min(minV, v);
                maxV = Math.max(maxV, v);
            }
            double area = (maxU - minU) * (maxV - minV);
            if (area < bestArea) {
                bestArea = area;
                best = new double[][] {
                    {ux * minU + vx * minV, uy * minU + vy * minV},
                    {ux * maxU + vx * minV, uy * maxU + vy * minV},
                    {ux * maxU + vx * maxV, uy * maxU + vy * maxV},
                    {ux * minU + vx * maxV, uy * minU + vy * maxV},
                };
            }
        }
        return best == null ? new double[][] {hull[0], hull[0], hull[0], hull[0]} : best;
    }

    /**
     * Verbatim port of upstream {@code get_mini_boxes}: corners sorted by x,
     * ordered TL/TR/BR/BL, plus the short side length.
     */
    static double[][] getMiniBoxes(double[][] contour) {
        double[][] rect = minAreaRect(contour);
        if (rect.length != 4) {
            return null;
        }
        double[][] sorted = rect.clone();
        java.util.Arrays.sort(sorted, (a, b) -> Double.compare(a[0], b[0]));
        int index1;
        int index4;
        if (sorted[1][1] > sorted[0][1]) {
            index1 = 0;
            index4 = 1;
        } else {
            index1 = 1;
            index4 = 0;
        }
        int index2;
        int index3;
        if (sorted[3][1] > sorted[2][1]) {
            index2 = 2;
            index3 = 3;
        } else {
            index2 = 3;
            index3 = 2;
        }
        return new double[][] {sorted[index1], sorted[index2], sorted[index3], sorted[index4]};
    }

    static double miniSide(double[][] box) {
        double d01 = Math.hypot(box[1][0] - box[0][0], box[1][1] - box[0][1]);
        double d12 = Math.hypot(box[2][0] - box[1][0], box[2][1] - box[1][1]);
        double d23 = Math.hypot(box[3][0] - box[2][0], box[3][1] - box[2][1]);
        double d30 = Math.hypot(box[0][0] - box[3][0], box[0][1] - box[3][1]);
        return Math.min(Math.min(d01, d12), Math.min(d23, d30));
    }

    // ------------------------------------------------------------------
    // box_score_fast: bbox mean over a scanline-filled mask.
    // ------------------------------------------------------------------

    static double boxScoreFast(float[][] bitmap, double[][] box) {
        int h = bitmap.length;
        int w = bitmap[0].length;
        double[][] b = new double[4][2];
        for (int i = 0; i < 4; i++) {
            b[i][0] = box[i][0];
            b[i][1] = box[i][1];
        }
        double minX = Math.min(Math.min(b[0][0], b[1][0]), Math.min(b[2][0], b[3][0]));
        double maxX = Math.max(Math.max(b[0][0], b[1][0]), Math.max(b[2][0], b[3][0]));
        double minY = Math.min(Math.min(b[0][1], b[1][1]), Math.min(b[2][1], b[3][1]));
        double maxY = Math.max(Math.max(b[0][1], b[1][1]), Math.max(b[2][1], b[3][1]));
        int xmin = (int) clip(Math.floor(minX), 0, w - 1);
        int xmax = (int) clip(Math.ceil(maxX), 0, w - 1);
        int ymin = (int) clip(Math.floor(minY), 0, h - 1);
        int ymax = (int) clip(Math.ceil(maxY), 0, h - 1);
        for (int i = 0; i < 4; i++) {
            b[i][0] -= xmin;
            b[i][1] -= ymin;
        }
        boolean[][] mask = fillPoly(b, xmax - xmin + 1, ymax - ymin + 1);
        double sum = 0;
        int count = 0;
        for (int y = ymin; y <= ymax; y++) {
            for (int x = xmin; x <= xmax; x++) {
                if (mask[y - ymin][x - xmin]) {
                    sum += bitmap[y][x];
                    count++;
                }
            }
        }
        return count == 0 ? 0.0 : sum / count;
    }

    /** Even-odd scanline polygon fill on an integer grid. */
    static boolean[][] fillPoly(double[][] poly, int w, int h) {
        boolean[][] mask = new boolean[h][w];
        int n = poly.length;
        for (int y = 0; y < h; y++) {
            List<Double> xs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                double[] p1 = poly[i];
                double[] p2 = poly[(i + 1) % n];
                if ((p1[1] <= y && p2[1] > y) || (p2[1] <= y && p1[1] > y)) {
                    xs.add(p1[0] + (y - p1[1]) / (p2[1] - p1[1]) * (p2[0] - p1[0]));
                }
            }
            xs.sort(Double::compare);
            for (int i = 0; i + 1 < xs.size(); i += 2) {
                int x0 = (int) Math.max(0, Math.ceil(xs.get(i)));
                int x1 = (int) Math.min(w - 1, Math.floor(xs.get(i + 1)));
                for (int x = x0; x <= x1; x++) {
                    mask[y][x] = true;
                }
            }
        }
        return mask;
    }

    // ------------------------------------------------------------------
    // Unclip: miter offset on straight edges + sampled circular arcs.
    // ------------------------------------------------------------------

    /**
     * Offsets a convex quadrilateral outward by
     * {@code area * ratio / perimeter} (upstream formula via
     * shapely/pyclipper). Returns the expanded polygon, or null when the
     * offset collapses.
     */
    static double[][] unclip(double[][] quad, double ratio) {
        double area = Math.abs(signedArea(quad));
        double peri = perimeter(quad);
        if (area < 1e-9 || peri < 1e-9) {
            return null;
        }
        // Ensure counterclockwise order for outward normals.
        double[][] q = quad;
        if (signedArea(q) < 0) {
            q = new double[][] {quad[0], quad[3], quad[2], quad[1]};
        }
        double dist = area * ratio / peri;
        // Outward unit normal per edge (CCW polygon: outward = right side).
        double[][] normals = new double[4][2];
        for (int i = 0; i < 4; i++) {
            double[] p1 = q[i];
            double[] p2 = q[(i + 1) % 4];
            double ex = p2[0] - p1[0];
            double ey = p2[1] - p1[1];
            double len = Math.hypot(ex, ey);
            if (len < 1e-12) {
                return null;
            }
            normals[i][0] = ey / len;
            normals[i][1] = -ex / len;
        }
        // Round join: at each vertex, an arc centered on the vertex from the
        // previous edge's offset direction to the current edge's. Consecutive
        // arc endpoints are joined by the straight offset edges.
        List<double[]> expanded = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            double[] center = q[i];
            double a0 = Math.atan2(normals[(i + 3) % 4][1], normals[(i + 3) % 4][0]);
            double a1 = Math.atan2(normals[i][1], normals[i][0]);
            double sweep = a1 - a0;
            while (sweep <= -Math.PI) {
                sweep += 2 * Math.PI;
            }
            while (sweep > Math.PI) {
                sweep -= 2 * Math.PI;
            }
            int steps = Math.max(1, (int) Math.ceil(Math.abs(sweep) / Math.toRadians(2.0)));
            for (int s = 0; s <= steps; s++) {
                double a = a0 + sweep * s / steps;
                expanded.add(new double[] {
                    center[0] + dist * Math.cos(a), center[1] + dist * Math.sin(a)
                });
            }
        }
        if (expanded.size() < 3) {
            return null;
        }
        return expanded.toArray(new double[0][]);
    }

    private static double signedArea(double[][] poly) {
        double a = 0;
        for (int i = 0; i < poly.length; i++) {
            double[] p = poly[i];
            double[] q = poly[(i + 1) % poly.length];
            a += p[0] * q[1] - q[0] * p[1];
        }
        return a / 2;
    }

    private static double perimeter(double[][] poly) {
        double p = 0;
        for (int i = 0; i < poly.length; i++) {
            double[] a = poly[i];
            double[] b = poly[(i + 1) % poly.length];
            p += Math.hypot(b[0] - a[0], b[1] - a[1]);
        }
        return p;
    }
}
