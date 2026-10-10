package cn.edu.ahu.cardqr.verify

import android.content.Context
import cn.edu.ahu.cardqr.captcha.CaptchaRecognizer
import cn.edu.ahu.cardqr.captcha.CompactCaptchaModel
import cn.edu.ahu.cardqr.data.AutoLogin
import cn.edu.ahu.cardqr.data.CampusCardException
import cn.edu.ahu.cardqr.data.CampusCardSession
import cn.edu.ahu.cardqr.data.CardQr
import cn.edu.ahu.cardqr.data.LoginApi
import cn.edu.ahu.cardqr.data.LoginOutcome
import cn.edu.ahu.cardqr.qr.QrEncoder
import cn.edu.ahu.cardqr.qr.QrRenderer
import java.io.File

/**
 * Offline verification harness for the Android sources.
 *
 * `all` runs the checks that back the JUnit suite (QR matrices against frozen fixtures,
 * payload-shape parsing, the captcha model's cross-language fixture, the auto-login retry
 * policy) so they can be validated without an Android device *and* without Gradle.
 *
 * `captcha [image]` pushes one captcha image -- by default the last one the live service
 * handed out, saved under reports/ -- through the same decode -> scale -> model path the app
 * uses, and prints what the model read. Use it to eyeball the model against a fresh image.
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
            "captcha" -> captcha(if (args.size > 1) args[1] else null)
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

    /** Finds a file by relative path, whether the harness runs from the repo root or android/. */
    private fun locate(vararg relatives: String): File? {
        val roots = generateSequence(File("").absoluteFile) { it.parentFile }.toList()
        for (relative in relatives) {
            for (root in roots) {
                val candidate = File(root, relative)
                if (candidate.isFile) return candidate
            }
        }
        return null
    }

    private fun locateDir(vararg relatives: String): File? {
        val roots = generateSequence(File("").absoluteFile) { it.parentFile }.toList()
        for (relative in relatives) {
            for (root in roots) {
                val candidate = File(root, relative)
                if (candidate.isDirectory) return candidate
            }
        }
        return null
    }

    private val modelAsset get() = locate(
        "app/src/main/assets/captcha_compact.bin",
        "android/app/src/main/assets/captcha_compact.bin",
    )

    private val assetDir get() = locateDir(
        "app/src/main/assets",
        "android/app/src/main/assets",
    )

    private val captchaFixture get() = locate(
        "android/reports/captcha_live.jpg",
        "reports/captcha_live.jpg",
    )

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

        captchaChecks()
        loginPolicyChecks()
    }

    // ----------------------------------------------------------- captcha model

    /**
     * Mirrors CompactCaptchaModelTest plus one thing JUnit cannot do here: pushing a *real*
     * captcha image the service actually handed out through the Android wrapper.
     */
    private fun captchaChecks() {
        val asset = modelAsset
        check(
            "captcha model asset present",
            asset != null,
            "expected app/src/main/assets/captcha_compact.bin",
        )
        if (asset == null) return
        println("NOTE captcha model size=${asset.length()} bytes")

        val model = asset.inputStream().use(CompactCaptchaModel::load)

        // Deterministic pseudo-random pixels: the same formula produces the same string on any
        // machine, which is what pins the Kotlin runtime to the Python export.
        val pseudo = IntArray(CompactCaptchaModel.IMAGE_WIDTH * CompactCaptchaModel.IMAGE_HEIGHT) { index ->
            val x = index % CompactCaptchaModel.IMAGE_WIDTH
            val y = index / CompactCaptchaModel.IMAGE_WIDTH
            val red = (x * 17 + y * 3) and 0xff
            val green = (x * 5 + y * 11) and 0xff
            val blue = (x * 13 + y * 7) and 0xff
            (0xff shl 24) or (red shl 16) or (green shl 8) or blue
        }
        val parity = model.recognize(pseudo)
        check("captcha model parity fixture (BQWW)", parity == "BQWW", "got $parity")

        val dir = assetDir
        check("captcha assets dir located", dir != null)
        if (dir == null) return

        // The wrapper must degrade to null, never throw, when the asset is missing.
        val broken = CaptchaRecognizer(Context(File(dir, "does-not-exist")))
        check("recognizer reports unavailable for a missing model", !broken.isAvailable)
        check("recognizer returns null without a model", broken.recognize(byteArrayOf(1, 2, 3)) == null)

        val recognizer = CaptchaRecognizer(Context(dir))
        check("recognizer loads the shipped model", recognizer.isAvailable)

        val fixture = captchaFixture
        if (fixture == null) {
            info("captcha fixture image", false, "no reports/captcha_live.jpg; run 'direct' to capture one")
            return
        }
        val text = recognizer.recognize(fixture.readBytes())
        println("NOTE captcha fixture ${fixture.name} recognized as $text (local fixture from reports/)")
        check("real captcha image decodes to four characters", text?.length == 4, "got $text")
        check("recognizer never returns an empty string", !text.isNullOrEmpty())
        check("recognizer ignores a non-image payload", recognizer.recognize(ByteArray(4096)) == null)
    }

    // ------------------------------------------------------------ login policy

    /**
     * Mirrors AutoLoginTest. The real thing is in `app/src/test`; this copy exists so the
     * policy can be exercised by `tools/verify-android.ps1`, which does not run Gradle.
     */
    private fun loginPolicyChecks() {
        val sleeps = mutableListOf<Long>()
        var guess = "AAAA"
        var accepted = "AAAA"
        val api = object : LoginApi {
            val guesses = mutableListOf<String>()
            var fetches = 0
            override fun openSession() = Unit
            override fun fetchCaptcha(): ByteArray = byteArrayOf((fetches++).toByte())
            override fun login(username: String, password: String, captcha: String) {
                guesses += captcha
                if (captcha != accepted) {
                    throw CampusCardException("图形验证码错误", CampusCardException.Kind.CAPTCHA)
                }
            }
        }
        val flow = AutoLogin(api, { guess }, sleep = { sleeps += it })

        check("auto-login succeeds on the first guess", flow.login("u", "p") is LoginOutcome.Success)
        check("auto-login used one request", api.fetches == 1)
        check("auto-login did not sleep on the first attempt", sleeps.isEmpty())

        // Every guess rejected -> bounded attempts, then a manual fallback with a fresh image.
        accepted = "NONE"
        sleeps.clear()
        val before = api.guesses.size
        val outcome = flow.login("u", "p")
        val fallback = outcome as? LoginOutcome.ManualCaptchaRequired
        check("auto-login falls back to manual entry", fallback != null)
        check(
            "fallback reason is REJECTED",
            fallback?.reason == LoginOutcome.ManualCaptchaRequired.Reason.REJECTED,
            "${fallback?.reason}",
        )
        check(
            "retries are bounded to ${AutoLogin.DEFAULT_MAX_ATTEMPTS}",
            api.guesses.size - before == AutoLogin.DEFAULT_MAX_ATTEMPTS,
            "attempted ${api.guesses.size - before}",
        )
        check("fallback carries a fresh captcha image", (fallback?.captchaImage?.size ?: 0) > 0)
        check(
            "retries are paced at ${AutoLogin.DEFAULT_MIN_INTERVAL_MS}ms",
            sleeps.size == AutoLogin.DEFAULT_MAX_ATTEMPTS - 1,
            "sleeps=$sleeps",
        )

        // No model at all: straight to manual, without a single login attempt.
        val noModelApi = object : LoginApi {
            var guesses = 0
            override fun openSession() = Unit
            override fun fetchCaptcha(): ByteArray = byteArrayOf(9)
            override fun login(username: String, password: String, captcha: String) {
                guesses++
            }
        }
        val unavailable = AutoLogin(noModelApi, null).login("u", "p")
        check(
            "no model goes straight to manual entry",
            (unavailable as? LoginOutcome.ManualCaptchaRequired)?.reason ==
                LoginOutcome.ManualCaptchaRequired.Reason.MODEL_UNAVAILABLE,
        )
        check("no model never posts a login", noModelApi.guesses == 0)

        check("log summary carries no captcha text", !AutoLogin.redactForLog(outcome).contains("AAAA"))
    }

    /**
     * Minimal reader for the generated QrFixtures.kt, so this harness does not need the
     * generated Kotlin source on its compile classpath.
     */
    private fun loadQrFixtures(): List<Triple<String, Int, List<String>>> {
        val file = locate(
            "app/src/test/java/cn/edu/ahu/cardqr/qr/QrFixtures.kt",
            "android/app/src/test/java/cn/edu/ahu/cardqr/qr/QrFixtures.kt",
        )
        if (file == null) {
            println("NOTE QrFixtures.kt not found under ${File("").absolutePath}")
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

    // ---------------------------------------------------------------- captcha

    /** Read one captcha image with the shipped model, for eyeballing against the picture. */
    private fun captcha(path: String?) {
        val dir = assetDir
        if (dir == null || modelAsset == null) {
            check("captcha assets located", false, "expected app/src/main/assets/captcha_compact.bin")
            return
        }
        val recognizer = CaptchaRecognizer(Context(dir))
        check("captcha model loaded", recognizer.isAvailable)

        val file = path?.let { File(it) } ?: captchaFixture
        if (file == null || !file.isFile) {
            check("captcha image found", false, "pass a path: captcha <image>")
            return
        }
        val text = recognizer.recognize(file.readBytes())
        println("CAPTCHA ${file.name} -> $text")
        check("recognized four characters", text?.length == 4, "got $text")
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
                // Show what the shipped model makes of the image we just saved.
                val dir = assetDir
                if (dir != null) {
                    val read = CaptchaRecognizer(Context(dir)).recognize(bytes)
                    println("NOTE local model reads that captcha as: $read")
                }
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
