package cn.edu.ahu.cardqr.verify

import cn.edu.ahu.cardqr.crypto.CasDes

/** Pinpoints which block length misbehaves in the DES port. */
object BlockProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val cases = listOf(
            "P", "@", "!", "P@ss", "w0rd", "P@ssw0rd!",
            "0", "01", "012", "0123", "01234", "012345", "0123456",
            "a", "ab", "abc", "abcd", "abcde", "abcdef",
        )
        for (c in cases) {
            println("LEN=${c.length} ${c.padEnd(10)} -> ${CasDes.strEnc(c, "1", "2", "3")}")
        }
    }
}
