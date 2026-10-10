package cn.edu.ahu.cardqr.crypto

/**
 * Bit-exact Kotlin port of the CAS login page's `strEnc(data, '1', '2', '3')`.
 *
 * The school's `des.js` is a hand-rolled DES that packs each UTF-16 code unit into 16 bits
 * MSB-first and zero-pads a partial 64-bit block. It is *not* byte-compatible with a
 * standard DES implementation, so this is a faithful transcription rather than a call into
 * `javax.crypto`. The Python twin of this file (`ahu_client/cas_des.py`) is pinned against
 * the original JavaScript by `tools/des_vectors.json`; the same vectors back
 * `CasDesTest`, so both ports are checked against the served file.
 */
object CasDes {

    private val S_BOXES: Array<Array<IntArray>> = arrayOf(
        arrayOf(
            intArrayOf(14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7),
            intArrayOf(0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8),
            intArrayOf(4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0),
            intArrayOf(15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13),
        ),
        arrayOf(
            intArrayOf(15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10),
            intArrayOf(3, 13, 4, 7, 15, 2, 8, 14, 12, 0, 1, 10, 6, 9, 11, 5),
            intArrayOf(0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15),
            intArrayOf(13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9),
        ),
        arrayOf(
            intArrayOf(10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8),
            intArrayOf(13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1),
            intArrayOf(13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7),
            intArrayOf(1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12),
        ),
        arrayOf(
            intArrayOf(7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15),
            intArrayOf(13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9),
            intArrayOf(10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4),
            intArrayOf(3, 15, 0, 6, 10, 1, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14),
        ),
        arrayOf(
            intArrayOf(2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9),
            intArrayOf(14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6),
            intArrayOf(4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14),
            intArrayOf(11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3),
        ),
        arrayOf(
            intArrayOf(12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11),
            intArrayOf(10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8),
            intArrayOf(9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6),
            intArrayOf(4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13),
        ),
        arrayOf(
            intArrayOf(4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1),
            intArrayOf(13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6),
            intArrayOf(1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2),
            intArrayOf(6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12),
        ),
        arrayOf(
            intArrayOf(13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7),
            intArrayOf(1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2),
            intArrayOf(7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8),
            intArrayOf(2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11),
        ),
    )

