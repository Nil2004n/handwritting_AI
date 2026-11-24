package ai.notebookai.proof;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import notebookai.ocr.OcrException;
import notebookai.ocr.PixelImage;

/**
 * ANDROID image adapter: the counterpart of the desktop {@code DesktopImageIO}. Everything platform-specific about getting pixels into the
 * engine lives here; the engine itself never sees a Bitmap.
 *
 * <p>Contract it fulfils (see docs/ENGINE.md): true BGR, no alpha (transparent pixels composited over black), display orientation (EXIF tag
 * applied), original resolution (no {@code inSampleSize}: sub-sampling would change the pixels the recognizer sees).
 *
 * <p>Memory: a decoded bitmap (4 bytes/pixel) plus the {@code int[]} copy (4) plus the BGR array (3) coexist briefly; a 12 MP photo peaks around
 * 130 MB. Run on a background thread; consider a size cap policy in the host app (a product decision, not made here).
 */
public final class AndroidImageAdapter {

    private AndroidImageAdapter() {}

    /** Decodes JPEG/PNG bytes. EXIF orientation is read with the framework {@link ExifInterface} (API 24+: stream constructor). */
    public static PixelImage decode(byte[] encoded) throws OcrException, IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inScaled = false; // no density scaling
        Bitmap bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.length, options);
        if (bitmap == null) {
            throw new OcrException("Unreadable or corrupt image data.");
        }
        try {
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            int[] argb = new int[PixelImage.byteLength(w, h) / 3];
            bitmap.getPixels(argb, 0, w, 0, 0, w, h); // sRGB, NON-premultiplied 0xAARRGGBB
            return PixelImage.fromArgbPacked(argb, w, h).withExifOrientation(exifOrientation(encoded));
        } finally {
            bitmap.recycle();
        }
    }

    /** EXIF orientation 1-8; 1 when absent or unreadable (the same fallback as the desktop adapter). */
    static int exifOrientation(byte[] encoded) {
        try {
            return new ExifInterface(new ByteArrayInputStream(encoded)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
        } catch (IOException | RuntimeException e) {
            return 1;
        }
    }
}
