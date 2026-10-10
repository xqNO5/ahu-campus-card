package cn.edu.ahu.cardqr.captcha

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/*
 * ═════════════════════════════════════════════════════════════════════════════
 *  推理运行时单测
 * ═════════════════════════════════════════════════════════════════════════════
 *
 *  来源：Ahu_Plus 的 `com.ahu_plus.data.repository.CompactCaptchaModelTest`
 *  （经 ahu-captcha-extract 抽取版并入，只改了包名）。
 *
 *  三条测试各自解决一个不同的失效模式：
 *
 *  1. `deployed model matches Python export parity fixture`
 *     ← **最重要的那条**。它把 train.py 导出的真实模型喂给一个**确定性生成的
 *     伪随机图**（不是真验证码，是固定公式算出来的像素），断言输出恰好是 "BQWW"。
 *
 *     为什么要这么写：手写推理最容易错在**权重布局**上（HWC vs CHW、
 *     transpose 方向、量化轴），而这类错误不会抛异常，只会安静地算出乱码。
 *     用一个固定输入把「Python 导出」和「Kotlin 推理」钉死在同一份输出上，
 *     任何一侧改了布局，这条断言立刻红。
 *
 *  2. `loads fixed binary format and runs deterministic inference`
 *     ← 用全零权重构造一个合法模型，验证解析器能走通全流程。
 *
 *  3. `rejects a model with invalid magic`
 *     ← 验证损坏的模型会被拒绝，而不是带病推理。
 *
 *  ⚠️ 测试从相对路径 `src/main/assets/captcha_compact.bin` 读模型，
 *     所以要在**模块根目录**下运行（Gradle 的 test 任务默认就是）。
 *     它同时试了带不带 `app/` 前缀两种路径，为了兼容从仓库根目录跑的情况。
 */
class CompactCaptchaModelTest {
    @Test
    fun `deployed model matches Python export parity fixture`() {
        val asset = modelAsset()
        val model = asset.inputStream().use(CompactCaptchaModel::load)
        // 确定性的伪随机图：同样的公式在任何机器上都得到同样的像素，
        // 所以 "BQWW" 这个期望值是跨平台稳定的
        val pixels = IntArray(CompactCaptchaModel.IMAGE_WIDTH * CompactCaptchaModel.IMAGE_HEIGHT) { index ->
            val x = index % CompactCaptchaModel.IMAGE_WIDTH
            val y = index / CompactCaptchaModel.IMAGE_WIDTH
            val red = (x * 17 + y * 3) and 0xff
            val green = (x * 5 + y * 11) and 0xff
            val blue = (x * 13 + y * 7) and 0xff
            (0xff shl 24) or (red shl 16) or (green shl 8) or blue
        }

        assertEquals("BQWW", model.recognize(pixels))
    }

    @Test
    fun `loads fixed binary format and runs deterministic inference`() {
        val bytes = zeroModelBytes()

        val model = CompactCaptchaModel.fromBytes(bytes)
        val result = model.recognize(
            IntArray(CompactCaptchaModel.IMAGE_WIDTH * CompactCaptchaModel.IMAGE_HEIGHT) { -1 },
        )

        // 全零权重 → 所有 logits 相同 → argmax 恒为 0 → 字符集首字符
        assertEquals("AAAA", result)
    }

    @Test
    fun `rejects a model with invalid magic`() {
        val bytes = zeroModelBytes().also { it[0] = 'X'.code.toByte() }

        try {
            CompactCaptchaModel.fromBytes(bytes)
            fail("invalid magic should fail")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    /**
     * The shipped asset, and the same file must be the one the APK carries: this is the only
     * thing tying the `.bin` to the runtime.
     */
    private fun modelAsset(): File = listOf(
        File("src/main/assets/captcha_compact.bin"),
        File("app/src/main/assets/captcha_compact.bin"),
    ).firstOrNull(File::isFile) ?: error("captcha_compact.bin not found")

    /** 手工拼一个字节布局合法的空模型（权重全 0，scale 全 1，bias 全 0）。 */
    private fun zeroModelBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(CompactCaptchaModel.expectedByteSize(1))
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("AHUCAP1\u0000".toByteArray(Charsets.US_ASCII))
        buffer.putInt(1)   // version
        buffer.putInt(1)   // modelCount
        buffer.putInt(CompactCaptchaModel.IMAGE_HEIGHT)
        buffer.putInt(CompactCaptchaModel.IMAGE_WIDTH)
        buffer.putInt(CompactCaptchaModel.CROP_WIDTH)
        buffer.putInt(CompactCaptchaModel.CHAR_COUNT)
        intArrayOf(12, 37, 62, 87).forEach(buffer::putInt)
        buffer.putInt(CompactCaptchaModel.CLASS_COUNT)
        buffer.put("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toByteArray(Charsets.US_ASCII))

        putLayer(
            buffer,
            CompactCaptchaModel.CONV1_CHANNELS * CompactCaptchaModel.CONV1_KERNEL *
                CompactCaptchaModel.CONV1_KERNEL * CompactCaptchaModel.INPUT_CHANNELS,
            CompactCaptchaModel.CONV1_CHANNELS,
        )
        putLayer(
            buffer,
            CompactCaptchaModel.CONV2_CHANNELS * CompactCaptchaModel.CONV2_KERNEL *
                CompactCaptchaModel.CONV2_KERNEL * CompactCaptchaModel.CONV1_CHANNELS,
            CompactCaptchaModel.CONV2_CHANNELS,
        )
        putLayer(
            buffer,
            CompactCaptchaModel.CLASS_COUNT * CompactCaptchaModel.DENSE_INPUTS,
            CompactCaptchaModel.CLASS_COUNT,
        )
        return buffer.array()
    }

    /** scale 用 1f 而不是 0f —— 加载时会校验 scale >= 0 且有限，0 也是合法的，但 1 更直观。 */
    private fun putLayer(buffer: ByteBuffer, weights: Int, outputs: Int) {
        buffer.put(ByteArray(weights))
        repeat(outputs) { buffer.putFloat(1f) }
        repeat(outputs) { buffer.putFloat(0f) }
    }
}
