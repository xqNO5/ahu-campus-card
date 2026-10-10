package com.example.captcha

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * ═════════════════════════════════════════════════════════════════════════════
 *  设备端验证码推理运行时 —— 纯 Kotlin，零第三方依赖
 * ═════════════════════════════════════════════════════════════════════════════
 *
 *  来源：Ahu_Plus 的 `com.ahu_plus.data.repository.CompactCaptchaModel`，
 *  逐行保留（只改了包名和加了注释）。
 *
 *  ── 这个文件为什么值得看 ──
 *
 *  1. **没有引入任何推理框架**。没有 TFLite、ONNX Runtime、Paddle Lite ——
 *     卷积、maxpool、全连接都是手写的 for 循环。代价是这几百行代码，
 *     收益是 APK 不涨 6 MB（TFLite Android AAR 本身就 6.23 MB）。
 *     小模型 + 固定输入尺寸 + 只做推理，手写的性价比是成立的。
 *
 *  2. **权重 int8、激活 float**。模型按输出通道量化：每层的权重存成 int8，
 *     另外存 每通道一个 float scale + 一个 float bias。
 *     推理时 `sum(int8 权重 × float 输入) * scale + bias`，
 *     累加过程仍是 float，所以不用写定点数乘法器，精度也够。
 *     体积直接降到 1/4。
 *
 *  3. **BatchNorm 在导出时折叠进卷积**了（见 train.py 的 fold_batch_norm）。
 *     所以运行时这里看不到 BN 层 —— 这是训练侧/推理侧的分工，
 *     运行时越简单越好。
 *
 *  4. **多模型集成 = logits 相加取 argmax**。三个不同随机种子训出来的网络
 *     共享同一份输入裁剪，各自算 logits，逐元素相加再取最大。
 *     比投票简单，也不用存概率。
 *
 *  ── 模型文件格式（与 tools/captcha/train.py 的 export_model 是一份契约）──
 *
 *    magic "AHUCAP1\0" | version | modelCount | H | W | cropW | charCount
 *    | centers[4] | charsetLen | charset(ASCII)
 *    | modelCount × { conv1, conv2, dense }，每层 = int8 权重 + f32 scales + f32 biases
 *
 *    全部小端序。加载时逐字段 require 校验，任何一项不符就抛异常 ——
 *    宁可加载失败回退手动输入，也不要拿一个尺寸错位的模型算出垃圾结果。
 *
 *  ★ 耦合点 ★ 这份格式是**私有契约**：改 train.py 的 export_model，
 *  就必须同步改这里的 fromBytes 和下面的各 *_WEIGHTS 常量，反之亦然。
 *  没有版本迁移机制，VERSION 字段只是用来拒绝不认识的文件的。
 *
 *  ★ 耦合点 ★ 常量 IMAGE_WIDTH/HEIGHT/CROP_WIDTH/centers/charset 全部写死。
 *  这些值来自「验证码固定四位、图片固定 100x40」这个协议事实。
 *  服务端样式一变，整条链路（采集 → 训练 → 导出 → 这里）都要重来，
 *  不能只改这里的常量。
 */

/**
 * Fixed-shape int8-weight CNN ensemble for four-character adwmh captchas.
 * Activations remain float; per-output-channel scales dequantize weights while computing.
 */
