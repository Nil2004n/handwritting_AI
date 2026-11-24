package notebookai.ocr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The desktop decode adapter: EXIF end to end, alpha, gray, channel order, errors. Generated, non-private images only. */
class DesktopImageIOTest {

    @TempDir
    Path tmp;

    private static BufferedImage gradient(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, ((x * 255 / (w - 1)) << 16) | ((y * 255 / (h - 1)) << 8) | ((x * 7 + y * 13) & 0xFF));
            }
        }
        return img;
    }

    private static byte[] png(BufferedImage img) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ImageIO.write(img, "png", o);
        return o.toByteArray();
    }

    /** A JPEG with a minimal EXIF APP1 segment carrying only the Orientation tag. */
    private static byte[] jpegWithOrientation(BufferedImage img, int orientation) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", o);
        byte[] jpeg = o.toByteArray();
        byte[] tiff = {
            'M', 'M', 0, 42, 0, 0, 0, 8, // big-endian TIFF header, IFD0 at offset 8
            0, 1, // one entry
            0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0, // Orientation, SHORT, count 1
            0, 0, 0, 0 // no next IFD
        };
        byte[] exif = new byte[6 + tiff.length];
        System.arraycopy(new byte[] {'E', 'x', 'i', 'f', 0, 0}, 0, exif, 0, 6);
        System.arraycopy(tiff, 0, exif, 6, tiff.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2); // SOI
        out.write(0xFF);
        out.write(0xE1);
        out.write((exif.length + 2) >> 8);
        out.write((exif.length + 2) & 0xFF);
        out.write(exif);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    @Test
    void decodedPixelsAreTrueBgr() throws Exception {
        BufferedImage img = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, 0x102030); // R=0x10 G=0x20 B=0x30
        img.setRGB(1, 0, 0xAABBCC);
        PixelImage d = DesktopImageIO.decode(png(img));
        assertArrayEquals(new byte[] {0x30, 0x20, 0x10, (byte) 0xCC, (byte) 0xBB, (byte) 0xAA}, d.bgr());
    }

    @Test
    void everyExifOrientationEqualsTheNormalImageTransformed() throws Exception {
        BufferedImage img = gradient(37, 23);
        PixelImage normal = DesktopImageIO.decode(jpegWithOrientation(img, 1));
        for (int tag = 2; tag <= 8; tag++) {
            PixelImage got = DesktopImageIO.decode(jpegWithOrientation(img, tag));
            PixelImage want = normal.withExifOrientation(tag);
            assertEquals(want.width(), got.width(), "width, tag " + tag);
            assertEquals(want.height(), got.height(), "height, tag " + tag);
            assertArrayEquals(want.bgr(), got.bgr(), "pixels, tag " + tag);
            if (tag >= 5) {
                assertEquals(23, got.width(), "tags 5-8 swap the axes");
            }
        }
        // The path-based entry point reads the same tag.
        Path f = tmp.resolve("rot6.jpg");
        Files.write(f, jpegWithOrientation(img, 6));
        assertArrayEquals(normal.withExifOrientation(6).bgr(), DesktopImageIO.decode(f).bgr());
    }

    @Test
    void translucentPixelsCompositeOverBlackAndMatchTheArgbAdapterPath() throws Exception {
        BufferedImage img = new BufferedImage(16, 4, BufferedImage.TYPE_INT_ARGB);
        int[] argb = new int[16 * 4];
        for (int i = 0; i < argb.length; i++) {
            argb[i] = ((i * 4) << 24) | (0xFF0000 >> ((i % 3) * 8)) | (i * 3 & 0xFF) << 8;
        }
        img.setRGB(0, 0, 16, 4, argb, 0, 16);
        PixelImage desktop = DesktopImageIO.decode(png(img));
        // What an Android host does: Bitmap.getPixels (non-premultiplied ARGB) -> PixelImage.fromArgbPacked.
        PixelImage portable = PixelImage.fromArgbPacked(argb, 16, 4);
        assertArrayEquals(desktop.bgr(), portable.bgr());
    }

    /**
     * Gray sources: the desktop decode (Java2D drawImage onto a black 3BYTE_BGR) REPLICATES the gray sample into B=G=R. It does
     * not apply the linear-gray-to-sRGB gamma that {@code BufferedImage.getRGB} would (that route turns sample 8 into 50).
     * The engine contract is "the converted BGR pixels"; a mobile adapter must therefore hand over gray as replicated values.
     */
    @Test
    void grayPngsAreReplicatedNotGammaConverted() throws Exception {
        BufferedImage gray = new BufferedImage(32, 8, BufferedImage.TYPE_BYTE_GRAY);
        for (int x = 0; x < 32; x++) {
            for (int y = 0; y < 8; y++) {
                gray.getRaster().setSample(x, y, 0, x * 8);
            }
        }
        PixelImage desktop = DesktopImageIO.decode(png(gray));
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 32; x++) {
                int i = (y * 32 + x) * 3;
                assertEquals(x * 8, desktop.bgr()[i] & 0xFF, "B @" + x);
                assertEquals(x * 8, desktop.bgr()[i + 1] & 0xFF, "G @" + x);
                assertEquals(x * 8, desktop.bgr()[i + 2] & 0xFF, "R @" + x);
            }
        }
        // The route to avoid: getRGB would give a different (gamma-converted) value for the same sample.
        assertEquals(50, gray.getRGB(1, 0) & 0xFF, "documented platform quirk: getRGB gamma-converts linear gray");
    }

    @Test
    void rejectsUnsupportedNamesAndCorruptData() throws Exception {
        Path txt = tmp.resolve("note.txt");
        Files.writeString(txt, "hello");
        assertThrows(OcrException.class, () -> DesktopImageIO.decode(txt));
        assertThrows(OcrException.class, () -> DesktopImageIO.decode(new byte[] {1, 2, 3, 4}));
        Path fake = tmp.resolve("fake.png");
        Files.writeString(fake, "not a png");
        assertThrows(OcrException.class, () -> DesktopImageIO.decode(fake));
    }
}
