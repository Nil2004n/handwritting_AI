package notebookai.spelling;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The bundled offline backend: SCOWL 2020.12.07 word lists (US, UK and Canadian English) held in memory, checked with the rules in
 * {@link TextScanner} and {@link WordList}. <b>This is not Microsoft's, Office's, Windows', Hunspell's or any other product's checker</b>; see
 * docs/SPELLING.md for what it is and is not.
 *
 * <p>Dictionaries load lazily, once per language, the first time that language is used (about 1 MB of text each, tens of milliseconds to a few hundred on
 * a cold JVM). Every resource is verified against {@code dict/SHA256SUMS}; a missing or altered resource makes that language fail with a
 * {@link SpellException} while other languages keep working. After loading the backend holds only immutable data and no native resources, so concurrent
 * {@link #check}/{@link #suggest} calls are safe and {@link #close()} merely drops the data.
 */
public final class WordListBackend implements SpellBackend {

    /** Where resources come from (the class loader for the bundled data; an in-memory map in tests). */
    public interface ResourceSource {
        /** Opens {@code name} (for example {@code en-US.txt}), or returns null if it does not exist. */
        InputStream open(String name) throws IOException;
    }

    private static final String SOURCE_VERSION = "SCOWL 2020.12.07";

    private final ResourceSource source;
    private final Map<SpellLanguage, WordList> loaded = new EnumMap<>(SpellLanguage.class);
    private final Map<SpellLanguage, SpellException> failed = new EnumMap<>(SpellLanguage.class);
    private Map<String, String> checksums; // resource name -> sha256, read once
    private volatile boolean closed;

    public WordListBackend(ResourceSource source) {
        this.source = source;
    }

    /** The backend over the word lists bundled in this library's jar. */
    public static WordListBackend bundled() {
        return new WordListBackend(name -> WordListBackend.class.getResourceAsStream("dict/" + name));
    }

    /** A backend over explicit words (tests and small custom lists); nothing is read from disk and no checksum applies. */
    public static WordListBackend ofWords(SpellLanguage language, Collection<String> all, Collection<String> common) {
        WordListBackend b = new WordListBackend(name -> null);
        b.loaded.put(language, new WordList(all, common));
        return b;
    }

    @Override
    public String identity(SpellLanguage language) {
        try {
            WordList w = list(language);
            return "bundled word list, " + SOURCE_VERSION + " " + language.tag() + " (" + w.size() + " words, " + w.commonSize() + " in the common tier)";
        } catch (SpellException e) {
            return "bundled word list, " + SOURCE_VERSION + " " + language.tag() + " (unavailable: " + e.getMessage() + ")";
        }
    }

    @Override
    public boolean supports(SpellLanguage language) {
        return language != null && !closed;
    }

    @Override
    public SpellCheckResult check(String text, SpellLanguage language, WordFilter user) throws SpellException {
        if (text == null || user == null || language == null) {
            throw new IllegalArgumentException("text, language and user filter are required");
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new SpellException("The text is too long to check (" + text.length() + " characters; the limit is " + MAX_TEXT_LENGTH + ").");
        }
        WordList dict = list(language);
        TextScanner.Scan scan = TextScanner.scan(text);
        List<SpellIssue> issues = new ArrayList<>();
        int checked = 0;
        int skipped = scan.skipped();
        boolean truncated = false;
        for (int[] run : scan.runs()) {
            String token = text.substring(run[0], run[1]);
            if (token.length() > Words.MAX_TOKEN || !Words.allLatin(token)) {
                skipped++;
                continue;
            }
            int hyphen = firstHyphen(token);
            if (hyphen >= 0) {
                // whole compound first (some compounds are listed), then each part on its own; a flagged part keeps only its own range
                Verdict whole = verdict(token, dict, user);
                if (whole == Verdict.KNOWN) {
                    checked++;
                    continue;
                }
                int partStart = 0;
                for (int i = 0; i <= token.length(); i++) {
                    if (i == token.length() || Words.isHyphen(token.charAt(i))) {
                        if (i > partStart) {
                            String part = token.substring(partStart, i);
                            Verdict v = verdict(part, dict, user);
                            if (v == Verdict.SKIP) {
                                skipped++;
                            } else {
                                checked++;
                                if (v != Verdict.KNOWN) {
                                    if (issues.size() >= MAX_ISSUES) {
                                        truncated = true;
                                    } else {
                                        issues.add(new SpellIssue(run[0] + partStart, run[0] + i, part, kindOf(part)));
                                    }
                                }
                            }
                        }
                        partStart = i + 1;
                    }
                }
                continue;
            }
            Verdict v = verdict(token, dict, user);
            if (v == Verdict.SKIP) {
                skipped++;
            } else {
                checked++;
                if (v != Verdict.KNOWN) {
                    if (issues.size() >= MAX_ISSUES) {
                        truncated = true;
                    } else {
                        issues.add(new SpellIssue(run[0], run[1], token, kindOf(token)));
                    }
                }
            }
        }
        return new SpellCheckResult(language, text.length(), issues, truncated, checked, skipped);
    }

    private enum Verdict { KNOWN, UNKNOWN, SKIP }

    private static Verdict verdict(String token, WordList dict, WordFilter user) {
        String key = Words.key(token);
        Words.Shape shape = Words.shape(key);
        int letters = Words.letterCount(key);
        if (shape == Words.Shape.MIXED || shape == Words.Shape.CASELESS || letters < 2) {
            return Verdict.SKIP; // names/identifiers like iPhone, McDonald; single letters
        }
        if (shape == Words.Shape.UPPER && letters <= 3) {
            return Verdict.SKIP; // short acronyms (OCR, PDF, USA)
        }
        if (user.accepts(token) || dict.known(key, shape)) {
            return Verdict.KNOWN;
        }
        return Verdict.UNKNOWN;
    }

    private static IssueKind kindOf(String token) {
        return Words.shape(Words.key(token)) == Words.Shape.TITLE ? IssueKind.UNKNOWN_CAPITALIZED : IssueKind.UNKNOWN_WORD;
    }

    private static int firstHyphen(String token) {
        for (int i = 0; i < token.length(); i++) {
            if (Words.isHyphen(token.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public List<String> suggest(String token, SpellLanguage language, int max) throws SpellException {
        if (token == null || language == null || max < 1) {
            throw new IllegalArgumentException("token, language and a positive max are required");
        }
        WordList dict = list(language);
        String key = Words.key(token);
        List<String> out = dict.suggest(key, Words.shape(key), max);
        if (token.indexOf('’') >= 0) { // keep the user's typographic apostrophe style
            List<String> styled = new ArrayList<>();
            for (String s : out) {
                styled.add(s.replace('\'', '’'));
            }
            return styled;
        }
        return out;
    }

    @Override
    public synchronized void close() {
        closed = true;
        loaded.clear();
        failed.clear();
    }

    // ---------------------------------------------------------------- loading

    private synchronized WordList list(SpellLanguage language) throws SpellException {
        if (closed) {
            throw new SpellException("The spelling backend is closed.");
        }
        WordList w = loaded.get(language);
        if (w != null) {
            return w;
        }
        SpellException previous = failed.get(language);
        if (previous != null) {
            throw previous;
        }
        try {
            WordList fresh = load(language);
            loaded.put(language, fresh);
            return fresh;
        } catch (SpellException e) {
            failed.put(language, e);
            throw e;
        }
    }

    private WordList load(SpellLanguage language) throws SpellException {
        try {
            if (checksums == null) {
                checksums = parseChecksums(readText("SHA256SUMS"));
            }
            List<String> all = lines(readVerified(language.tag() + ".txt"));
            List<String> common = lines(readVerified(language.tag() + ".common.txt"));
            if (all.size() < 1000) {
                throw new SpellException("The " + language.tag() + " dictionary is implausibly small (" + all.size() + " entries).");
            }
            return new WordList(all, common);
        } catch (IOException e) {
            throw new SpellException("The " + language.tag() + " dictionary could not be read: " + e.getMessage(), e);
        }
    }

    private byte[] readVerified(String name) throws IOException, SpellException {
        byte[] data = readBytes(name);
        String expected = checksums.get(name);
        if (expected == null) {
            throw new SpellException("Dictionary resource " + name + " is not listed in SHA256SUMS.");
        }
        if (!expected.equalsIgnoreCase(sha256(data))) {
            throw new SpellException("Dictionary resource " + name + " is corrupt (checksum mismatch).");
        }
        return data;
    }

    private byte[] readBytes(String name) throws IOException, SpellException {
        try (InputStream in = source.open(name)) {
            if (in == null) {
                throw new SpellException("Dictionary resource " + name + " is missing.");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            for (int n = in.read(buf); n >= 0; n = in.read(buf)) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private String readText(String name) throws IOException, SpellException {
        return new String(readBytes(name), StandardCharsets.UTF_8);
    }

    private static List<String> lines(byte[] data) {
        List<String> out = new ArrayList<>();
        for (String line : new String(data, StandardCharsets.UTF_8).split("\n")) {
            if (!line.isEmpty()) {
                out.add(line);
            }
        }
        return out;
    }

    private static Map<String, String> parseChecksums(String text) {
        Map<String, String> m = new HashMap<>();
        for (String line : text.split("\n")) {
            String t = line.trim();
            int sp = t.indexOf(' ');
            if (sp > 0) {
                m.put(t.substring(t.lastIndexOf(' ') + 1), t.substring(0, sp));
            }
        }
        return m;
    }

    private static String sha256(byte[] data) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(data);
            char[] hex = "0123456789abcdef".toCharArray();
            char[] out = new char[h.length * 2];
            for (int i = 0; i < h.length; i++) {
                out[2 * i] = hex[(h[i] >> 4) & 0xF];
                out[2 * i + 1] = hex[h[i] & 0xF];
            }
            return new String(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
