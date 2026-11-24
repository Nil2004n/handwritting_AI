package notebookai.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Locates and validates the bundled PaddleOCR ONNX assets.
 *
 * <p>Expected layout (project root):
 * <pre>
 * models/det.onnx   detector (PP-OCRv6_small_det, DB)
 * models/det.yml    detector pairing config (DB postprocess params)
 * models/rec.onnx   recognizer (PP-OCRv6_small_rec, CTC head)
 * models/rec.yml    recognizer pairing config (embeds the character dict)
 * models/checksums.sha256  committed sha256 manifest (sha256sum -c format)
 * </pre>
 *
 * <p>Binaries stay out of git; {@code tools/download-models.sh} reproduces
 * this directory from pinned sources and verifies the same manifest.
 */
public final class ModelConfig {

    public static final String MODEL_IDENTITY =
            "PP-OCRv6_small (det 2.48M params + rec 5.2M params, ONNX, ONNX Runtime CPU)";

    public static final String DET_ONNX = "det.onnx";
    public static final String DET_YML = "det.yml";
    public static final String REC_ONNX = "rec.onnx";
    public static final String REC_YML = "rec.yml";
    public static final String CHECKSUMS = "checksums.sha256";

    private ModelConfig() {}

    /** System property that points at the models directory (set by the native jpackage launchers, which cannot set env vars). */
    public static final String MODELS_PROPERTY = "notebookai.models";

    /**
     * Resolves the models directory. Precedence: (1) the {@code NOTEBOOK_AI_MODELS} environment variable, when set -
     * an explicit user override always wins; (2) the {@code notebookai.models} system property, used by the native
     * installers (their launcher passes {@code -Dnotebookai.models=$APPDIR/../models}); (3) the {@code models}
     * directory under the given project root, so running from a source checkout keeps working unchanged.
     */
    public static Path modelsDir(Path projectRoot) {
        return resolveModelsDir(projectRoot, System.getenv("NOTEBOOK_AI_MODELS"), System.getProperty(MODELS_PROPERTY));
    }

    /** Pure form of {@link #modelsDir} for tests. Blank values count as unset. */
    static Path resolveModelsDir(Path projectRoot, String env, String property) {
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        if (property != null && !property.isBlank()) {
            return Path.of(property);
        }
        return projectRoot.resolve("models");
    }

    /** Throws a clear error naming every missing required asset. */
    public static void validateFiles(Path modelsDir) throws notebookai.ocr.OcrException {
        List<String> missing = new ArrayList<>();
        for (String name : new String[] {DET_ONNX, DET_YML, REC_ONNX, REC_YML, CHECKSUMS}) {
            if (!Files.isRegularFile(modelsDir.resolve(name))) {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            throw new notebookai.ocr.OcrException(
                    "OCR setup incomplete: missing files in "
                            + modelsDir.toAbsolutePath()
                            + ": "
                            + String.join(", ", missing)
                            + ". Run tools/download-models.sh, then retry.");
        }
    }

    /**
     * Validates SHA-256 checksums recorded in {@code checksums.sha256}
     * (standard {@code sha256sum} format) against the files on disk.
     */
    public static void validateChecksums(Path modelsDir) throws notebookai.ocr.OcrException, IOException {
        Map<String, String> expected = readManifest(modelsDir.resolve(CHECKSUMS));
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            Path file = modelsDir.resolve(entry.getKey());
            if (!Files.isRegularFile(file)) {
                problems.add(entry.getKey() + " (missing)");
                continue;
            }
            String actual = sha256Hex(file);
            if (!actual.equalsIgnoreCase(entry.getValue())) {
                problems.add(entry.getKey() + " (checksum mismatch)");
            }
        }
        if (!problems.isEmpty()) {
            throw new notebookai.ocr.OcrException(
                    "OCR setup invalid: checksum problems in "
                            + modelsDir.toAbsolutePath()
                            + ": "
                            + String.join(", ", problems)
                            + ". Re-run tools/download-models.sh.");
        }
    }

    /** Short model fingerprint (first 12 hex of det/rec sha256 from the checksums manifest), no paths. */
    public static String modelFingerprint(Path modelsDir) {
        try {
            Map<String, String> m = readManifest(modelsDir.resolve(CHECKSUMS));
            String det = m.getOrDefault(DET_ONNX, "?");
            String rec = m.getOrDefault(REC_ONNX, "?");
            return "det=" + det.substring(0, Math.min(12, det.length()))
                    + " rec=" + rec.substring(0, Math.min(12, rec.length()));
        } catch (IOException | RuntimeException e) {
            return "det=? rec=?";
        }
    }

    static Map<String, String> readManifest(Path manifest) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
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

    static String sha256Hex(Path file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
        byte[] bytes = Files.readAllBytes(file);
        byte[] hash = digest.digest(bytes);
        StringBuilder hex = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /**
     * Reads the recognizer `character_dict` as an ordered list.
     * These are CTC output classes 1..N (`ModelConfig.readDecodeTable`
     * adds the blank class at 0 and the space class at the end).
     */
    public static java.util.List<String> readRecDictionary(Path recYml) throws IOException {
        List<String> lines = Files.readAllLines(recYml, StandardCharsets.UTF_8);
        List<String> dict = new ArrayList<>();
        boolean inDict = false;
        for (String line : lines) {
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
     * Builds the exact inference decode table, mirroring upstream
     * {@code CTCLabelDecode} with the tools/pipeline default
     * {@code use_space_char=True}: {@code ["blank"] + dict + [" "]}.
     * The recognizer's 18,710 output classes are then fully addressed:
     * class 0 is blank (ignored), 1..18708 are the dictionary, and 18709
     * is the space the model emits between words.
     */
    public static java.util.List<String> readDecodeTable(Path recYml) throws IOException {
        java.util.List<String> table = new java.util.ArrayList<>();
        table.add("blank");
        table.addAll(readRecDictionary(recYml));
        table.add(" ");
        return java.util.Collections.unmodifiableList(table);
    }

    /** Unquotes a YAML single-quoted scalar ('' is an escaped quote). */
    static String unquoteYmlScalar(String raw) {
        String s = raw.trim();
        if (s.length() >= 2 && s.charAt(0) == '\'' && s.charAt(s.length() - 1) == '\'') {
            return s.substring(1, s.length() - 1).replace("''", "'");
        }
        return s;
    }

    /**
     * Counts entries under the {@code character_dict:} key of the recognizer
     * pairing config, proving the decode dictionary is present and readable.
     * Returns the number of dictionary entries found (0 when the key is absent).
     */
    public static int countRecCharacters(Path recYml) throws IOException {
        List<String> lines = Files.readAllLines(recYml, StandardCharsets.UTF_8);
        boolean inDict = false;
        int count = 0;
        for (String line : lines) {
            if (!inDict) {
                if (line.trim().equals("character_dict:")) {
                    inDict = true;
                }
                continue;
            }
            if (line.startsWith("  - ")) {
                count++;
            } else if (!line.startsWith(" ") && !line.trim().isEmpty() && !line.startsWith("#")) {
                break;
            }
        }
        return count;
    }
}
