package notebookai.ocr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The portable image contract: validation, ownership, EXIF permutation, alpha compositing. */
class PixelImageTest {

    private static byte[] randomBgr(int w, int h, long seed) {
        byte[] b = new byte[w * h * 3];
        new Random(seed).nextBytes(b);
        return b;
    }

    @Test
    void validatesDimensionsAndLength() {
        assertThrows(IllegalArgumentException.class, () -> PixelImage.wrapBgr(new byte[3], 0, 1));
        assertThrows(IllegalArgumentException.class, () -> PixelImage.wrapBgr(new byte[3], 1, -1));
        assertThrows(IllegalArgumentException.class, () -> PixelImage.wrapBgr(new byte[5], 1, 1)); // exact length
        assertThrows(IllegalArgumentException.class, () -> PixelImage.wrapBgr(new byte[2], 1, 1));
        assertThrows(IllegalArgumentException.class, () -> PixelImage.wrapBgr(null, 1, 1));
        // 46341 * 46341 * 3 overflows int: rejected, not wrapped around.
        assertThrows(IllegalArgumentException.class, () -> PixelImage.byteLength(46341, 46341));
        assertThrows(IllegalArgumentException.class, () -> PixelImage.byteLength(Integer.MAX_VALUE, 3));
        assertEquals(3 * 4 * 5, PixelImage.byteLength(4, 5));
    }

    @Test
    void wrapSharesTheArrayAndCopyDoesNot() {
        byte[] data = randomBgr(4, 3, 1);
        PixelImage wrapped = PixelImage.wrapBgr(data, 4, 3);
        assertSame(data, wrapped.bgr(), "wrap is zero-copy by contract");
        PixelImage copied = PixelImage.copyOfBgr(data, 4, 3);
        assertNotSame(data, copied.bgr());
        data[0] ^= 0x55;
        assertEquals(data[0] ^ 0x55, copied.bgr()[0] & 0xFF ^ 0, "copy must not follow later changes to the source");
        assertNotSame(wrapped.bgr(), wrapped.toBgrCopy());
        assertArrayEquals(wrapped.bgr(), wrapped.toBgrCopy());
    }

    /** The AWT implementation the application used before the engine was separated (kept here as the oracle). */
    private static BufferedImage legacyOrientation(BufferedImage img, int orientation) {
        int w = img.getWidth();
        int h = img.getHeight();
        switch (orientation) {
            case 2: return legacy(img, w, h, w, h, (x, y) -> new int[] {w - 1 - x, y});
            case 3: return legacy(img, w, h, w, h, (x, y) -> new int[] {w - 1 - x, h - 1 - y});
            case 4: return legacy(img, w, h, w, h, (x, y) -> new int[] {x, h - 1 - y});
            case 5: return legacy(img, w, h, h, w, (x, y) -> new int[] {y, x});
            case 6: return legacy(img, w, h, h, w, (x, y) -> new int[] {h - 1 - y, x});
            case 7: return legacy(img, w, h, h, w, (x, y) -> new int[] {h - 1 - y, w - 1 - x});
            case 8: return legacy(img, w, h, h, w, (x, y) -> new int[] {y, w - 1 - x});
            default: return img;
        }
    }

    private interface M {
        int[] map(int x, int y);
    }

