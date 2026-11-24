package notebookai.spelling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Checker rules on a small, controlled dictionary: deterministic, independent of any real dictionary version. */
class CheckerBehaviorTest {

    private static final List<String> WORDS = Arrays.asList(
            "the", "quick", "brown", "fox", "jumps", "over", "lazy", "dog", "dogs", "don't", "cannot", "color", "Paris", "Einstein", "hello", "world",
            "receive", "relieve", "which", "with", "wish", "witch", "wick", "tea", "ten", "well", "known", "well-known", "mother-in-law", "café", "naïve",
            "cat", "cats", "it's", "price", "total", "and", "or", "cost", "is", "a", "I", "NASA", "page", "text", "word", "words", "spelling", "mistake", "see", "now", "mail", "today", "in", "or", "call",
            "fact", "end", "Next", "bin", "toys");
    private static final List<String> COMMON = Arrays.asList("the", "dog", "with", "which", "wish", "tea", "ten", "cat", "and", "is");

    private final SpellBackend backend = WordListBackend.ofWords(SpellLanguage.EN_US, WORDS, COMMON);

    private SpellCheckResult check(String text) throws SpellException {
        return backend.check(text, SpellLanguage.EN_US, WordFilter.NONE);
    }

    private List<String> tokens(String text) throws SpellException {
        List<String> out = new ArrayList<>();
        for (SpellIssue i : check(text).issues()) {
            assertEquals(i.token(), text.substring(i.start(), i.end()), "range must select exactly the token");
            out.add(i.token());
        }
        return out;
    }

    @Test
    void knownWordsAreNotFlaggedAndUnknownOnesAreWithExactRanges() throws Exception {
        assertEquals(List.of(), tokens("The quick brown fox jumps over the lazy dog."));
        String text = "The quikc brown fox jumpz over";
        SpellCheckResult r = check(text);
        assertEquals(2, r.issues().size());
        assertEquals(new SpellIssue(4, 9, "quikc", IssueKind.UNKNOWN_WORD), r.issues().get(0));
        assertEquals(new SpellIssue(20, 25, "jumpz", IssueKind.UNKNOWN_WORD), r.issues().get(1));
        assertEquals(text.length(), r.textLength());
        assertTrue(r.tokensChecked() >= 5);
    }

    @Test
    void caseHandling() throws Exception {
        assertEquals(List.of(), tokens("Hello HELLO hello"));          // lower entry accepts Title and UPPER
        assertEquals(List.of(), tokens("Paris PARIS"));                // Title entry accepts itself and UPPER
        assertEquals(List.of("paris"), tokens("paris"));               // ...but not the lower-case form
        assertEquals(List.of("Teh"), tokens("Teh"));                   // Title-case unknown
        assertEquals(IssueKind.UNKNOWN_CAPITALIZED, check("Teh").issues().get(0).kind());
        assertEquals(List.of("TEHS"), tokens("TEHS"));                 // 4+ letter ALL CAPS unknown is flagged
        assertEquals(List.of(), tokens("XYZ OCR"));                    // up to 3 letters: acronym heuristic, skipped
        assertEquals(List.of(), tokens("iPhone McDonald O'Brien eBay"));// mixed case is never flagged
        assertEquals(List.of(), tokens("NASA"));                       // listed acronym
    }

    @Test
    void apostrophesContractionsAndPossessives() throws Exception {
        assertEquals(List.of(), tokens("don't it's"));
        assertEquals(List.of(), tokens("don\u2019t it\u2019s"));       // curly apostrophes look up the same, ranges unchanged
        assertEquals(List.of("dont"), tokens("dont"));
        assertEquals(List.of(), tokens("the dog's cat's"));            // possessive 's accepted when the base word is known
        assertEquals(List.of("dogz's"), tokens("dogz's"));
        assertEquals(List.of(), tokens("the dogs' toys 'text' words"));// trailing/leading apostrophes are not part of a word
        SpellIssue curly = check("dont\u2019t").issues().get(0);
        assertEquals(0, curly.start());
        assertEquals(6, curly.end());
    }

    @Test
    void hyphenatedWords() throws Exception {
        assertEquals(List.of(), tokens("well-known mother-in-law"));
        assertEquals(List.of(), tokens("well-cat"));                   // parts known individually
        String text = "a well-knwon fact";
        SpellIssue i = check(text).issues().get(0);
        assertEquals("knwon", i.token());                              // only the unknown part, not the whole compound
        assertEquals(text.indexOf("knwon"), i.start());
        assertEquals(List.of(), tokens("well\u2010known"));            // Unicode hyphen
    }

    @Test
    void accentedLettersAndCombiningMarks() throws Exception {
        assertEquals(List.of(), tokens("café naïve"));
        String decomposed = "cafe\u0301 and nai\u0308ve";             // e + combining acute, i + combining diaeresis
        assertEquals(List.of(), tokens(decomposed));
        String text = "cafe\u0301z cafe\u0301";
        SpellIssue i = check(text).issues().get(0);
        assertEquals("cafe\u0301z", i.token());
        assertEquals(0, i.start());
        assertEquals(6, i.end());                                      // the combining mark is part of the range
    }

    @Test
    void rangesAreCorrectAfterSupplementaryCharacters() throws Exception {
        String text = "\uD83D\uDE00 teh \uD835\uDC9C wrd \uD83D\uDE00\uD83D\uDE00 dogz";
        SpellCheckResult r = check(text);
        assertEquals(List.of("teh", "wrd", "dogz"), r.issues().stream().map(SpellIssue::token).toList());
        for (SpellIssue i : r.issues()) {
            assertEquals(i.token(), text.substring(i.start(), i.end()));
        }
        // a supplementary letter inside a word does not shift later ranges
        String inside = "hello \uD835\uDC00bc dogz";
        assertEquals("dogz", check(inside).issues().get(check(inside).issues().size() - 1).token());
    }

