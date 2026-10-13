package com.agent.coding.tool;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the EXIF orientation parse/bake used by view_image's resize path
 * (qwenpaw #8136): a portrait JPEG must not reach the model sideways.
 */
class ImageExifTest {

    @Test
    void readOrientationParsesLittleEndianExif() {
        assertEquals(6, ImageExif.readOrientation(jpegWithOrientation(ByteOrder.LITTLE_ENDIAN, 6)));
    }

    @Test
    void readOrientationParsesBigEndianExif() {
        assertEquals(8, ImageExif.readOrientation(jpegWithOrientation(ByteOrder.BIG_ENDIAN, 8)));
    }

    @Test
    void readOrientationDefaultsToUpright() {
        assertEquals(1, ImageExif.readOrientation(jpegWithOrientation(ByteOrder.LITTLE_ENDIAN, 1)));
        assertEquals(1, ImageExif.readOrientation(jpegWithOrientation(ByteOrder.LITTLE_ENDIAN, 9)));
        assertEquals(1, ImageExif.readOrientation("not a jpeg".getBytes()));
        assertEquals(1, ImageExif.readOrientation(truncated()));
        assertEquals(1, ImageExif.readOrientation(new byte[0]));
    }

    @Test
    void orientationSixRotatesClockwiseAndSwapsDims() {
        BufferedImage img = solid(40, 20, Color.BLUE);
        img.setRGB(0, 0, Color.RED.getRGB());

        BufferedImage out = ImageExif.applyOrientation(img, 6);

        assertEquals(20, out.getWidth());
        assertEquals(40, out.getHeight());
        assertRedish(out.getRGB(19, 0));
        assertTrue(!isRed(out.getRGB(10, 10)));
    }

    @Test
    void orientationTwoMirrorsHorizontally() {
        BufferedImage img = solid(4, 3, Color.GREEN);
        img.setRGB(0, 0, Color.RED.getRGB());

        BufferedImage out = ImageExif.applyOrientation(img, 2);

        assertEquals(4, out.getWidth());
        assertEquals(3, out.getHeight());
        assertRedish(out.getRGB(3, 0));
        assertTrue(!isRed(out.getRGB(0, 0)));
    }

    @Test
    void uprightOrientationIsNoOp() {
        BufferedImage img = solid(10, 10, Color.GREEN);
        assertSame(img, ImageExif.applyOrientation(img, 1));
    }

    @Test
    void roundTripThroughViewImageResizePath() throws Exception {
        // A "landscape-stored" JPEG with orientation 6 and oversized edges:
        // after view_image's resize the pixels must be upright, i.e. taller
        // than wide.
        BufferedImage img = solid(1600, 900, Color.BLUE);
        ByteArrayOutputStream jpg = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", jpg);
        byte[] jpeg = withExif(jpg.toByteArray(), 6);

        assertEquals(6, ImageExif.readOrientation(jpeg));
        BufferedImage upright = ImageExif.applyOrientation(
                ImageIO.read(new java.io.ByteArrayInputStream(jpeg)),
                ImageExif.readOrientation(jpeg));
        assertEquals(900, upright.getWidth());
        assertEquals(1600, upright.getHeight());
    }

    // --- fixtures -----------------------------------------------------------

    /** Minimal JPEG byte stream whose APP1 EXIF carries the orientation. */
    private static byte[] jpegWithOrientation(ByteOrder order, int orientation) {
        byte[] tiff = new byte[8 + 2 + 12 + 4];
        ByteBuffer b = ByteBuffer.wrap(tiff).order(order);
        b.put(0, (byte) (order == ByteOrder.LITTLE_ENDIAN ? 'I' : 'M'));
        b.put(1, (byte) (order == ByteOrder.LITTLE_ENDIAN ? 'I' : 'M'));
        b.putShort(2, (short) 42);
        b.putInt(4, 8);
        b.putShort(8, (short) 1);                    // IFD0 entries
        b.putShort(10, (short) 274);                 // Orientation tag
        b.putShort(12, (short) 3);                   // SHORT
        b.putInt(14, 1);                             // count
        b.putShort(18, (short) orientation);         // inline value
        b.putInt(20, 0);                             // next-IFD offset

        return withExifHeader(tiff);
    }

    /**
     * Splice an EXIF APP1 segment right after the JPEG SOI marker — EXIF
     * always precedes the scan data, and the parser stops at SOS.
     */
    private static byte[] withExif(byte[] jpeg, int orientation) {
        byte[] tiff = new byte[8 + 2 + 12 + 4];
        ByteBuffer b = ByteBuffer.wrap(tiff).order(ByteOrder.LITTLE_ENDIAN);
        b.put(0, (byte) 'I');
        b.put(1, (byte) 'I');
        b.putShort(2, (short) 42);
        b.putInt(4, 8);
        b.putShort(8, (short) 1);
        b.putShort(10, (short) 274);
        b.putShort(12, (short) 3);
        b.putInt(14, 1);
        b.putShort(18, (short) orientation);
        b.putInt(20, 0);

        byte[] withHeader = withExifHeader(tiff, java.util.Arrays.copyOf(jpeg, 2));
        byte[] spliced = new byte[withHeader.length + jpeg.length - 2];
        System.arraycopy(withHeader, 0, spliced, 0, withHeader.length);
        System.arraycopy(jpeg, 2, spliced, withHeader.length, jpeg.length - 2);
        return spliced;
    }

    private static byte[] withExifHeader(byte[] tiff) {
        return withExifHeader(tiff, new byte[]{(byte) 0xFF, (byte) 0xD8});
    }

    private static byte[] withExifHeader(byte[] tiff, byte[] prefix) {
        int payloadLen = 6 + tiff.length;
        byte[] jpeg = new byte[prefix.length + 4 + payloadLen];
        System.arraycopy(prefix, 0, jpeg, 0, prefix.length);
        int at = prefix.length;
        jpeg[at] = (byte) 0xFF;
        jpeg[at + 1] = (byte) 0xE1;
        jpeg[at + 2] = (byte) ((payloadLen + 2) >> 8);
        jpeg[at + 3] = (byte) (payloadLen + 2);
        jpeg[at + 4] = 'E';
        jpeg[at + 5] = 'x';
        jpeg[at + 6] = 'i';
        jpeg[at + 7] = 'f';
        jpeg[at + 8] = 0;
        jpeg[at + 9] = 0;
        System.arraycopy(tiff, 0, jpeg, at + 10, tiff.length);
        return jpeg;
    }

    private static byte[] truncated() {
        byte[] jpeg = jpegWithOrientation(ByteOrder.LITTLE_ENDIAN, 6);
        byte[] cut = new byte[jpeg.length / 2];
        System.arraycopy(jpeg, 0, cut, 0, cut.length);
        return cut;
    }

    private static BufferedImage solid(int w, int h, Color color) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, color.getRGB());
            }
        }
        return img;
    }

    private static void assertRedish(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int b = rgb & 0xFF;
        assertTrue(r > 200 && b < 100, "expected red-ish, got " + Integer.toHexString(rgb));
    }

    private static boolean isRed(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int b = rgb & 0xFF;
        return r > 200 && b < 100;
    }
}
