package com.example.captcha

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * ═════════════════════════════════════════════════════════════════════════════
 *  Android 包装层：图片字节 → 四个字符
 * ═════════════════════════════════════════════════════════════════════════════
 *
 *  来源：Ahu_Plus 的 `com.ahu_plus.data.repository.AdwmhCaptchaRecognizer`，
 *  逐行保留（改了包名，日志从项目的脱敏 SafeLog 换成 android.util.Log）。
 *
 *  这一层只做三件事：解码 PNG/JPEG → 缩放到模型的固定输入尺寸 → 调模型。
 *  它存在的意义是把 Android 的 Bitmap 依赖挡在 CompactCaptchaModel 外面，
 *  让后者保持纯 JVM、可单测。
 *
 *  ── 关键设计：**永不抛异常，失败返回 null** ──
 *
 *  模型文件缺失、损坏、图片解不出来、推理出错 —— 全部吞掉返回 null，
 *  由调用方回退到「让用户手动输入验证码」。
 *  验证码识别的定位是**锦上添花**：识别成功用户无感，识别失败用户照样能登录。
 *  让它有失败路径能打断登录流程，是把一个可选优化变成了新的故障点。
 *
 *  ★ 耦合点 1：Context / 生命周期 ★
 *  构造时要 Context 读 assets。原项目在 `AhuPlusApplication` 里手工 DI 构造一次
 *  （`adwmhCaptchaRecognizer = AdwmhCaptchaRecognizer(this)`），
 *  然后按依赖顺序注入给 `AdwmhCardRepository`。
 *  你那边用 Hilt/Koin 的话，声明成 @Singleton 即可 —— 模型是几万个浮点，
 *  重复构造会白白吃内存和 CPU（每次构造都重新解析 357 KiB）。
 *
 *  ★ 耦合点 2：上层怎么用它 ★
 *  见 README「怎么接进去」一节 —— 核心是那个「识别 → 登录 → 失败重试 N 次 →
 *  仍失败则回退手动输入」的循环。这份文件本身不关心重试策略。
 *
 *  ★ 耦合点 3：assets 里的 captcha_compact.bin ★
 *  模型不进版本库的常规位置的话，要么改 [ASSET_MODEL]，
 *  要么改成从文件系统 / 下载目录加载。原项目直接打进 assets。
 */
class AdwmhCaptchaRecognizer(
    context: Context,
) {
    private val model: CompactCaptchaModel?

    /**
     * 模型是否可用。上层用它决定「要不要走自动识别这条路」——
     * 不可用时直接弹手动输入，少一次没意义的网络请求。
     */
    val isAvailable: Boolean
        get() = model != null

    init {
        model = try {
            // use {} 关流；模型对象解析完就把 InputStream 释放掉
            context.assets.open(ASSET_MODEL).use(CompactCaptchaModel::load)
                .also { Log.i(TAG, "本地验证码模型加载成功") }
        } catch (e: Throwable) {
            // 捕获 Throwable 而不是 Exception：assets 缺失会抛 IOException，
            // 但模型损坏时 OOM 也可能出现，任何一种都不该让 App 起不来。
            Log.w(TAG, "本地验证码模型不可用，将回退手动输入: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * 识别一张验证码图片。
     *
     * @param imageBytes 服务端返回的原始图片字节（jpg/png 都行，交给 BitmapFactory 判断）
     * @return 四个字符；任何一步失败都返回 null
     */
    suspend fun recognize(imageBytes: ByteArray): String? = withContext(Dispatchers.Default) {
        val localModel = model ?: return@withContext null
        val decoded = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: return@withContext null
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
        } catch (e: Throwable) {
            Log.w(TAG, "本地验证码识别失败: ${e.javaClass.simpleName}")
            null
        } finally {
            // 位图是 native 内存，不依赖 GC —— 必须显式回收，
            // 否则连续重试几次就能把 Android 的位图内存吃满
            scaled.recycle()
        }
    }

    companion object {
        private const val TAG = "AdwmhCaptchaRecognizer"
        private const val ASSET_MODEL = "captcha_compact.bin"
    }
}
