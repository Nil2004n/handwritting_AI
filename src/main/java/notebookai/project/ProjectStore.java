package notebookai.project;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Reads and writes {@code .nbai} project files (see HANDOFF.md "Phase 4" for the format).
 *
 * <p>Container: a ZIP archive with exactly one {@code project.json} (UTF-8, versioned, strict
 * JSON) and one {@code pages/<pageId>/original} entry per page holding the ORIGINAL JPEG/PNG bytes.
 * The working image is not stored: it is regenerated from the original plus the saved prep state
 * (rotation, straighten, crop) by the same deterministic code. Nothing is ever extracted to disk and
 * nothing is deserialized natively: entries are read into bounded memory buffers and only entry
 * names matching a fixed pattern are accepted.
 *
 * <p>Saving writes a temporary sibling file in the target directory, fsyncs it, re-reads and
 * validates it, then atomically renames it over the target (falling back to a non-atomic replace
 * where the filesystem lacks atomic rename). A failure at any point deletes the temporary file and
 * leaves the previous valid project untouched. This is NOT crash-durability: the containing
 * directory is not fsynced and an OS crash/power loss during the final rename is outside what
 * this code can guarantee.
 */
public final class ProjectStore {

    public static final String FORMAT_NAME = "notebook-ai-project";
    public static final int FORMAT_VERSION = 1;
    public static final String EXTENSION = ".nbai";

    // Documented bounds (also reported in error messages).
    public static final int MAX_PAGES = 500;
    public static final long MAX_IMAGE_BYTES = 64L * 1024 * 1024;
    public static final long MAX_JSON_BYTES = 32L * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024;
    public static final long MAX_PIXELS = 120_000_000L;
    public static final int MAX_REGIONS_PER_PAGE = 10_000;
    public static final int MAX_NAME_CHARS = 255;

    /**
     * Resource limits. What each measures:
     * <ul>
     *   <li>{@code maxImageBytes}: size of ONE page's original image file as stored in the archive
     *       (== its entry's uncompressed size; images are stored uncompressed);</li>
     *   <li>{@code maxJsonBytes}: expanded size of {@code project.json} (all text, regions, metadata);</li>
     *   <li>{@code maxTotalBytes}: the archive file's size on disk AND the sum of expanded bytes read
     *       (json + all images), checked while reading, so a lying size header cannot bypass it;</li>
     *   <li>{@code maxPixels}: width x height from the image HEADER (nothing is decoded before this check);
     *       decoding additionally passes {@link MemoryGuard};</li>
     *   <li>{@code maxPages}, {@code maxRegionsPerPage}: counts.</li>
     * </ul>
     * Configurable so tests can exercise exact boundaries with tiny limits.
     */
    public record Limits(int maxPages, long maxImageBytes, long maxJsonBytes, long maxTotalBytes,
            long maxPixels, int maxRegionsPerPage) {
        public static final Limits DEFAULT = new Limits(MAX_PAGES, MAX_IMAGE_BYTES, MAX_JSON_BYTES,
                MAX_TOTAL_BYTES, MAX_PIXELS, MAX_REGIONS_PER_PAGE);
    }

    private static String fmt(long bytes) {
        return bytes >= (1L << 20) ? (bytes >> 20) + " MiB" : bytes + " bytes";
    }

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern IMAGE_ENTRY = Pattern.compile("pages/([A-Za-z0-9_-]{1,64})/original");
    private static final String JSON_ENTRY = "project.json";

    private ProjectStore() {}

    // ================================================================== save

    /** Saves {@code project} to {@code target} safely (see class comment). */
    public static void save(ProjectData project, Path target) throws ProjectException {
        save(project, target, Limits.DEFAULT, null);
    }

    /** Saves with explicit limits (tests, tooling). */
    public static void save(ProjectData project, Path target, Limits lim) throws ProjectException {
        save(project, target, lim, null);
    }