    private val KEY_SHIFT = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)

    private val IP = intArrayOf(
        57, 49, 41, 33, 25, 17, 9, 1, 59, 51, 43, 35, 27, 19, 11, 3,
        61, 53, 45, 37, 29, 21, 13, 5, 63, 55, 47, 39, 31, 23, 15, 7,
        56, 48, 40, 32, 24, 16, 8, 0, 58, 50, 42, 34, 26, 18, 10, 2,
        60, 52, 44, 36, 28, 20, 12, 4, 62, 54, 46, 38, 30, 22, 14, 6,
    )

    private val EP = intArrayOf(
        31, 0, 1, 2, 3, 4, 3, 4, 5, 6, 7, 8, 7, 8, 9, 10,
        11, 12, 11, 12, 13, 14, 15, 16, 15, 16, 17, 18, 19, 20, 19, 20,
        21, 22, 23, 24, 23, 24, 25, 26, 27, 28, 27, 28, 29, 30, 31, 0,
    )

    private val P_BOX = intArrayOf(
        15, 6, 19, 20, 28, 11, 27, 16, 0, 14, 22, 25, 4, 17, 30, 9,
        1, 7, 23, 13, 31, 26, 2, 8, 18, 12, 29, 5, 21, 10, 3, 24,
    )

    private val FP = intArrayOf(
        39, 7, 47, 15, 55, 23, 63, 31, 38, 6, 46, 14, 54, 22, 62, 30,
        37, 5, 45, 13, 53, 21, 61, 29, 36, 4, 44, 12, 52, 20, 60, 28,
        35, 3, 43, 11, 51, 19, 59, 27, 34, 2, 42, 10, 50, 18, 58, 26,
        33, 1, 41, 9, 49, 17, 57, 25, 32, 0, 40, 8, 48, 16, 56, 24,
    )

    /** PC-2 selection, in the original's index order. */
    private val PC2 = intArrayOf(
        13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3,
        25, 7, 15, 6, 26, 19, 12, 1, 40, 51, 30, 36, 46, 54, 29, 39,
        50, 44, 32, 47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31,
    )

    /** Equivalent of the page's `strEnc(data, "1", "2", "3")`. */
    @JvmStatic
    @JvmOverloads
    fun strEnc(data: String, firstKey: String = "1", secondKey: String = "2", thirdKey: String = "3"): String {
        if (data.isEmpty()) return ""
        val keys = listOf(firstKey, secondKey, thirdKey).filter { it.isNotEmpty() }.map { keyBytes(it) }

        val sb = StringBuilder()
        val full = data.length / 4
        for (i in 0 until full) {
            sb.append(encryptBlock(data.substring(i * 4, i * 4 + 4), keys))
        }
        val rem = data.length % 4
        if (rem > 0) {
            sb.append(encryptBlock(data.substring(full * 4), keys))
        }
        return sb.toString()
    }

    private fun encryptBlock(block: String, keys: List<List<IntArray>>): String {
        var bits = strToBits(block)
        for (key in keys) {
            for (k in key) {
                bits = des(bits, k)
            }
        }
        return bitsToHex(bits)
    }

    /**
     * Pack up to 4 UTF-16 code units into 64 bits, MSB first, zero-padded.
     *
     * The padding matters: the page calls this with single-character keys ("1"/"2"/"3"),
     * which the original `strToBt` still expands to a full 64-bit block.
     */
    private fun strToBits(s: String): IntArray {
        val bt = IntArray(64)
        val n = minOf(s.length, 4)
        for (i in 0 until n) {
            val k = s[i].code
            for (j in 0 until 16) {
                bt[16 * i + j] = (k shr (15 - j)) and 1
            }
        }
        return bt
    }

    /** Key bytes for a string: every 4-code-unit group is one 64-bit key block. */
    private fun keyBytes(key: String): List<IntArray> {
        val out = ArrayList<IntArray>()
        var i = 0
        while (i < key.length) {
            val end = minOf(i + 4, key.length)
            out.add(strToBits(key.substring(i, end)))
            i += 4
        }
        return out
    }

    private fun bitsToHex(bits: IntArray): String {
        val sb = StringBuilder(16)
        for (i in 0 until 16) {
            val v = bits[i * 4] * 8 + bits[i * 4 + 1] * 4 + bits[i * 4 + 2] * 2 + bits[i * 4 + 3]
            sb.append("0123456789ABCDEF"[v])
        }
        return sb.toString()
    }

    private fun permute(src: IntArray, table: IntArray): IntArray {
        val out = IntArray(table.size)
        for (i in table.indices) out[i] = src[table[i]]
        return out
    }

    private fun xor(a: IntArray, b: IntArray): IntArray {
        val out = IntArray(a.size)
        for (i in a.indices) out[i] = a[i] xor b[i]
        return out
    }

    private fun sBoxPermute(expandByte: IntArray): IntArray {
        val out = IntArray(32)
        for (m in 0 until 8) {
            val row = expandByte[m * 6] * 2 + expandByte[m * 6 + 5]
            val col = expandByte[m * 6 + 1] * 8 + expandByte[m * 6 + 2] * 4 +
                expandByte[m * 6 + 3] * 2 + expandByte[m * 6 + 4]
            val v = S_BOXES[m][row][col]
            for (b in 0 until 4) {
                out[m * 4 + b] = (v shr (3 - b)) and 1
            }
        }
        return out
    }

    private fun generateKeys(keyByte: IntArray): Array<IntArray> {
        val key = IntArray(56)
        for (i in 0 until 7) {
            var j = 0
            var k = 7
            while (j < 8) {
                key[i * 8 + j] = keyByte[8 * k + i]
                j++
                k--
            }
        }

        val keys = Array(16) { IntArray(48) }
        for (i in 0 until 16) {
            repeat(KEY_SHIFT[i]) {
                val tempLeft = key[0]
                val tempRight = key[28]
                for (k in 0 until 27) {
                    key[k] = key[k + 1]
                    key[28 + k] = key[29 + k]
                }
                key[27] = tempLeft
                key[55] = tempRight
            }
            for (m in 0 until 48) keys[i][m] = key[PC2[m]]
        }
        return keys
    }

    private fun des(dataByte: IntArray, keyByte: IntArray): IntArray {
        val keys = generateKeys(keyByte)
        val ip = permute(dataByte, IP)
        var left = ip.copyOfRange(0, 32)
        var right = ip.copyOfRange(32, 64)
        for (i in 0 until 16) {
            val tempRight = xor(permute(sBoxPermute(xor(permute(right, EP), keys[i])), P_BOX), left)
            left = right
            right = tempRight
        }
        // the original swaps the halves before the final permutation
        return permute(right + left, FP)
    }
}
