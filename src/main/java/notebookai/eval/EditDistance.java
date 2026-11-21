package notebookai.eval;

/**
 * Levenshtein edit distance and CER/WER error rates.
 *
 * <p>Rules (fixed, documented here and in HANDOFF):
 * <ul>
 *   <li>Comparison is on Unicode code points, raw/verbatim: no case folding,
 *       no punctuation stripping, no whitespace collapsing. Whitespace
 *       differences (including line breaks) count as errors in CER.</li>
 *   <li>CER = distance(refChars, hypChars) / max(1, len(refChars)). Empty
 *       reference with empty hypothesis scores 0; empty reference with
 *       non-empty hypothesis scores 1.</li>
 *   <li>WER tokenizes on runs of whitespace ({@code \s+}); empty tokens are
 *       dropped. Same formula on token sequences.</li>
 *   <li>Line structure is reported separately (never folded into CER): line
 *       counts and per-line CER with positional (index) alignment.</li>
 * </ul>
 */
public final class EditDistance {

    private EditDistance() {}

    /** Levenshtein distance over Unicode code points. */
    public static int levenshtein(int[] a, int[] b) {
        int[] prev = new int[b.length + 1];
        int[] curr = new int[b.length + 1];
        for (int j = 0; j <= b.length; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length; i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length; j++) {
                int cost = a[i - 1] == b[j - 1] ? 0 : 1;
                curr[j] = Math.min(Math.min(prev[j] + 1, curr[j - 1] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length];
    }

    /** Character error rate (raw, verbatim). */
    public static double cer(String ref, String hyp) {
        int[] r = ref.codePoints().toArray();
        int[] h = hyp.codePoints().toArray();
        if (r.length == 0) {
            return h.length == 0 ? 0.0 : 1.0;
        }
        return (double) levenshtein(r, h) / r.length;
    }

    /** Word error rate (tokens split on whitespace runs). */
    public static double wer(String ref, String hyp) {
        String[] r = tokens(ref);
        String[] h = tokens(hyp);
        if (r.length == 0) {
            return h.length == 0 ? 0.0 : 1.0;
        }
        return (double) levenshteinStrings(r, h) / r.length;
    }

    static String[] tokens(String s) {
        String t = s.trim();
        if (t.isEmpty()) {
            return new String[0];
        }
        return t.split("\\s+");
    }

    private static int levenshteinStrings(String[] a, String[] b) {
        int[] prev = new int[b.length + 1];
        int[] curr = new int[b.length + 1];
        for (int j = 0; j <= b.length; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length; i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length; j++) {
                int cost = a[i - 1].equals(b[j - 1]) ? 0 : 1;
                curr[j] = Math.min(Math.min(prev[j] + 1, curr[j - 1] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length];
    }

    /** Splits into lines keeping all lines (trailing empties preserved). */
    public static String[] lines(String s) {
        return s.split("\n", -1);
    }
}
