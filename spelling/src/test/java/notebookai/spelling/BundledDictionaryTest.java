package notebookai.spelling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The REAL bundled SCOWL lists. Assertions avoid depending on exact suggestion order across dictionary versions. */
class BundledDictionaryTest {

    private static List<String> tokens(WordListBackend b, SpellLanguage l, String text) throws SpellException {
        return b.check(text, l, WordFilter.NONE).issues().stream().map(SpellIssue::token).toList();
    }

    @Test
    void everyBundledLanguageLoadsAndKnowsCommonWords() throws Exception {
        try (WordListBackend b = WordListBackend.bundled()) {
            for (SpellLanguage l : SpellLanguage.values()) {
                assertEquals(List.of(), tokens(b, l, "The quick brown fox jumps over the lazy dog. It's a don't-care situation."), l.tag());
                assertTrue(b.identity(l).contains("SCOWL 2020.12.07 " + l.tag()), b.identity(l));
                assertTrue(b.identity(l).matches(".*\\((\\d{5,}) words.*"), b.identity(l));
            }
        }
    }

    @Test
    void variantSpecificSpellings() throws Exception {
        try (WordListBackend b = WordListBackend.bundled()) {
            assertEquals(List.of(), tokens(b, SpellLanguage.EN_US, "color"));
            assertEquals(List.of("colour"), tokens(b, SpellLanguage.EN_US, "colour"));
            assertEquals(List.of(), tokens(b, SpellLanguage.EN_GB, "colour"));
            assertEquals(List.of("color"), tokens(b, SpellLanguage.EN_GB, "color"));
            assertEquals(List.of(), tokens(b, SpellLanguage.EN_CA, "color colour"));
        }
    }

    @Test
    void realMisspellingsAreFlaggedAndNamesAndTechnicalTermsAreUnknownNotWrong() throws Exception {
        try (WordListBackend b = WordListBackend.bundled()) {
            assertEquals(List.of("recieve", "wich", "teh"), tokens(b, SpellLanguage.EN_US, "I recieve wich teh"));
            // names/technical words: reported as unknown (the kind says so); nothing here claims they are wrong
            List<SpellIssue> issues = b.check("Zxqvtonia and kubernetesify", SpellLanguage.EN_US, WordFilter.NONE).issues();
            assertEquals(IssueKind.UNKNOWN_CAPITALIZED, issues.get(0).kind());
            assertEquals(IssueKind.UNKNOWN_WORD, issues.get(1).kind());
            // a valid-word OCR error is NOT detectable by a dictionary
            assertEquals(List.of(), tokens(b, SpellLanguage.EN_US, "I come form London"));
        }
    }

    @Test
    void suggestionsContainTheLikelyWord() throws Exception {
        try (WordListBackend b = WordListBackend.bundled()) {
            assertTrue(b.suggest("recieve", SpellLanguage.EN_US, 5).contains("receive"));
            assertTrue(b.suggest("teh", SpellLanguage.EN_US, 5).contains("the"));
            assertTrue(b.suggest("wich", SpellLanguage.EN_US, 8).contains("which"));
            assertTrue(b.suggest("definately", SpellLanguage.EN_US, 8).contains("definitely"));
            assertTrue(b.suggest("Lodnon", SpellLanguage.EN_US, 5).contains("London"));
            assertTrue(b.suggest("COMPLLCATED", SpellLanguage.EN_US, 5).contains("COMPLICATED"));
            assertEquals(b.suggest("wich", SpellLanguage.EN_US, 8), b.suggest("wich", SpellLanguage.EN_US, 8));
        }
    }

    private static WordListBackend.ResourceSource tampered(String name, byte[] replacement) {
        return n -> n.equals(name) ? new ByteArrayInputStream(replacement) : WordListBackend.class.getResourceAsStream("dict/" + n);
    }

    @Test
    void corruptOrMissingResourcesFailThatLanguageOnly() throws Exception {
        try (WordListBackend corrupt = new WordListBackend(tampered("en-US.txt", "hello\nworld\n".getBytes()))) {
            SpellException e = assertThrows(SpellException.class, () -> corrupt.check("hello", SpellLanguage.EN_US, WordFilter.NONE));
            assertTrue(e.getMessage().contains("corrupt"), e.getMessage());
            assertEquals(List.of(), tokens(corrupt, SpellLanguage.EN_GB, "color".equals("x") ? "" : "the dog"), "other languages keep working");
            assertThrows(SpellException.class, () -> corrupt.check("hello", SpellLanguage.EN_US, WordFilter.NONE), "failure is remembered, not retried silently");
            assertTrue(corrupt.identity(SpellLanguage.EN_US).contains("unavailable"));
        }
        WordListBackend.ResourceSource missing = n -> n.equals("en-CA.common.txt") ? null : WordListBackend.class.getResourceAsStream("dict/" + n);
        try (WordListBackend b = new WordListBackend(missing)) {
            SpellException e = assertThrows(SpellException.class, () -> b.check("hello", SpellLanguage.EN_CA, WordFilter.NONE));
            assertTrue(e.getMessage().contains("missing"), e.getMessage());
        }
        try (WordListBackend none = new WordListBackend(n -> null)) {
            assertThrows(SpellException.class, () -> none.check("hello", SpellLanguage.EN_US, WordFilter.NONE));
        }
        WordListBackend.ResourceSource unreadable = n -> { throw new IOException("disk on fire"); };
        try (WordListBackend b = new WordListBackend(unreadable)) {
            SpellException e = assertThrows(SpellException.class, () -> b.check("hello", SpellLanguage.EN_US, WordFilter.NONE));
            assertTrue(e.getMessage().contains("disk on fire"), e.getMessage());
        }
    }

    @Test
    void concurrentChecksAreSafe() throws Exception {
        try (WordListBackend b = WordListBackend.bundled()) {
            b.check("warm up", SpellLanguage.EN_US, WordFilter.NONE);
            Thread[] ts = new Thread[4];
            boolean[] ok = new boolean[ts.length];
            for (int i = 0; i < ts.length; i++) {
                final int id = i;
                ts[i] = new Thread(() -> {
                    try {
                        for (int k = 0; k < 50; k++) {
                            ok[id] = tokens(b, SpellLanguage.EN_US, "the quikc brown foxx").equals(List.of("quikc", "foxx"));
                            if (!ok[id]) {
                                return;
                            }
                        }
                    } catch (SpellException e) {
                        ok[id] = false;
                    }
                });
                ts[i].start();
            }
            for (Thread t : ts) {
                t.join();
            }
            for (boolean o : ok) {
                assertTrue(o);
            }
        }
    }

    @Test
    void resourcesAndNoticeAreBundled() throws Exception {
        for (String n : List.of("SHA256SUMS", "SCOWL-COPYRIGHT.txt", "PROVENANCE.txt", "en-US.txt", "en-GB.common.txt")) {
            try (InputStream in = WordListBackend.class.getResourceAsStream("dict/" + n)) {
                assertFalse(in == null, n);
            }
        }
        try (InputStream in = WordListBackend.class.getResourceAsStream("dict/SCOWL-COPYRIGHT.txt")) {
            String t = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(t.contains("Kevin Atkinson") && t.contains("Princeton University") && t.contains("Geoff Kuenning"));
        }
    }
}