internal class CompactCaptchaModel private constructor(
    private val charset: CharArray,
    private val centers: IntArray,
    private val networks: List<Network>,
) {
    /**
     * 识别一张验证码，返回四个字符。
     *
     * @param rgbPixels 长度必须是 W×H 的 ARGB_8888 像素数组（Android 的
     *        `Bitmap.getPixels` 输出的格式）。这里只用低 24 位，
     *        alpha 通道被忽略。
     */
    fun recognize(rgbPixels: IntArray): String {
        require(rgbPixels.size == IMAGE_WIDTH * IMAGE_HEIGHT)
        return buildString(CHAR_COUNT) {
            repeat(CHAR_COUNT) { position ->
                // 四个字符位置各裁一次窗，四个位置共用同一批网络
                val crop = makeCrop(rgbPixels, centers[position])
                val ensembleLogits = FloatArray(CLASS_COUNT)
                networks.forEach { network ->
                    val logits = network.infer(crop)
                    logits.indices.forEach { i -> ensembleLogits[i] += logits[i] }
                }
                var best = 0
                for (i in 1 until ensembleLogits.size) {
                    if (ensembleLogits[i] > ensembleLogits[best]) best = i
                }
                append(charset[best])
            }
        }
    }

    /**
     * 从整图里抠出一个字符窗，并就地归一化成 float。
     *
     * 越界的位置补白色（[WHITE]）—— 首位和末位字符的中心靠近边缘，
     * 裁剪框必然会探出图片，补白而不是裁剪窗口，才能保持四个位置
     * 的输入形状完全一致（固定 shape 是这个方案的性能前提）。
     */
    private fun makeCrop(pixels: IntArray, centerX: Int): FloatArray {
        val crop = FloatArray(IMAGE_HEIGHT * CROP_WIDTH * INPUT_CHANNELS)
        val left = centerX - CROP_WIDTH / 2
        var dst = 0
        repeat(IMAGE_HEIGHT) { y ->
            repeat(CROP_WIDTH) { cropX ->
                val sourceX = left + cropX
                val pixel = if (sourceX in 0 until IMAGE_WIDTH) {
                    pixels[y * IMAGE_WIDTH + sourceX]
                } else {
                    WHITE
                }
                // ARGB int → 三个 0..1 的 float，通道顺序 R,G,B
                crop[dst++] = ((pixel ushr 16) and 0xff) / 255f
                crop[dst++] = ((pixel ushr 8) and 0xff) / 255f
                crop[dst++] = (pixel and 0xff) / 255f
            }
        }
        return crop
    }

    /** 一层量化权重：int8 权重 + 每输出通道一个 scale 和一个 bias。 */
    private data class QuantizedLayer(
        val weights: ByteArray,
        val scales: FloatArray,
        val biases: FloatArray,
    )

    private data class Network(
        val conv1: QuantizedLayer,
        val conv2: QuantizedLayer,
        val dense: QuantizedLayer,
    ) {
        fun infer(input: FloatArray): FloatArray {
            val first = conv(
                input = input,
                inputHeight = IMAGE_HEIGHT,
                inputWidth = CROP_WIDTH,
                inputChannels = INPUT_CHANNELS,
                outputChannels = CONV1_CHANNELS,
                kernel = CONV1_KERNEL,
                stride = CONV1_STRIDE,
                layer = conv1,
            )
            val pooled = maxPool2x2(first, CONV1_HEIGHT, CONV1_WIDTH, CONV1_CHANNELS)
            val second = conv(
                input = pooled,
                inputHeight = POOL_HEIGHT,
                inputWidth = POOL_WIDTH,
                inputChannels = CONV1_CHANNELS,
                outputChannels = CONV2_CHANNELS,
                kernel = CONV2_KERNEL,
                stride = 1,
                layer = conv2,
            )
            return dense(second, dense)
        }

        /**
         * 朴素卷积（im2col 都没做）。输入尺寸固定且小，直接三重循环最快也最省内存。
         *
         * 布局约定：输入和输出都是 HWC（高度、宽度、通道），权重是
         * [输出通道][kernelY][kernelX][输入通道]，与 train.py 导出时的
         * transpose 对应。写错布局不会报错，只会识别出乱码 ——
         * 所以有 CompactCaptchaModelTest 那个 fixture 兜着。
         */
        private fun conv(
            input: FloatArray,
            inputHeight: Int,
            inputWidth: Int,
            inputChannels: Int,
            outputChannels: Int,
            kernel: Int,
            stride: Int,
            layer: QuantizedLayer,
        ): FloatArray {
            val outputHeight = (inputHeight - kernel) / stride + 1
            val outputWidth = (inputWidth - kernel) / stride + 1
            val output = FloatArray(outputHeight * outputWidth * outputChannels)
            var outputIndex = 0
            repeat(outputHeight) { y ->
                repeat(outputWidth) { x ->
                    repeat(outputChannels) { outputChannel ->
                        var sum = 0f
                        var weightIndex = outputChannel * kernel * kernel * inputChannels
                        repeat(kernel) { kernelY ->
                            val inputRow = ((y * stride + kernelY) * inputWidth + x * stride) * inputChannels
                            repeat(kernel) { kernelX ->
                                var inputIndex = inputRow + kernelX * inputChannels
                                repeat(inputChannels) {
                                    // 权重是 int8，读出来先转 Int 再乘 float，避免精度丢失
                                    sum += input[inputIndex++] * layer.weights[weightIndex++].toInt()
                                }
                            }
                        }
                        // 反量化 + 加 bias，然后就地 ReLU
                        val value = sum * layer.scales[outputChannel] + layer.biases[outputChannel]
                        output[outputIndex++] = if (value > 0f) value else 0f
                    }
                }
            }
            return output
        }

        private fun maxPool2x2(
            input: FloatArray,
            inputHeight: Int,
            inputWidth: Int,
            channels: Int,
        ): FloatArray {
            val outputHeight = inputHeight / 2
            val outputWidth = inputWidth / 2
            val output = FloatArray(outputHeight * outputWidth * channels)
            var outputIndex = 0
            repeat(outputHeight) { y ->
                repeat(outputWidth) { x ->
                    repeat(channels) { channel ->
                        var best = Float.NEGATIVE_INFINITY
                        repeat(2) { dy ->
                            repeat(2) { dx ->
                                val index = (((y * 2 + dy) * inputWidth + x * 2 + dx) * channels) + channel
                                if (input[index] > best) best = input[index]
                            }
                        }
                        output[outputIndex++] = best
                    }
                }
            }
            return output
        }

        /** 全连接。注意不加 softmax —— 取 argmax 的两者等价，省一次 exp。 */
        private fun dense(input: FloatArray, layer: QuantizedLayer): FloatArray {
            val output = FloatArray(CLASS_COUNT)
            repeat(CLASS_COUNT) { outputChannel ->
                var sum = 0f
                var weightIndex = outputChannel * DENSE_INPUTS
                input.indices.forEach { inputIndex ->
                    sum += input[inputIndex] * layer.weights[weightIndex++].toInt()
                }
                output[outputChannel] = sum * layer.scales[outputChannel] + layer.biases[outputChannel]
            }
            return output
        }
    }

    companion object {
        const val IMAGE_HEIGHT = 40
        const val IMAGE_WIDTH = 100
        internal const val CROP_WIDTH = 48
        internal const val CHAR_COUNT = 4
        internal const val CLASS_COUNT = 36
        internal const val INPUT_CHANNELS = 3
        internal const val CONV1_CHANNELS = 24
        internal const val CONV1_KERNEL = 5
        internal const val CONV1_STRIDE = 2
        internal const val CONV1_HEIGHT = 18
        internal const val CONV1_WIDTH = 22
        internal const val POOL_HEIGHT = 9
        internal const val POOL_WIDTH = 11
        internal const val CONV2_CHANNELS = 48
        internal const val CONV2_KERNEL = 3
        internal const val CONV2_HEIGHT = 7
        internal const val CONV2_WIDTH = 9
        internal const val DENSE_INPUTS = CONV2_HEIGHT * CONV2_WIDTH * CONV2_CHANNELS

        private const val VERSION = 1
        private const val MAGIC = "AHUCAP1\u0000"
        private const val WHITE = -0x1
        /** 四个字符窗的中心 x 坐标。验证码是等距四位，所以直接写死。 */
        private val EXPECTED_CENTERS = intArrayOf(12, 37, 62, 87)
        private const val CONV1_WEIGHTS = CONV1_CHANNELS * CONV1_KERNEL * CONV1_KERNEL * INPUT_CHANNELS
        private const val CONV2_WEIGHTS = CONV2_CHANNELS * CONV2_KERNEL * CONV2_KERNEL * CONV1_CHANNELS
        private const val DENSE_WEIGHTS = CLASS_COUNT * DENSE_INPUTS

        fun load(input: InputStream): CompactCaptchaModel = fromBytes(input.readBytes())

        /**
         * 解析模型文件。这里所有 require 都是**故意的**：
         * 模型损坏时抛异常 → 上层捕获 → 回退手动输入。
         * 比带病推理出一串看似正常的错字符要好得多。
         */
        internal fun fromBytes(bytes: ByteArray): CompactCaptchaModel {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(buffer.remaining() >= 8) { "模型文件过短" }
            val magic = ByteArray(8).also { buffer.get(it) }.toString(Charsets.US_ASCII)
            require(magic == MAGIC) { "模型 magic 不匹配" }
            require(buffer.int == VERSION) { "不支持的模型版本" }
            val modelCount = buffer.int
            require(modelCount in 1..5) { "模型数量非法" }
            require(buffer.int == IMAGE_HEIGHT && buffer.int == IMAGE_WIDTH) { "模型图片尺寸不匹配" }
            require(buffer.int == CROP_WIDTH && buffer.int == CHAR_COUNT) { "模型字符布局不匹配" }
            val centers = IntArray(CHAR_COUNT) { buffer.int }
            require(centers.contentEquals(EXPECTED_CENTERS)) { "模型字符中心不匹配" }
            val charsetLength = buffer.int
            require(charsetLength == CLASS_COUNT) { "模型字符集长度不匹配" }
            val charset = ByteArray(charsetLength).also { buffer.get(it) }
                .toString(Charsets.US_ASCII)
                .toCharArray()
            val networks = List(modelCount) {
                Network(
                    conv1 = buffer.readLayer(CONV1_WEIGHTS, CONV1_CHANNELS),
                    conv2 = buffer.readLayer(CONV2_WEIGHTS, CONV2_CHANNELS),
                    dense = buffer.readLayer(DENSE_WEIGHTS, CLASS_COUNT),
                )
            }
            require(!buffer.hasRemaining()) { "模型文件包含未识别数据" }
            return CompactCaptchaModel(charset, centers, networks)
        }

        /** 给定集成数时模型文件应有的字节数。单测用它构造合法的空模型。 */
        internal fun expectedByteSize(modelCount: Int): Int {
            val header = 8 + 7 * Int.SIZE_BYTES + CHAR_COUNT * Int.SIZE_BYTES + CLASS_COUNT
            val perModel = CONV1_WEIGHTS + 2 * CONV1_CHANNELS * Float.SIZE_BYTES +
                CONV2_WEIGHTS + 2 * CONV2_CHANNELS * Float.SIZE_BYTES +
                DENSE_WEIGHTS + 2 * CLASS_COUNT * Float.SIZE_BYTES
            return header + modelCount * perModel
        }

        private fun ByteBuffer.readLayer(weightCount: Int, outputChannels: Int): QuantizedLayer {
            require(remaining() >= weightCount + outputChannels * 2 * Float.SIZE_BYTES) {
                "模型权重不完整"
            }
            val weights = ByteArray(weightCount).also { get(it) }
            val scales = FloatArray(outputChannels) { float }
            val biases = FloatArray(outputChannels) { float }
            // scale 为负或 NaN 会让推理静默出错，这里挡在加载阶段
            require(scales.all { it.isFinite() && it >= 0f } && biases.all { it.isFinite() }) {
                "模型权重包含非法数值"
            }
            return QuantizedLayer(weights, scales, biases)
        }
    }
}
