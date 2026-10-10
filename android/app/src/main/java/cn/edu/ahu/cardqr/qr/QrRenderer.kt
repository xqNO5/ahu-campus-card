package cn.edu.ahu.cardqr.qr

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Draws a [QrEncoder.QrCode] into an Android bitmap.
 *
 * Kept apart from the encoder so the encoding logic stays pure JVM code that unit tests can
 * exercise without a device or emulator.
 */
object QrRenderer {

    /**
     * Render [qr] into a square bitmap of approximately [sizePx], with [quietZone] modules
     * of white margin. The scale is an integer so module edges stay crisp.
     */
    @JvmStatic
    @JvmOverloads
    fun render(qr: QrEncoder.QrCode, sizePx: Int, quietZone: Int = 4): Bitmap {
        val total = qr.size + quietZone * 2
        val scale = maxOf(1, sizePx / total)
        val dim = total * scale

        val pixels = IntArray(dim * dim)
        for (y in 0 until dim) {
            val my = y / scale - quietZone
            val rowBase = y * dim
            for (x in 0 until dim) {
                val mx = x / scale - quietZone
                val dark = mx in 0 until qr.size && my in 0 until qr.size && qr.isDark(mx, my)
                pixels[rowBase + x] = if (dark) Color.BLACK else Color.WHITE
            }
        }

        // The config parameter is @NonNull in the platform API, so pass ARGB_8888 explicitly
        // rather than null.
        val bitmap = Bitmap.createBitmap(dim, dim, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, dim, 0, 0, dim, dim)
        return bitmap
    }

    /** Convenience: encode then render in one call. */
    @JvmStatic
    @JvmOverloads
    fun render(text: String, sizePx: Int, ecc: QrEncoder.Ecc = QrEncoder.Ecc.LOW, quietZone: Int = 4): Bitmap =
        render(QrEncoder.encode(text, ecc), sizePx, quietZone)
}
