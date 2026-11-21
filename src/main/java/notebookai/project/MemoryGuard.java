package notebookai.project;

/**
 * Refuses to decode an image BEFORE allocating if it cannot fit in the JVM heap. Decoding a page
 * keeps roughly four full-size 3-byte-per-pixel buffers alive at once (decoded raster, BGR copy,
 * the working-image copy and the rendered view), i.e. about {@link #BYTES_PER_PIXEL} bytes per
 * pixel; the check allows that to use at most {@link #HEAP_FRACTION} of the maximum heap. This is
 * an estimate, not a guarantee: native inference memory is separate and not counted.
 */
public final class MemoryGuard {

    public static final long BYTES_PER_PIXEL = 12;
    public static final double HEAP_FRACTION = 0.7;

    private MemoryGuard() {}

    /** Returns null if a width x height image may be decoded, else a user-facing reason. */
    public static String check(long width, long height, long maxHeapBytes) {
        long needed = width * height * BYTES_PER_PIXEL;
        if (needed > (long) (maxHeapBytes * HEAP_FRACTION)) {
            return "This image (" + width + "x" + height + ", about " + (needed >> 20)
                    + " MiB to process) is too large for the available memory (limit "
                    + (maxHeapBytes >> 20) + " MiB heap). Use a smaller image or start the application with more "
                    + "memory (e.g. JAVA_OPTS=-Xmx4g).";
        }
        return null;
    }

    public static String checkAgainstRuntime(long width, long height) {
        return check(width, height, Runtime.getRuntime().maxMemory());
    }
}
