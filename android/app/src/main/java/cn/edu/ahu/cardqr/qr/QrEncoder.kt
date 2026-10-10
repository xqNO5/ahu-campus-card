package cn.edu.ahu.cardqr.qr

/**
 * Dependency-free QR Code encoder (byte mode, versions 1..40, all EC levels).
 *
 * The campus-card pay code is a plain 20-digit ASCII string and the school's own frontend
 * draws it locally with a JS library, so we encode it locally too -- no ZXing, no network.
 *
 * Implements ISO/IEC 18004: mode + character count, data codewords, Reed-Solomon error
 * correction, module placement, the eight data masks with penalty scoring, and the
 * version/format information areas.
 *
 * This file is deliberately free of Android imports so the whole encoder can be unit
 * tested on the JVM; drawing lives in `QrRenderer`.
 */
object QrEncoder {

    enum class Ecc(val formatBits: Int) {
        LOW(1), MEDIUM(0), QUARTILE(3), HIGH(2)
    }

    const val BYTE_MODE = 0b0100

    private val ECC_CODEWORDS_PER_BLOCK = arrayOf(
        // ver:  0   1   2   3   4   5   6   7   8   9  10  11  12  13  14  15  16  17  18  19  20  21  22  23  24  25  26  27  28  29  30  31  32  33  34  35  36  37  38  39  40
        intArrayOf(-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28),
        intArrayOf(-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        intArrayOf(-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
    )

    private val NUM_ERROR_CORRECTION_BLOCKS = arrayOf(
        intArrayOf(-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25),
        intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49),
        intArrayOf(-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68),
        intArrayOf(-1, 1, 1, 2, 4, 4, 4, 5, 5, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81),
    )

    /** Row/column centres of alignment patterns per version. */
    private val ALIGNMENT_POSITIONS = arrayOf(
        intArrayOf(),
        intArrayOf(6, 18), intArrayOf(6, 22), intArrayOf(6, 26), intArrayOf(6, 30), intArrayOf(6, 34),
        intArrayOf(6, 22, 38), intArrayOf(6, 24, 42), intArrayOf(6, 26, 46), intArrayOf(6, 28, 50),
        intArrayOf(6, 30, 54), intArrayOf(6, 32, 58), intArrayOf(6, 34, 62), intArrayOf(6, 26, 46, 66),
        intArrayOf(6, 26, 48, 70), intArrayOf(6, 26, 50, 74), intArrayOf(6, 30, 54, 78),
        intArrayOf(6, 30, 56, 82), intArrayOf(6, 30, 58, 86), intArrayOf(6, 34, 62, 90),
        intArrayOf(6, 28, 50, 72, 94), intArrayOf(6, 26, 50, 74, 98), intArrayOf(6, 30, 54, 78, 102),
        intArrayOf(6, 28, 54, 80, 106), intArrayOf(6, 32, 58, 84, 110), intArrayOf(6, 30, 58, 86, 114),
        intArrayOf(6, 34, 62, 90, 118), intArrayOf(6, 26, 50, 74, 98, 122), intArrayOf(6, 30, 54, 78, 102, 126),
        intArrayOf(6, 26, 52, 78, 104, 130), intArrayOf(6, 30, 56, 82, 108, 134), intArrayOf(6, 34, 60, 86, 112, 138),
        intArrayOf(6, 30, 58, 86, 114, 142), intArrayOf(6, 34, 62, 90, 118, 146), intArrayOf(6, 30, 54, 78, 102, 126, 150),
        intArrayOf(6, 24, 50, 76, 102, 128, 154), intArrayOf(6, 28, 54, 80, 106, 132, 158),
        intArrayOf(6, 32, 58, 84, 110, 136, 162), intArrayOf(6, 26, 54, 82, 110, 138, 166),
        intArrayOf(6, 30, 58, 86, 114, 142, 170),
    )

    /** A square QR matrix; `modules[y][x]` is true for a dark module. */
    class QrCode(val size: Int, private val modules: Array<BooleanArray>) {
        fun isDark(x: Int, y: Int): Boolean = modules[y][x]
    }

    /** Encode [text] (UTF-8, byte mode) at [ecc], picking the smallest fitting version. */
    @JvmStatic
    @JvmOverloads
    fun encode(text: String, ecc: Ecc = Ecc.LOW): QrCode {
        val data = text.toByteArray(Charsets.UTF_8)
        val version = chooseVersion(data.size, ecc)
        val allCodewords = buildCodewords(data, version, ecc)
        return buildMatrix(version, ecc, allCodewords)
    }

    // ---------------------------------------------------------------- version pick

    private fun chooseVersion(dataLen: Int, ecc: Ecc): Int {
        for (version in 1..40) {
            val capacityBits = numDataCodewords(version, ecc) * 8
            val countBits = charCountBits(version)
            val needed = 4 + countBits + dataLen * 8
            if (needed <= capacityBits) return version
        }
        throw IllegalArgumentException("内容过长，无法编码进 QR 码 (${dataLen} 字节)")
    }

    private fun charCountBits(version: Int): Int =
        if (version <= 9) 8 else 16

    private fun numRawDataModules(version: Int): Int {
        var result = (16 * version + 128) * version + 64
        if (version >= 2) {
            val numAlign = version / 7 + 2
            result -= (25 * numAlign - 10) * numAlign - 55
            if (version >= 7) result -= 36
        }
        return result
    }

    private fun numDataCodewords(version: Int, ecc: Ecc): Int =
        numRawDataModules(version) / 8 -
            ECC_CODEWORDS_PER_BLOCK[ecc.ordinal][version] * NUM_ERROR_CORRECTION_BLOCKS[ecc.ordinal][version]

    // ------------------------------------------------------------- data codewords

    private fun buildCodewords(data: ByteArray, version: Int, ecc: Ecc): ByteArray {
        val bits = BitList()
        bits.append(BYTE_MODE, 4)
        bits.append(data.size, charCountBits(version))
        for (b in data) bits.append(b.toInt() and 0xFF, 8)

        val capacityBits = numDataCodewords(version, ecc) * 8
        // terminator, then pad to a byte boundary, then alternate pad codewords
        bits.append(0, minOf(4, capacityBits - bits.size))
        bits.append(0, (8 - bits.size % 8) % 8)
        var pad = 0xEC
        while (bits.size < capacityBits) {
            bits.append(pad, 8)
            pad = if (pad == 0xEC) 0x11 else 0xEC
        }

        val dataCodewords = bits.toByteArray()

        // split into blocks, add ECC, then interleave
        val numBlocks = NUM_ERROR_CORRECTION_BLOCKS[ecc.ordinal][version]
        val blockEccLen = ECC_CODEWORDS_PER_BLOCK[ecc.ordinal][version]
        val rawCodewords = numRawDataModules(version) / 8
        val numShortBlocks = numBlocks - rawCodewords % numBlocks
        val shortBlockLen = rawCodewords / numBlocks

        val divisor = reedSolomonDivisor(blockEccLen)
        val blocks = ArrayList<ByteArray>(numBlocks)
        var offset = 0
        for (i in 0 until numBlocks) {
            val dataLen = shortBlockLen - blockEccLen + if (i < numShortBlocks) 0 else 1
            val dat = dataCodewords.copyOfRange(offset, offset + dataLen)
            offset += dataLen
            val eccBytes = reedSolomonRemainder(dat, divisor)
            val block = if (i < numShortBlocks) dat + eccBytes else dat + ByteArray(0) + eccBytes
            blocks.add(block)
        }

        val result = ByteArray(rawCodewords)
        var k = 0
        val maxDataLen = shortBlockLen - blockEccLen + 1
        for (i in 0 until maxDataLen) {
            for (b in blocks.indices) {
                val block = blocks[b]
                // skip the padding slot in short blocks
                val dataLen = block.size - blockEccLen
                if (i < dataLen) {
                    result[k++] = block[i]
                }
            }
        }
        for (i in 0 until blockEccLen) {
            for (b in blocks.indices) {
                val block = blocks[b]
                result[k++] = block[block.size - blockEccLen + i]
            }
        }
        return result
    }

    private fun reedSolomonDivisor(degree: Int): IntArray {
        val result = IntArray(degree)
        result[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in result.indices) {
                result[j] = multiply(result[j], root)
                if (j + 1 < result.size) result[j] = result[j] xor result[j + 1]
            }
            root = multiply(root, 0x02)
        }
        return result
    }

