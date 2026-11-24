package notebookai.assist;

import static org.junit.jupiter.api.Assertions.assertEquals;

import notebookai.assist.SafeReplace.Context;
import notebookai.assist.SafeReplace.Result;
import notebookai.spelling.IssueKind;
import notebookai.spelling.SpellIssue;
import org.junit.jupiter.api.Test;

class SafeReplaceTest {

    private static final Context CTX = new Context(1, "p1", 7);

    private static SpellIssue issue(String text, String token, int from) {
        int s = text.indexOf(token, from);
        return new SpellIssue(s, s + token.length(), token, IssueKind.UNKNOWN_WORD);
    }

    @Test
    void validatesIdentityRangeAndTokenBeforeAllowingAReplacement() {
        String text = "the teh dog teh";
        SpellIssue second = issue(text, "teh", 5);
        assertEquals(Result.APPLY, SafeReplace.validate(CTX, CTX, text, second, "the"));
        assertEquals(Result.STALE_CONTEXT, SafeReplace.validate(CTX, new Context(2, "p1", 7), text, second, "the"));   // other project
        assertEquals(Result.STALE_CONTEXT, SafeReplace.validate(CTX, new Context(1, "p2", 7), text, second, "the"));   // other page
        assertEquals(Result.STALE_CONTEXT, SafeReplace.validate(CTX, new Context(1, "p1", 8), text, second, "the"));   // text changed
        assertEquals(Result.STALE_CONTEXT, SafeReplace.validate(null, CTX, text, second, "the"));
        assertEquals(Result.STALE_CONTEXT, SafeReplace.validate(new Context(1, null, 7), new Context(1, null, 7), text, second, "the"));
        assertEquals(Result.STALE_TEXT, SafeReplace.validate(CTX, CTX, "the teh dog xyz", second, "the"));              // same revision, other characters
        assertEquals(Result.INVALID_RANGE, SafeReplace.validate(CTX, CTX, "the", second, "the"));                       // text shorter than the range
    }

    @Test
    void rejectsUnusableReplacements() {
        String text = "a teh b";
        SpellIssue i = issue(text, "teh", 0);
        for (String bad : new String[] {null, "", "two\nlines", "cr\rhere"}) {
            assertEquals(Result.INVALID_REPLACEMENT, SafeReplace.validate(CTX, CTX, text, i, bad), String.valueOf(bad));
        }
        assertEquals(Result.APPLY, SafeReplace.validate(CTX, CTX, text, i, "a lot of words"), "spaces inside a replacement are fine");
    }

    @Test
    void applyChangesOnlyTheRangeAndKeepsSurroundings() {
        String text = "teh  teh,\n(teh) 😀teh";
        SpellIssue third = issue(text, "teh", text.indexOf("(teh)") + 1);
        assertEquals("teh  teh,\n(the) 😀teh", SafeReplace.apply(text, third, "the"));
        SpellIssue last = issue(text, "teh", text.lastIndexOf("teh"));
        assertEquals("teh  teh,\n(teh) 😀the", SafeReplace.apply(text, last, "the")); // offsets are right after a supplementary character
        SpellIssue first = issue(text, "teh", 0);
        assertEquals("The  teh,\n(teh) 😀teh", SafeReplace.apply(text, first, "The"));
    }
}
