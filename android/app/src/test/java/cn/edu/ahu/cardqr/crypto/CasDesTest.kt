package cn.edu.ahu.cardqr.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Pins the Kotlin DES port to the vectors taken from the live login page. */
class CasDesTest {

    @Test
    fun matchesServedImplementation() {
        for (v in DesVectors.ALL) {
            assertEquals("input=${v.input}", v.expected, CasDes.strEnc(v.input, "1", "2", "3"))
        }
    }

    @Test
    fun emptyInputProducesEmptyOutput() {
        assertEquals("", CasDes.strEnc("", "1", "2", "3"))
    }

    @Test
    fun nonMultipleOfFourLengthIsPadded() {
        // 'a' is a single 16-bit unit, zero-padded to one 64-bit block.
        assertEquals("A62B4F77D5F8C6C7", CasDes.strEnc("a", "1", "2", "3"))
    }
}

/** Pins the WebVPN host-crypt to hashes observed working against the live VPN. */
class WebVpnHostCryptTest {

    @Test
    fun knownHostsMapToObservedHashes() {
        val known = mapOf(
            "open.weixin.qq.com" to
                "77726476706e69737468656265737421ffe7449269276d59660187e289446d36a8d6",
            "res.wx.qq.com" to
                "77726476706e69737468656265737421e2f252d2302826416f468aa395",
            "ycard.ahu.edu.cn" to
                "77726476706e69737468656265737421e9f4408e237e69586b468ca88d1b203b",
            "one.ahu.edu.cn" to
                "77726476706e69737468656265737421fff944d226387d1e7b0c9ce29b5b",
        )
        for ((host, hash) in known) {
            assertEquals(host, hash, WebVpnHostCrypt.hostHash(host))
            assertEquals(host, host, WebVpnHostCrypt.unhostHash(hash))
        }
    }

    @Test
    fun hashRoundTripsForArbitraryHosts() {
        for (host in listOf("adwmh.ahu.edu.cn", "www.ahu.edu.cn", "a.b.c.example.org")) {
            assertEquals(host, host, WebVpnHostCrypt.unhostHash(WebVpnHostCrypt.hostHash(host)))
        }
    }

    @Test
    fun tunnelRewritesPathAndQuery() {
        val url = WebVpnHostCrypt.tunnel(
            "wvpn.ahu.edu.cn",
            "https://ycard.ahu.edu.cn/berserker-app/ykt/tsm/queryCard?x=1",
        )
        val expected = "https://wvpn.ahu.edu.cn/https/" +
            WebVpnHostCrypt.hostHash("ycard.ahu.edu.cn") +
            "/berserker-app/ykt/tsm/queryCard?x=1"
        assertEquals(expected, url)
    }

    @Test
    fun nonCryptHashIsRejected() {
        assertEquals(null, WebVpnHostCrypt.unhostHash("deadbeefdeadbeefdeadbeefdeadbeef"))
        assertNotNull(WebVpnHostCrypt.unhostHash(WebVpnHostCrypt.hostHash("x.y")))
    }
}
