package notebookai.ocr;

/**
 * Opt-in experiment switches for controlled accuracy studies. ALL defaults are
 * OFF and production behavior is unchanged unless a JVM property is given
 * explicitly (e.g. {@code -Dnotebookai.exp.cropPad=0.5}). Never chosen by
 * ground truth, file name, image hash or confidence. The active set is shown
 * in {@link OcrService#identity()} so an experimental run cannot pass as
 * production.
 */
public final class Experiments {

    private Experiments() {}

    /** Recognizer crop padding fraction: 1.0 = upstream unclip padding (default), 0 = core only. */
    public static double cropPad() {
        String v = System.getProperty("notebookai.exp.cropPad");
        if (v == null || v.trim().isEmpty()) {
            return 1.0;
        }
        return Math.max(0.0, Math.min(1.0, Double.parseDouble(v)));
    }

    /** CTC prefix beam width; 0 = greedy argmax decoding (default). */
    public static int beamWidth() {
        String v = System.getProperty("notebookai.exp.beam");
        return v == null || v.trim().isEmpty() ? 0 : Math.max(0, Integer.parseInt(v));
    }

    public static boolean any() {
        return cropPad() != 1.0 || beamWidth() > 0;
    }

    public static String describe() {
        return any() ? "EXPERIMENT cropPad=" + cropPad() + " beam=" + beamWidth() : "";
    }
}
