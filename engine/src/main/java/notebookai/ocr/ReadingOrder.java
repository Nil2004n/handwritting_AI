package notebookai.ocr;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Tilt-aware reading order and display-line grouping over recognized regions.
 *
 * <p>Upstream {@code sorted_boxes} orders by the first corner's y, which
 * assumes horizontal text: on a page rotated by ~30 degrees the top-left
 * corner of a LOWER line can sit above that of the line before it, so lines
 * come out in the wrong order. Here every region is projected into the
 * dominant text frame (u along the text, v across it): rows are clustered by
 * v, ordered by v, and regions inside a row ordered by u. A row is split
 * into separate display lines wherever the gap along u exceeds one line
 * thickness, so table cells and columns are not glued together, while
 * regions that the detector split inside one sentence (small gap) are
 * joined with a space. Region results themselves are never merged or edited.
 */
public final class ReadingOrder {

    /**
     * A gap up to this fraction of the TALLER neighbor's padded thickness is a
     * word gap inside one written line. Padded thickness is ~2.4x the text
     * height (unclip adds ~0.7x per side), so 0.6 is ~1.4 text heights. The
     * taller box is used because a short fragment box (e.g. a word with no
     * ascenders) under-reports the line height; measured on the real sample, a
     * word gap of 86px beside boxes of 159px and 84px was wrongly split by the
     * previous min-based half-thickness rule.
     */
    static final double WORD_GAP = 0.6;

    private ReadingOrder() {}

    /** Display lines in reading order; each entry lists region indices left-to-right. */
    public static List<List<Integer>> group(List<double[][]> polys) {
        int n = polys.size();
        List<List<Integer>> lines = new ArrayList<>();
        if (n == 0) {
            return lines;
        }
        double theta = dominantAngle(polys);
        double cos = Math.cos(theta);
        double sin = Math.sin(theta);
        double[] uc = new double[n];
        double[] vc = new double[n];
        double[] u0 = new double[n];
        double[] u1 = new double[n];
        double[] thick = new double[n];
        for (int i = 0; i < n; i++) {
            double[][] p = polys.get(i);
            double minU = Double.MAX_VALUE;
            double maxU = -Double.MAX_VALUE;
            double sv = 0;
            double su = 0;
            for (double[] q : p) {
                double u = q[0] * cos + q[1] * sin;
                minU = Math.min(minU, u);
                maxU = Math.max(maxU, u);
                su += u;
                sv += -q[0] * sin + q[1] * cos;
            }
            u0[i] = minU;
            u1[i] = maxU;
            uc[i] = su / p.length;
            vc[i] = sv / p.length;
            thick[i] = Math.min(edge(p, 0, 1), edge(p, 0, 3));
        }
        List<Integer> byV = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            byV.add(i);
        }
        byV.sort(Comparator.comparingDouble(i -> vc[i]));

        List<List<Integer>> rows = new ArrayList<>();
        List<Integer> row = null;
        double rowV = 0;
        double rowT = 0;
        for (int i : byV) {
            if (row != null && Math.abs(vc[i] - rowV) < 0.5 * Math.min(thick[i], rowT)) {
                row.add(i);
                rowV = (rowV * (row.size() - 1) + vc[i]) / row.size();
                rowT = Math.min(rowT, thick[i]);
            } else {
                row = new ArrayList<>();
                row.add(i);
                rows.add(row);
                rowV = vc[i];
                rowT = thick[i];
            }
        }
        for (List<Integer> r : rows) {
            r.sort(Comparator.comparingDouble(i -> uc[i]));
            List<Integer> current = new ArrayList<>();
            int prev = -1;
            for (int i : r) {
                if (prev >= 0 && u0[i] - u1[prev] > WORD_GAP * Math.max(thick[i], thick[prev])) {
                    lines.add(current);
                    current = new ArrayList<>();
                }
                current.add(i);
                prev = i;
            }
            lines.add(current);
        }
        return lines;
    }

    /**
     * Dominant text direction in radians, in (-pi/2, pi/2]: length-weighted
     * mean of doubled angles over elongated regions (aspect >= 2), falling
     * back to all regions, then to 0.
     */
    static double dominantAngle(List<double[][]> polys) {
        double sc = 0;
        double ss = 0;
        for (int pass = 0; pass < 2 && sc == 0 && ss == 0; pass++) {
            for (double[][] p : polys) {
                double e01 = edge(p, 0, 1);
                double e03 = edge(p, 0, 3);
                double len = Math.max(e01, e03);
                double wid = Math.min(e01, e03);
                if (pass == 0 && (wid <= 0 || len / wid < 2)) {
                    continue;
                }
                double dx = e01 >= e03 ? p[1][0] - p[0][0] : p[3][0] - p[0][0];
                double dy = e01 >= e03 ? p[1][1] - p[0][1] : p[3][1] - p[0][1];
                double a = Math.atan2(dy, dx);
                sc += len * Math.cos(2 * a);
                ss += len * Math.sin(2 * a);
            }
        }
        return (sc == 0 && ss == 0) ? 0 : Math.atan2(ss, sc) / 2;
    }

    private static double edge(double[][] p, int a, int b) {
        return Math.hypot(p[b][0] - p[a][0], p[b][1] - p[a][1]);
    }
}
