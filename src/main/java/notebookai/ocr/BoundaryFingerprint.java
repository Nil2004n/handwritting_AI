package notebookai.ocr;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import notebookai.model.WorkingImage;

/**
 * Diagnostic only ({@code ./gradlew boundaryFingerprint -Pimages=a.png,b.jpg [-PhashOnly=true]}): prints a canonical,
 * line-oriented fingerprint of every pipeline boundary so a refactor can be compared before/after with {@code diff}.
 *
 * <p><b>Canonical serialization</b> (never object identity, never platform array layout, never locale formatting):
 * <ul>
 *   <li>Pixel buffers: SHA-256 over the ASCII header {@code "NBAI-FP1 u8 BGR W H\n"} followed by the raw bytes in
 *       row-major order, blue first, no row padding, top-left origin.</li>
 *   <li>Float tensors / maps: SHA-256 over {@code "NBAI-FP1 f32 <dims comma separated>\n"} followed by every value as
 *       4 bytes, big-endian IEEE-754 bit pattern ({@code Float.floatToRawIntBits}), in the stated row-major order.</li>
 *   <li>Coordinates: decimal with exactly 4 digits after the point, {@code Locale.ROOT}.</li>
 *   <li>Text: raw recognizer text, newline-escaped as {@code \n}.</li>
 * </ul>
 * Files are identified by name and SHA-256 of their bytes, never by path, so output contains no machine-specific path.
 * With {@code --hash-only} no recognized text is printed (use for private images): only its SHA-256 and length.
 */
public final class BoundaryFingerprint {

    private BoundaryFingerprint() {}

    public static void main(String[] args) throws Exception {
        boolean hashOnly = false;
        List<Path> images = new ArrayList<>();
        for (String a : args) {
            if (a.equals("--hash-only")) {
                hashOnly = true;
            } else {
                images.add(Paths.get(a));
            }
        }
        if (images.isEmpty()) {
            System.err.println("Usage: boundaryFingerprint -Pimages=a.png,b.jpg [-PhashOnly=true]");
            System.exit(2);
        }
        Path root = Paths.get("").toAbsolutePath();
        try (OcrService service = OcrService.open(root)) {
            for (Path image : images) {
                fingerprint(service, image, hashOnly);
            }
        }
    }

