package notebookai.assist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import notebookai.spelling.IssueKind;
import notebookai.spelling.SpellIssue;
import org.junit.jupiter.api.Test;

class IgnoredOccurrencesTest {

    private static SpellIssue i(int s, String t) {
        return new SpellIssue(s, s + t.length(), t, IssueKind.UNKNOWN_WORD);
    }

    @Test
    void hidesExactlyOneOccurrenceOnOnePage() {
        List<SpellIssue> raw = List.of(i(0, "teh"), i(4, "wrd"), i(8, "teh"), i(12, "teh"));
        IgnoredOccurrences ig = new IgnoredOccurrences();
        assertTrue(ig.ignore("p1", raw, raw.get(2)));                         // the second "teh"
        assertEquals(List.of(raw.get(0), raw.get(1), raw.get(3)), ig.visible("p1", raw));
        assertEquals(raw, ig.visible("p2", raw), "another page is unaffected");
        assertFalse(ig.ignore("p1", raw, raw.get(2)), "ignoring twice changes nothing");
        assertEquals(1, ig.size());
    }

    @Test
    void ordinalSurvivesOffsetShiftsThatLeaveEarlierTokensAlone() {
        IgnoredOccurrences ig = new IgnoredOccurrences();
        List<SpellIssue> before = List.of(i(0, "teh"), i(4, "teh"));
        ig.ignore("p", before, before.get(1));
        List<SpellIssue> after = List.of(i(0, "teh"), i(9, "teh"));          // text inserted between them: same ordinal
        assertEquals(List.of(after.get(0)), ig.visible("p", after));
    }

    @Test
    void unknownIssueAndClearing() {
        IgnoredOccurrences ig = new IgnoredOccurrences();
        assertFalse(ig.ignore("p", List.of(i(0, "teh")), i(5, "teh")), "not one of the issues");
        ig.ignore("p", List.of(i(0, "teh")), i(0, "teh"));
        ig.ignore("q", List.of(i(0, "teh")), i(0, "teh"));
        ig.clearPage("p");
        assertEquals(1, ig.size());
        ig.clear();
        assertEquals(0, ig.size());
    }
}
