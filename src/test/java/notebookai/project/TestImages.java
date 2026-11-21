package notebookai.project;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/** Generated image fixtures (no private data). */
final class TestImages {
    private TestImages() {}

    static byte[] encode(String format, int w, int h, int seed) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = (x * 255 / Math.max(1, w - 1) + seed * 40) & 0xFF;
                int g = (y * 255 / Math.max(1, h - 1) + seed * 70) & 0xFF;
                int b = ((x + y) * 3 + seed * 90) & 0xFF;
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, format, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] png(int w, int h, int seed) {
        return encode("png", w, h, seed);
    }

    static byte[] jpeg(int w, int h, int seed) {
        return encode("jpg", w, h, seed);
    }
}
