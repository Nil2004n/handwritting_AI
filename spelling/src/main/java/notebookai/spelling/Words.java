package notebookai.spelling;

import java.text.Normalizer;
import java.util.Locale;

/** Case, normalization and script helpers shared by the checker and the personal word list. Lookup normalization never touches the checked text. */
final class Words {

    private Words() {}

    /** Longest token (UTF-16 units) that is looked up; longer runs are garbage (a run-on of OCR noise) and are skipped. */
    static final int MAX_TOKEN = 64;

    /** Capitalization pattern of a token's letters. */
    enum Shape {
        /** no upper-case letters */
        LOWER,
        /** first letter upper-case, all others lower-case (Paris, Don't) */
        TITLE,
        /** all letters upper-case (NASA, DON'T) */
        UPPER,
        /** anything else (iPhone, McDonald, O'Brien, eBay): names/identifiers, never flagged */
        MIXED,
        /** letters without case (not Latin script anyway) */
        CASELESS
    }

    /** Lookup key: NFC-normalized, typographic apostrophes (U+2018, U+2019, U+02BC, U+FF07) replaced by the ASCII apostrophe. */
    static String key(String token) {
        String n = Normalizer.normalize(token, Normalizer.Form.NFC);
        return n.replace('’', '\'').replace('‘', '\'').replace('ʼ', '\'').replace('＇', '\'');
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    static String upper(String s) {
        return s.toUpperCase(Locale.ROOT);
    }

    /** First letter upper-case, the rest unchanged ({@code lowerWord} is expected to be lower-case already). */
    static String title(String lowerWord) {
        if (lowerWord.isEmpty()) {
            return lowerWord;
        }
        int first = lowerWord.codePointAt(0);
        int w = Character.charCount(first);
        return new String(Character.toChars(Character.toUpperCase(first))) + lowerWord.substring(w);
    }

    static Shape shape(String s) {
        int upper = 0;
        int lower = 0;
        int caseless = 0;
        boolean firstIsUpper = false;
        boolean seenFirst = false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) {
                continue;
            }
            if (Character.isUpperCase(cp)) {
                upper++;
                if (!seenFirst) {
                    firstIsUpper = true;
                }
            } else if (Character.isLowerCase(cp)) {
                lower++;
            } else {
                caseless++;
            }
            seenFirst = true;
        }
        if (upper == 0 && lower == 0) {
            return caseless > 0 ? Shape.CASELESS : Shape.LOWER;
        }
        if (upper == 0) {
            return Shape.LOWER;
        }
        if (lower == 0) {
            return Shape.UPPER;
        }
        return firstIsUpper && upper == 1 ? Shape.TITLE : Shape.MIXED;
    }

    static int letterCount(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetter(cp)) {
                n++;
            }
        }
        return n;
    }

    /** True when every letter is Latin script (combining marks and common characters are neutral). Other scripts are not English words. */
    static boolean allLatin(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetter(cp) && Character.UnicodeScript.of(cp) != Character.UnicodeScript.LATIN) {
                return false;
            }
        }
        return true;
    }

    static boolean isLetterOrMark(int cp) {
        if (Character.isLetter(cp)) {
            return true;
        }
        int t = Character.getType(cp);
        return t == Character.NON_SPACING_MARK || t == Character.COMBINING_SPACING_MARK || t == Character.ENCLOSING_MARK;
    }

    static boolean isApostrophe(int cp) {
        return cp == '\'' || cp == '’' || cp == '‘' || cp == 'ʼ' || cp == '＇';
    }

    static boolean isHyphen(int cp) {
        return cp == '-' || cp == '‐' || cp == '‑';
    }
}
