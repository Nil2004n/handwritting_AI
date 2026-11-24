package notebookai.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** CTC greedy decode: duplicates, blanks, mapping offset, scores, errors. */
class CtcDecoderTest {

    private static final List<String> DICT = List.of("blank", "a", "b", "c", " ");

    private static float[][] steps(int classes, int[] best, float[] conf) {
        float[][] logits = new float[best.length][classes];
        for (int t = 0; t < best.length; t++) {
            for (int c = 0; c < classes; c++) {
                logits[t][c] = c == best[t] ? conf[t] : 0.01f;
            }
        }
        return logits;
    }

    @Test
    void collapsesDuplicatesAndDropsBlanks() throws Exception {
        // blank, a, a, blank, b, b, blank  ->  "ab"
        CtcDecoder.Line line =
                CtcDecoder.decode(
                        steps(5, new int[] {0, 1, 1, 0, 2, 2, 0}, new float[] {9, 8, 8, 9, 7, 7, 9}),
                        DICT);
        assertEquals("ab", line.text());
        // Mean over the two kept positions: (8 + 7) / 2.
        assertEquals(7.5, line.score(), 1e-6);
    }

    @Test
    void repeatedCharAcrossBlankIsKept() throws Exception {
        // a, blank, a  ->  "aa" (duplicate collapse needs adjacency).
        CtcDecoder.Line line =
                CtcDecoder.decode(
                        steps(5, new int[] {1, 0, 1}, new float[] {8, 9, 6}), DICT);
        assertEquals("aa", line.text());
        assertEquals(7.0, line.score(), 1e-6);
    }

    @Test
    void allBlankGivesEmptyTextAndZeroScore() throws Exception {
        CtcDecoder.Line line =
                CtcDecoder.decode(steps(5, new int[] {0, 0, 0}, new float[] {9, 9, 9}), DICT);
        assertEquals("", line.text());
        assertEquals(0.0, line.score(), 1e-9);
    }

    @Test
    void classOneMapsToFirstDictionaryEntry() throws Exception {
        CtcDecoder.Line line =
                CtcDecoder.decode(steps(5, new int[] {3}, new float[] {5}), DICT);
        assertEquals("c", line.text());
    }

    @Test
    void outOfRangeClassRaisesCleanError() {
        // 6 columns exist but the table only addresses 0..4.
        OcrException failure =
                assertThrows(
                        OcrException.class,
                        () -> CtcDecoder.decode(steps(6, new int[] {5}, new float[] {5}), DICT));
        assertTrue(failure.getMessage().contains("outside"), failure.getMessage());
    }

    @Test
    void trailingSpaceClassDecodesToSpace() throws Exception {
        // Class 4 is the space appended by use_space_char.
        CtcDecoder.Line line =
                CtcDecoder.decode(steps(5, new int[] {1, 4}, new float[] {8, 7}), DICT);
        assertEquals("a ", line.text());
    }

    @Test
    void emptyInputGivesEmptyLine() throws Exception {
        CtcDecoder.Line line = CtcDecoder.decode(new float[0][], DICT);
        assertEquals("", line.text());
        assertEquals(0.0, line.score(), 1e-9);
    }
}
