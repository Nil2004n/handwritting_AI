package notebookai.spelling;

/**
 * An English variant with a bundled dictionary. Only languages whose dictionaries are actually bundled and verified are listed.
 * ({@code en-AU} is not offered: no verified plain word list for it was available; see docs/SPELLING.md.)
 */
public enum SpellLanguage {
    EN_US("en-US", "English (United States)"),
    EN_GB("en-GB", "English (United Kingdom)"),
    EN_CA("en-CA", "English (Canada)");

    private final String tag;
    private final String displayName;

    SpellLanguage(String tag, String displayName) {
        this.tag = tag;
        this.displayName = displayName;
    }

    /** BCP 47 tag, also the dictionary resource stem ({@code en-US.txt}). */
    public String tag() {
        return tag;
    }

    public String displayName() {
        return displayName;
    }

    /** The language for a tag (case-insensitive), or {@code null} when it is not supported. */
    public static SpellLanguage fromTag(String tag) {
        if (tag != null) {
            for (SpellLanguage l : values()) {
                if (l.tag.equalsIgnoreCase(tag.trim())) {
                    return l;
                }
            }
        }
        return null;
    }
}