    private static BufferedImage legacy(BufferedImage src, int w, int h, int dw, int dh, M m) {
        BufferedImage dst = new BufferedImage(dw, dh, BufferedImage.TYPE_3BYTE_BGR);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int[] d = m.map(x, y);
                dst.setRGB(d[0], d[1], src.getRGB(x, y));
            }
        }
        return dst;
    }

    @Test
    void exifOrientationEqualsTheLegacyAwtImplementationForAllEightTags() {
        int w = 7;
        int h = 4; // non-square, so a wrong axis swap changes the dimensions
        byte[] bgr = randomBgr(w, h, 42);
        BufferedImage ref = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = (y * w + x) * 3;
                ref.setRGB(x, y, 0xFF000000 | ((bgr[i + 2] & 0xFF) << 16) | ((bgr[i + 1] & 0xFF) << 8) | (bgr[i] & 0xFF));
            }
        }
        for (int tag = 1; tag <= 8; tag++) {
            PixelImage got = PixelImage.wrapBgr(bgr, w, h).withExifOrientation(tag);
            BufferedImage want = legacyOrientation(ref, tag);
            assertEquals(want.getWidth(), got.width(), "width for tag " + tag);
            assertEquals(want.getHeight(), got.height(), "height for tag " + tag);
            for (int y = 0; y < got.height(); y++) {
                for (int x = 0; x < got.width(); x++) {
                    int i = (y * got.width() + x) * 3;
                    int rgb = want.getRGB(x, y);
                    assertEquals(rgb & 0xFF, got.bgr()[i] & 0xFF, "B tag " + tag + " @" + x + "," + y);
                    assertEquals((rgb >> 8) & 0xFF, got.bgr()[i + 1] & 0xFF, "G tag " + tag);
                    assertEquals((rgb >> 16) & 0xFF, got.bgr()[i + 2] & 0xFF, "R tag " + tag);
                }
            }
        }
    }

    @Test
    void orientationOneZeroAndGarbageReturnTheSameInstance() {
        PixelImage img = PixelImage.wrapBgr(randomBgr(3, 2, 7), 3, 2);
        for (int tag : new int[] {1, 0, 9, -1, 255}) {
            assertSame(img, img.withExifOrientation(tag), "tag " + tag);
        }
    }

    @Test
    void ordinaryOrientationsComposeAsExpected() {
        PixelImage img = PixelImage.wrapBgr(randomBgr(5, 3, 9), 5, 3);
        // 6 (90 cw) then 8 (90 ccw) is the identity; 3 twice is the identity.
        assertArrayEquals(img.bgr(), img.withExifOrientation(6).withExifOrientation(8).bgr());
        assertArrayEquals(img.bgr(), img.withExifOrientation(3).withExifOrientation(3).bgr());
    }

    @Test
    void argbPackedOpaquePixelsAreExactAndChannelOrderIsBgr() {
        int[] argb = {0xFF102030, 0xFFAABBCC};
        PixelImage img = PixelImage.fromArgbPacked(argb, 2, 1);
        assertArrayEquals(new byte[] {0x30, 0x20, 0x10, (byte) 0xCC, (byte) 0xBB, (byte) 0xAA}, img.bgr());
    }

    @Test
    void argbPackedCompositesOverBlackLikeJava2d() {
        // Every alpha value with several colours, against Java2D's SrcOver onto a black 3BYTE_BGR image.
        int n = 256 * 7;
        int[] argb = new int[n];
        int[] colours = {0xFF0000, 0x00FF00, 0x0000FF, 0x808080, 0x010203, 0xFEFDFC, 0x7F3A91};
        int k = 0;
        for (int c : colours) {
            for (int a = 0; a < 256; a++) {
                argb[k++] = (a << 24) | c;
            }
        }
        BufferedImage src = new BufferedImage(n, 1, BufferedImage.TYPE_INT_ARGB);
        src.setRGB(0, 0, n, 1, argb, 0, n);
        BufferedImage dst = new BufferedImage(n, 1, BufferedImage.TYPE_3BYTE_BGR);
        dst.getGraphics().drawImage(src, 0, 0, null);
        PixelImage got = PixelImage.fromArgbPacked(argb, n, 1);
        int maxDiff = 0;
        for (int x = 0; x < n; x++) {
            int rgb = dst.getRGB(x, 0);
            int i = x * 3;
            maxDiff = Math.max(maxDiff, Math.abs((rgb & 0xFF) - (got.bgr()[i] & 0xFF)));
            maxDiff = Math.max(maxDiff, Math.abs(((rgb >> 8) & 0xFF) - (got.bgr()[i + 1] & 0xFF)));
            maxDiff = Math.max(maxDiff, Math.abs(((rgb >> 16) & 0xFF) - (got.bgr()[i + 2] & 0xFF)));
        }
        assertEquals(0, maxDiff, "alpha compositing must match Java2D exactly");
    }
}
