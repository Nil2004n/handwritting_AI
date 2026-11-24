package notebookai.ocr;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the OCR engine gets its models from, independent of platform conventions.
 *
 * <p>A model set is five named files: {@code det.onnx}, {@code det.yml}, {@code rec.onnx}, {@code rec.yml} (its
 * {@code character_dict} is the decoding table) and {@code checksums.sha256} ({@code sha256sum} format, covering the other
 * four). Two ways to supply them:
 * <ul>
 *   <li>{@link #fromDirectory(Path)}: files on disk (desktop installs; on Android, app-private storage such as
 *       {@code Context.getFilesDir()} after copying the APK assets there once). ONNX Runtime reads the model from the path;
 *       nothing is held on the Java heap. <b>Recommended on Android</b> (the two ONNX files are about 31 MB).</li>
 *   <li>{@link #fromBytes(Map)}: arrays already in memory. ONNX Runtime receives the bytes when the sessions are created.
 *       The set keeps the references until the engine that uses it is closed (it does not rely on ONNX Runtime having
 *       copied them), so the arrays must not be modified while the engine is open.</li>
 * </ul>
 *
 * <p>Environment variables, system properties and default install paths are <i>not</i> handled here: locating the
 * directory is a host-application concern (see the desktop {@code ModelConfig}). Instances are immutable and hold no native
 * resources, so they need no closing.
 */
public final class ModelAssets {

    /** Model identity shown to users and stored with results. */
    public static final String MODEL_IDENTITY =
            "PP-OCRv6_small (det 2.48M params + rec 5.2M params, ONNX, ONNX Runtime CPU)";

    public static final String DET_ONNX = "det.onnx";
    public static final String DET_YML = "det.yml";
    public static final String REC_ONNX = "rec.onnx";
    public static final String REC_YML = "rec.yml";
    public static final String CHECKSUMS = "checksums.sha256";

    static final String[] REQUIRED = {DET_ONNX, DET_YML, REC_ONNX, REC_YML, CHECKSUMS};

    private final File directory; // null when backed by bytes
    private final Map<String, byte[]> bytes; // null when backed by a directory

    private ModelAssets(File directory, Map<String, byte[]> bytes) {
        this.directory = directory;
        this.bytes = bytes;
    }

    /**
     * Model files in {@code directory} (existence is checked by {@link #verify()}, which {@link OcrEngine#open} calls).
     * A {@link File} rather than a {@code java.nio.file.Path}: {@code java.io} works on every Android API level without desugaring.
     */
    public static ModelAssets fromDirectory(File directory) {
        if (directory == null) {
            throw new IllegalArgumentException("directory is null");
        }
        return new ModelAssets(directory, null);
    }

    /** Model files held in memory, keyed by the five required file names. Arrays are referenced, not copied. */
    public static ModelAssets fromBytes(Map<String, byte[]> files) {
        Map<String, byte[]> copy = new LinkedHashMap<>(files); // the map is copied; the arrays are not
        return new ModelAssets(null, Collections.unmodifiableMap(copy));
    }

    /** The backing directory, or {@code null} for in-memory sets. Desktop diagnostics only. */
    File directory() {
        return directory;
    }

    /**
     * Checks that all five files exist and that every file named in {@code checksums.sha256} matches its SHA-256.
     * Streams file contents (constant memory).
     *
     * @throws OcrException naming every missing or mismatching file
     */
    public void verify() throws OcrException {
        List<String> missing = new ArrayList<>();
        for (String name : REQUIRED) {
            if (!has(name)) {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            throw new OcrException("OCR setup incomplete: missing model files: " + join(missing) + ".");
        }
        Map<String, String> expected;
        try {
            expected = parseManifest(readText(CHECKSUMS));
        } catch (IOException e) {
            throw new OcrException("Could not read model files: " + e.getMessage(), e);
        }
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            if (!has(entry.getKey())) {
                problems.add(entry.getKey() + " (missing)");
                continue;
            }
            try {
                if (!sha256Hex(entry.getKey()).equalsIgnoreCase(entry.getValue())) {
                    problems.add(entry.getKey() + " (checksum mismatch)");
                }
            } catch (IOException e) {
                throw new OcrException("Could not read model files: " + e.getMessage(), e);
            }
        }
        if (!problems.isEmpty()) {
            throw new OcrException("OCR setup invalid: checksum problems: " + join(problems) + ".");
        }
    }

    /** The CTC decode table: {@code ["blank"] + character_dict + [" "]} (see {@link #parseDecodeTable}). */
    List<String> decodeTable() throws IOException {
        return parseDecodeTable(readText(REC_YML));
    }

    /** Short model fingerprint (first 12 hex of the detector and recognizer SHA-256 as recorded in the manifest), no paths. */
    public String fingerprint() {
        try {
            Map<String, String> m = parseManifest(readText(CHECKSUMS));
            String det = m.getOrDefault(DET_ONNX, "?");
            String rec = m.getOrDefault(REC_ONNX, "?");
            return "det=" + det.substring(0, Math.min(12, det.length())) + " rec=" + rec.substring(0, Math.min(12, rec.length()));
        } catch (IOException | RuntimeException e) {
            return "det=? rec=?";
        }
    }

    /** Creates a CPU ONNX Runtime session for {@code det.onnx} or {@code rec.onnx}. The caller closes it. */
    OrtSession createSession(OrtEnvironment env, String onnxName) throws OrtException, OcrException {
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            if (directory != null) {
                return env.createSession(new File(directory, onnxName).getPath(), options);
            }
            byte[] model = bytes.get(onnxName);
            if (model == null) {
                throw new OcrException("OCR setup incomplete: missing model file " + onnxName + ".");
            }
            return env.createSession(model, options);
        }
    }

    // ---------------------------------------------------------------- file access

    boolean has(String name) {
        return directory != null ? new File(directory, name).isFile() : bytes.containsKey(name);
    }

    private byte[] readAll(String name) throws IOException {
        if (directory == null) {
            return bytes.get(name);
        }
        try (InputStream in = new FileInputStream(new File(directory, name))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            for (int n = in.read(buf); n >= 0; n = in.read(buf)) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    String readText(String name) throws IOException {
        return new String(readAll(name), StandardCharsets.UTF_8);
    }

    String sha256Hex(String name) throws IOException {
        MessageDigest digest = newDigest();
        if (directory != null) {
            try (InputStream in = new FileInputStream(new File(directory, name))) {
                byte[] buf = new byte[1 << 16];
                for (int n = in.read(buf); n >= 0; n = in.read(buf)) {
                    digest.update(buf, 0, n);
                }
            }
        } else {
            digest.update(bytes.get(name));
        }
        return hex(digest.digest());
    }

    /** {@code String.join(", ", parts)} (that method needs Android API 26). */
    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(part);
        }
        return sb.toString();
    }

    static MessageDigest newDigest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }

    static String hex(byte[] hash) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[hash.length * 2];
        for (int i = 0; i < hash.length; i++) {
            out[2 * i] = digits[(hash[i] >> 4) & 0xF];
            out[2 * i + 1] = digits[hash[i] & 0xF];
        }
        return new String(out);
    }

    // ---------------------------------------------------------------- parsing (pure, shared with the desktop ModelConfig)

    /** Parses {@code sha256sum} output: {@code <hex>  <name>} per line; blank lines and {@code #} comments are ignored. */
    static Map<String, String> parseManifest(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length >= 2) {
                result.put(parts[parts.length - 1], parts[0]);
            }
        }
        return result;
    }

    /** The recognizer {@code character_dict} entries (CTC classes 1..N) from a {@code rec.yml} text. */
    static List<String> parseDictionary(String ymlText) {
        List<String> dict = new ArrayList<>();
        boolean inDict = false;
        for (String line : ymlText.split("\\R", -1)) {
            if (!inDict) {
                if (line.trim().equals("character_dict:")) {
                    inDict = true;
                }
                continue;
            }
            if (line.startsWith("  - ")) {
                dict.add(unquoteYmlScalar(line.substring(4)));
            } else if (!line.startsWith(" ") && !line.trim().isEmpty() && !line.startsWith("#")) {
                break;
            }
        }
        return dict;
    }

    /**
     * The exact inference decode table, mirroring upstream {@code CTCLabelDecode} with {@code use_space_char=True}:
     * {@code ["blank"] + dict + [" "]}. Class 0 is blank (ignored), 1..18708 are the dictionary, 18709 is the space.
     */
    static List<String> parseDecodeTable(String ymlText) {
        List<String> table = new ArrayList<>();
        table.add("blank");
        table.addAll(parseDictionary(ymlText));
        table.add(" ");
        return Collections.unmodifiableList(table);
    }

    /** Unquotes a YAML single-quoted scalar ({@code ''} is an escaped quote). */
    static String unquoteYmlScalar(String raw) {
        String s = raw.trim();
        if (s.length() >= 2 && s.charAt(0) == '\'' && s.charAt(s.length() - 1) == '\'') {
            return s.substring(1, s.length() - 1).replace("''", "'");
        }
        return s;
    }
}
