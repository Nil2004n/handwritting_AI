package notebookai.project;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain-text (UTF-8, no BOM) export of the REVIEWED transcripts, verbatim.
 *
 * <p>Rules (documented in HANDOFF.md): a page contributes exactly its current edited text (never
 * silently the raw OCR, never normalized, whitespace untouched). Pages are joined with the single
 * separator {@link #SEPARATOR} (a blank line) between consecutive pages, always, so page positions
 * are preserved: an unrecognized or empty page contributes an empty string but its separators
 * remain. Page labels are added only on explicit request.
 */
public final class TextExport {

    /** Exactly one of these sits between consecutive pages. */
    public static final String SEPARATOR = "\n\n";

    public enum Status { UNRECOGNIZED, EMPTY, TEXT }

    public record ExportPage(int number, String name, boolean recognized, String text) {
        public Status status() {
            if (!recognized) {
                return text.isEmpty() ? Status.UNRECOGNIZED : Status.TEXT;
            }
            return text.isEmpty() ? Status.EMPTY : Status.TEXT;
        }
    }

    private TextExport() {}

    public static List<ExportPage> pages(ProjectData project) {
        List<ExportPage> out = new ArrayList<>();
        int n = 1;
        for (PageData p : project.pages()) {
            out.add(new ExportPage(n++, p.name(), p.recognized(), p.editedText()));
        }
        return out;
    }

    /** All pages in project order. {@code labels}: prefix each page with "--- Page N ---\n" (opt-in). */
    public static String renderAll(List<ExportPage> pages, boolean labels) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) {
            if (i > 0) {
                sb.append(SEPARATOR);
            }
            if (labels) {
                sb.append("--- Page ").append(pages.get(i).number()).append(" ---\n");
            }
            sb.append(pages.get(i).text());
        }
        return sb.toString();
    }

    /** Current page only: its text, verbatim, never labelled. */
    public static String renderOne(ExportPage page) {
        return page.text();
    }

    /** Human-readable completeness warnings (empty list = nothing to warn about). */
    public static List<String> warnings(List<ExportPage> pages) {
        List<String> w = new ArrayList<>();
        StringBuilder un = new StringBuilder();
        StringBuilder em = new StringBuilder();
        for (ExportPage p : pages) {
            if (p.status() == Status.UNRECOGNIZED) {
                un.append(un.length() > 0 ? ", " : "").append(p.number());
            } else if (p.status() == Status.EMPTY) {
                em.append(em.length() > 0 ? ", " : "").append(p.number());
            }
        }
        if (un.length() > 0) {
            w.add("Not recognized yet (exported as empty text): page(s) " + un);
        }
        if (em.length() > 0) {
            w.add("Recognized but empty: page(s) " + em);
        }
        return w;
    }

    /**
     * Writes {@code text} as UTF-8 without BOM via a temporary sibling file and a rename, so a
     * failed write never leaves a truncated target. Text that cannot be represented in UTF-8
     * (unpaired surrogates) is rejected instead of being silently altered.
     */
    public static void writeUtf8(Path target, String text) throws ProjectException {
        byte[] bytes;
        try {
            CharsetEncoder enc = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            ByteBuffer bb = enc.encode(CharBuffer.wrap(text));
            bytes = new byte[bb.remaining()];
            bb.get(bytes);
        } catch (CharacterCodingException e) {
            throw new ProjectException("The text contains characters that cannot be written as UTF-8 "
                    + "(an unpaired surrogate). Nothing was exported.");
        }
        Path abs = target.toAbsolutePath();
        Path dir = abs.getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            throw new ProjectException("Cannot export: the destination folder does not exist.");
        }
        if (Files.isDirectory(abs)) {
            throw new ProjectException("Cannot export: " + abs + " is a folder.");
        }
        Path tmp = null;
        try {
            tmp = AtomicFiles.createTemp(dir, abs, ".nbai-export-");
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, abs, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING);
            }
            tmp = null;
        } catch (IOException e) {
            throw new ProjectException("Could not write the export file: " + AtomicFiles.describe(e, dir)
                    + ". An existing file with that name was left unchanged.", e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // Best effort.
                }
            }
        }
    }
}
