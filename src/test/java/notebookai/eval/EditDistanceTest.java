package notebookai.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Metric correctness: empty inputs, insertions, deletions, substitutions. */
class EditDistanceTest {

    @Test
    void identicalStringsScoreZero() {
        assertEquals(0, EditDistance.levenshtein("abc".codePoints().toArray(), "abc".codePoints().toArray()));
        assertEquals(0.0, EditDistance.cer("Hello world", "Hello world"), 1e-12);
        assertEquals(0.0, EditDistance.wer("Hello world", "Hello world"), 1e-12);
    }

    @Test
    void emptyCases() {
        assertEquals(0.0, EditDistance.cer("", ""), 1e-12);
        assertEquals(0.0, EditDistance.wer("", ""), 1e-12);
        assertEquals(1.0, EditDistance.cer("", "x"), 1e-12);
        assertEquals(1.0, EditDistance.wer("", "x"), 1e-12);
        assertEquals(0, EditDistance.levenshtein(new int[0], new int[0]));
        assertEquals(3, EditDistance.levenshtein(new int[0], "abc".codePoints().toArray()));
    }

    @Test
    void insertionDeletionSubstitution() {
        assertEquals(1, EditDistance.levenshtein("abc".codePoints().toArray(), "abbc".codePoints().toArray()));
        assertEquals(1, EditDistance.levenshtein("abc".codePoints().toArray(), "ac".codePoints().toArray()));
        assertEquals(1, EditDistance.levenshtein("abc".codePoints().toArray(), "axc".codePoints().toArray()));
        // kitten -> sitting is the classic distance-3 example.
        assertEquals(
                3,
                EditDistance.levenshtein(
                        "kitten".codePoints().toArray(), "sitting".codePoints().toArray()));
    }

    @Test
    void cerNormalizesByReferenceLength() {
        // 1 substitution in 4 reference chars.
        assertEquals(0.25, EditDistance.cer("abcd", "axcd"), 1e-12);
        // Name case-sensitivity is preserved (no folding): 1 of 6 differs.
        assertEquals(1.0 / 6.0, EditDistance.cer("Debjoy", "debjoy"), 1e-12);
    }

    @Test
    void werTokenizesOnWhitespace() {
        assertEquals(0.5, EditDistance.wer("a b", "a c"), 1e-12);
        assertEquals(0.0, EditDistance.wer("a  b", "a b"), 1e-12);
        assertEquals(1.0 / 3.0, EditDistance.wer("a b c", "a b c d"), 1e-12);
    }

    @Test
    void multilineSplitsKeepEveryLine() {
        assertEquals(3, EditDistance.lines("a\nb\nc").length);
        assertEquals(2, EditDistance.lines("a\n").length);
        assertEquals(1, EditDistance.lines("").length);
    }

    @Test
    void manifestParsing() throws Exception {
        String manifest =
                "# comment\n"
                        + "\n"
                        + "img/a.jpg\t3\t-1.5\tfirst\\nsecond\\\\end\n"
                        + "/abs/b.png\t\t\tplain\n";
        java.util.List<Evaluate.Case> cases =
                Evaluate.parseManifest(manifest, java.nio.file.Paths.get("/repo"));
        assertEquals(2, cases.size());
        assertEquals(java.nio.file.Paths.get("/repo/img/a.jpg"), cases.get(0).image());
        assertEquals(3, cases.get(0).rotateCw());
        assertEquals(-1.5, cases.get(0).straighten(), 1e-12);
        assertEquals("first\nsecond\\end", cases.get(0).expected());
        assertEquals(java.nio.file.Paths.get("/abs/b.png"), cases.get(1).image());
        assertEquals(0, cases.get(1).rotateCw());
        assertEquals("plain", cases.get(1).expected());
    }
}
