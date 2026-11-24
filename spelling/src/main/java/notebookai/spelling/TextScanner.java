package notebookai.spelling;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Splits text into candidate word runs and decides which parts are not natural-language words. Offsets are UTF-16 units of the ORIGINAL text; nothing
 * is normalized or copied back, so a range always selects exactly the characters that were scanned (including text after supplementary characters).
 *
 * <p>Method: the text is cut into whitespace-delimited chunks. A chunk that looks like a URL, e-mail address, file path, dotted file/domain name, code
 * or an equation is skipped entirely. In the remaining chunks, a <i>run</i> is a maximal sequence of letters, combining marks and digits/underscores,
 * with apostrophes and hyphens allowed between two letters/digits. A run containing a digit or underscore is an identifier (mc2, 3rd, user_id) and is
 * skipped; any other run is returned as a word run (it may still contain apostrophes and hyphens). The heuristics are deliberately conservative and
 * documented in docs/SPELLING.md; they cannot recognise every equation or name.
 */
final class TextScanner {

    private TextScanner() {}

    /** Word runs (inclusive start, exclusive end) in text order, plus how many chunks/runs were skipped on purpose. */
    record Scan(List<int[]> runs, int skipped) {}

    static Scan scan(String text) {
        List<int[]> runs = new ArrayList<>();
        int skipped = 0;
        int n = text.length();
        int i = 0;
        while (i < n) {
            int cp = text.codePointAt(i);
            if (isSpace(cp)) {
                i += Character.charCount(cp);
                continue;
            }
            int chunkStart = i;
            while (i < n && !isSpace(text.codePointAt(i))) {
                i += Character.charCount(text.codePointAt(i));
            }
            if (isProtectedChunk(text.substring(chunkStart, i))) {
                skipped++;
                continue;
            }
            skipped += scanChunk(text, chunkStart, i, runs);
        }
        return new Scan(runs, skipped);
    }

    private static boolean isSpace(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x200B;
    }

    /** Returns the number of skipped identifier runs. */
    private static int scanChunk(String text, int cs, int ce, List<int[]> runs) {
        int skipped = 0;
        int p = cs;
        while (p < ce) {
            int cp = text.codePointAt(p);
            if (!(Words.isLetterOrMark(cp) || Character.isDigit(cp) || cp == '_')) {
                p += Character.charCount(cp);
                continue;
            }
            int q = p;
            boolean digit = false;
            boolean underscore = false;
            boolean letter = false;
            while (q < ce) {
                int c = text.codePointAt(q);
                int w = Character.charCount(c);
                if (Words.isLetterOrMark(c)) {
                    letter |= Character.isLetter(c);
                    q += w;
                } else if (Character.isDigit(c)) {
                    digit = true;
                    q += w;
                } else if (c == '_') {
                    underscore = true;
                    q += w;
                } else if ((Words.isApostrophe(c) || Words.isHyphen(c)) && q > p && q + w < ce) {
                    int prev = text.codePointBefore(q);
                    int next = text.codePointAt(q + w);
                    if ((Words.isLetterOrMark(prev) || Character.isDigit(prev)) && (Character.isLetter(next) || Character.isDigit(next))) {
                        q += w;
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            }
            if (digit || underscore) {
                skipped++;
            } else if (letter) {
                runs.add(new int[] {p, q});
            }
            p = q;
        }
        return skipped;
    }

    /** True for chunks that are not prose: URL, e-mail, path, dotted file/domain name, code-like text, equation-like text. */
    static boolean isProtectedChunk(String chunk) {
        String lower = chunk.toLowerCase(Locale.ROOT);
        // URL
        if (lower.contains("://") || lower.contains("www.") || lower.startsWith("mailto:")) {
            return true;
        }
        // e-mail address
        int at = chunk.indexOf('@');
        if (at > 0 && at < chunk.length() - 1 && chunk.indexOf('.', at) > at + 1) {
            return true;
        }
        // file system path: absolute/relative/home, Windows drive or UNC, backslashes, or a/b/c with at least two separators
        if (lower.startsWith("/") && chunk.length() > 1 && Character.isLetterOrDigit(chunk.codePointAt(1))
                || lower.startsWith("~/") || lower.startsWith("./") || lower.startsWith("../") || lower.startsWith("\\\\")
                || (chunk.length() >= 3 && Character.isLetter(chunk.charAt(0)) && chunk.charAt(1) == ':' && (chunk.charAt(2) == '\\' || chunk.charAt(2) == '/'))
                || chunk.indexOf('\\') >= 0 && chunk.length() > 2
                || count(chunk, '/') >= 2) {
            return true;
        }
        // code-like
        if (chunk.indexOf('_') >= 0 || lower.contains("::") || lower.contains("->") || lower.contains("=>") || lower.endsWith("()") || lower.contains("();")
                || chunk.indexOf('{') >= 0 || chunk.indexOf('}') >= 0 || chunk.indexOf('=') >= 0
                || lower.startsWith("$") && chunk.length() > 1 && Character.isLetter(chunk.codePointAt(1))
                || lower.startsWith("#") && chunk.length() > 1 && Character.isLetter(chunk.codePointAt(1))
                || tagLike(chunk)) {
            return true;
        }
        // equation-like operators between alphanumerics: a+b, x^2, 3*y, a|b
        for (int i = 1; i < chunk.length() - 1; i++) {
            char c = chunk.charAt(i);
            if ((c == '+' || c == '*' || c == '^' || c == '|' || c == '<' || c == '>' || c == '~')
                    && Character.isLetterOrDigit(chunk.charAt(i - 1)) && Character.isLetterOrDigit(chunk.charAt(i + 1))) {
                return true;
            }
        }
        // dotted file/domain/package name made only of lower-case/digit segments (file.txt, example.com, java.util); "end.Next" is NOT matched
        String core = stripEdgePunctuation(chunk);
        return core.indexOf('.') > 0 && dottedLowerName(core);
    }

    private static boolean tagLike(String chunk) {
        int lt = chunk.indexOf('<');
        int gt = chunk.indexOf('>');
        return lt >= 0 && gt > lt + 1;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    private static String stripEdgePunctuation(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && !Character.isLetterOrDigit(s.charAt(a))) {
            a++;
        }
        while (b > a && !Character.isLetterOrDigit(s.charAt(b - 1))) {
            b--;
        }
        return s.substring(a, b);
    }

    private static boolean dottedLowerName(String core) {
        String[] segments = core.split("\\.", -1);
        if (segments.length < 2) {
            return false;
        }
        for (String seg : segments) {
            if (seg.isEmpty()) {
                return false;
            }
            for (int i = 0; i < seg.length(); i++) {
                char c = seg.charAt(i);
                if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-')) {
                    return false;
                }
            }
        }
        return true;
    }
}
