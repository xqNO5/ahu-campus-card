package cn.edu.ahu.cardqr.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the retry policy of [AutoLogin].
 *
 * This is the part of the captcha integration that decides whether a stale session recovers
 * silently or dumps a login form on the user, and it is also the part most likely to be
 * "improved" into an unbounded retry loop later. Both are worth a test. The flow is pure JVM:
 * the solver is a lambda and the portal is [LoginApi], so no socket and no Android are needed.
 */
class AutoLoginTest {

    /** Fake portal: hands out distinct images and accepts a scripted set of guesses. */
    private class FakePortal(
        imageCount: Int,
        private val accepted: (String) -> Boolean,
    ) : LoginApi {
        val images = List(imageCount) { byteArrayOf(it.toByte(), 0x7f) }
        val fetched = mutableListOf<ByteArray>()
        val guesses = mutableListOf<String>()
        var sessions = 0

        override fun openSession() {
            sessions++
        }

        override fun fetchCaptcha(): ByteArray =
            images.getOrElse(fetched.size) { images.last() }.also { fetched += it }

        override fun login(username: String, password: String, captcha: String) {
            guesses += captcha
            if (!accepted(captcha)) {
                throw CampusCardException("图形验证码错误", CampusCardException.Kind.CAPTCHA)
            }
        }
    }

    private fun flow(
        api: LoginApi,
        solver: ((ByteArray) -> String?)?,
        sleeps: MutableList<Long> = mutableListOf(),
        maxAttempts: Int = AutoLogin.DEFAULT_MAX_ATTEMPTS,
    ) = AutoLogin(
        api = api,
        recognize = solver,
        maxAttempts = maxAttempts,
        minIntervalMs = 1_500L,
        sleep = { sleeps += it },
    )

    @Test
    fun `solves the captcha and logs in without any user input`() {
        val api = FakePortal(imageCount = 3) { it == "AB12" }
        val sleeps = mutableListOf<Long>()

        val outcome = flow(api, { "AB12" }, sleeps).login("2024001", "secret")

        assertEquals(LoginOutcome.Success, outcome)
        assertEquals(listOf("AB12"), api.guesses)
        assertEquals(1, api.fetched.size)
        assertTrue("a first-attempt success must not sleep", sleeps.isEmpty())
    }

    @Test
    fun `retries with a fresh captcha when the server rejects the guess`() {
        val api = FakePortal(imageCount = 3) { it == "CCCC" }
        val sleeps = mutableListOf<Long>()
        val scripted = ArrayDeque(listOf("AAAA", "BBBB", "CCCC"))

        val outcome = flow(api, { scripted.removeFirst() }, sleeps).login("2024001", "secret")

        assertEquals(LoginOutcome.Success, outcome)
        assertEquals(listOf("AAAA", "BBBB", "CCCC"), api.guesses)
        // Every attempt uses a different image: a rejected captcha is consumed server-side.
        assertEquals(3, api.fetched.distinct().size)
        assertEquals(listOf(1_500L, 1_500L), sleeps)
    }

    @Test
    fun `falls back to manual entry with a fresh captcha once the budget is spent`() {
        val api = FakePortal(imageCount = 4) { false }

        val outcome = flow(api, { "AAAA" }).login("2024001", "secret")

        val fallback = outcome as LoginOutcome.ManualCaptchaRequired
        assertEquals(LoginOutcome.ManualCaptchaRequired.Reason.REJECTED, fallback.reason)
        assertEquals("bounded retries: one initial attempt plus two", 3, api.guesses.size)
        // The image handed to the user must not be one the server already consumed.
        assertEquals(4, api.fetched.size)
        assertNotNull(fallback.captchaImage)
        assertTrue(fallback.captchaImage!!.contentEquals(api.images[3]))
    }

    @Test
    fun `a failed recognition never burns a login request`() {
        val api = FakePortal(imageCount = 4) { true }
        // Blank, null and short results are all "the model had nothing useful".
        val scripted = ArrayDeque<String?>(listOf("", null, "AB"))

        val outcome = flow(api, { scripted.removeFirst() }).login("2024001", "secret")

        val fallback = outcome as LoginOutcome.ManualCaptchaRequired
        assertEquals(LoginOutcome.ManualCaptchaRequired.Reason.RECOGNITION_FAILED, fallback.reason)
        assertTrue("no login may be attempted with a guess the model did not produce", api.guesses.isEmpty())
        assertEquals(4, api.fetched.size)
    }

    @Test
    fun `an unavailable model goes straight to manual entry`() {
        val api = FakePortal(imageCount = 2) { true }

        val outcome = flow(api, null).login("2024001", "secret")

        val fallback = outcome as LoginOutcome.ManualCaptchaRequired
        assertEquals(LoginOutcome.ManualCaptchaRequired.Reason.MODEL_UNAVAILABLE, fallback.reason)
        assertTrue(api.guesses.isEmpty())
        assertEquals("exactly one captcha is fetched, for the user to read", 1, api.fetched.size)
    }

    @Test
    fun `a throwing solver is treated as a miss instead of breaking the flow`() {
        val api = FakePortal(imageCount = 4) { true }

        val outcome = flow(api, { error("model exploded") }).login("2024001", "secret")

        assertEquals(
            LoginOutcome.ManualCaptchaRequired.Reason.RECOGNITION_FAILED,
            (outcome as LoginOutcome.ManualCaptchaRequired).reason,
        )
        assertTrue(api.guesses.isEmpty())
    }

    @Test
    fun `credentials that are simply wrong are not retried as a captcha problem`() {
        val api = object : LoginApi {
            override fun openSession() = Unit
            override fun fetchCaptcha(): ByteArray = byteArrayOf(1)
            override fun login(username: String, password: String, captcha: String) {
                throw CampusCardException("学号或密码错误", CampusCardException.Kind.CREDENTIALS)
            }
        }

        val thrown = assertThrows(CampusCardException::class.java) {
            flow(api, { "AB12" }).login("2024001", "wrong")
        }
        assertEquals(CampusCardException.Kind.CREDENTIALS, thrown.kind)
    }

    @Test
    fun `the log summary never carries the captcha text`() {
        val api = FakePortal(imageCount = 3) { false }

        val outcome = flow(api, { "AB12" }).login("2024001", "secret")

        val line = AutoLogin.redactForLog(outcome)
        assertTrue(line, !line.contains("AB12"))
        assertEquals("auto-login ok", AutoLogin.redactForLog(LoginOutcome.Success))
    }

    @Test
    fun `the retry budget is honoured exactly`() {
        val api = FakePortal(imageCount = 2) { false }

        flow(api, { "AB12" }, maxAttempts = 1).login("2024001", "secret")

        assertEquals(1, api.guesses.size)
        assertEquals(2, api.fetched.size)
    }
}
