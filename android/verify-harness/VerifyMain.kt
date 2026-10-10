package cn.edu.ahu.cardqr.verify

import cn.edu.ahu.cardqr.crypto.CasDes
import cn.edu.ahu.cardqr.crypto.WebVpnHostCrypt
import cn.edu.ahu.cardqr.qr.QrEncoder
import cn.edu.ahu.cardqr.qr.QrRenderer
import java.io.File

/**
 * Local verification harness. Prints the exact values the Python reference produced, so the
 * Kotlin port can be diffed against the same reference data without an Android device, and
 * additionally re-runs the assertions that back the JUnit test suite.
 *
 * Modes: all | des | hash | qr | assert
 */
object VerifyMain {

    private var failures = 0

    @JvmStatic
    fun main(args: Array<String>) {
        when (val mode = if (args.isNotEmpty()) args[0] else "all") {
            "des" -> des()
            "hash" -> hash()
            "qr" -> qr()
            "assert" -> assertions()
            "e2e" -> e2e()
            "all" -> {
                des(); hash(); qr(); assertions()
            }
            else -> println("unknown mode: $mode")
        }
        if (failures > 0) {
            println("SUMMARY FAILURES=$failures")
            System.exit(1)
        }
        println("SUMMARY OK")
    }

    private fun check(label: String, ok: Boolean, detail: String = "") {
        if (!ok) failures++
        println("CHECK ${if (ok) "OK " else "FAIL"} $label${if (ok || detail.isEmpty()) "" else " :: $detail"}")
    }

    /** Records a result without failing the run. */
    private fun info(label: String, ok: Boolean, detail: String = "") {
        println("CHECK ${if (ok) "OK " else "WARN"} $label${if (detail.isEmpty()) "" else " :: $detail"}")
    }

    // ------------------------------------------------------------------- DES

    private fun des() {
        val vectors = listOf(
            "abc123" to "4860B1E277464DB1C804E426A2405196",
            "12345678" to "C1BB5938DF9F2190B89172CB54C8C33A",
            "a" to "A62B4F77D5F8C6C7",
            "abcd" to "A9CF2704230383D1",
            "user" to "EB6D16D13160C8DC",
            "P@ssw0rd!" to "296CA990DAE05D9440D6C5A9B2ABE5BEBD2D248631100F73",
            "z" to "BE0FA30326A18481",
            "0123456789abcdef" to
                "79E8E3AF508DA690F1F11362D4A351BEAB0FA0A611B9EAE82161BB34952010E8",
            "STUDENT0001P@ssw0rdLT-1082442-nwZfwnrkHP1yM0V9hSDijtI7LO3zYI-cas" to
                "2A009BDF27B00D5C14DB74A98E47E9A4D494DD1B6CFA1719150E8C7CFC4F9995" +
                "5FFFE617478AE50D0DE74966926DEB0916BEA721E007042CC1794954AE067DB0" +
                "5F048E03006EB19EC4C9FFDC305D3FBCC93DC729D836894EDF619B0F2E28553E" +
                "47293DEDE9FFCCCCF1DB6B271711AF13E218500CE1843CE88AB35967D5AA0BA7",
        )
        for ((input, expected) in vectors) {
            val got = CasDes.strEnc(input, "1", "2", "3")
            check("des len=${input.length}", got == expected, "got=$got expected=$expected")
        }
    }

    // ------------------------------------------------------------------ hash

    private fun hash() {
        val hosts = listOf(
            "open.weixin.qq.com",
            "res.wx.qq.com",
            "ycard.ahu.edu.cn",
            "one.ahu.edu.cn",
            "adwmh.ahu.edu.cn",
        )
        for (h in hosts) {
            val hash = WebVpnHostCrypt.hostHash(h)
            val back = WebVpnHostCrypt.unhostHash(hash)
            check("hostHash $h", back == h, "hash=$hash back=$back")
        }
        val tunnelled = WebVpnHostCrypt.tunnel(
            "wvpn.ahu.edu.cn",
            "https://ycard.ahu.edu.cn/berserker-app/ykt/tsm/queryCard?x=1",
        )
        println("TUNNEL $tunnelled")
        check(
            "tunnel keeps path+query",
            tunnelled.endsWith("/berserker-app/ykt/tsm/queryCard?x=1") &&
                tunnelled.contains(WebVpnHostCrypt.hostHash("ycard.ahu.edu.cn")),
            tunnelled,
        )
    }

