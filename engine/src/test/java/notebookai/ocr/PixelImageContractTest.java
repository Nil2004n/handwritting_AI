package notebookai.ocr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** Pure-Java checks of the portable image contract (the AWT parity checks live in the desktop module's tests). */
class PixelImageContractTest {

    private static byte[] random(int w, int h, long seed) {
        byte[] b = new byte[w * h * 3];
        new Random(seed).nextBytes(b);
        return b;
    }

    @Test
    void validationRejectsBadDimensionsLengthsAndOverflow() {
        assertThrows(IllegalArgumentException.class, () -> PixelImage.wrapBgr(new byte[3], 0, 1));
        assertThrows(IllegalArgumentException.class, () -> PixelImage.wrapBgr(new byte[5], 1, 1));
        assertThrows(IllegalArgumentException.class, () -> PixelImage.wrapBgr(null, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> PixelImage.byteLength(46341, 46341));
        assertEquals(60, PixelImage.byteLength(4, 5));
    }

    @Test
    void wrapIsZeroCopyAndCopyIsIndependent() {
        byte[] data = random(4, 3, 1);
        assertSame(data, PixelImage.wrapBgr(data, 4, 3).bgr());
        PixelImage copy = PixelImage.copyOfBgr(data, 4, 3);
        assertNotSame(data, copy.bgr());
        byte before = copy.bgr()[0];
        data[0] ^= 0x55;
        assertEquals(before, copy.bgr()[0]);
    }

    @Test
    void orientationHasTheDocumentedGeometry() {
        // 2x1 image [A B]: tag 6 (90 cw) -> 1x2 column [A over B]; tag 8 (90 ccw) -> [B over A]; tag 2 -> [B A].
        byte[] ab = {1, 1, 1, 2, 2, 2};
        PixelImage img = PixelImage.wrapBgr(ab, 2, 1);
        PixelImage cw = img.withExifOrientation(6);
        assertEquals(1, cw.width());
        assertEquals(2, cw.height());
        assertArrayEquals(new byte[] {1, 1, 1, 2, 2, 2}, cw.bgr());
        assertArrayEquals(new byte[] {2, 2, 2, 1, 1, 1}, img.withExifOrientation(8).bgr());
        assertArrayEquals(new byte[] {2, 2, 2, 1, 1, 1}, img.withExifOrientation(2).bgr());
        assertSame(img, img.withExifOrientation(1));
        assertSame(img, img.withExifOrientation(42));
    }

    @Test
    void inverseOrientationsCancel() {
        PixelImage img = PixelImage.wrapBgr(random(5, 3, 9), 5, 3);
        assertArrayEquals(img.bgr(), img.withExifOrientation(6).withExifOrientation(8).bgr());
        assertArrayEquals(img.bgr(), img.withExifOrientation(3).withExifOrientation(3).bgr());
        assertArrayEquals(img.bgr(), img.withExifOrientation(5).withExifOrientation(5).bgr());
        assertArrayEquals(img.bgr(), img.withExifOrientation(7).withExifOrientation(7).bgr());
    }

    @Test
    void argbPackedIsBgrAndCompositesOverBlack() {
        PixelImage img = PixelImage.fromArgbPacked(new int[] {0xFF102030, 0x80FFFFFF, 0x00123456}, 3, 1);
        assertArrayEquals(new byte[] {0x30, 0x20, 0x10, (byte) 128, (byte) 128, (byte) 128, 0, 0, 0}, img.bgr());
    }
}
