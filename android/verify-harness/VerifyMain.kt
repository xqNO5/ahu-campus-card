package cn.edu.ahu.cardqr.verify

import cn.edu.ahu.cardqr.data.CampusCardSession
import cn.edu.ahu.cardqr.data.CardQr
import cn.edu.ahu.cardqr.qr.QrEncoder
import cn.edu.ahu.cardqr.qr.QrRenderer
import java.io.File

/**
 * Offline verification harness for the Android sources.
 *
 * `all` runs the checks that back the JUnit suite (QR matrices against frozen fixtures,
 * payload-shape parsing) so the encoder can be validated without an Android device.
 *
 * `direct` exercises the real service the way the app does: a one-time captcha login followed
 * by login-free code fetches. It only needs a captcha once -- the session cookie is cached
 * under reports/, which is exactly the app's "open and scan" behaviour.
 *
 *   direct                                  fetch a captcha and save it for reading
 *   direct -Dahu.captcha=XXXX               complete the login and fetch the code
 */
object VerifyMain {

    private var failures = 0

    @JvmStatic
    fun main(args: Array<String>) {
        when (val mode = if (args.isNotEmpty()) args[0] else "all") {
            "qr" -> { qr(); assertions() }
            "assert" -> assertions()
            "direct" -> direct()
            "all" -> { qr(); assertions() }
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

    /** The same checks the JUnit suite makes, runnable without Gradle. */
    private fun assertions() {
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
                        if ((rows[y][x] == '#') != qr.isDark(x, y)) diff++
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

        check(
            "version grows with payload",
            QrEncoder.encode("1234", QrEncoder.Ecc.LOW).size <
                QrEncoder.encode("1".repeat(200), QrEncoder.Ecc.LOW).size,
        )

        val rejected = try {
            QrEncoder.encode("x".repeat(4000), QrEncoder.Ecc.HIGH); false
        } catch (_: IllegalArgumentException) {
            true
        }
        check("oversize rejected", rejected)

        // The real service payload is 20 digits plus a 94-character token; the encoder must
        // handle that shape at the same version the official page uses.
        val real = "41194784288188343519SPAEMBAGdcymoBAQE1ATEyAwC+x2oAAAAziQO6OPpKoqEDAGYD" +
            "AAAADGfTGqgtAaf9PCtTuAifP77Ia7aBR5WSSivsCnXC"
        val realQr = QrEncoder.encode(real, QrEncoder.Ecc.LOW)
        check("real payload encodes at version 6", (realQr.size - 17) / 4 == 6, "size=${realQr.size}")
        check("real payload renders", QrRenderer.render(real, 512).width > 0)

        // CardQr parsing of that payload
        val parsed = CardQr(real, "2026-10-10 23:40:30")
        check("CardQr splits numeric code", parsed.numericCode.length == 20, parsed.numericCode)
        check("CardQr reports token length", parsed.tokenLength == real.length - 20, "${parsed.tokenLength}")
    }

    /**
     * Minimal reader for the generated QrFixtures.kt, so this harness does not need the
     * generated Kotlin source on its compile classpath.
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

    // ---------------------------------------------------------------- direct

    private fun direct() {
        val cookieFile = File("reports/.adwmh_session.json")
        val captchaFile = File("reports/captcha_live.jpg")
        val session = CampusCardSession()

        if (cookieFile.isFile) {
            session.restoreCookie(cookieFile.readText().trim().removeSurrounding("\"", "\""))
            println("NOTE restored cookie: ${session.currentCookie()?.take(12)}…")
        }

        val captchaArg = System.getProperty("ahu.captcha", "")?.trim().orEmpty()

        // With a cached session there is nothing to solve: prove the login-free path first.
        if (captchaArg.isEmpty() && session.isAuthenticated) {
            try {
                val qr = session.fetchQrCode()
                check("relaunch qr without captcha", qr.payload.isNotEmpty(), "len=${qr.payload.length}")
                check(
                    "relaunch payload shape",
                    qr.numericCode.length == 20 && qr.tokenLength > 0,
                    "digits=${qr.numericCode.length} token=${qr.tokenLength}",
                )
                println("QRPAYLOAD len=${qr.payload.length} digits=${qr.numericCode.length} token=${qr.tokenLength}")
                check("relaunch render", QrRenderer.render(qr.payload, 720).width > 0)
                check("relaunch balance", session.fetchBalance() != null)
                println("NOTE 重启后无需任何交互即可取码，即 App 的打开即用路径")
                return
            } catch (e: Exception) {
                check("relaunch without captcha", false, "${e.javaClass.simpleName}: ${e.message}")
                return
            }
        }

        if (captchaArg.isEmpty()) {
            try {
                session.openSession()
                captchaFile.parentFile?.mkdirs()
                val bytes = session.fetchCaptcha()
                captchaFile.writeBytes(bytes)
                cookieFile.parentFile?.mkdirs()
                cookieFile.writeText(session.currentCookie() ?: "")
                println("NOTE captcha saved to ${captchaFile.absolutePath} (${bytes.size} bytes)")
                println("NOTE now run: direct -Dahu.captcha=<the 4 characters>")
            } catch (e: Exception) {
                check("direct captcha fetch", false, "${e.javaClass.simpleName}: ${e.message}")
            }
            return
        }

        try {
            var usedLogin = false
            if (!session.isAuthenticated || !session.hasValidSession()) {
                session.login(
                    System.getProperty("ahu.user", ""),
                    System.getProperty("ahu.pass", ""),
                    captchaArg,
                )
                usedLogin = true
            }
            println("NOTE login performed this run: $usedLogin")

            val qr = session.fetchQrCode()
            check("direct qr payload", qr.payload.isNotEmpty(), "len=${qr.payload.length}")
            check(
                "direct payload shape (digits + token)",
                qr.numericCode.length == 20 && qr.tokenLength > 0,
                "digits=${qr.numericCode.length} token=${qr.tokenLength}",
            )
            println("QRPAYLOAD len=${qr.payload.length} digits=${qr.numericCode.length} token=${qr.tokenLength}")

            check("direct render", QrRenderer.render(qr.payload, 720).width > 0)
            check("direct balance", session.fetchBalance() != null)

            session.currentCookie()?.let { cookieFile.writeText(it) }
            println("NOTE cookie cached at ${cookieFile.absolutePath}")

            val again = session.fetchQrCode()
            check("direct second fetch without re-login", again.payload.isNotEmpty(), "len=${again.payload.length}")
        } catch (e: Exception) {
            check("direct run", false, "${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