    /** Test hook: {@code beforeCommit} runs after the temp file is written+validated, before the rename. */
    static void save(ProjectData project, Path target, FailureInjector beforeCommit)
            throws ProjectException {
        save(project, target, Limits.DEFAULT, beforeCommit);
    }

    static void save(ProjectData project, Path target, Limits lim, FailureInjector beforeCommit)
            throws ProjectException {
        if (project.size() > lim.maxPages()) {
            throw new ProjectException("A project can hold at most " + lim.maxPages() + " pages.");
        }
        Path abs = target.toAbsolutePath();
        Path dir = abs.getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            throw new ProjectException("Cannot save: the folder for " + abs.getFileName()
                    + " does not exist. The previous file (if any) was left unchanged.");
        }
        if (Files.isDirectory(abs)) {
            throw new ProjectException("Cannot save: " + abs + " is a folder.");
        }
        Path tmp = null;
        try {
            tmp = AtomicFiles.createTemp(dir, abs, ".nbai-save-");
            try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.WRITE)) {
                writeArchive(project, out, lim);
            }
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ch.force(true);
            }
            // Re-read and validate what was actually written before it can replace anything.
            ProjectData check = read(tmp, false, lim);
            if (check.size() != project.size()) {
                throw new ProjectException("Verification failed: page count mismatch after writing.");
            }
            for (int i = 0; i < check.size(); i++) {
                if (!check.pages().get(i).id().equals(project.pages().get(i).id())
                        || !check.pages().get(i).sha256().equals(project.pages().get(i).sha256())) {
                    throw new ProjectException("Verification failed: page content mismatch after writing.");
                }
            }
            if (beforeCommit != null) {
                beforeCommit.run();
            }
            try {
                Files.move(tmp, abs, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING); // non-atomic fallback
            }
            tmp = null;
        } catch (ProjectException e) {
            throw new ProjectException(e.getMessage() + " The previous file (if any) was left unchanged.", e);
        } catch (IOException e) {
            throw new ProjectException("Could not save the project: " + AtomicFiles.describe(e, dir)
                    + ". The previous file (if any) was left unchanged.", e);
        } catch (RuntimeException e) {
            throw new ProjectException("Could not save the project: " + describe(e)
                    + ". The previous file (if any) was left unchanged.", e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // Best effort: a leftover ".nbai-save-*.tmp" is harmless and safe to delete.
                }
            }
        }
    }

    /** Test-only failure injection. */
    interface FailureInjector {
        void run() throws IOException;
    }

    private static void writeArchive(ProjectData project, OutputStream raw, Limits lim) throws IOException, ProjectException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", FORMAT_NAME);
        root.put("formatVersion", (long) FORMAT_VERSION);
        root.put("writtenBy", "notebook-ai");
        root.put("projectId", project.projectId());
        List<Object> pages = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (PageData p : project.pages()) {
            if (!ID.matcher(p.id()).matches() || !ids.add(p.id())) {
                throw new ProjectException("Invalid or duplicate page id.");
            }
            String fmt = PageData.detectFormat(p.original());
            if (fmt == null) {
                throw new ProjectException("Page \"" + p.name() + "\" is not a JPEG or PNG image.");
            }
            if (p.original().length > lim.maxImageBytes()) {
                throw new ProjectException("Page \"" + p.name() + "\" is larger than "
                        + (lim.maxImageBytes() >> 20) + " MiB.");
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id());
            m.put("name", p.name());
            m.put("image", "pages/" + p.id() + "/original");
            m.put("imageFormat", fmt);
            m.put("imageBytes", (long) p.original().length);
            m.put("imageSha256", p.sha256());
            Map<String, Object> prep = new LinkedHashMap<>();
            prep.put("quarterTurnsCw", (long) p.quarterTurnsCw());
            prep.put("fineDegrees", p.fineDegrees());
            int[] crop = p.crop();
            if (crop == null) {
                prep.put("crop", null);
            } else {
                List<Object> c = new ArrayList<>();
                for (int v : crop) {
                    c.add((long) v);
                }
                prep.put("crop", c);
            }
            m.put("prep", prep);
            m.put("recognized", p.recognized());
            m.put("rawText", p.rawText());
            m.put("editedText", p.editedText());
            m.put("ocrIdentity", p.ocrIdentity());
            m.put("regionsPrepKey", p.recognized() ? p.prepKey() : null);
            List<Object> regs = new ArrayList<>();
            for (RegionData r : p.regions()) {
                Map<String, Object> rm = new LinkedHashMap<>();
                List<Object> poly = new ArrayList<>();
                for (double[] pt : r.polygon()) {
                    List<Object> xy = new ArrayList<>();
                    xy.add(pt[0]);
                    xy.add(pt[1]);
                    poly.add(xy);
                }
                rm.put("polygon", poly);
                List<Object> box = new ArrayList<>();
                for (int v : r.box()) {
                    box.add((long) v);
                }
                rm.put("box", box);
                rm.put("text", r.text());
                rm.put("detScore", r.detScore());
                rm.put("recScore", r.recScore());
                regs.add(rm);
            }
            m.put("regions", regs);
            pages.add(m);
        }
        root.put("pages", pages);
        byte[] json;
        try {
            json = MiniJson.write(root).getBytes(StandardCharsets.UTF_8);
        } catch (MiniJson.JsonException e) {
            throw new ProjectException("Could not encode the project: " + e.getMessage());
        }
        if (json.length > lim.maxJsonBytes()) {
            throw new ProjectException("The project's text data is larger than " + (lim.maxJsonBytes() >> 20) + " MiB.");
        }
        try (ZipOutputStream zos = new ZipOutputStream(raw)) {
            zos.setLevel(Deflater.DEFAULT_COMPRESSION);
            zos.putNextEntry(new ZipEntry(JSON_ENTRY));
            zos.write(json);
            zos.closeEntry();
            zos.setLevel(Deflater.NO_COMPRESSION); // JPEG/PNG are already compressed
            for (PageData p : project.pages()) {
                zos.putNextEntry(new ZipEntry("pages/" + p.id() + "/original"));
                zos.write(p.original());
                zos.closeEntry();
            }
        }
    }

    // ================================================================== load

    /** Reads and fully validates a project file. Never throws unchecked exceptions for bad input. */
    public static ProjectData load(Path file) throws ProjectException {
        return read(file, true, Limits.DEFAULT);
    }

    /** Loads with explicit limits (tests, tooling). */
    public static ProjectData load(Path file, Limits lim) throws ProjectException {
        return read(file, true, lim);
    }

    private static ProjectData read(Path file, boolean retainImages, Limits lim) throws ProjectException {
        String label = file.getFileName() == null ? file.toString() : file.getFileName().toString();
        if (!Files.exists(file)) {
            throw new ProjectException("Cannot open " + label + ": the file does not exist.");
        }
        if (!Files.isRegularFile(file)) {
            throw new ProjectException("Cannot open " + label + ": it is not a regular file.");
        }
        try {
            if (Files.size(file) > lim.maxTotalBytes()) {
                throw new ProjectException("Cannot open " + label + ": the file is larger than the supported limit.");
            }
        } catch (IOException e) {
            throw new ProjectException("Cannot read " + label + ": " + describe(e), e);
        }
        try (ZipFile zf = new ZipFile(file.toFile())) {
            return readZip(zf, label, retainImages, lim);
        } catch (java.util.zip.ZipException e) {
            throw new ProjectException(label + " is not a valid notebook-ai project (the archive is corrupt or "
                    + "incomplete: " + describe(e) + ").", e);
        } catch (IOException e) {
            throw new ProjectException("Cannot read " + label + ": " + describe(e), e);
        } catch (RuntimeException e) {
            throw new ProjectException(label + " could not be read as a project (" + describe(e) + ").", e);
        }
    }

    private static ProjectData readZip(ZipFile zf, String label, boolean retain, Limits lim) throws IOException, ProjectException {
        // --- entry-name validation: fixed pattern, no duplicates, no extras, bounded count
        Set<String> names = new HashSet<>();
        int count = 0;
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            count++;
            if (count > lim.maxPages() + 1) {
                throw bad(label, "it contains too many entries (limit " + (lim.maxPages() + 1) + ")");
            }
            String n = e.getName();
            if (e.isDirectory() || n.length() > 200) {
                throw bad(label, "it contains an unexpected entry");
            }
            if (!(n.equals(JSON_ENTRY) || IMAGE_ENTRY.matcher(n).matches())) {
                throw bad(label, "it contains an entry with an unsafe or unknown name");
            }
            if (!names.add(n)) {
                throw bad(label, "it contains a duplicate entry \"" + n + "\"");
            }
        }
        if (!names.contains(JSON_ENTRY)) {
            throw bad(label, "project.json is missing");
        }
        ZipEntry jsonEntry = zf.getEntry(JSON_ENTRY);
        byte[] jsonBytes = readBounded(zf.getInputStream(jsonEntry), lim.maxJsonBytes(), null, "project.json", label);
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(jsonBytes)).toString();
        } catch (CharacterCodingException e) {
            throw bad(label, "project.json is not valid UTF-8");
        }
        Object parsed;
        try {
            parsed = MiniJson.parse(text);
        } catch (MiniJson.JsonException e) {
            throw bad(label, "project.json is not valid JSON (" + e.getMessage() + ")");
        }
        Map<String, Object> root = obj(parsed, "project", label);
        if (!FORMAT_NAME.equals(root.get("format"))) {
            throw new ProjectException(label + " is not a notebook-ai project file.");
        }
        Object ver = root.get("formatVersion");
        if (!(ver instanceof Long v) || v < 1) {
            throw bad(label, "formatVersion is missing or invalid");
        }
        if (v > FORMAT_VERSION) {
            throw new ProjectException(label + " was written by a newer version of notebook-ai (project format v"
                    + v + "; this version reads up to v" + FORMAT_VERSION
                    + "). Update notebook-ai to open it.");
        }
        String projectId = str(root, "projectId", label, 64);
        if (!ID.matcher(projectId).matches()) {
            throw bad(label, "projectId is invalid");
        }
        List<Object> pageList = list(root.get("pages"), "pages", label);
        if (pageList.size() > lim.maxPages()) {
            throw bad(label, "it has more than " + lim.maxPages() + " pages");
        }
        ProjectData project = new ProjectData(projectId);
        Set<String> referenced = new HashSet<>();
        referenced.add(JSON_ENTRY);
        Set<String> ids = new HashSet<>();
        long[] total = {jsonBytes.length};
        for (Object po : pageList) {
            Map<String, Object> pm = obj(po, "page", label);
            String id = str(pm, "id", label, 64);
            if (!ID.matcher(id).matches() || !ids.add(id)) {
                throw bad(label, "a page id is invalid or duplicated");
            }
            String name = str(pm, "name", label, MAX_NAME_CHARS);
            String image = str(pm, "image", label, 200);
            if (!image.equals("pages/" + id + "/original")) {
                throw bad(label, "page " + id + " references an unexpected image entry");
            }
            if (!names.contains(image)) {
                throw bad(label, "the image for page " + id + " is missing");
            }
            referenced.add(image);
            String sha = str(pm, "imageSha256", label, 64);
            long declared = lng(pm, "imageBytes", label);
            ZipEntry ie = zf.getEntry(image);
            if (ie.getSize() > lim.maxImageBytes()) {
                throw bad(label, "the image for page " + id + " exceeds the " + fmt(lim.maxImageBytes()) + " limit");
            }
            MessageDigest md = sha256();
            byte[] img = readBounded(zf.getInputStream(ie), lim.maxImageBytes(), md, "image of page " + id, label);
            total[0] += img.length;
            if (total[0] > lim.maxTotalBytes()) {
                throw bad(label, "the expanded size exceeds the supported limit");
            }
            if (img.length != declared) {
                throw bad(label, "the image for page " + id + " has the wrong size (damaged file)");
            }
            if (!hex(md.digest()).equals(sha)) {
                throw bad(label, "the image for page " + id + " fails its integrity check (damaged file)");
            }
            if (PageData.detectFormat(img) == null) {
                throw bad(label, "the image for page " + id + " is not a JPEG or PNG");
            }
            int[] dim = notebookai.ocr.DesktopImageIO.peekDimensions(img);
            if (dim == null || dim[0] <= 0 || dim[1] <= 0) {
                throw bad(label, "the image for page " + id + " is unreadable");
            }
            if ((long) dim[0] * dim[1] > lim.maxPixels()) {
                throw bad(label, "the image for page " + id + " is larger than " + (lim.maxPixels() / 1_000_000)
                        + " megapixels");
            }
            PageData page = new PageData(id, name, retain ? img : new byte[0], sha);
            // prep
            Map<String, Object> prep = obj(pm.get("prep"), "prep", label);
            long q = lng(prep, "quarterTurnsCw", label);
            double fine = dbl(prep, "fineDegrees", label);
            if (q < 0 || q > 3 || fine < -10.0 || fine > 10.0) {
                throw bad(label, "page " + id + " has invalid preparation settings");
            }
            int[] crop = null;
            if (prep.get("crop") != null) {
                List<Object> c = list(prep.get("crop"), "crop", label);
                if (c.size() != 4) {
                    throw bad(label, "page " + id + " has an invalid crop");
                }
                crop = new int[4];
                for (int i = 0; i < 4; i++) {
                    crop[i] = (int) lngv(c.get(i), "crop", label, 0, 1_000_000);
                }
                if (crop[2] <= crop[0] || crop[3] <= crop[1]) {
                    throw bad(label, "page " + id + " has an invalid crop");
                }
            }
            page.setPrep((int) q, fine, crop);
            // text / recognition state
            Object recObj = pm.get("recognized");
            if (!(recObj instanceof Boolean recognized)) {
                throw bad(label, "page " + id + ": recognized flag missing");
            }
            String raw = optStr(pm, "rawText", label);
            String edited = str(pm, "editedText", label, Integer.MAX_VALUE);
            String identity = optStr(pm, "ocrIdentity", label);
            if (recognized && raw == null) {
                throw bad(label, "page " + id + " is marked recognized but has no raw text");
            }
            if (!recognized && (raw != null || identity != null)) {
                throw bad(label, "page " + id + " has OCR data but is not marked recognized");
            }
            List<RegionData> regions = new ArrayList<>();
            String notice = null;
            List<Object> regList = list(pm.get("regions"), "regions", label);
            if (!recognized && !regList.isEmpty()) {
                throw bad(label, "page " + id + " has regions but is not recognized");
            }
            if (regList.size() > lim.maxRegionsPerPage()) {
                throw bad(label, "page " + id + " has too many regions");
            }
            for (Object ro : regList) {
                regions.add(region(ro, label));
            }
            if (recognized && !regions.isEmpty()) {
                String savedKey = optStr(pm, "regionsPrepKey", label);
                if (!page.prepKey().equals(savedKey)) {
                    regions.clear();
                    notice = "Region overlays were not restored (they did not match the saved image preparation). "
                            + "Extract again to inspect regions.";
                }
            }
            page.restoreRecognition(recognized, raw, edited, identity, regions);
            page.setLoadNotice(notice);
            project.add(page);
        }
        for (String n : names) {
            if (!referenced.contains(n)) {
                throw bad(label, "it contains an unreferenced entry \"" + n + "\"");
            }
        }
        return project;
    }

    private static RegionData region(Object o, String label) throws ProjectException {
        Map<String, Object> m = obj(o, "region", label);
        List<Object> poly = list(m.get("polygon"), "polygon", label);
        if (poly.size() != 4) {
            throw bad(label, "a region polygon is invalid");
        }
        double[][] p = new double[4][2];
        for (int i = 0; i < 4; i++) {
            List<Object> xy = list(poly.get(i), "polygon", label);
            if (xy.size() != 2) {
                throw bad(label, "a region polygon is invalid");
            }
            p[i][0] = dblv(xy.get(0), "polygon", label);
            p[i][1] = dblv(xy.get(1), "polygon", label);
        }
        List<Object> b = list(m.get("box"), "box", label);
        if (b.size() != 4) {
            throw bad(label, "a region box is invalid");
        }
        int[] box = new int[4];
        for (int i = 0; i < 4; i++) {
            box[i] = (int) lngv(b.get(i), "box", label, -1_000_000, 1_000_000);
        }
        return new RegionData(p, box, str(m, "text", label, Integer.MAX_VALUE),
                dbl(m, "detScore", label), dbl(m, "recScore", label));
    }

    // ------------------------------------------------------------------ helpers

    private static ProjectException bad(String label, String why) {
        return new ProjectException(label + " is damaged or not a valid project: " + why + ".");
    }

    private static byte[] readBounded(InputStream in, long limit, MessageDigest md, String what, String label)
            throws IOException, ProjectException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            long n = 0;
            int r;
            while ((r = is.read(buf)) > 0) {
                n += r;
                if (n > limit) {
                    throw bad(label, what + " exceeds the " + fmt(limit) + " limit");
                }
                if (md != null) {
                    md.update(buf, 0, r);
                }
                out.write(buf, 0, r);
            }
            return out.toByteArray();
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] h) {
        StringBuilder sb = new StringBuilder();
        for (byte b : h) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object o, String what, String label) throws ProjectException {
        if (o instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw bad(label, what + " is not an object");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o, String what, String label) throws ProjectException {
        if (o instanceof List<?> l) {
            return (List<Object>) l;
        }
        throw bad(label, what + " is not a list");
    }

    private static String str(Map<String, Object> m, String key, String label, int max) throws ProjectException {
        Object o = m.get(key);
        if (!(o instanceof String s) || s.length() > max) {
            throw bad(label, "field \"" + key + "\" is missing or invalid");
        }
        return s;
    }

    private static String optStr(Map<String, Object> m, String key, String label) throws ProjectException {
        Object o = m.get(key);
        if (o == null) {
            return null;
        }
        if (!(o instanceof String s)) {
            throw bad(label, "field \"" + key + "\" is invalid");
        }
        return s;
    }

    private static long lng(Map<String, Object> m, String key, String label) throws ProjectException {
        Object o = m.get(key);
        if (!(o instanceof Long l)) {
            throw bad(label, "field \"" + key + "\" is missing or not an integer");
        }
        return l;
    }

    private static long lngv(Object o, String what, String label, long lo, long hi) throws ProjectException {
        if (!(o instanceof Long l) || l < lo || l > hi) {
            throw bad(label, what + " contains an invalid number");
        }
        return l;
    }

    private static double dbl(Map<String, Object> m, String key, String label) throws ProjectException {
        return dblv(m.get(key), key, label);
    }

    private static double dblv(Object o, String what, String label) throws ProjectException {
        if (o instanceof Long l) {
            return l.doubleValue();
        }
        if (o instanceof Double d && !d.isNaN() && !d.isInfinite()) {
            return d;
        }
        throw bad(label, "\"" + what + "\" contains an invalid number");
    }

    private static String describe(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
