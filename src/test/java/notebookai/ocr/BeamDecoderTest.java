package notebookai.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class BeamDecoderTest {

    private static final List<String> TABLE = List.of("blank", "A", "B", " ");

    private static float[] f(float blank, float a, float b, float sp) {
        return new float[] {blank, a, b, sp};
    }

    @Test
    void beamSumsAlignmentsWhereGreedyCommitsToBlank() throws Exception {
        float[][] p = {f(0.6f, 0.4f, 0, 0), f(0.6f, 0.4f, 0, 0)};
        assertEquals("", CtcDecoder.decode(p, TABLE).text()); // greedy: blank, blank
        assertEquals("A", CtcDecoder.decodeBeam(p, TABLE, 8)); // P("A") = 0.64 > P("") = 0.36
    }

    @Test
    void beamAgreesWithGreedyOnConfidentOutputAndHandlesRepeats() throws Exception {
        float[][] p = {
            f(0.01f, 0.98f, 0.01f, 0), f(0.98f, 0.01f, 0.01f, 0), f(0.01f, 0.98f, 0.01f, 0),
            f(0.98f, 0.01f, 0.01f, 0), f(0.01f, 0.01f, 0.98f, 0)
        };
        String greedy = CtcDecoder.decode(p, TABLE).text();
        assertEquals("AAB", greedy); // blank between the A's keeps them distinct
        assertEquals(greedy, CtcDecoder.decodeBeam(p, TABLE, 8));
    }

    @Test
    void experimentsAreOffByDefault() {
        assertEquals(1.0, Experiments.cropPad());
        assertEquals(0, Experiments.beamWidth());
        assertEquals("", Experiments.describe());
    }
}
