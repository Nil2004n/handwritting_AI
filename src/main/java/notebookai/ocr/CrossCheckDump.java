package notebookai.ocr;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import notebookai.eval.Evaluate;
import notebookai.model.WorkingImage;

/**
 * Diagnostic only ({@code ./gradlew crossCheckDump -PevalManifest=... -PoutDir=DIR}): exports the
 * data of every pipeline stage as raw little-endian files so an independent reference
 * implementation (tools/xcheck/xcheck.py: cv2 + onnxruntime, upstream PaddleOCR rules) can be run
 * on IDENTICAL inputs per stage. Never used at runtime; writes only under the given directory.
 */
public final class CrossCheckDump {

    private CrossCheckDump() {}

    public static void main(String[] args) throws Exception {
        String manifest = null;
        String out = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--manifest") && i + 1 < args.length) {
                manifest = args[++i];
            } else if (args[i].equals("--out") && i + 1 < args.length) {
                out = args[++i];
            }
        }
        if (manifest == null || out == null) {
            System.err.println("Usage: crossCheckDump -PevalManifest=cases.tsv -PoutDir=DIR");
            System.exit(2);
        }
        Path root = Paths.get("").toAbsolutePath();
        List<Evaluate.Case> cases = Evaluate.parseManifest(Files.readString(Paths.get(manifest)), root);
        try (OcrService service = OcrService.open(root)) {
            for (int k = 0; k < cases.size(); k++) {
                dump(service, cases.get(k), Paths.get(out).resolve("case" + k));
            }
        }
    }

    private static void dump(OcrService service, Evaluate.Case c, Path dir) throws Exception {
        Files.createDirectories(dir);
        ImageOps.DecodedImage d = ImageOps.decode(c.image());
        WorkingImage wi = new WorkingImage(d.bgr(), d.width(), d.height());
        if (c.rotateCw() != 0) {
            wi.rotateSteps(c.rotateCw());
        }
        if (c.straighten() != 0) {
            wi.setStraighten(c.straighten());
        }
        WorkingImage.Rendered r = wi.render();
        StringBuilder meta = new StringBuilder();
        meta.append("image=").append(c.image()).append('\n');
        meta.append("rotate=").append(c.rotateCw()).append('\n');
        meta.append("straighten=").append(c.straighten()).append('\n');
        meta.append("decoded=").append(d.width()).append('x').append(d.height()).append('\n');
        meta.append("working=").append(r.width()).append('x').append(r.height()).append('\n');
        Files.write(dir.resolve("decoded.u8"), d.bgr());
        Files.write(dir.resolve("working.u8"), r.bgr());

        DetPreprocess.DetInput in = DetPreprocess.preprocess(r.bgr(), r.width(), r.height());
        writeFloats(dir.resolve("det_in.f32"), in.chw());
        meta.append("det_in=").append(in.n()).append(',').append(in.c()).append(',').append(in.h()).append(',')
                .append(in.w()).append('\n');
        float[][] prob = service.runDetector(in);
        float[] flat = new float[prob.length * prob[0].length];
        for (int y = 0; y < prob.length; y++) {
            System.arraycopy(prob[y], 0, flat, y * prob[0].length, prob[0].length);
        }
        writeFloats(dir.resolve("det_prob.f32"), flat);
        meta.append("det_prob=").append(prob.length).append(',').append(prob[0].length).append('\n');

        dumpDetectorStages(prob, r.width(), r.height(), dir);

        // Single detector pass (a second full-size run exhausts RAM): regions come from the SAME
        // probability map through the production postprocess, in detector order.
        List<DbPostprocess.Quad> quads =
                DbPostprocess.boxesFromBitmap(prob, r.width(), r.height(), null, OcrService.SCORE_MODE);
        meta.append("regions=").append(quads.size()).append('\n');
        for (int i = 0; i < quads.size(); i++) {
            double[][] poly = quads.get(i).points();
            CropOps.Crop crop = CropOps.rotateCrop(r.bgr(), r.width(), r.height(), poly);
            RecPreprocess.RecInput ri = RecPreprocess.preprocess(crop.bgr(), crop.width(), crop.height());
            float[][] lg = service.recognizeLogits(crop.bgr(), crop.width(), crop.height());
            CtcDecoder.Line line = CtcDecoder.decode(lg, service.decodeTable());
            Files.write(dir.resolve("r" + i + "_crop.u8"), crop.bgr());
            writeFloats(dir.resolve("r" + i + "_recin.f32"), ri.chw());
            float[] lf = new float[lg.length * lg[0].length];
            for (int t = 0; t < lg.length; t++) {
                System.arraycopy(lg[t], 0, lf, t * lg[0].length, lg[0].length);
            }
            writeFloats(dir.resolve("r" + i + "_logits.f32"), lf);
            StringBuilder q = new StringBuilder();
            for (double[] pt : poly) {
                q.append(pt[0]).append(' ').append(pt[1]).append('\n');
            }
            Files.writeString(dir.resolve("r" + i + "_quad.txt"), q.toString());
            meta.append("r").append(i).append("=crop ").append(crop.width()).append('x').append(crop.height())
                    .append(" rot90=").append(crop.rotated()).append(" recin_w=").append(ri.width())
                    .append(" logits=").append(lg.length).append(',').append(lg[0].length)
                    .append(String.format(java.util.Locale.ROOT, " det=%.3f rec=%.3f kept=%s",
                            quads.get(i).score(), line.score(), line.score() >= OcrService.DROP_SCORE))
                    .append(" text=").append(line.text().replace('\n', ' ')).append('\n');
        }
        Files.writeString(dir.resolve("meta.txt"), meta.toString());
        System.out.println("dumped " + dir);
    }

    private static void writeFloats(Path p, float[] a) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(a.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.asFloatBuffer().put(a);
        Files.write(p, bb.array());
    }

    /** Detector post-processing stage data (contours, candidates, boxes) for tools/xcheck/xcheck_det.py. */
    private static void dumpDetectorStages(float[][] prob, int w, int h, Path dir) throws IOException {
        int mh = prob.length;
        int mw = prob[0].length;
        boolean[][] bitmap = new boolean[mh][mw];
        long on = 0;
        for (int y = 0; y < mh; y++) {
            for (int x = 0; x < mw; x++) {
                bitmap[y][x] = prob[y][x] > 0.2;
                if (bitmap[y][x]) {
                    on++;
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("MASK_ON ").append(on).append('\n');
        for (int[] c : DbPostprocess.traceContours(bitmap)) {
            sb.append("CONTOUR ").append(c.length / 2);
            for (int v : c) {
                sb.append(' ').append(v);
            }
            sb.append('\n');
        }
        List<DbPostprocess.Candidate> trace = new java.util.ArrayList<>();
        List<DbPostprocess.Quad> pre =
                DbPostprocess.boxesFromBitmap(prob, w, h, trace, OcrService.SCORE_MODE, false);
        for (DbPostprocess.Candidate c : trace) {
            sb.append("CAND ").append(c.index()).append(' ').append(c.status().replace(' ', '_')).append(" side=")
                    .append(c.miniSide()).append(" score=").append(c.detScore()).append(" slow=")
                    .append(c.slowScore());
            if (c.mini() != null) {
                sb.append(" mini=");
                for (double[] p : c.mini()) {
                    sb.append(p[0]).append(',').append(p[1]).append(';');
                }
                if (c.quad() != null) {
                    double[][] e = DbPostprocess.unclip(c.mini(), 1.4);
                    double[][] m2 = e == null ? null : DbPostprocess.getMiniBoxes(e);
                    if (m2 != null) {
                        sb.append(" mini2=");
                        for (double[] p : m2) {
                            sb.append(p[0]).append(',').append(p[1]).append(';');
                        }
                    }
                    sb.append(" quad=");
                    for (double[] p : c.quad()) {
                        sb.append(p[0]).append(',').append(p[1]).append(';');
                    }
                }
            }
            sb.append('\n');
        }
        for (DbPostprocess.Quad q : DbPostprocess.boxesFromBitmap(prob, w, h, null, OcrService.SCORE_MODE, true)) {
            sb.append("FINAL_TRIMMED ");
            for (double[] p : q.points()) {
                sb.append(p[0]).append(',').append(p[1]).append(';');
            }
            sb.append('\n');
        }
        Files.writeString(dir.resolve("det_stages.txt"), sb.toString());
    }
}
