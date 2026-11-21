package notebookai.ocr;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import notebookai.model.OcrResult;

/**
 * Debug artifact writer: source overlay with region boxes plus one image per
 * kept region. Only used when explicitly requested (CLI {@code --debug-dir});
 * never writes into the source tree.
 */
public final class DebugImages {

    private DebugImages() {}

    public static void write(
            Path debugDir, Path imagePath, ImageOps.DecodedImage decoded, OcrResult result)
            throws OcrException {
        try {
            Files.createDirectories(debugDir);
            BufferedImage base = toBuffered(decoded);
            Graphics2D g = base.createGraphics();
            try {
                g.setColor(Color.RED);
                for (OcrResult.TextRegion region : result.regions()) {
                    double[][] poly = region.polygon();
                    Polygon p = new Polygon();
                    for (double[] pt : poly) {
                        p.addPoint((int) Math.round(pt[0]), (int) Math.round(pt[1]));
                    }
                    g.drawPolygon(p);
                }
            } finally {
                g.dispose();
            }
            String stem = stem(imagePath);
            ImageIO.write(base, "png", debugDir.resolve(stem + "_overlay.png").toFile());
            int i = 0;
            for (OcrResult.TextRegion region : result.regions()) {
                CropOps.Crop crop =
                        CropOps.rotateCrop(
                                decoded.bgr(), decoded.width(), decoded.height(), region.polygon());
                ImageIO.write(
                        toBuffered(crop.bgr(), crop.width(), crop.height()),
                        "png",
                        debugDir.resolve(stem + "_crop_" + i + ".png").toFile());
                i++;
            }
        } catch (IOException e) {
            throw new OcrException("Could not write debug images: " + e.getMessage(), e);
        }
    }

    private static BufferedImage toBuffered(ImageOps.DecodedImage decoded) {
        return toBuffered(decoded.bgr(), decoded.width(), decoded.height());
    }

    private static BufferedImage toBuffered(byte[] bgr, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        img.getRaster().setDataElements(0, 0, w, h, bgr);
        return img;
    }

    private static String stem(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
