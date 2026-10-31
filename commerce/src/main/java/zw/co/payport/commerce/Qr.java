package zw.co.payport.commerce;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.Map;

/** A QR code as a small SVG (one path, crisp at any size, prints well on a counter sign). */
public final class Qr {

    private Qr() {}

    public static String svg(String text) {
        try {
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 2));
            int w = m.getWidth(), h = m.getHeight();
            StringBuilder path = new StringBuilder();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (m.get(x, y)) path.append('M').append(x).append(' ').append(y).append("h1v1h-1z");
                }
            }
            return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + w + " " + h + "\" shape-rendering=\"crispEdges\">"
                    + "<rect width=\"100%\" height=\"100%\" fill=\"#fff\"/><path fill=\"#000\" d=\"" + path + "\"/></svg>";
        } catch (Exception e) {
            throw new IllegalStateException("QR code could not be drawn", e);
        }
    }
}
