package zw.co.payport.commerce;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;

/**
 * Merchant pictures are untrusted uploads. Only JPEG and PNG are accepted (checked by their first bytes, not the file
 * name), at most 3 MB and 4000 x 4000 pixels (read from the header before decoding, so a crafted file cannot exhaust
 * memory), and every picture is decoded and written again: metadata such as GPS location is removed and anything
 * hidden in the file is dropped. Pages serve the re-encoded copy only.
 */
public final class Images {

    public record Clean(byte[] bytes, String contentType, int width, int height, String sha256) {}

    static final int MAX_BYTES = 3 * 1024 * 1024, MAX_SIDE = 4000;

    private Images() {}

    static String kind(byte[] b) {
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "jpeg";
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "png";
        return null;
    }

    public static Clean clean(byte[] raw) {
        if (raw == null || raw.length == 0) throw new IllegalArgumentException("Choose a picture");
        if (raw.length > MAX_BYTES) throw new IllegalArgumentException("The picture is too large (at most 3 MB)");
        String kind = kind(raw);
        if (kind == null) throw new IllegalArgumentException("Use a JPEG or PNG picture");
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw new IllegalArgumentException("Use a JPEG or PNG picture");
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w < 1 || h < 1 || w > MAX_SIDE || h > MAX_SIDE) throw new IllegalArgumentException("The picture is too large (at most 4000 x 4000)");
                BufferedImage img = reader.read(0);
                BufferedImage out = new BufferedImage(w, h, "png".equals(kind) ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
                var g = out.createGraphics();
                if (!"png".equals(kind)) {
                    g.setColor(java.awt.Color.WHITE);
                    g.fillRect(0, 0, w, h);
                }
                g.drawImage(img, 0, 0, null);
                g.dispose();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                if (!ImageIO.write(out, kind, bytes)) throw new IllegalArgumentException("Use a JPEG or PNG picture");
                byte[] b = bytes.toByteArray();
                return new Clean(b, "image/" + kind, w, h, Shop.sha256(java.util.Base64.getEncoder().encodeToString(b)));
            } finally {
                reader.dispose();
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("That picture could not be read");
        }
    }
}