    static void fingerprint(OcrService service, Path image, boolean hashOnly) throws Exception {
        byte[] file = Files.readAllBytes(image);
        System.out.println("CASE " + image.getFileName() + " file_sha256=" + hex(MessageDigest.getInstance("SHA-256").digest(file)));
        ImageOps.DecodedImage d = ImageOps.decode(image);
        System.out.println("decoded " + d.width() + "x" + d.height() + " " + pixelHash(d.bgr(), d.width(), d.height()));
        WorkingImage wi = new WorkingImage(d.bgr(), d.width(), d.height());
        WorkingImage.Rendered r = wi.render();
        System.out.println("working " + r.width() + "x" + r.height() + " " + pixelHash(r.bgr(), r.width(), r.height()));

        DetPreprocess.DetInput in = DetPreprocess.preprocess(r.bgr(), r.width(), r.height());
        System.out.println("det_input n=" + in.n() + " c=" + in.c() + " h=" + in.h() + " w=" + in.w() + " "
                + floatHash(in.chw(), in.n() + "," + in.c() + "," + in.h() + "," + in.w()));
        float[][] prob = service.runDetector(in);
        float[] flat = new float[prob.length * prob[0].length];
        for (int y = 0; y < prob.length; y++) {
            System.arraycopy(prob[y], 0, flat, y * prob[0].length, prob[0].length);
        }
        System.out.println("det_map h=" + prob.length + " w=" + prob[0].length + " "
                + floatHash(flat, prob.length + "," + prob[0].length) + " (backend dependent: compare with tolerance across runtimes)");

        List<DbPostprocess.Quad> quads =
                DbPostprocess.boxesFromBitmap(prob, r.width(), r.height(), null, OcrService.SCORE_MODE);
        System.out.println("regions " + quads.size());
        StringBuilder combinedRaw = new StringBuilder();
        for (int i = 0; i < quads.size(); i++) {
            double[][] poly = quads.get(i).points();
            CropOps.Crop crop = CropOps.rotateCrop(r.bgr(), r.width(), r.height(), poly);
            RecPreprocess.RecInput ri = RecPreprocess.preprocess(crop.bgr(), crop.width(), crop.height());
            float[][] lg = service.recognizeLogits(crop.bgr(), crop.width(), crop.height());
            CtcDecoder.Line line = CtcDecoder.decode(lg, service.decodeTable());
            float[] lf = new float[lg.length * lg[0].length];
            for (int t = 0; t < lg.length; t++) {
                System.arraycopy(lg[t], 0, lf, t * lg[0].length, lg[0].length);
            }
            StringBuilder q = new StringBuilder();
            for (double[] pt : poly) {
                q.append(String.format(Locale.ROOT, "(%.4f,%.4f)", pt[0], pt[1]));
            }
            System.out.println("R" + i + " quad=" + q + String.format(Locale.ROOT, " det=%.4f", quads.get(i).score()));
            System.out.println("R" + i + " crop " + crop.width() + "x" + crop.height() + " rot90=" + crop.rotated() + " "
                    + pixelHash(crop.bgr(), crop.width(), crop.height()));
            System.out.println("R" + i + " rec_input w=" + ri.width() + " " + floatHash(ri.chw(), "3," + RecPreprocess.REC_HEIGHT + "," + ri.width()));
            System.out.println("R" + i + " logits " + lg.length + "x" + lg[0].length + " " + floatHash(lf, lg.length + "," + lg[0].length)
                    + " (backend dependent)");
            String raw = line.text().replace("\n", "\\n");
            System.out.println("R" + i + String.format(Locale.ROOT, " rec=%.4f", line.score()) + " raw_sha256=" + sha(raw)
                    + " raw_len=" + raw.length() + (hashOnly ? "" : " raw=\"" + raw + "\""));
            combinedRaw.append(raw).append('\n');
        }
        // The production path (this is what users and the evaluation see) on the same oriented pixels.
        notebookai.model.OcrResult res = service.recognize(r.bgr(), r.width(), r.height(), null);
        String text = res.combinedText().replace("\n", "\\n");
        System.out.println("FINAL regions=" + res.regions().size() + " text_sha256=" + sha(text) + " text_len=" + text.length()
                + (hashOnly ? "" : " text=\"" + text + "\""));
        for (int i = 0; i < res.regions().size(); i++) {
            double[][] p = res.regions().get(i).polygon();
            StringBuilder q = new StringBuilder();
            for (double[] pt : p) {
                q.append(String.format(Locale.ROOT, "(%.4f,%.4f)", pt[0], pt[1]));
            }
            int[] b = res.regions().get(i).box();
            System.out.println("FINAL R" + i + " box=" + b[0] + "," + b[1] + "," + b[2] + "," + b[3] + " quad=" + q
                    + String.format(Locale.ROOT, " det=%.4f rec=%.4f", res.regions().get(i).detScore(), res.regions().get(i).recScore()));
        }
        System.out.println();
    }

    /** SHA-256 of the canonical u8 BGR serialization (see class comment). */
    static String pixelHash(byte[] bgr, int w, int h) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(("NBAI-FP1 u8 BGR " + w + " " + h + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        md.update(bgr, 0, w * h * 3);
        return "sha256=" + hex(md.digest());
    }

    /** SHA-256 of the canonical big-endian f32 serialization (see class comment). */
    static String floatHash(float[] v, String dims) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(("NBAI-FP1 f32 " + dims + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        ByteBuffer bb = ByteBuffer.allocate(4 * 4096);
        for (int i = 0; i < v.length; i++) {
            bb.putInt(Float.floatToRawIntBits(v[i]));
            if (!bb.hasRemaining()) {
                md.update(bb.array(), 0, bb.position());
                bb.clear();
            }
        }
        md.update(bb.array(), 0, bb.position());
        return "sha256=" + hex(md.digest());
    }

    private static String sha(String s) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    @SuppressWarnings("unused")
    private static byte[] unused() throws IOException {
        return new ByteArrayOutputStream().toByteArray();
    }
}
