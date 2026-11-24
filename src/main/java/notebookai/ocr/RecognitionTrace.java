package notebookai.ocr;

import java.util.ArrayList;
import java.util.List;

/**
 * Diagnostic data recorded by {@link OcrEngine} when a trace is requested: every detector candidate and every recognized
 * region BEFORE drop_score filtering. Pure data (no files, no images, no AWT); the desktop {@code DebugTrace} turns it
 * into debug files. Package-private: not part of the stable engine API.
 */
final class RecognitionTrace {

    /** One region that reached recognition (all of them, kept or dropped). */
    record RegionTrace(
            String id,
            int candidateIndex,
            int readingOrder,
            double[][] polygon,
            double detScore,
            int cropW,
            int cropH,
            boolean rotated,
            String rawText,
            double recScore,
            boolean kept,
            byte[] cropBgr) {}

    int srcW;
    int srcH;
    String exifNote = "";
    int detW;
    int detH;
    double scaleX; // source px per probability-map px
    double scaleY;
    float[][] prob;
    final List<DbPostprocess.Candidate> candidates = new ArrayList<>();
    final List<RegionTrace> regions = new ArrayList<>();
    String finalText = "";
}
