package notebookai.ocr;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * DESKTOP debug writer (AWT/ImageIO): turns a {@link RecognitionTrace} recorded by {@link OcrEngine} into files. Records
 * every detector candidate and every recognized region BEFORE drop_score filtering, and writes the probability map, candidate
 * overlay, exact recognizer-input crops and a plain-text {@code <stem>_trace.txt}. Detector scores and recognition scores
 * are kept in separate columns. Never touches the source tree; nothing is written unless a debug directory is supplied.
 */
final class DebugTrace {

    private DebugTrace() {}

    static void write(RecognitionTrace trace, Path dir, Path imagePath, PixelImage decoded, double dropScore)
            throws IOException {
        final float[][] prob = trace.prob;
        final List<DbPostprocess.Candidate> candidates = trace.candidates;
        final List<RecognitionTrace.RegionTrace> regions = trace.regions;
        final int srcW = trace.srcW;
        final int srcH = trace.srcH;
        final int detW = trace.detW;
        final int detH = trace.detH;
        final double scaleX = trace.scaleX;
        final double scaleY = trace.scaleY;
        final String exifNote = trace.exifNote;
        final String finalText = trace.finalText;
        Files.createDirectories(dir);
        String stem = stem(imagePath);

        // Probability map (grayscale), plus a copy with candidate/region overlays.
        int mh = prob.length;
        int mw = prob[0].length;
        BufferedImage map = new BufferedImage(mw, mh, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < mh; y++) {
            for (int x = 0; x < mw; x++) {
                int v = (int) Math.max(0, Math.min(255, Math.round(prob[y][x] * 255)));
                map.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        ImageIO.write(map, "png", dir.resolve(stem + "_probmap.png").toFile());

        BufferedImage ov = new BufferedImage(mw, mh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = ov.createGraphics();
        try {
            g.drawImage(map, 0, 0, null);
            for (DbPostprocess.Candidate c : candidates) {
                if (c.mini() == null) {
                    continue;
                }
                boolean ok = c.status().equals("accepted");
                g.setColor(ok ? Color.GREEN : Color.RED);
                Polygon p = new Polygon();
                for (double[] pt : c.mini()) {
                    p.addPoint((int) Math.round(pt[0]), (int) Math.round(pt[1]));
                }
                g.drawPolygon(p);
                g.drawString("C" + c.index(), (int) c.mini()[0][0], (int) c.mini()[0][1]);
            }
        } finally {
            g.dispose();
        }
        ImageIO.write(ov, "png", dir.resolve(stem + "_probmap_candidates.png").toFile());

        // Source overlay: kept = green, dropped by drop_score = orange, all labelled.
        BufferedImage src = toBuffered(decoded.bgr(), srcW, srcH);
        Graphics2D sg = src.createGraphics();
        try {
            sg.setStroke(new java.awt.BasicStroke(4f));
            sg.setFont(sg.getFont().deriveFont(48f));
            for (RecognitionTrace.RegionTrace r : regions) {
                sg.setColor(r.kept() ? Color.GREEN : Color.ORANGE);
                Polygon p = new Polygon();
                for (double[] pt : r.polygon()) {
                    p.addPoint((int) Math.round(pt[0]), (int) Math.round(pt[1]));
                }
                sg.drawPolygon(p);
                sg.drawString(r.id(), (int) r.polygon()[0][0], (int) r.polygon()[0][1]);
            }
        } finally {
            sg.dispose();
        }
        ImageIO.write(src, "png", dir.resolve(stem + "_regions.png").toFile());
        ImageIO.write(toBuffered(decoded.bgr(), srcW, srcH), "png", dir.resolve(stem + "_oriented.png").toFile());

        for (RecognitionTrace.RegionTrace r : regions) {
            ImageIO.write(
                    toBuffered(r.cropBgr(), r.cropW(), r.cropH()),
                    "png",
                    dir.resolve(stem + "_crop_" + r.id() + ".png").toFile());
        }

        StringBuilder t = new StringBuilder();
        t.append(String.format(Locale.ROOT, "oriented source: %dx%d %s%n", srcW, srcH, exifNote));
        t.append(String.format(Locale.ROOT, "detector input: %dx%d (WxH) scale src/map: x=%.4f y=%.4f%n",
                detW, detH, scaleX, scaleY));
        t.append("recognition drop_score: ").append(dropScore).append('\n');
        t.append("\n== detector candidates (det = DETECTOR box score) ==\n");
        for (DbPostprocess.Candidate c : candidates) {
            t.append(String.format(Locale.ROOT, "C%-3d contourPts=%-5d miniSide=%.1f det=%.3f(fast) slow=%.3f %s",
                    c.index(), c.contourPoints(), c.miniSide(), c.detScore(), c.slowScore(), c.status()));
            if (c.mini() != null) {
                t.append("  mapBox=").append(bbox(c.mini(), 1, 1)).append(" mini=").append(pts(c.mini()));
            }
            if (c.quad() != null) {
                t.append("  srcQuad=").append(pts(c.quad()));
            }
            t.append('\n');
        }
        t.append("\n== recognized regions, reading order, BEFORE drop_score (rec = RECOGNIZER score) ==\n");
        for (RecognitionTrace.RegionTrace r : regions) {
            t.append(String.format(Locale.ROOT,
                    "%s (cand C%d, final-order %d) det=%.3f rec=%.3f %s crop=%dx%d%s%n    quad=%s%n    raw=\"%s\"%n",
                    r.id(), r.candidateIndex(), r.readingOrder(), r.detScore(), r.recScore(),
                    r.kept() ? "KEPT" : "DROPPED(drop_score)", r.cropW(), r.cropH(),
                    r.rotated() ? " (rot90)" : "", pts(r.polygon()), r.rawText()));
        }
        t.append("\n== final transcript ==\n").append(finalText).append('\n');
        Files.writeString(dir.resolve(stem + "_trace.txt"), t.toString());
    }

    private static String bbox(double[][] p, double sx, double sy) {
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -1, y1 = -1;
        for (double[] q : p) {
            x0 = Math.min(x0, q[0]); x1 = Math.max(x1, q[0]);
            y0 = Math.min(y0, q[1]); y1 = Math.max(y1, q[1]);
        }
        return String.format(Locale.ROOT, "[%.0f,%.0f,%.0f,%.0f]", x0 * sx, y0 * sy, x1 * sx, y1 * sy);
    }

    private static String pts(double[][] p) {
        StringBuilder sb = new StringBuilder();
        for (double[] q : p) {
            sb.append(String.format(Locale.ROOT, "(%.0f,%.0f)", q[0], q[1]));
        }
        return sb.toString();
    }

    static BufferedImage toBuffered(byte[] bgr, int w, int h) {
        return DesktopImageIO.toBufferedImage(bgr, w, h);
    }

    private static String stem(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
