package notebookai.eval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Character alignment (Levenshtein backtrace) used to classify recognition
 * errors as substitutions, insertions or deletions instead of eyeballing.
 * Ties prefer match/substitute, then deletion, then insertion (deterministic).
 */
public final class Alignment {

    /** One edit operation. {@code type}: '=' match, 'S' substitute, 'I' hypothesis-only, 'D' reference-only. */
    public record Op(char type, int refIdx, int hypIdx, int ref, int hyp) {}

    private Alignment() {}

    public static List<Op> align(int[] ref, int[] hyp) {
        int n = ref.length;
        int m = hyp.length;
        int[][] d = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= m; j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int cost = ref[i - 1] == hyp[j - 1] ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
            }
        }
        List<Op> ops = new ArrayList<>();
        int i = n;
        int j = m;
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && d[i][j] == d[i - 1][j - 1] + (ref[i - 1] == hyp[j - 1] ? 0 : 1)) {
                boolean same = ref[i - 1] == hyp[j - 1];
                ops.add(new Op(same ? '=' : 'S', i - 1, j - 1, ref[i - 1], hyp[j - 1]));
                i--;
                j--;
            } else if (i > 0 && d[i][j] == d[i - 1][j] + 1) {
                ops.add(new Op('D', i - 1, -1, ref[i - 1], -1));
                i--;
            } else {
                ops.add(new Op('I', -1, j - 1, -1, hyp[j - 1]));
                j--;
            }
        }
        Collections.reverse(ops);
        return ops;
    }

    /** Number of non-match operations (equals the Levenshtein distance). */
    public static int errors(List<Op> ops) {
        int e = 0;
        for (Op o : ops) {
            if (o.type() != '=') {
                e++;
            }
        }
        return e;
    }
}
