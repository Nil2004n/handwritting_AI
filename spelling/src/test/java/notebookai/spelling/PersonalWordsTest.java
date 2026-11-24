package notebookai.spelling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Entry validation, normalization, matching, duplicates and revisions of the personal/ignore word list. */
class PersonalWordsTest {

    @Test
    void validEntriesAreNormalized() {
        assertEquals("don't", PersonalWords.normalizeEntry("  don’t "));
        assertEquals("café", PersonalWords.normalizeEntry("café"));        // NFC
        assertEquals("Bhattacharya", PersonalWords.normalizeEntry("Bhattacharya"));
        assertEquals("state-of-art", PersonalWords.normalizeEntry("state-of-art"));
    }

    @Test
    void invalidEntriesAreRejected() {
        for (String bad : Arrays.asList(null, "", "   ", "a", "-", "'", "-word", "word-", "'word", "word'", "two words", "abc123", "12", "x_y",
                "tab\tword", "a".repeat(PersonalWords.MAX_ENTRY + 1), "😀😀", "wor.d", "a@b")) {
            assertNull(PersonalWords.normalizeEntry(bad), String.valueOf(bad));
            assertEquals(PersonalWords.AddResult.INVALID, new PersonalWords().add(bad));
        }
        assertEquals("a".repeat(PersonalWords.MAX_ENTRY), PersonalWords.normalizeEntry("a".repeat(PersonalWords.MAX_ENTRY)));
    }

    @Test
    void addRemoveDuplicatesAndRevision() {
        PersonalWords w = new PersonalWords();
        long r0 = w.revision();
        assertEquals(PersonalWords.AddResult.ADDED, w.add("ocr"));
        assertTrue(w.revision() > r0);
        long r1 = w.revision();
        assertEquals(PersonalWords.AddResult.DUPLICATE, w.add("ocr"));
        assertEquals(PersonalWords.AddResult.DUPLICATE, w.add("  ocr  "));
        assertEquals(r1, w.revision(), "a duplicate changes nothing");
        assertEquals(PersonalWords.AddResult.ADDED, w.add("OCR"));   // differs only in case: a different entry
        assertEquals(List.of("OCR", "ocr"), w.entries());
        assertTrue(w.remove("ocr"));
        assertFalse(w.remove("ocr"), "absent");
        assertFalse(w.remove("nope"));
        assertEquals(List.of("OCR"), w.entries());
        long r2 = w.revision();
        w.clear();
        assertTrue(w.revision() > r2);
        w.clear();
    }

    @Test
    void matchingFollowsTheDocumentedCaseRules() {
        PersonalWords w = new PersonalWords();
        w.add("ocr");        // lower-case: any case
        w.add("Zoltan");     // Title: itself and UPPER
        w.add("iPhoneX");    // mixed: exact only
        assertTrue(w.accepts("ocr") && w.accepts("Ocr") && w.accepts("OCR"));
        assertTrue(w.accepts("Zoltan") && w.accepts("ZOLTAN"));
        assertFalse(w.accepts("zoltan"));
        assertTrue(w.accepts("iPhoneX"));
        assertFalse(w.accepts("iphonex"));
        assertFalse(w.accepts("zoltans"));
        assertTrue(new PersonalWords().add("don't") == PersonalWords.AddResult.ADDED);
        PersonalWords apo = new PersonalWords();
        apo.add("don’t");
        assertTrue(apo.accepts("don't") && apo.accepts("DON’T"));
        assertFalse(apo.accepts(null));
    }

    @Test
    void replaceAllSkipsMalformedEntriesAndCountsThem() {
        PersonalWords w = new PersonalWords();
        int skipped = w.replaceAll(Arrays.asList("good", "also-good", "bad entry", "123", "", "good"));
        assertEquals(3, skipped);
        assertEquals(List.of("also-good", "good"), w.entries());
        long rev = w.revision();
        w.replaceAll(Arrays.asList("good", "also-good"));
        assertEquals(rev, w.revision(), "same content: no revision change");
    }
}
