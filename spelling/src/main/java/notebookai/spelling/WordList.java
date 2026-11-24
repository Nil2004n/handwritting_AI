package notebookai.spelling;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One language's dictionary: a full list plus a smaller "common words" list used only as a ranking tier. Immutable after construction, hence safe for
 * concurrent use.
 *
 * <p>Lookup: an entry is matched case-aware like Hunspell does. A lower-case token needs the lower-case entry; a Title-case token matches the
 * Title-case entry or the lower-case entry (a sentence start); an UPPER-CASE token matches the entry in any case. A possessive {@code 's} is accepted when
 * the base word is known.
 *
 * <p>Suggestions: every entry whose length is within the allowed edit distance is compared with a bounded Damerau-Levenshtein (optimal string alignment)
 * distance; at most 2 edits (1 for tokens of up to 3 letters). Ranking is a documented heuristic (see {@link #suggest}), deterministic for a given list.
 */
final class WordList {

    private final Set<String> all;
    private final Set<String> common;
    private final String[] lowers;     // lower-case form per entry
    private final String[] entries;    // entry as listed
    private final Map<Integer, int[]> byLength; // length of lowers[i] -> entry indexes

    WordList(Collection<String> allEntries, Collection<String> commonEntries) {
        this.all = new HashSet<>(allEntries);
        this.common = new HashSet<>(commonEntries);
        List<String> list = new ArrayList<>(all);
        java.util.Collections.sort(list); // deterministic order regardless of the input collection
        this.entries = list.toArray(new String[0]);
        this.lowers = new String[entries.length];
        Map<Integer, List<Integer>> tmp = new HashMap<>();
        for (int i = 0; i < entries.length; i++) {
            lowers[i] = Words.lower(Words.key(entries[i]));
            tmp.computeIfAbsent(lowers[i].length(), k -> new ArrayList<>()).add(i);
        }
        this.byLength = new HashMap<>();
        for (Map.Entry<Integer, List<Integer>> e : tmp.entrySet()) {
            int[] a = new int[e.getValue().size()];
            for (int j = 0; j < a.length; j++) {
                a[j] = e.getValue().get(j);
            }
            byLength.put(e.getKey(), a);
        }
    }

    int size() {
        return all.size();
    }

    int commonSize() {
        return common.size();
    }

    /** Whether {@code key} (already {@link Words#key normalized}) is a known word given its case {@code shape}. */
    boolean known(String key, Words.Shape shape) {
        if (knownExact(key, shape)) {
            return true;
        }
        // possessive: base word + 's
        if (key.length() > 2 && key.endsWith("'s")) {
            String base = key.substring(0, key.length() - 2);
            return knownExact(base, Words.shape(base));
        }
        return false;
    }

    private boolean knownExact(String key, Words.Shape shape) {
        String lower = Words.lower(key);
        switch (shape) {
            case LOWER:
                return all.contains(key);
            case TITLE:
                return all.contains(key) || all.contains(lower);
            case UPPER:
                return all.contains(key) || all.contains(lower) || all.contains(Words.title(lower));
            default:
                return true; // mixed/caseless are never flagged
        }
    }

    /**
     * Ranked candidates for {@code key}. Rank = (edit distance, not a plain adjacent transposition, first letter differs, candidate is a proper
     * noun/acronym while the token is lower-case, not in the common tier, candidate text). It is a deterministic heuristic, not a probability.
     */
    List<String> suggest(String key, Words.Shape shape, int max) {
        String q = Words.lower(key);
        int n = q.length();
        int maxDist = n <= 3 ? 1 : 2;
        List<Candidate> found = new ArrayList<>();
        for (int len = Math.max(1, n - maxDist); len <= n + maxDist; len++) {
            int[] idx = byLength.get(len);
            if (idx == null) {
                continue;
            }
            for (int i : idx) {
                int d = osa(q, lowers[i], maxDist);
                if (d < 0 || d == 0) {
                    continue; // too far, or identical to the (unknown) token ignoring case: not a correction
                }
                found.add(new Candidate(entries[i], lowers[i], d, q));
            }
        }
        Comparator<Candidate> order = Comparator
                .comparingInt((Candidate c) -> c.distance)
                .thenComparingInt(c -> c.plainTransposition ? 0 : 1)
                .thenComparingInt(c -> c.firstLetterDiffers ? 1 : 0)
                .thenComparingInt(c -> shape == Words.Shape.LOWER && !c.entry.equals(c.lower) ? 1 : 0)
                .thenComparingInt(c -> common.contains(c.entry) ? 0 : 1)
                .thenComparing(c -> c.lower)
                .thenComparing(c -> c.entry);
        found.sort(order);
        Set<String> out = new LinkedHashSet<>();
        for (Candidate c : found) {
            out.add(present(c, shape));
            if (out.size() >= max) {
                break;
            }
        }
        return new ArrayList<>(out);
    }

    private static String present(Candidate c, Words.Shape shape) {
        switch (shape) {
            case TITLE:
                return c.entry.equals(c.lower) ? Words.title(c.lower) : c.entry;
            case UPPER:
                return Words.upper(c.lower);
            default:
                return c.entry;
        }
    }

    private static final class Candidate {
        final String entry;
        final String lower;
        final int distance;
        final boolean plainTransposition;
        final boolean firstLetterDiffers;

        Candidate(String entry, String lower, int distance, String query) {
            this.entry = entry;
            this.lower = lower;
            this.distance = distance;
            this.plainTransposition = distance == 1 && lower.length() == query.length() && sameLetters(lower, query);
            this.firstLetterDiffers = lower.isEmpty() || query.isEmpty() || lower.charAt(0) != query.charAt(0);
        }
    }

    private static boolean sameLetters(String a, String b) {
        char[] x = a.toCharArray();
        char[] y = b.toCharArray();
        Arrays.sort(x);
        Arrays.sort(y);
        return Arrays.equals(x, y);
    }

    /** Optimal-string-alignment (Damerau-Levenshtein with adjacent transpositions) distance, or -1 when it exceeds {@code max}. */
    static int osa(String a, String b, int max) {
        int n = a.length();
        int m = b.length();
        if (Math.abs(n - m) > max) {
            return -1;
        }
        int[] prev2 = new int[m + 1];
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int v = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    v = Math.min(v, prev2[j - 2] + 1);
                }
                cur[j] = v;
                rowMin = Math.min(rowMin, v);
            }
            if (rowMin > max) {
                return -1;
            }
            int[] t = prev2;
            prev2 = prev;
            prev = cur;
            cur = t;
        }
        return prev[m] <= max ? prev[m] : -1;
    }
}
