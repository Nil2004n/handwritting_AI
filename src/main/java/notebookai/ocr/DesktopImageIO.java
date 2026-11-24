package notebookai.ocr;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * DESKTOP image adapter (AWT {@code ImageIO} + metadata-extractor): file/byte decoding to {@link PixelImage}, EXIF
 * orientation, and conversion back to {@link BufferedImage} for debug output. Not part of the portable engine: an Android
 * host replaces this class with its own adapter (BitmapFactory + androidx ExifInterface, see the handoff documentation).
 *
 * <p>Decoding keeps the historical behavior exactly: {@code ImageIO.read}; anything other than {@code TYPE_3BYTE_BGR} is
 * drawn onto a black {@code TYPE_3BYTE_BGR} image with Java2D (so transparent pixels composite over black and gray/indexed
 * sources go through Java2D's colour conversion); the EXIF orientation tag (default 1) is applied after conversion.
 */
public final class DesktopImageIO {

    private DesktopImageIO() {}

    /** Loads JPG/JPEG/PNG, applies EXIF orientation, returns true-BGR pixels. */
    public static PixelImage decode(Path path) throws OcrException, IOException {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!(name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png"))) {
            throw new OcrException("Unsupported image type (Phase 1 supports JPG/JPEG and PNG): " + path);
        }
        BufferedImage img;
        try (InputStream in = Files.newInputStream(path)) {
            img = ImageIO.read(in);
        }
        if (img == null) {
            throw new OcrException("Unreadable or corrupt image: " + path);
        }
        return finishDecode(img, readExifOrientation(path));
    }

    /**
     * Loads JPEG/PNG from in-memory bytes (project-owned image content), same pipeline as {@link #decode(Path)}:
     * EXIF orientation applied, true BGR bytes returned.
     */
    public static PixelImage decode(byte[] data) throws OcrException, IOException {
        BufferedImage img;
        try (InputStream in = new ByteArrayInputStream(data)) {
            img = ImageIO.read(in);
        }
        if (img == null) {
            throw new OcrException("Unreadable or corrupt image data.");
        }
        int orientation = 1;
        try (InputStream in = new ByteArrayInputStream(data)) {
            orientation = exifOrientation(in);
        } catch (Exception ignored) {
            // Fall through to "normal".
        }
        return finishDecode(img, orientation);
    }

    /** Image dimensions from the header only (no pixel decode), or null if unreadable. */
    public static int[] peekDimensions(byte[] data) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                return new int[] {reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static PixelImage finishDecode(BufferedImage img, int orientation) {
        img = toBgr(img);
        int w = img.getWidth();
        int h = img.getHeight();
        byte[] bgr = new byte[PixelImage.byteLength(w, h)];
        // Raster.getDataElements returns bytes in BAND order, which for TYPE_3BYTE_BGR is R,G,B (not the B,G,R memory
        // order). Swap so the buffer really is BGR like cv2.imread: the detector's ImageNet mean/std are applied per
        // channel in that order, and the UI preview reads it as BGR.
        img.getRaster().getDataElements(0, 0, w, h, bgr);
        for (int i = 0; i < bgr.length; i += 3) {
            byte r = bgr[i];
            bgr[i] = bgr[i + 2];
            bgr[i + 2] = r;
        }
        // Orientation is a pure pixel permutation, so applying it to the BGR bytes equals applying it to the image first.
        return PixelImage.wrapBgr(bgr, w, h).withExifOrientation(orientation);
    }

    /** Converts true-BGR pixels to a {@link BufferedImage} (for debug PNGs and previews). */
    public static BufferedImage toBufferedImage(byte[] bgr, int w, int h) {
        int[] argb = new int[w * h];
        for (int i = 0; i < w * h; i++) {
            argb[i] = 0xFF000000 | ((bgr[i * 3 + 2] & 0xFF) << 16) | ((bgr[i * 3 + 1] & 0xFF) << 8) | (bgr[i * 3] & 0xFF);
        }
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, w, h, argb, 0, w);
        return img;
    }

    public static BufferedImage toBufferedImage(PixelImage image) {
        return toBufferedImage(image.bgr(), image.width(), image.height());
    }

    private static BufferedImage toBgr(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_3BYTE_BGR) {
            return img;
        }
        BufferedImage bgr = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        bgr.getGraphics().drawImage(img, 0, 0, null);
        return bgr;
    }

    /** EXIF orientation tag (1-8); 1 (normal) when absent or unreadable. */
    static int readExifOrientation(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return exifOrientation(in);
        } catch (Exception ignored) {
            return 1; // Fall through to "normal".
        }
    }

    private static int exifOrientation(InputStream in) throws Exception {
        Metadata metadata = ImageMetadataReader.readMetadata(in);
        ExifIFD0Directory dir = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
        if (dir != null && dir.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
            return dir.getInt(ExifIFD0Directory.TAG_ORIENTATION);
        }
        return 1;
    }
}
