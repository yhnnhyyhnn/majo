package com.agent.coding.tool;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * EXIF orientation support for the image re-encode path (qwenpaw #8136).
 *
 * <p>{@code ImageIO.read} ignores the EXIF orientation tag, so a phone
 * portrait JPEG (orientation 6/8) that gets scaled and re-encoded — which
 * strips the metadata — would land in the model context lying on its side,
 * with no tag left to correct it. The orientation must therefore be baked
 * into the pixels before any resize/re-encode.
 */
final class ImageExif {

    private ImageExif() {
    }

    /**
     * Read the EXIF orientation (tag 274 in IFD0) from raw JPEG bytes.
     * Returns 1 when absent, unparsable or out of range — orientation 1
     * means "upright", so every failure mode degrades to a no-op.
     */
    static int readOrientation(byte[] jpeg) {
        try {
            int tiff = findExifTiffOffset(jpeg);
            if (tiff < 0) {
                return 1;
            }
            ByteBuffer buf = ByteBuffer.wrap(jpeg);
            buf.order(tiffOrder(jpeg, tiff));

            int ifdOffset = buf.getInt(tiff + 4);
            int entries = u16(buf, tiff + ifdOffset);
            for (int i = 0; i < entries; i++) {
                int entry = tiff + ifdOffset + 2 + i * 12;
                if (u16(buf, entry) == 274) {
                    // count is a full 4-byte field: read it as such, or a
                    // big-endian count of 1 reads as its zero high half.
                    if (u16(buf, entry + 2) == 3 && buf.getInt(entry + 4) == 1) { // SHORT, count 1: value inline
                        int value = u16(buf, entry + 8);
                        return value >= 1 && value <= 8 ? value : 1;
                    }
                    return 1; // unexpected type/size: don't guess
                }
            }
        } catch (Exception e) {
            // Malformed EXIF must never fail the image load.
        }
        return 1;
    }

    /**
     * Bake an EXIF orientation into the pixels. Values mirror the spec's
     * row/column semantics (2 flip-x, 3 rotate-180, 4 flip-y, 5 transpose,
     * 6 rotate-90-CW, 7 transverse, 8 rotate-270-CW).
     */
    static BufferedImage applyOrientation(BufferedImage img, int orientation) {
        int w = img.getWidth();
        int h = img.getHeight();
        // AffineTransform's 6-arg ctor takes (m00, m10, m01, m11, m02, m12):
        // x' = m00*x + m01*y + m02, y' = m10*x + m11*y + m12.
        AffineTransform t = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);   // x' = w-x
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);  // + y' = h-y
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);   // y' = h-y
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);    // x' = y,   y' = x
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);   // x' = h-y, y' = x
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);  // x' = h-y, y' = w-x
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, w);   // x' = y,   y' = w-x
            default -> null;
        };
        if (t == null) {
            return img;
        }
        Rectangle out = t.createTransformedShape(new Rectangle(w, h)).getBounds();
        // Graphics2D (not AffineTransformOp): the op rejects some source
        // color models ImageIO produces (e.g. 3BYTE_BGR JPEGs).
        BufferedImage result = new BufferedImage(out.width, out.height,
                img.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, t, null);
        g.dispose();
        return result;
    }

    /** Offset of the TIFF header inside the first APP1 segment carrying EXIF. */
    private static int findExifTiffOffset(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 4 || u16(jpeg, 0) != 0xFFD8) {
            return -1;
        }
        int pos = 2;
        while (pos + 4 <= jpeg.length) {
            int marker = u16(jpeg, pos);
            if (marker == 0xFFDA) { // start of scan: no EXIF ahead
                return -1;
            }
            int len = u16(jpeg, pos + 2);
            if (len < 2) {
                return -1;
            }
            if (marker == 0xFFE1) { // APP1: check for the Exif magic
                int payload = pos + 4;
                if (payload + 6 <= jpeg.length
                        && jpeg[payload] == 'E' && jpeg[payload + 1] == 'x'
                        && jpeg[payload + 2] == 'i' && jpeg[payload + 3] == 'f'
                        && jpeg[payload + 4] == 0 && jpeg[payload + 5] == 0) {
                    return payload + 6;
                }
            }
            pos += 2 + len;
        }
        return -1;
    }

    private static ByteOrder tiffOrder(byte[] jpeg, int tiff) {
        return jpeg[tiff] == 'I' && jpeg[tiff + 1] == 'I'
                ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
    }

    private static int u16(ByteBuffer buf, int index) {
        return buf.getShort(index) & 0xFFFF;
    }

    private static int u16(byte[] bytes, int index) {
        return ((bytes[index] & 0xFF) << 8) | (bytes[index + 1] & 0xFF);
    }
}