    // -------------------------------------------------------------------- QR

    private fun qr() {
        val payload = "40936904090512345678"
        for ((name, ecc) in listOf(
            "L" to QrEncoder.Ecc.LOW,
            "M" to QrEncoder.Ecc.MEDIUM,
            "Q" to QrEncoder.Ecc.QUARTILE,
            "H" to QrEncoder.Ecc.HIGH,
        )) {
            val qr = QrEncoder.encode(payload, ecc)
            println("QR ecc=$name version=${(qr.size - 17) / 4} size=${qr.size}")
            for (y in 0 until qr.size) {
                val sb = StringBuilder(qr.size)
                for (x in 0 until qr.size) sb.append(if (qr.isDark(x, y)) '#' else '.')
                println("QRROW $name $y $sb")
            }
            val bmp = QrRenderer.render(qr, 200)
            println("QRRENDER ecc=$name dim=${bmp.width} corner=${bmp.getPixel(0, 0)}")
        }
    }

    // -------------------------------------------------------------- assertions

    /** The same assertions the JUnit suite makes, runnable without Gradle. */
    private fun assertions() {
        // DES vectors
        check("des abc123", CasDes.strEnc("abc123") == "4860B1E277464DB1C804E426A2405196")
        check("des empty", CasDes.strEnc("") == "")

        // host hash
        check(
            "hash open.weixin",
            WebVpnHostCrypt.hostHash("open.weixin.qq.com") ==
                "77726476706e69737468656265737421ffe7449269276d59660187e289446d36a8d6",
        )
        check(
            "hash ycard",
            WebVpnHostCrypt.hostHash("ycard.ahu.edu.cn") ==
                "77726476706e69737468656265737421e9f4408e237e69586b468ca88d1b203b",
        )
        check(
            "hash one",
            WebVpnHostCrypt.hostHash("one.ahu.edu.cn") ==
                "77726476706e69737468656265737421fff944d226387d1e7b0c9ce29b5b",
        )

        // QR against the frozen reference fixtures
        val fixtures = loadQrFixtures()
        check("qr fixtures loaded", fixtures.isNotEmpty(), "expected the generated QrFixtures.kt")
        check("qr fixtures cover 4 EC levels", fixtures.size == 4, "found ${fixtures.size}")
        for ((eccName, version, rows) in fixtures) {
            val ecc = when (eccName) {
                "LOW" -> QrEncoder.Ecc.LOW
                "MEDIUM" -> QrEncoder.Ecc.MEDIUM
                "QUARTILE" -> QrEncoder.Ecc.QUARTILE
                else -> QrEncoder.Ecc.HIGH
            }
            val qr = QrEncoder.encode("40936904090512345678", ecc)
            val sizeOk = rows.size == qr.size
            var diff = 0
            if (sizeOk) {
                for (y in 0 until qr.size) {
                    for (x in 0 until qr.size) {
                        val expected = rows[y][x] == '#'
                        if (expected != qr.isDark(x, y)) diff++
                    }
                }
            }
            check(
                "qr matrix ecc=$eccName v$version",
                sizeOk && diff == 0 && (qr.size - 17) / 4 == version,
                "size=${qr.size} expected=${rows.size} diff=$diff",
            )
        }

        // finder patterns
        val qr = QrEncoder.encode("40936904090512345678", QrEncoder.Ecc.LOW)
        var findersOk = true
        for ((ox, oy) in listOf(0 to 0, qr.size - 7 to 0, 0 to qr.size - 7)) {
            for (i in 0 until 7) {
                if (!qr.isDark(ox + i, oy) || !qr.isDark(ox + i, oy + 6) ||
                    !qr.isDark(ox, oy + i) || !qr.isDark(ox + 6, oy + i)
                ) findersOk = false
            }
            if (!qr.isDark(ox + 3, oy + 3) || qr.isDark(ox + 1, oy + 1)) findersOk = false
        }
        check("finders", findersOk)

        // size ordering
        check(
            "version grows with payload",
            QrEncoder.encode("1234", QrEncoder.Ecc.LOW).size <
                QrEncoder.encode("1".repeat(200), QrEncoder.Ecc.LOW).size,
        )

        // oversize rejection
        val rejected = try {
            QrEncoder.encode("x".repeat(4000), QrEncoder.Ecc.HIGH); false
        } catch (_: IllegalArgumentException) {
            true
        }
        check("oversize rejected", rejected)
    }

