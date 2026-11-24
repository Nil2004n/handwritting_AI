package notebookai.assist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import notebookai.spelling.SpellLanguage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpellingPreferencesTest {

    @TempDir
    Path dir;

    @Test
    void defaultsRoundTripAndTolerateDamage() throws Exception {
        Path f = dir.resolve("p/spelling.properties");
        SpellingPreferences p = new SpellingPreferences(f);
        assertNull(p.load());
        assertTrue(p.enabled());
        assertEquals(SpellLanguage.EN_US, p.language());
        p.set(false, SpellLanguage.EN_GB);
        p.save();
        SpellingPreferences q = new SpellingPreferences(f);
        assertNull(q.load());
        assertFalse(q.enabled());
        assertEquals(SpellLanguage.EN_GB, q.language());
        Files.writeString(f, "enabled=maybe\nlanguage=xx-YY\n");
        SpellingPreferences r = new SpellingPreferences(f);
        r.load();
        assertTrue(r.enabled(), "unknown value: default");
        assertEquals(SpellLanguage.EN_US, r.language());
        Files.write(f, new byte[] {'\\', 'u', 'Z', 'Z', 'Z', 'Z'}); // malformed unicode escape
        assertNotNull(new SpellingPreferences(f).load());
    }
}
