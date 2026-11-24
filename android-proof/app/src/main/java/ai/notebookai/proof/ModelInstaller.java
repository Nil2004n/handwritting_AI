package ai.notebookai.proof;

import android.content.Context;
import android.content.res.AssetManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import notebookai.ocr.ModelAssets;
import notebookai.ocr.OcrException;

/**
 * Makes the five model files available as a directory in app-private storage, which is what {@link ModelAssets#fromDirectory} wants (ONNX Runtime
 * reads from the path; nothing large sits on the Java heap). Assets inside an APK are not files, so they are copied once and then verified by the
 * ENGINE's own checksum check (the same one the desktop uses) on every open; a failed or partial copy is therefore detected, and re-copied.
 */
public final class ModelInstaller {

    private static final String[] FILES = {"det.onnx", "det.yml", "rec.onnx", "rec.yml", "checksums.sha256"};

    private ModelInstaller() {}

    /** Returns a verified model set in {@code <filesDir>/models}, copying from the APK assets when absent or invalid. */
    public static ModelAssets install(Context context) throws IOException, OcrException {
        File dir = new File(context.getFilesDir(), "models");
        ModelAssets assets = ModelAssets.fromDirectory(dir);
        try {
            assets.verify();
            return assets; // already installed and intact
        } catch (OcrException needsCopy) {
            // fall through: copy (first run, partial copy, or corrupted storage)
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        AssetManager am = context.getAssets();
        for (String name : FILES) {
            File tmp = new File(dir, name + ".part");
            try (InputStream in = am.open("models/" + name); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[1 << 16];
                for (int n = in.read(buf); n >= 0; n = in.read(buf)) {
                    out.write(buf, 0, n);
                }
            }
            File target = new File(dir, name);
            if (target.exists() && !target.delete()) {
                throw new IOException("cannot replace " + target);
            }
            if (!tmp.renameTo(target)) {
                throw new IOException("cannot move " + tmp + " to " + target);
            }
        }
        assets.verify(); // throws OcrException if the copy is not intact
        return assets;
    }
}
