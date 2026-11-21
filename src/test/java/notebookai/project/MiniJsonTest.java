package notebookai.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MiniJsonTest {

    @Test
    void roundTripsArbitraryStringsExactly() throws Exception {
        String nasty = "line1\nline2\r\nTab\there \"quoted\" back\\slash \u0000\u001f\u007f \u2028\u2029 emoji \uD83D\uDE00"
                + " lone-high \uD800 x lone-low \uDC00 y  trailing   ";
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("s", nasty);
        m.put("n", null);
        m.put("l", List.of(1L, 2.5, true, "x"));
        Object back = MiniJson.parse(MiniJson.write(m));
        assertEquals(m, back);
        assertEquals(nasty, ((Map<?, ?>) back).get("s"));
    }

    @Test
    void doublesRoundTripExactly() throws Exception {
        for (double d : new double[] {0.1, -3.3333333333333335, 1e-9, 1234567.891, 2.5, -0.0}) {
            Object o = MiniJson.parse(MiniJson.write(List.of(d)));
            assertEquals(Double.valueOf(d), ((List<?>) o).get(0).getClass() == Long.class
                    ? Double.valueOf(((Long) ((List<?>) o).get(0)).doubleValue()) : ((List<?>) o).get(0));
        }
    }

    @Test
    void rejectsMalformedAndRiskyInput() {
        for (String bad : new String[] {"", "{", "[1,]", "{\"a\":1,}", "{\"a\":1}x", "'a'", "{\"a\":1,\"a\":2}",
                "[01]", "[1.]", "[.5]", "\"abc", "\"\\x\"", "\"\\u12\"", "\"raw\ncontrol\"", "nul", "[1e]", "{1:2}"}) {
            assertThrows(MiniJson.JsonException.class, () -> MiniJson.parse(bad), bad);
        }
    }

    @Test
    void enforcesDepthLimit() {
        String deep = "[".repeat(MiniJson.MAX_DEPTH + 5) + "]".repeat(MiniJson.MAX_DEPTH + 5);
        assertThrows(MiniJson.JsonException.class, () -> MiniJson.parse(deep));
        String ok = "[".repeat(MiniJson.MAX_DEPTH) + "]".repeat(MiniJson.MAX_DEPTH);
        try {
            MiniJson.parse(ok);
        } catch (MiniJson.JsonException e) {
            throw new AssertionError("depth exactly at the limit must parse: " + e.getMessage());
        }
    }

    @Test
    void refusesToWriteNonFiniteNumbers() {
        assertThrows(MiniJson.JsonException.class, () -> MiniJson.write(List.of(Double.NaN)));
        assertThrows(MiniJson.JsonException.class, () -> MiniJson.write(List.of(Double.POSITIVE_INFINITY)));
    }
}
