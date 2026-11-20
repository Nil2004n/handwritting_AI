package notebookocr.model;

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

    public static Path modelsDir(Path projectRoot) {
        return projectRoot.resolve("models");
    }

    /** Throws a clear error naming every missing required asset. */
    public static void validateFiles(Path modelsDir) throws notebookocr.ocr.OcrException {
        List<String> missing = new ArrayList<>();
        for (String name : new String[] {DET_ONNX, DET_YML, REC_ONNX, REC_YML, CHECKSUMS}) {
            if (!Files.isRegularFile(modelsDir.resolve(name))) {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            throw new notebookocr.ocr.OcrException(
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
    public static void validateChecksums(Path modelsDir) throws notebookocr.ocr.OcrException, IOException {
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
            throw new notebookocr.ocr.OcrException(
                    "OCR setup invalid: checksum problems in "
                            + modelsDir.toAbsolutePath()
                            + ": "
                            + String.join(", ", problems)
                            + ". Re-run tools/download-models.sh.");
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