    /**
     * Minimal reader for the generated QrFixtures.kt, so this harness does not need the
     * generated Kotlin source on its compile classpath.
     *
     * Searched under the working directory and every ancestor, trying both the
     * module-relative and repo-relative forms, because the harness is launched from
     * android/ by tools/verify-android.ps1 but from the repo root by hand.
     */
    private fun loadQrFixtures(): List<Triple<String, Int, List<String>>> {
        val relative = "app/src/test/java/cn/edu/ahu/cardqr/qr/QrFixtures.kt"
        val roots = generateSequence(File("").absoluteFile) { it.parentFile }.toList()
        val candidates = roots.map { File(it, relative) } +
            roots.map { File(File(it, "android"), relative) }
        val file = candidates.firstOrNull { it.isFile }
        if (file == null) {
            println("NOTE QrFixtures.kt not found; looked in ${candidates.size} locations under ${File("").absolutePath}")
            return emptyList()
        }
        println("NOTE reading QR fixtures from ${file.absolutePath}")
        val text = file.readText()
        val out = ArrayList<Triple<String, Int, List<String>>>()
        val pattern = Regex(
            """Fixture\(QrEncoder\.Ecc\.(\w+),\s*(\d+),\s*listOf\((.*?)\n\s*\)\),""",
            RegexOption.DOT_MATCHES_ALL,
        )
        for (m in pattern.findAll(text)) {
            val ecc = m.groupValues[1]
            val version = m.groupValues[2].toInt()
            val rows = Regex("\"([.#]+)\"").findAll(m.groupValues[3]).map { it.groupValues[1] }.toList()
            if (rows.isNotEmpty()) out.add(Triple(ecc, version, rows))
        }
        return out
    }

    // ------------------------------------------------------------------- end to end

    /**
     * Drives the real client against the live portal. Requires credentials via
     * `-Dahu.user=... -Dahu.pass=...`; skipped (reported as WARN) when absent.
     *
     * Only aggregate facts are printed: no credentials, card number or barcode value.
     */
    private fun e2e() {
        val user = System.getProperty("ahu.user")
        val pass = System.getProperty("ahu.pass")
        if (user.isNullOrEmpty() || pass.isNullOrEmpty()) {
            info("e2e skipped", false, "provide -Dahu.user and -Dahu.pass")
            return
        }

        try {
            val client = cn.edu.ahu.cardqr.data.AhuClient(user, pass)
            client.login()
            info("e2e webvpn login", true)

            val token = client.cardToken()
            info("e2e card sso token", token.isNotEmpty(), "length=${token.length}")

            val cards = client.queryCards()
            check("e2e queryCard", cards.isNotEmpty(), "cards=${cards.size}")
            val card = cards.firstOrNull()
            if (card != null) {
                println("E2E card accountLength=${card.account.length} balanceFen=${card.balanceFen}")
                val code = client.fetchPayCode(card.account)
                check(
                    "e2e barcode",
                    code.value.isNotEmpty() && code.expiresSeconds > 0,
                    "digits=${code.value.length} expires=${code.expiresSeconds}",
                )
                // prove the fetched value is renderable
                val bmp = QrRenderer.render(code.value, 512)
                check("e2e render", bmp.width == bmp.height && bmp.width > 0, "dim=${bmp.width}")
            }
        } catch (e: Exception) {
            check("e2e run", false, "${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
