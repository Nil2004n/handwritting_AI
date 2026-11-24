package notebookai.assist;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import notebookai.project.AtomicFiles;
import notebookai.spelling.SpellLanguage;

/**
 * The user's spelling settings, persisted per user in {@code spelling.properties} (UTF-8 key=value). Unknown or malformed values fall back to the
 * defaults (assistance ON, {@code en-US}); a damaged file is ignored, never fatal. Auto-replacement is not offered, so there is no setting for it.
 */
public final class SpellingPreferences {

    private final Path file;
    private boolean enabled = true;
    private SpellLanguage language = SpellLanguage.EN_US;

    public SpellingPreferences(Path file) {
        this.file = file;
    }

    public synchronized boolean enabled() {
        return enabled;
    }

    public synchronized SpellLanguage language() {
        return language;
    }

    public synchronized void set(boolean enabled, SpellLanguage language) {
        this.enabled = enabled;
        this.language = language == null ? SpellLanguage.EN_US : language;
    }

    /** Loads the file if present; returns a notice when it had to be ignored, else null. */
    public synchronized String load() {
        enabled = true;
        language = SpellLanguage.EN_US;
        if (!Files.isRegularFile(file)) {
            return null;
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException | IllegalArgumentException e) {
            return "The spelling preferences file could not be read and was ignored (defaults are used).";
        }
        String en = p.getProperty("enabled");
        if ("false".equalsIgnoreCase(en)) {
            enabled = false;
        }
        SpellLanguage l = SpellLanguage.fromTag(p.getProperty("language"));
        if (l != null) {
            language = l;
        }
        return null;
    }

    public synchronized void save() throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        String body = "# notebook-ai spelling preferences\nenabled=" + enabled + "\nlanguage=" + language.tag() + "\n";
        Path tmp = AtomicFiles.createTemp(dir, file, ".spelling-prefs-");
        try {
            Files.write(tmp, body.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
