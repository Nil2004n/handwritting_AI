package notebookai.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The engine through its public API only (PixelImage + ModelAssets), on the deterministic rendered-print fixture.
 * Skipped, like the other model-dependent tests, when models/ is absent.
 */
class OcrEngineTest {

    private static final Path ROOT = Paths.get("").toAbsolutePath();
    private static final Path MODELS = ROOT.resolve("models");
    private static final Path FIXTURE = ROOT.resolve("src/test/resources/fixtures/print-900x500.png");
    private static final Path EXPECTED = ROOT.resolve("src/test/resources/fixtures/print-900x500.expected.txt");

    private static void requireModels() {
        Assumptions.assumeTrue(Files.isRegularFile(MODELS.resolve("rec.onnx")), "models missing");
    }

    private static String expectedText() throws Exception {
        return Files.readString(EXPECTED, StandardCharsets.UTF_8).stripTrailing();
    }

    @Test
    void recognizesTheFixtureFromADirectoryAndFromBytesIdentically() throws Exception {
        requireModels();
        PixelImage image = DesktopImageIO.decode(FIXTURE);
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        for (String n : ModelAssets.REQUIRED) {
            bytes.put(n, Files.readAllBytes(MODELS.resolve(n)));
        }
        try (OcrEngine fromDir = OcrEngine.open(ModelAssets.fromDirectory(MODELS));
                OcrEngine fromBytes = OcrEngine.open(ModelAssets.fromBytes(bytes))) {
            OcrResult a = fromDir.recognize(image);
            OcrResult b = fromBytes.recognize(image);
            assertEquals(expectedText(), a.combinedText());
            assertEquals(a.combinedText(), b.combinedText());
            assertEquals(a.regions().size(), b.regions().size());
            for (int i = 0; i < a.regions().size(); i++) {
                assertEquals(a.regions().get(i).text(), b.regions().get(i).text());
                assertEquals(java.util.Arrays.deepToString(a.regions().get(i).polygon()),
                        java.util.Arrays.deepToString(b.regions().get(i).polygon()));
            }
            assertEquals(900, a.imageWidth());
            assertEquals(500, a.imageHeight());
            assertEquals(fromDir.persistableIdentity(), fromBytes.persistableIdentity());
        }
    }

    @Test
    void closeWaitsForARunningCallAndLaterCallsFailCleanly() throws Exception {
        requireModels();
        PixelImage image = DesktopImageIO.decode(FIXTURE);
        OcrEngine engine = OcrEngine.open(ModelAssets.fromDirectory(MODELS));
        CompletableFuture<OcrResult> running = CompletableFuture.supplyAsync(() -> {
            try {
                return engine.recognize(image);
            } catch (OcrException e) {
                throw new IllegalStateException(e);
            }
        });
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (engine.activeCalls() == 0 && !running.isDone() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        Assumptions.assumeFalse(running.isDone(), "the call finished before it could be observed in flight");
        engine.close(); // must block until the in-flight call is done, never abort it
        assertTrue(running.isDone(), "close() returned while a recognition call was still running");
        assertEquals(expectedText(), running.get().combinedText());
        assertThrows(OcrException.class, () -> engine.recognize(image));
        assertThrows(OcrException.class, () -> engine.recognizeLine(image));
        engine.close(); // idempotent
    }

    @Test
    void anUnloadableModelFailsWithoutLeakingAndReportsOcrException() throws Exception {
        requireModels();
        for (String broken : new String[] {"det.onnx", "rec.onnx"}) {
            Map<String, byte[]> bytes = new LinkedHashMap<>();
            for (String n : ModelAssets.REQUIRED) {
                bytes.put(n, Files.readAllBytes(MODELS.resolve(n)));
            }
            byte[] garbage = "not an onnx model".getBytes(StandardCharsets.UTF_8);
            bytes.put(broken, garbage);
            // keep the manifest consistent with the garbage so the failure is ONNX Runtime's, not the checksum gate
            StringBuilder m = new StringBuilder();
            for (String n : new String[] {"det.onnx", "det.yml", "rec.onnx", "rec.yml"}) {
                byte[] h = MessageDigest.getInstance("SHA-256").digest(bytes.get(n));
                m.append(ModelAssets.hex(h)).append("  ").append(n).append('\n');
            }
            bytes.put("checksums.sha256", m.toString().getBytes(StandardCharsets.UTF_8));
            OcrException e = assertThrows(OcrException.class, () -> OcrEngine.open(ModelAssets.fromBytes(bytes)), broken);
            assertTrue(e.getMessage().contains("ONNX Runtime session failed"), e.getMessage());
        }
    }

    @Test
    void aChecksumFailureStopsBeforeAnySessionIsCreated() throws Exception {
        requireModels();
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        for (String n : ModelAssets.REQUIRED) {
            bytes.put(n, Files.readAllBytes(MODELS.resolve(n)));
        }
        bytes.get("rec.yml")[bytes.get("rec.yml").length - 1] ^= 1;
        OcrException e = assertThrows(OcrException.class, () -> OcrEngine.open(ModelAssets.fromBytes(bytes)));
        assertTrue(e.getMessage().contains("rec.yml (checksum mismatch)"), e.getMessage());
        assertFalse(e.getMessage().contains("ONNX"), e.getMessage());
    }
}
