package notebookai.ocr;

/**
 * The only image type the OCR engine accepts. Platform-neutral: no AWT, no JavaFX, no Android types.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li><b>Pixels:</b> 8 bits per channel, 3 channels, in the order <b>B, G, R</b> (like OpenCV {@code imread}).
 *       The detector's ImageNet mean/std are applied per channel in exactly this order, so a swapped order silently
 *       degrades recognition. Adapters must convert; the engine never guesses.</li>
 *   <li><b>Layout:</b> packed row-major, pixel (x, y) starts at byte {@code (y * width + x) * 3}; stride is exactly
 *       {@code width * 3} (no row padding); array length is exactly {@code width * height * 3}.</li>
 *   <li><b>Coordinates:</b> origin is the top-left pixel centre-less grid corner: x grows right, y grows down; a
 *       result coordinate (x, y) refers to this image, <i>after</i> orientation has been applied.</li>
 *   <li><b>Alpha:</b> none. Adapters composite transparent pixels over <b>black</b> before building a
 *       {@code PixelImage} (this is what the desktop adapter has always done); see {@link #fromArgbPacked}.</li>
 *   <li><b>Orientation:</b> a {@code PixelImage} is expected to be <i>display-oriented</i> (EXIF orientation already
 *       applied). Reading the EXIF tag is a platform adapter job; applying it is {@link #withExifOrientation(int)}.</li>
 *   <li><b>Ownership / mutability:</b> immutable by contract. {@link #wrapBgr} takes ownership of the array without
 *       copying (large photos are tens of MB) and the caller must not modify it afterwards; {@link #copyOfBgr} copies.
 *       {@link #bgr()} returns the internal array (a view, not a copy); callers must treat it as read-only.</li>
 *   <li><b>Validation:</b> width and height &ge; 1, {@code width * height * 3} must fit in an {@code int}, and the
 *       array length must match exactly; violations throw {@link IllegalArgumentException} (a caller bug, not a
 *       recognition failure).</li>
 * </ul>
 */
public final class PixelImage {

    private final byte[] bgr;
    private final int width;
    private final int height;

    private PixelImage(byte[] bgr, int width, int height) {
        this.bgr = bgr;
        this.width = width;
        this.height = height;
    }

    /** Byte count of a {@code width x height} BGR image; throws if non-positive or larger than an {@code int}. */
    public static int byteLength(int width, int height) {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("image dimensions must be positive: " + width + "x" + height);
        }
        try {
            return Math.multiplyExact(Math.multiplyExact(width, height), 3);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("image too large: " + width + "x" + height, e);
        }
    }

    /** Wraps (does not copy) a BGR array; the caller gives up the right to modify it. */
    public static PixelImage wrapBgr(byte[] bgr, int width, int height) {
        if (bgr == null) {
            throw new IllegalArgumentException("pixel array is null");
        }
        int expected = byteLength(width, height);
        if (bgr.length != expected) {
            throw new IllegalArgumentException(
                    "pixel array has " + bgr.length + " bytes, expected " + expected + " for " + width + "x" + height + " BGR");
        }
        return new PixelImage(bgr, width, height);
    }

    /** Copies a BGR array (use when the caller will keep mutating its own buffer). */
    public static PixelImage copyOfBgr(byte[] bgr, int width, int height) {
        PixelImage checked = wrapBgr(bgr, width, height);
        return new PixelImage(bgr.clone(), checked.width, checked.height);
    }

    /**
     * Builds an image from packed {@code 0xAARRGGBB} integers (Android {@code Bitmap.getPixels}, AWT {@code getRGB}).
     * Non-opaque pixels are composited over black, {@code channel = round(alpha * channel / 255)}, which is what Java2D's
     * SrcOver does onto a black opaque image (the historical desktop behavior). Opaque pixels are copied exactly.
     * Note for Android: {@code Bitmap.getPixels} returns <i>non-premultiplied</i> ARGB; premultiplied sources must be
     * un-premultiplied first or, equivalently, passed as already-composited (alpha 255) values.
     */
    public static PixelImage fromArgbPacked(int[] argb, int width, int height) {
        int pixels = byteLength(width, height) / 3;
        if (argb == null || argb.length < pixels) {
            throw new IllegalArgumentException("pixel array has fewer than width*height entries");
        }
        byte[] out = new byte[pixels * 3];
        for (int i = 0; i < pixels; i++) {
            int p = argb[i];
            int a = p >>> 24;
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            if (a != 255) {
                r = (a * r + 127) / 255;
                g = (a * g + 127) / 255;
                b = (a * b + 127) / 255;
            }
            out[i * 3] = (byte) b;
            out[i * 3 + 1] = (byte) g;
            out[i * 3 + 2] = (byte) r;
        }
        return new PixelImage(out, width, height);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** The internal BGR array (NOT a copy). Read-only by contract; length is {@code width * height * 3}. */
    public byte[] bgr() {
        return bgr;
    }

    /** An independent copy of the BGR bytes. */
    public byte[] toBgrCopy() {
        return bgr.clone();
    }

    /**
     * Returns this image transformed by an EXIF orientation tag (1-8, EXIF 2.3 mapping: 2/4/5/7 mirror, 3/4/6/8
     * rotate), or {@code this} for 1 and for any value outside 1-8 (which is how absent/garbled tags are treated).
     * Pure pixel permutation: no value changes, so applying it before or after any colour conversion is equivalent.
     */
    public PixelImage withExifOrientation(int orientation) {
        if (orientation < 2 || orientation > 8) {
            return this;
        }
        final int w = width;
        final int h = height;
        final boolean swap = orientation >= 5; // 5-8 transpose the axes: destination is h x w
        final int dw = swap ? h : w;
        byte[] dst = new byte[bgr.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int dx;
                int dy;
                switch (orientation) {
                    case 2 -> { dx = w - 1 - x; dy = y; }            // mirror horizontally
                    case 3 -> { dx = w - 1 - x; dy = h - 1 - y; }    // rotate 180
                    case 4 -> { dx = x; dy = h - 1 - y; }            // mirror vertically
                    case 5 -> { dx = y; dy = x; }                    // transpose
                    case 6 -> { dx = h - 1 - y; dy = x; }            // rotate 90 clockwise
                    case 7 -> { dx = h - 1 - y; dy = w - 1 - x; }    // transverse
                    default -> { dx = y; dy = w - 1 - x; }           // 8: rotate 90 counter-clockwise
                }
                System.arraycopy(bgr, (y * w + x) * 3, dst, (dy * dw + dx) * 3, 3);
            }
        }
        return new PixelImage(dst, dw, swap ? w : h);
    }
}
