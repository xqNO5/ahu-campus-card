package android.graphics;

/**
 * Stand-in for Android's Bitmap.
 *
 * Details are deliberately mirrored from the real SDK so the offline harness cannot accept
 * code the Android build would reject:
 *   * `Config` is a nested type, not a top-level class;
 *   * the config parameter is non-nullable;
 *   * `createScaledBitmap` takes a non-null source and filter flag, and the width/height
 *     getters are fields on Android and properties in Kotlin (`bitmap.width`).
 *
 * The pixel methods behave like their ARGB_8888 counterparts, which is what the captcha
 * recognizer feeds into the model.
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

    /**
     * Bilinear rescale, like the real implementation with `filter = true`. The server sends
     * 100x40 already, so the recognizer normally skips this; it exists so the fallback path is
     * compiled and runnable rather than untested.
     */
    public static Bitmap createScaledBitmap(Bitmap src, int dstWidth, int dstHeight, boolean filter) {
        if (src == null) {
            throw new NullPointerException("src");
        }
        if (dstWidth == src.width && dstHeight == src.height) {
            return src;
        }
        Bitmap dst = new Bitmap(dstWidth, dstHeight);
        double scaleX = (double) src.width / dstWidth;
        double scaleY = (double) src.height / dstHeight;
        for (int y = 0; y < dstHeight; y++) {
            for (int x = 0; x < dstWidth; x++) {
                if (!filter) {
                    int sx = Math.min(src.width - 1, (int) (x * scaleX));
                    int sy = Math.min(src.height - 1, (int) (y * scaleY));
                    dst.pixels[y * dstWidth + x] = src.pixels[sy * src.width + sx];
                    continue;
                }
                double fx = (x + 0.5) * scaleX - 0.5;
                double fy = (y + 0.5) * scaleY - 0.5;
                int x0 = (int) Math.floor(fx);
                int y0 = (int) Math.floor(fy);
                double dx = fx - x0;
                double dy = fy - y0;
                int x1 = clamp(x0 + 1, src.width - 1);
                int y1 = clamp(y0 + 1, src.height - 1);
                x0 = clamp(x0, src.width - 1);
                y0 = clamp(y0, src.height - 1);
                dst.pixels[y * dstWidth + x] = blend(
                    blend(src.pixels[y0 * src.width + x0], src.pixels[y0 * src.width + x1], dx),
                    blend(src.pixels[y1 * src.width + x0], src.pixels[y1 * src.width + x1], dx),
                    dy
                );
            }
        }
        return dst;
    }

    private static int clamp(int value, int max) {
        return value < 0 ? 0 : Math.min(value, max);
    }

    /** Per-channel linear blend, weighted by [t] toward [b]. */
    private static int blend(int a, int b, double t) {
        int alpha = (int) Math.round(((a >>> 24) & 0xff) * (1 - t) + ((b >>> 24) & 0xff) * t);
        int red = (int) Math.round(((a >> 16) & 0xff) * (1 - t) + ((b >> 16) & 0xff) * t);
        int green = (int) Math.round(((a >> 8) & 0xff) * (1 - t) + ((b >> 8) & 0xff) * t);
        int blue = (int) Math.round((a & 0xff) * (1 - t) + (b & 0xff) * t);
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    public void setPixels(int[] pixels, int offset, int stride, int x, int y, int width, int height) {
        for (int row = 0; row < height; row++) {
            System.arraycopy(pixels, offset + row * stride, this.pixels, (y + row) * this.width + x, width);
        }
    }

    /** Mirrors the SDK's ARGB_8888 in-place read. */
    public void getPixels(int[] pixels, int offset, int stride, int x, int y, int width, int height) {
        for (int row = 0; row < height; row++) {
            System.arraycopy(this.pixels, (y + row) * this.width + x, pixels, offset + row * stride, width);
        }
    }

    public void recycle() {
        // No native memory in the stub; the contract is that the bitmap becomes unusable.
    }

    /** Test helper: read a pixel back. */
    public int getPixel(int x, int y) {
        return pixels[y * width + x];
    }
}
