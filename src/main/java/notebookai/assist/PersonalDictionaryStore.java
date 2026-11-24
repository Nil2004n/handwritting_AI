package notebookai.assist;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import notebookai.project.AtomicFiles;
import notebookai.spelling.PersonalWords;

/**
 * Persistence of the personal dictionary: one UTF-8 text file, first line {@code # notebook-ai personal dictionary v1}, then one entry per line
 * ({@code #} lines are comments). Only words are stored, never transcripts or snippets. Writes are atomic (temporary sibling + rename); a failed write
 * throws {@link IOException} and leaves the previous file intact. Loading never throws: malformed lines are skipped and counted (the original is copied to
 * {@code <file>.bak} before the first save that would drop them), an undecodable file yields an empty list plus a notice, and a file from a NEWER
 * format version is loaded read-only and is never overwritten.
 */
public final class PersonalDictionaryStore {

    static final int VERSION = 1;
    private static final Pattern HEADER = Pattern.compile("^#\\s*notebook-ai personal dictionary v(\\d+)\\s*$");

    /** What {@link #load()} found. {@code notice} is null when everything was fine. */
    public record Loaded(List<String> entries, int skipped, boolean readOnly, String notice) {}

    private final Path file;
    private boolean readOnly;
    private boolean backedUp;
    private int lastSkipped;

    public PersonalDictionaryStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public synchronized Loaded load() {
        readOnly = false;
        lastSkipped = 0;
        if (!Files.isRegularFile(file)) {
            return new Loaded(List.of(), 0, false, null);
        }
        String text;
        try {
            byte[] bytes = Files.readAllBytes(file);
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new Loaded(List.of(), 0, false, "The personal dictionary file is not valid UTF-8 and was ignored (it will be kept as a .bak copy before the next save).");
        } catch (IOException e) {
            return new Loaded(List.of(), 0, false, "The personal dictionary could not be read: " + AtomicFiles.describe(e, file.getParent()));
        }
        List<String> entries = new ArrayList<>();
        int skipped = 0;
        boolean first = true;
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (first) {
                first = false;
                Matcher m = HEADER.matcher(line);
                if (m.matches()) {
                    if (Integer.parseInt(m.group(1)) > VERSION) {
                        readOnly = true;
                        return new Loaded(List.of(), 0, true, "The personal dictionary was written by a newer version of notebook-ai (format v" + m.group(1)
                                + "); it is not loaded and will not be overwritten.");
                    }
                    continue;
                }
            }
            if (line.startsWith("#")) {
                continue;
            }
            String entry = PersonalWords.normalizeEntry(line);
            if (entry == null) {
                skipped++;
            } else {
                entries.add(entry);
            }
        }
        lastSkipped = skipped;
        return new Loaded(entries, skipped, false, skipped == 0 ? null
                : skipped + " invalid line(s) in the personal dictionary were skipped (the original is kept as a .bak copy before the next save).");
    }

    /** Writes the entries atomically. Throws {@link IOException} on any failure or if the file is read-only because of a newer format. */
    public synchronized void save(Collection<String> entries) throws IOException {
        if (readOnly) {
            throw new IOException("The personal dictionary file is in a newer format and is not overwritten.");
        }
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        if (lastSkipped > 0 && !backedUp && Files.isRegularFile(file)) {
            Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            backedUp = true;
        }
        StringBuilder sb = new StringBuilder("# notebook-ai personal dictionary v").append(VERSION).append('\n');
        for (String e : new TreeSet<>(entries)) {
            sb.append(e).append('\n');
        }
        Path tmp = AtomicFiles.createTemp(dir, file, ".personal-dictionary-");
        try {
            Files.write(tmp, sb.toString().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            lastSkipped = 0;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