    @Test
    void newlinesPunctuationNumbersAndDecimals() throws Exception {
        String text = "Price: $12.50 total;\nteh\r\nthe,teh.wrd";
        assertEquals(List.of("teh", "teh", "wrd"), tokens(text));
        assertEquals(List.of(), tokens("12 3.5 1,000 $9.99 50% 2nd mc2 x1y user_id"));
        assertEquals(List.of(), tokens(""));
        assertEquals(List.of(), tokens("   \n\t  "));
    }

    @Test
    void repeatedWordsAreNotSpellingIssues() throws Exception {
        assertEquals(List.of(), tokens("the the dog dog"));            // a doubled word is grammar, not spelling
        assertEquals(List.of("teh", "teh"), tokens("teh teh"));        // each occurrence is reported at its own position
        List<SpellIssue> i = check("teh teh").issues();
        assertEquals(0, i.get(0).start());
        assertEquals(4, i.get(1).start());
    }

    @Test
    void protectedTokensAreSkipped() throws Exception {
        assertEquals(List.of(), tokens("see https://exmple.com/pagee?x=1 and www.exmple.org now"));
        assertEquals(List.of(), tokens("mail jhon.smyth@exmple.com today"));
        assertEquals(List.of(), tokens("in /usr/locl/bin or C:\\Usrs\\Fred or ~/Documnts/fle and src/mian/jva"));
        assertEquals(List.of(), tokens("call foo_bar() or std::vectr and x->nxt or a=b"));
        assertEquals(List.of(), tokens("E=mc2 and a+b and x^2 and fle.txt and exmple.com"));
        assertEquals(List.of(), tokens("camelCaseThing and snake_case_thing and abc123 and 3rd"));
        assertEquals(List.of("fle", "Txt"), tokens("fle.Txt"));        // not all lower-case segments: treated as prose (documented limit)
        assertEquals(List.of("teh"), tokens("end.Next teh"));          // a missing space after a period is not hidden
        assertEquals(List.of(), tokens("and/or"));                    // a single slash separates two words, both known
        assertEquals(List.of("teh", "wrd"), tokens("teh/wrd"));
    }

    @Test
    void otherScriptsAndTooLongTokensAreSkipped() throws Exception {
        assertEquals(List.of(), tokens("\u043F\u0440\u0438\u0432\u0435\u0442 \u65E5\u672C\u8A9E \u03B1\u03B2\u03B3"));
        assertEquals(List.of(), tokens("x".repeat(80)));
        SpellCheckResult r = check("hello \u043F\u0440\u0438\u0432\u0435\u0442 teh");
        assertEquals(1, r.issues().size());
        assertTrue(r.tokensSkipped() >= 1);
    }

    @Test
    void userWordsAreAcceptedWithDocumentedCaseRules() throws Exception {
        PersonalWords user = new PersonalWords();
        assertEquals(PersonalWords.AddResult.ADDED, user.add("teh"));
        assertEquals(PersonalWords.AddResult.ADDED, user.add("Zoltan"));
        SpellCheckResult r = backend.check("teh Teh TEHS zoltan Zoltan ZOLTAN dogz", SpellLanguage.EN_US, user);
        assertEquals(List.of("TEHS", "zoltan", "dogz"), r.issues().stream().map(SpellIssue::token).toList());
        // personal words do not leak into the checked text or the dictionary
        assertEquals(List.of("teh"), tokens("teh"));
    }

    @Test
    void suggestionsAreDeterministicAndPreserveCase() throws Exception {
        assertEquals("the", backend.suggest("teh", SpellLanguage.EN_US, 5).get(0));      // adjacent transposition outranks substitutions
        assertEquals("The", backend.suggest("Teh", SpellLanguage.EN_US, 5).get(0));
        assertEquals("THE", backend.suggest("TEH", SpellLanguage.EN_US, 5).get(0));
        List<String> recieve = backend.suggest("recieve", SpellLanguage.EN_US, 5);
        assertEquals("receive", recieve.get(0));
        assertTrue(recieve.size() <= 5);
        assertEquals(backend.suggest("wich", SpellLanguage.EN_US, 5), backend.suggest("wich", SpellLanguage.EN_US, 5));
        assertEquals("don't", backend.suggest("dont", SpellLanguage.EN_US, 3).get(0));
        assertEquals("it\u2019s", backend.suggest("it\u2019z", SpellLanguage.EN_US, 3).get(0)); // curly apostrophe style is kept
        assertEquals(List.of(), backend.suggest("zzzzzzzzzz", SpellLanguage.EN_US, 5));  // nothing within two edits
    }

    @Test
    void limitsAreEnforcedNotSilentlyApplied() throws Exception {
        assertThrows(SpellException.class, () -> check("x".repeat(SpellBackend.MAX_TEXT_LENGTH + 1)));
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < SpellBackend.MAX_ISSUES + 50; i++) {
            many.append("teh ");
        }
        SpellCheckResult r = check(many.toString());
        assertEquals(SpellBackend.MAX_ISSUES, r.issues().size());
        assertTrue(r.truncated());
        assertFalse(check("teh").truncated());
    }

    @Test
    void closedBackendFailsCleanly() throws Exception {
        WordListBackend b = WordListBackend.ofWords(SpellLanguage.EN_US, WORDS, COMMON);
        b.close();
        assertThrows(SpellException.class, () -> b.check("hello", SpellLanguage.EN_US, WordFilter.NONE));
        b.close(); // idempotent
    }
}
