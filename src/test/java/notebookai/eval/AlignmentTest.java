package notebookai.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class AlignmentTest {

    private static List<Alignment.Op> a(String r, String h) {
        return Alignment.align(r.codePoints().toArray(), h.codePoints().toArray());
    }

    @Test
    void errorCountEqualsLevenshteinDistance() {
        for (String[] p : new String[][] {{"kitten", "sitting"}, {"ITSELF", "ITSIELF"}, {"", "ab"}, {"ab", ""}, {"same", "same"}}) {
            int expected = EditDistance.levenshtein(p[0].codePoints().toArray(), p[1].codePoints().toArray());
            assertEquals(expected, Alignment.errors(a(p[0], p[1])), p[0] + "->" + p[1]);
        }
    }

    @Test
    void classifiesInsertionSubstitutionAndDeletion() {
        List<Alignment.Op> ins = a("ITSELF", "ITSIELF");
        assertEquals(1, Alignment.errors(ins));
        assertEquals(1, ins.stream().filter(o -> o.type() == 'I').count());
        assertEquals('I', ins.stream().filter(o -> o.type() == 'I').findFirst().get().hyp());
        List<Alignment.Op> sub = a("debjoy", "dehjay");
        assertEquals(2, sub.stream().filter(o -> o.type() == 'S').count());
        List<Alignment.Op> del = a("abc", "ac");
        assertEquals('D', del.stream().filter(o -> o.type() != '=').findFirst().get().type());
    }
}
