package cn.edu.ahu.cardqr.captcha

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log

/*
 * ═════════════════════════════════════════════════════════════════════════════
 *  Android 包装层：验证码图片字节 → 四个字符
 * ═════════════════════════════════════════════════════════════════════════════
 *
 *  来源：Ahu_Plus 的 `com.ahu_plus.data.repository.AdwmhCaptchaRecognizer`
 *  （经 ahu-captcha-extract 抽取版并入）。
 *
 *  与抽取版的两处差异，都是为了让本工程保持「零第三方运行时依赖」：
 *
 *   1. **去掉了 kotlinx.coroutines**。抽取版是 `suspend fun` + `withContext(Dispatchers.Default)`；
 *      本工程的登录流程本来就跑在自己的单线程 IO executor 上，所以识别直接做成同步调用，
 *      APK 不需要为此多带一个协程运行时。
 *   2. 与应用的调用方式对齐：返回 null 即「识别不了」，由 [cn.edu.ahu.cardqr.data.AutoLogin]
 *      统一下降级到手动输入。
 *
 *  ── 关键设计：**永不抛异常，失败返回 null** ──
 *
 *  模型文件缺失、损坏、图片解不出来、推理出错 —— 全部吞掉返回 null，
 *  由调用方回退到「让用户手动输入验证码」。
 *  验证码识别的定位是**锦上添花**：识别成功用户无感，识别失败用户照样能登录。
 *
 *  模型不是每次识别都重新解析：357 KiB 解析一次就够，所以本类应当长期持有
 *  （见 MainActivity 里那个懒加载字段），不要每个请求 new 一个。
 */
class CaptchaRecognizer internal constructor(
    private val model: CompactCaptchaModel?,
) {
    /** 生产路径：从 APK 的 assets 里加载 [ASSET_MODEL]。 */
    constructor(context: Context) : this(loadFromAssets(context))

    /**
     * 模型是否可用。上层用它决定「要不要走自动识别这条路」——
     * 不可用时直接弹手动输入，少一次没意义的网络请求。
     */
    val isAvailable: Boolean
        get() = model != null

    /**
     * 识别一张验证码图片。
     *
     * @param imageBytes 服务端返回的原始图片字节（jpg/png 都行，交给 BitmapFactory 判断）
     * @return 四个字符；任何一步失败都返回 null
     */
    fun recognize(imageBytes: ByteArray): String? {
        val localModel = model ?: return null
        return try {
            val decoded = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return null
            // 尺寸已经对得上就别缩放 —— createScaledBitmap 一次要拷贝整张位图
            val scaled = if (decoded.width == CompactCaptchaModel.IMAGE_WIDTH &&
                decoded.height == CompactCaptchaModel.IMAGE_HEIGHT
            ) {
                decoded
            } else {
                Bitmap.createScaledBitmap(
                    decoded,
                    CompactCaptchaModel.IMAGE_WIDTH,
                    CompactCaptchaModel.IMAGE_HEIGHT,
                    true,
                ).also { decoded.recycle() }
            }
            try {
                val pixels = IntArray(scaled.width * scaled.height)
                scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
                localModel.recognize(pixels)
            } finally {
                // 位图是 native 内存，不依赖 GC —— 必须显式回收
                scaled.recycle()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "本地验证码识别失败: ${e.javaClass.simpleName}")
            null
        }
    }

    private companion object {
        private const val TAG = "CaptchaRecognizer"
        private const val ASSET_MODEL = "captcha_compact.bin"

        /**
         * 加载失败只记一条脱敏日志（异常类名，不含任何识别内容），返回 null。
         * 捕获 Throwable 而不是 Exception：assets 缺失会抛 IOException，
         * 但模型损坏时 OOM 也可能出现，任何一种都不该让 App 起不来。
         */
        fun loadFromAssets(context: Context): CompactCaptchaModel? = try {
            context.assets.open(ASSET_MODEL).use(CompactCaptchaModel::load)
                .also { Log.i(TAG, "本地验证码模型加载成功") }
        } catch (e: Throwable) {
            Log.w(TAG, "本地验证码模型不可用，将回退手动输入: ${e.javaClass.simpleName}")
            null
        }
    }
}
