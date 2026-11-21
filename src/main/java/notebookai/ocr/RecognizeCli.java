package notebookai.ocr;

import java.nio.file.Path;
import java.nio.file.Paths;
import notebookai.model.OcrResult;

/**
 * CLI entry for {@code ./gradlew recognizeImage -Pimage=/path/to.jpg
 * [-PdebugDir=/tmp/ocr-debug]}. Uses the same {@link OcrService} as the UI,
 * prints text/regions/timings, exits nonzero with an actionable message on
 * failure. No display, no network.
 */
public final class RecognizeCli {

    private RecognizeCli() {}

    public static void main(String[] args) {
        String imageArg = null;
        String debugDirArg = null;
        boolean recOnly = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--rec-only")) {
                recOnly = true;
            } else if (args[i].equals("--debug-dir") && i + 1 < args.length) {
                debugDirArg = args[++i];
            } else if (imageArg == null) {
                imageArg = args[i];
            }
        }
        if (imageArg == null) {
            System.err.println(
                    "Usage: recognizeImage -Pimage=/path/to/image.jpg [-PdebugDir=/tmp/ocr-debug]");
            System.exit(2);
        }
        Path projectRoot = Paths.get("").toAbsolutePath();
        try (OcrService service = OcrService.open(projectRoot)) {
            if (recOnly) { // Recognizer-only on a pre-cropped line image (diagnostics).
                CtcDecoder.Line line = service.recognizeLineImage(Paths.get(imageArg));
                System.out.printf("rec-only %s rec=%.3f text=\"%s\"%n", imageArg, line.score(), line.text());
                return;
            }
            OcrResult result =
                    debugDirArg == null
                            ? service.recognize(Paths.get(imageArg))
                            : service.recognize(Paths.get(imageArg), Paths.get(debugDirArg));
            System.out.println("Image: " + imageArg);
            System.out.println("Size: " + result.imageWidth() + "x" + result.imageHeight());
            System.out.println("Regions: " + result.regions().size());
            int i = 0;
            for (OcrResult.TextRegion region : result.regions()) {
                System.out.printf(
                        "[%d] box=[%d,%d,%d,%d] det=%.3f rec=%.3f %s%n",
                        i++,
                        region.box()[0],
                        region.box()[1],
                        region.box()[2],
                        region.box()[3],
                        region.detScore(),
                        region.recScore(),
                        region.text());
            }
            System.out.println("--- text ---");
            System.out.println(result.combinedText().isEmpty() ? "(no text recognized)" : result.combinedText());
            OcrResult.Timings t = result.timings();
            System.out.printf(
                    "Timings ms: decode=%.0f detPre=%.0f detInfer=%.0f detPost=%.0f crop=%.0f rec=%.0f total=%.0f%n",
                    t.decodeMs(),
                    t.detPreMs(),
                    t.detInferMs(),
                    t.detPostMs(),
                    t.cropMs(),
                    t.recMs(),
                    t.totalMs());
        } catch (OcrException e) {
            System.err.println("Recognition FAILED: " + e.getMessage());
            System.exit(1);
        }
    }
}
