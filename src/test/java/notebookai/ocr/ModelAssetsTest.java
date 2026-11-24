package notebookai.ocr;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verification, fingerprinting and parsing of model sets, on synthetic files (no real models needed). */
class ModelAssetsTest {

    @TempDir
    Path dir;

    private static String sha(byte[] b) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : h) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    private Map<String, byte[]> synthetic() throws Exception {
        Map<String, byte[]> f = new LinkedHashMap<>();
        f.put("det.onnx", new byte[] {1, 2, 3, 4, 5});
        f.put("rec.onnx", new byte[] {9, 8, 7});
        f.put("det.yml", "Global:\n  model_name: det\n".getBytes(StandardCharsets.UTF_8));
        f.put("rec.yml", "PostProcess:\n  character_dict:\n  - 'a'\n  - 'b'\n  - ''''\nOther: 1\n".getBytes(StandardCharsets.UTF_8));
        StringBuilder m = new StringBuilder("# manifest\n");
        for (String n : new String[] {"det.onnx", "det.yml", "rec.onnx", "rec.yml"}) {
            m.append(sha(f.get(n))).append("  ").append(n).append('\n');
        }
        f.put("checksums.sha256", m.toString().getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private void write(Map<String, byte[]> files) throws Exception {
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            Files.write(dir.resolve(e.getKey()), e.getValue());
        }
    }

    @Test
    void validSetsVerifyFromDirectoryAndFromBytes() throws Exception {
        Map<String, byte[]> f = synthetic();
        write(f);
        assertDoesNotThrow(() -> ModelAssets.fromDirectory(dir).verify());
        assertDoesNotThrow(() -> ModelAssets.fromBytes(f).verify());
    }

    @Test
    void aTamperedFileIsNamedInTheError() throws Exception {
        Map<String, byte[]> f = synthetic();
        write(f);
        Files.write(dir.resolve("rec.onnx"), new byte[] {9, 8, 6});
        OcrException e = assertThrows(OcrException.class, () -> ModelAssets.fromDirectory(dir).verify());
        assertTrue(e.getMessage().contains("rec.onnx (checksum mismatch)"), e.getMessage());
        Map<String, byte[]> g = synthetic();
        g.put("det.onnx", new byte[] {0});
        e = assertThrows(OcrException.class, () -> ModelAssets.fromBytes(g).verify());
        assertTrue(e.getMessage().contains("det.onnx (checksum mismatch)"), e.getMessage());
    }

    @Test
    void missingFilesAreAllListed() throws Exception {
        Map<String, byte[]> f = synthetic();
        f.remove("det.yml");
        f.remove("rec.onnx");
        write(f);
        OcrException e = assertThrows(OcrException.class, () -> ModelAssets.fromDirectory(dir).verify());
        assertTrue(e.getMessage().contains("det.yml") && e.getMessage().contains("rec.onnx"), e.getMessage());
        assertThrows(OcrException.class, () -> ModelAssets.fromBytes(f).verify());
    }

    @Test
    void aManifestEntryWithoutAFileIsReported() throws Exception {
        Map<String, byte[]> f = synthetic();
        String extra = new String(f.get("checksums.sha256"), StandardCharsets.UTF_8) + "00".repeat(32) + "  extra.bin\n";
        f.put("checksums.sha256", extra.getBytes(StandardCharsets.UTF_8));
        OcrException e = assertThrows(OcrException.class, () -> ModelAssets.fromBytes(f).verify());
        assertTrue(e.getMessage().contains("extra.bin (missing)"), e.getMessage());
    }

    @Test
    void fingerprintUsesTheFirstTwelveHexOfDetAndRec() throws Exception {
        Map<String, byte[]> f = synthetic();
        String fp = ModelAssets.fromBytes(f).fingerprint();
        assertEquals("det=" + sha(f.get("det.onnx")).substring(0, 12) + " rec=" + sha(f.get("rec.onnx")).substring(0, 12), fp);
        assertEquals("det=? rec=?", ModelAssets.fromBytes(Map.of()).fingerprint());
    }

    @Test
    void decodeTableIsBlankPlusDictionaryPlusSpace() throws Exception {
        var table = ModelAssets.fromBytes(synthetic()).decodeTable();
        assertEquals(java.util.List.of("blank", "a", "b", "'", " "), table);
    }
}
