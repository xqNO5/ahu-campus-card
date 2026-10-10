package android.graphics;

/**
 * Stand-in for Android's Bitmap.
 *
 * Two details are deliberately mirrored from the real SDK so the offline harness cannot
 * accept code the Android build would reject:
 *   * `Config` is a nested type, not a top-level class;
 *   * the config parameter is non-nullable.
 */
public class Bitmap {

    /** Mirrors android.graphics.Bitmap.Config. */
    public static final class Config {
        public static final Config ARGB_8888 = new Config();

        private Config() {
        }
    }

    public final int width;
    public final int height;
    public final int[] pixels;

    private Bitmap(int width, int height) {
        this.width = width;
        this.height = height;
        this.pixels = new int[width * height];
    }

    public static Bitmap createBitmap(int width, int height, Config config) {
        if (config == null) {
            throw new NullPointerException("config");
        }
        return new Bitmap(width, height);
    }

    public void setPixels(int[] pixels, int offset, int stride, int x, int y, int width, int height) {
        for (int row = 0; row < height; row++) {
            System.arraycopy(pixels, offset + row * stride, this.pixels, (y + row) * this.width + x, width);
        }
    }

    /** Test helper: read a pixel back. */
    public int getPixel(int x, int y) {
        return pixels[y * width + x];
    }
}
