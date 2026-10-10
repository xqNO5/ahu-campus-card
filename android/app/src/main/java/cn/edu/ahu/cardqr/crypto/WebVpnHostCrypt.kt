package cn.edu.ahu.cardqr.crypto

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Sangfor WebVPN host-crypt.
 *
 * Every upstream application is addressed as `https://<vpn>/https/<HASH>/<path>` where
 *
 *     HASH = hex("wrdvpnisthebest!") + AES-CFB128(hostname)
 *
 * with Key = IV = `wrdvpnisthebest!` and no padding. Verified against live responses: the
 * hash `...ffe7449269276d59660187e289446d36a8d6` decrypts to `open.weixin.qq.com`, and
 * `...e2f252d2302826416f468aa395` to `res.wx.qq.com`.
 *
 * Note the CFB *feedback size is 128 bits* (a whole block), which several providers'
 * built-in `AES/CFB/NoPadding` does not give (that is CFB-8). So the mode is implemented
 * explicitly on top of the block cipher.
 */
object WebVpnHostCrypt {

    private const val KEY_TEXT = "wrdvpnisthebest!"
    private val KEY = KEY_TEXT.toByteArray(Charsets.US_ASCII)
    private val PREFIX = KEY.joinToString("") { "%02x".format(it) }

    /** `adwmh.ahu.edu.cn` -> its `/https/<hash>/` segment. */
    @JvmStatic
    fun hostHash(host: String): String =
        PREFIX + cfb128(host.toByteArray(Charsets.UTF_8), encrypt = true).toHex()

    /** Best-effort inverse of [hostHash]; returns null when the input is not one of ours. */
    @JvmStatic
    fun unhostHash(hash: String): String? {
        if (!hash.startsWith(PREFIX)) return null
        val raw = hash.substring(PREFIX.length)
        if (raw.length % 2 != 0) return null
        return try {
            String(cfb128(raw.hexToBytes(), encrypt = false), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /** Build the tunnelled URL for an absolute upstream URL, preserving scheme/host/path. */
    @JvmStatic
    fun tunnel(vpnHost: String, upstream: String): String {
        val schemeEnd = upstream.indexOf("://")
        require(schemeEnd > 0) { "need an absolute upstream URL, got $upstream" }
        val rest = upstream.substring(schemeEnd + 3)
        val pathStart = rest.indexOf('/')
        val hostAndPort = if (pathStart < 0) rest else rest.substring(0, pathStart)
        var path = if (pathStart < 0) "/" else rest.substring(pathStart)
        if (path.isEmpty()) path = "/"

        val host = hostAndPort.substringBefore(':')
        val port = hostAndPort.substringAfter(':', "")
        val hostSegment = if (port.isEmpty()) hostHash(host) else "$host:$port"
        return "https://$vpnHost/https/$hostSegment$path"
    }

    /**
     * AES-CFB with a 128-bit feedback segment.
     *
     * For each 16-byte chunk: `ciphertext = plaintext xor AES_encrypt(feedback)`, then the
     * register takes the full ciphertext block (zero-padded on a short final block). This
     * is CFB-128, not the CFB-8 that `AES/CFB/NoPadding` gives.
     */
    private fun cfb128(input: ByteArray, encrypt: Boolean): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY, "AES"))

        val out = ByteArray(input.size)
        var feedback = KEY.copyOf()

        var offset = 0
        while (offset < input.size) {
            val len = minOf(16, input.size - offset)
            val keystream = cipher.doFinal(feedback)
            val block = ByteArray(16)
            for (i in 0 until len) {
                val b = (input[offset + i].toInt() xor keystream[i].toInt()).toByte()
                out[offset + i] = b
                // On encryption the ciphertext is the input to the register; on decryption
                // it is the received input byte. Either way it is the data-side value.
                block[i] = if (encrypt) b else input[offset + i]
            }
            feedback = block
            offset += len
        }
        return out
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append("%02x".format(b))
        return sb.toString()
    }

    private fun String.hexToBytes(): ByteArray {
        val out = ByteArray(length / 2)
        for (i in out.indices) {
            out[i] = ((this[i * 2].digitToInt(16) shl 4) or this[i * 2 + 1].digitToInt(16)).toByte()
        }
        return out
    }
}