    private fun reedSolomonRemainder(data: ByteArray, divisor: IntArray): ByteArray {
        val result = ByteArray(divisor.size)
        for (b in data) {
            val factor = (b.toInt() and 0xFF) xor (result[0].toInt() and 0xFF)
            System.arraycopy(result, 1, result, 0, result.size - 1)
            result[result.size - 1] = 0
            for (i in divisor.indices) {
                result[i] = ((result[i].toInt() and 0xFF) xor multiply(divisor[i], factor)).toByte()
            }
        }
        return result
    }

    private fun multiply(x: Int, y: Int): Int {
        var z = 0
        var a = x
        var b = y
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((b ushr i) and 1) * a)
        }
        return z and 0xFF
    }

    // ------------------------------------------------------------------ matrix

    private fun buildMatrix(version: Int, ecc: Ecc, codewords: ByteArray): QrCode {
        val size = version * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }

        fun setFunction(x: Int, y: Int, dark: Boolean) {
            modules[y][x] = dark
            isFunction[y][x] = true
        }

        // timing patterns
        for (i in 0 until size) {
            setFunction(6, i, i % 2 == 0)
            setFunction(i, 6, i % 2 == 0)
        }
        // finder patterns + separators
        for (corner in 0 until 3) {
            val cx = if (corner == 0) 3 else if (corner == 1) size - 4 else 3
            val cy = if (corner == 0) 3 else if (corner == 1) 3 else size - 4
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val x = cx + dx
                    val y = cy + dy
                    if (x in 0 until size && y in 0 until size) {
                        val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                        setFunction(x, y, dist != 2 && dist != 4)
                    }
                }
            }
        }
        // alignment patterns
        val positions = ALIGNMENT_POSITIONS[version - 1]
        for (i in positions.indices) {
            for (j in positions.indices) {
                val isCorner = (i == 0 && j == 0) || (i == 0 && j == positions.size - 1) ||
                    (i == positions.size - 1 && j == 0)
                if (isCorner) continue
                val cx = positions[j]
                val cy = positions[i]
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        setFunction(cx + dx, cy + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
                    }
                }
            }
        }
        // reserve format information
        for (i in 0 until 9) {
            if (i != 6) {
                setFunction(8, i, false)
                setFunction(i, 8, false)
            }
        }
        for (i in 0 until 8) {
            setFunction(size - 1 - i, 8, false)
            setFunction(8, size - 1 - i, false)
        }
        setFunction(8, size - 8, true) // dark module
        // reserve version information (only meaningful for version >= 7)
        if (version >= 7) {
            for (i in 0 until 18) {
                val a = size - 11 + i % 3
                val b = i / 3
                setFunction(a, b, false)
                setFunction(b, a, false)
            }
        }

        drawCodewords(modules, isFunction, codewords, size)

        // Build each candidate from a clean copy rather than xoring the same matrix
        // repeatedly, so every mask is scored against the identical un-masked base.
        val base = Array(size) { modules[it].copyOf() }
        var bestMask = 0
        var bestPenalty = Int.MAX_VALUE
        for (mask in 0 until 8) {
            val candidate = Array(size) { base[it].copyOf() }
            applyMask(candidate, isFunction, mask, size)
            val p = penalty(candidate, size)
            if (p < bestPenalty) {
                bestPenalty = p
                bestMask = mask
            }
        }
        applyMask(modules, isFunction, bestMask, size)
        drawFormatBits(modules, ecc, bestMask, size)
        if (version >= 7) drawVersionBits(modules, version, size)

        return QrCode(size, modules)
    }

    private fun drawCodewords(modules: Array<BooleanArray>, isFunction: Array<BooleanArray>, data: ByteArray, size: Int) {
        var i = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) {
                for (j in 0 until 2) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!isFunction[y][x] && i < data.size * 8) {
                        modules[y][x] = ((data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1) != 0
                        i++
                    }
                }
            }
            right -= 2
        }
    }

    private fun applyMask(modules: Array<BooleanArray>, isFunction: Array<BooleanArray>, mask: Int, size: Int) {
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (isFunction[y][x]) continue
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (invert) modules[y][x] = !modules[y][x]
            }
        }
    }

    private fun drawFormatBits(modules: Array<BooleanArray>, ecc: Ecc, mask: Int, size: Int) {
        val data = (ecc.formatBits shl 3) or mask
        var rem = data
        for (i in 0 until 10) rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
        val bits = ((data shl 10) or rem) xor 0x5412

        fun bit(i: Int) = ((bits ushr i) and 1) != 0

        // Primary copy, split around the top-left finder.
        // Vertical: bits 0..5 down column 8 from row 0, bit 6 at row 7, bit 7 at row 8.
        for (i in 0..5) modules[i][8] = bit(i)
        modules[7][8] = bit(6)
        modules[8][8] = bit(7)
        // Horizontal: bit 8 at column 7, then bits 9..14 at columns 5,4,3,2,1,0.
        modules[8][7] = bit(8)
        for (i in 9..14) modules[8][14 - i] = bit(i)

        // Second copy: bits 0..7 along row 8 from the right edge inwards.
        for (i in 0..7) modules[8][size - 1 - i] = bit(i)
        // Second copy: bits 8..14 up column 8 starting at row size-7.
        for (i in 8..14) modules[size - 15 + i][8] = bit(i)

        // Fixed dark module.
        modules[size - 8][8] = true
    }

    private fun drawVersionBits(modules: Array<BooleanArray>, version: Int, size: Int) {
        var rem = version
        for (i in 0 until 12) rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25)
        val bits = (version shl 12) or rem
        for (i in 0 until 18) {
            val bit = ((bits ushr i) and 1) != 0
            val a = size - 11 + i % 3
            val b = i / 3
            modules[b][a] = bit
            modules[a][b] = bit
        }
    }

    private fun penalty(modules: Array<BooleanArray>, size: Int): Int {
        // Rule 1: runs of >= 5 same-coloured modules, each scoring (runLength - 2).
        // The container counts runs by length, matching the reference encoder.
        val container = IntArray(size + 1)
        for (y in 0 until size) {
            var prev = modules[y][0]
            var length = 0
            for (x in 0 until size) {
                if (modules[y][x] == prev) {
                    length++
                } else {
                    if (length >= 5) container[length]++
                    prev = modules[y][x]
                    length = 1
                }
            }
            if (length >= 5) container[length]++
        }
        for (x in 0 until size) {
            var prev = modules[0][x]
            var length = 0
            for (y in 0 until size) {
                if (modules[y][x] == prev) {
                    length++
                } else {
                    if (length >= 5) container[length]++
                    prev = modules[y][x]
                    length = 1
                }
            }
            if (length >= 5) container[length]++
        }
        var result = 0
        for (n in 5..size) result += container[n] * (n - 2)

        // Rule 2: every 2x2 block of a single colour.
        for (y in 0 until size - 1) {
            for (x in 0 until size - 1) {
                val c = modules[y][x]
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += 3
            }
        }

        // Rule 3: finder-like 1:1:3:1:1 patterns with a 4-module light run.
        val pattern1 = booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false)
        val pattern2 = booleanArrayOf(false, false, false, false, true, false, true, true, true, false, true)
        for (y in 0 until size) {
            for (x in 0..size - 11) {
                if (matches(modules, x, y, 1, 0, pattern1) || matches(modules, x, y, 1, 0, pattern2)) result += 40
            }
        }
        for (x in 0 until size) {
            for (y in 0..size - 11) {
                if (matches(modules, x, y, 0, 1, pattern1) || matches(modules, x, y, 0, 1, pattern2)) result += 40
            }
        }

        // Rule 4: dark/light balance.
        var dark = 0
        for (y in 0 until size) for (x in 0 until size) if (modules[y][x]) dark++
        val total = size * size
        result += kotlin.math.abs(dark * 20 - total * 10) / total * 10

        return result
    }

    private fun matches(
        modules: Array<BooleanArray>,
        x: Int, y: Int, dx: Int, dy: Int,
        pattern: BooleanArray,
    ): Boolean {
        for (i in pattern.indices) {
            if (modules[y + dy * i][x + dx * i] != pattern[i]) return false
        }
        return true
    }

    /** Simple MSB-first bit accumulator. */
    private class BitList {
        private val bytes = ArrayList<Byte>(64)
        private var bitLen = 0

        val size: Int get() = bitLen

        fun append(value: Int, len: Int) {
            for (i in len - 1 downTo 0) {
                if (bitLen % 8 == 0) bytes.add(0)
                if (((value ushr i) and 1) != 0) {
                    bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() or (1 shl (7 - bitLen % 8))).toByte()
                }
                bitLen++
            }
        }

        fun toByteArray(): ByteArray = ByteArray(bytes.size) { bytes[it] }
    }
}
