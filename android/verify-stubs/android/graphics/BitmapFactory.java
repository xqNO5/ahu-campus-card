package android.graphics;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Stand-in for Android's BitmapFactory.
 *
 * It really decodes (via ImageIO) instead of returning a canned bitmap, because the point of
 * the offline harness is to push a real captcha JPEG through the same decode -> scale ->
 * getPixels path the device takes. `getRGB` yields ARGB_8888 ints, exactly what
 * `Bitmap.getPixels` produces on Android.
 *
 * Mirrors the SDK contract that matters here: decode failures return null, they do not throw.
 */
public final class BitmapFactory {

    private BitmapFactory() {
    }

    public static Bitmap decodeByteArray(byte[] data, int offset, int length) {
        if (data == null || length <= 0) {
            return null;
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(data, offset, length));
            if (image == null) {
                return null;
            }
            int width = image.getWidth();
            int height = image.getHeight();
            int[] pixels = new int[width * height];
            image.getRGB(0, 0, width, height, pixels, 0, width);
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            return bitmap;
        } catch (IOException e) {
            return null;
        }
    }
}
