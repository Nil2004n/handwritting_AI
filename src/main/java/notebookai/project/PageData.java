package notebookai.project;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * One page of a project: the ORIGINAL imported image bytes (owned by the project, so reopening
 * never depends on the source file), the image-preparation state, and the OCR/text state.
 *
 * <p>State semantics (explicit, never inferred):
 * <ul>
 *   <li>{@code recognized == false}: page never extracted since its last preparation change;
 *       {@code rawText == null}, no regions. This is NOT the same as recognized-and-empty.</li>
 *   <li>{@code recognized == true}: {@code rawText} is the latest raw OCR output (possibly the
 *       empty string = "recognized, no text"); {@code regions} holds the region geometry for the
 *       prep state it was produced from.</li>
 *   <li>{@code editedText} is the user-reviewed transcript (starts equal to the raw text).</li>
 * </ul>
 * Transient things (selection rectangles, crop drafts, selected region, review results, running
 * jobs) are deliberately NOT part of this class.
 */
public final class PageData {

    private final String id;
    private String name;
    private final byte[] original; // never mutated
    private final String sha256;
    private int quarterTurnsCw;
    private double fineDegrees;
    private int[] crop; // [x0,y0,x1,y1] in the rotated frame, or null
    private boolean recognized;
    private String rawText; // null iff !recognized
    private String editedText = "";
    private String ocrIdentity; // null iff !recognized
    private List<RegionData> regions = new ArrayList<>();
    private String loadNotice; // transient, set by the loader only

    public PageData(String id, String name, byte[] original) {
        this(id, name, original, sha256Hex(original));
    }

    PageData(String id, String name, byte[] original, String sha256) {
        this.id = id;
        this.name = name;
        this.original = original;
        this.sha256 = sha256;
    }

    /** New page with a fresh stable identifier. */
    public static PageData create(String name, byte[] original) {
        return new PageData(UUID.randomUUID().toString(), name, original);
    }

    public static String sha256Hex(byte[] data) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : h) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Key identifying the prep state that region geometry/OCR belongs to. */
    public String prepKey() {
        return String.format(Locale.ROOT, "%d|%s|%s", quarterTurnsCw, Double.toString(fineDegrees),
                crop == null ? "-" : Arrays.toString(crop));
    }

    // ------------------------------------------------------------------ accessors

    public String id() { return id; }
    public String name() { return name; }
    public void setName(String n) { this.name = n; }
    public byte[] original() { return original; }
    public String sha256() { return sha256; }
    public int quarterTurnsCw() { return quarterTurnsCw; }
    public double fineDegrees() { return fineDegrees; }
    public int[] crop() { return crop == null ? null : crop.clone(); }
    public boolean recognized() { return recognized; }
    public String rawText() { return rawText; }
    public String editedText() { return editedText; }
    public String ocrIdentity() { return ocrIdentity; }
    public List<RegionData> regions() { return regions; }
    public String loadNotice() { return loadNotice; }
    public void setLoadNotice(String n) { this.loadNotice = n; }

    // ------------------------------------------------------------------ mutation

    /** Records the prep state; does NOT touch OCR state (callers clear it when prep changes). */
    public void setPrep(int quarterTurnsCw, double fineDegrees, int[] crop) {
        this.quarterTurnsCw = quarterTurnsCw;
        this.fineDegrees = fineDegrees;
        this.crop = crop == null ? null : crop.clone();
    }

    public void setEditedText(String text) {
        this.editedText = text == null ? "" : text;
    }

    /** Stores a fresh OCR result; the edited transcript restarts from the raw text. */
    public void setRecognition(String raw, String identity, List<RegionData> regionList) {
        this.recognized = true;
        this.rawText = raw == null ? "" : raw;
        this.editedText = this.rawText;
        this.ocrIdentity = identity;
        this.regions = new ArrayList<>(regionList);
    }

    /** Back to the explicit "not recognized" state (used when the prepared image changes). */
    public void clearRecognition() {
        this.recognized = false;
        this.rawText = null;
        this.editedText = "";
        this.ocrIdentity = null;
        this.regions = new ArrayList<>();
    }

    /** Restores persisted OCR state exactly (loader only). */
    void restoreRecognition(boolean recognized, String raw, String edited, String identity,
            List<RegionData> regionList) {
        this.recognized = recognized;
        this.rawText = raw;
        this.editedText = edited == null ? "" : edited;
        this.ocrIdentity = identity;
        this.regions = new ArrayList<>(regionList);
    }

    /** True when the edited transcript differs from what OCR produced (or text exists on an unrecognized page). */
    public boolean hasUserEdits() {
        return recognized ? !editedText.equals(rawText) : !editedText.isEmpty();
    }

    /** Independent copy for background saves (the immutable original bytes are shared). */
    public PageData copy() {
        PageData p = new PageData(id, name, original, sha256);
        p.quarterTurnsCw = quarterTurnsCw;
        p.fineDegrees = fineDegrees;
        p.crop = crop == null ? null : crop.clone();
        p.recognized = recognized;
        p.rawText = rawText;
        p.editedText = editedText;
        p.ocrIdentity = ocrIdentity;
        p.regions = new ArrayList<>(regions);
        return p;
    }

    /** Detects the container format from magic bytes: "jpeg", "png" or null. */
    public static String detectFormat(byte[] d) {
        if (d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) {
            return "jpeg";
        }
        if (d.length >= 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G'
                && d[4] == 0x0D && d[5] == 0x0A && d[6] == 0x1A && d[7] == 0x0A) {
            return "png";
        }
        return null;
    }

    @Override
    public String toString() {
        return "Page " + id + " (" + name + ")";
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
