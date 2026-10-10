package android.graphics;

/**
 * Stand-in for Android's Bitmap, enough to exercise QrRenderer on a plain JVM.
 *
 * The config parameter is typed as Object so the stub does not need to model
 * `Bitmap.Config` (Android treats a null config as ARGB_8888).
 */
public class Bitmap {

    public final int width;
    public final int height;
    public final int[] pixels;

    private Bitmap(int width, int height) {
        this.width = width;
        this.height = height;
        this.pixels = new int[width * height];
    }

    public static Bitmap createBitmap(int width, int height, Object config) {
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
