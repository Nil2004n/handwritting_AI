package ai.notebookai.proof;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Structured, machine-readable outcome of the proof run (JSON written by hand: no library needed). */
public final class ProofResult {

    /** One named check: pass/fail plus a short detail. */
    public static final class Check {
        public final String name;
        public final boolean passed;
        public final String detail;

        Check(String name, boolean passed, String detail) {
            this.name = name;
            this.passed = passed;
            this.detail = detail;
        }
    }

    public final List<Check> checks = new ArrayList<>();
    public String recognizedText = "";
    public String expectedText = "";
    public int regionCount;
    public long loadMillis;
    public long firstRecognizeMillis;
    public long repeatedMeanMillis;
    public long nativeHeapGrowthBytes;

    void check(String name, boolean passed, String detail) {
        checks.add(new Check(name, passed, detail));
    }

    public boolean allPassed() {
        for (Check c : checks) {
            if (!c.passed) {
                return false;
            }
        }
        return !checks.isEmpty();
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                b.append('\\').append(c);
            } else if (c == '\n') {
                b.append("\\n");
            } else if (c < 0x20) {
                b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    public String toJson() {
        StringBuilder b = new StringBuilder("{\"allPassed\":").append(allPassed());
        b.append(",\"regions\":").append(regionCount);
        b.append(",\"recognizedText\":\"").append(esc(recognizedText)).append('"');
        b.append(",\"expectedText\":\"").append(esc(expectedText)).append('"');
        b.append(",\"loadMillis\":").append(loadMillis).append(",\"firstRecognizeMillis\":").append(firstRecognizeMillis);
        b.append(",\"repeatedMeanMillis\":").append(repeatedMeanMillis).append(",\"nativeHeapGrowthBytes\":").append(nativeHeapGrowthBytes);
        b.append(",\"checks\":[");
        for (int i = 0; i < checks.size(); i++) {
            Check c = checks.get(i);
            b.append(i == 0 ? "" : ",").append("{\"name\":\"").append(esc(c.name)).append("\",\"passed\":").append(c.passed)
                    .append(",\"detail\":\"").append(esc(c.detail)).append("\"}");
        }
        return b.append("]}").toString();
    }
}
