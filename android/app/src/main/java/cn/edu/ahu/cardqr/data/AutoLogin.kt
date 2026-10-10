package cn.edu.ahu.cardqr.data

/**
 * The three calls the auto-login flow needs, extracted as an interface so the retry policy can
 * be unit-tested without a socket. [CampusCardSession] is the production implementation.
 */
interface LoginApi {
    /** Create the anonymous session that the captcha and login calls both need. */
    fun openSession()

    /** Fetch a fresh captcha image (JPEG bytes). */
    fun fetchCaptcha(): ByteArray

    /** Bind the credentials to the current session. Throws [CampusCardException] on failure. */
    fun login(username: String, password: String, captcha: String)
}

/** Result of an unattended login attempt. */
sealed class LoginOutcome {

    /** The session is bound to the account; the QR code can be fetched. */
    object Success : LoginOutcome()

    /**
     * Automatic solving is not possible, so the user has to read [captchaImage] and type the
     * four characters. [captchaImage] is a *fresh*, unused captcha whenever one could be
     * fetched; it is null only when the image request itself failed, in which case the UI
     * should fetch its own.
     */
    class ManualCaptchaRequired(
        val captchaImage: ByteArray?,
        val reason: Reason,
    ) : LoginOutcome() {
        enum class Reason {
            /** No local model (missing/corrupt asset): nothing was even attempted. */
            MODEL_UNAVAILABLE,

            /** The model ran but produced nothing usable, or produced unusable characters. */
            RECOGNITION_FAILED,

            /** Every recognized guess was rejected by the server: the model may be stale. */
            REJECTED,
        }
    }
}

/**
 * Solves the login captcha locally and signs in, falling back to manual entry.
 *
 * The portal always demands a captcha for `POST /user/login`, and that captcha is the only
 * thing standing between a stored session and a truly unattended re-login. The model in
 * [cn.edu.ahu.cardqr.captcha] recognizes it on-device, so this class turns "session expired"
 * from a user-visible event back into a background detail.
 *
 * Three deliberate properties, all borrowed from the model's original host project:
 *
 *  1. **Recognition is optional, retries are bounded.** [maxAttempts] image/login cycles
 *     (not an unbounded loop), with [minIntervalMs] between them. The portal is sensitive to
 *     bursts; hammering it turns an optional convenience into a rate-limiting incident.
 *  2. **Every failure path converges on [LoginOutcome.ManualCaptchaRequired]** instead of
 *     throwing distinguishable exceptions for the UI to sort out. Only real errors
 *     (bad credentials, network, protocol) propagate.
 *  3. **Nothing here logs the captcha or the recognized text** -- it is part of a login
 *     credential. See [redactForLog].
 *
 * @param recognize the on-device solver, or null when no model is available.
 * @param sleep injected so tests do not actually wait.
 */
class AutoLogin(
    private val api: LoginApi,
    private val recognize: ((ByteArray) -> String?)?,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {

    /**
     * Sign in with [username]/[password], solving the captcha locally.
     *
     * Throws [CampusCardException] for anything that human retyping cannot fix (wrong
     * credentials, network failure, unexpected server response).
     */
    fun login(username: String, password: String): LoginOutcome {
        val solver = recognize
            ?: return LoginOutcome.ManualCaptchaRequired(
                fetchCaptchaQuietly(),
                LoginOutcome.ManualCaptchaRequired.Reason.MODEL_UNAVAILABLE,
            )

        var lastImage: ByteArray? = null
        var reason = LoginOutcome.ManualCaptchaRequired.Reason.RECOGNITION_FAILED

        repeat(maxAttempts) { attempt ->
            // Pace the retries rather than firing them back to back.
            if (attempt > 0 && minIntervalMs > 0) sleep(minIntervalMs)

            val image = api.fetchCaptcha()
            lastImage = image

            val guess = try {
                solver(image)
            } catch (_: Throwable) {
                // The solver is contracted never to throw; if it does, treat it as a miss
                // rather than letting it break the login flow.
                null
            }
            if (guess.isNullOrBlank() || guess.length != CAPTCHA_LENGTH) return@repeat

            try {
                api.login(username, password, guess)
                return LoginOutcome.Success
            } catch (e: CampusCardException) {
                when (e.kind) {
                    // A wrong guess is expected sometimes: try again with a fresh image.
                    CampusCardException.Kind.CAPTCHA -> reason =
                        LoginOutcome.ManualCaptchaRequired.Reason.REJECTED
                    // Anything else (bad password, network, server) is not a captcha problem.
                    else -> throw e
                }
            }
        }

        // Hand the user an image the server has not already consumed for a failed attempt.
        val fresh = fetchCaptchaQuietly()
        return LoginOutcome.ManualCaptchaRequired(fresh ?: lastImage, reason)
    }

    /** The manual fallback must never itself fail: a dead captcha request just means null. */
    private fun fetchCaptchaQuietly(): ByteArray? = try {
        api.fetchCaptcha()
    } catch (_: Exception) {
        null
    }

    companion object {
        /**
         * Image/login cycles per login. Two retries is the balance point found in practice:
         * with ~93% whole-image accuracy, three attempts leave a low-single-digit-percent
         * chance of falling through to manual entry while staying far away from anything the
         * portal would read as abuse.
         */
        const val DEFAULT_MAX_ATTEMPTS = 3

        /** Minimum spacing between captcha requests, in milliseconds. */
        const val DEFAULT_MIN_INTERVAL_MS = 1_500L

        /** The portal's captcha is always four characters; that is a protocol fact. */
        const val CAPTCHA_LENGTH = 4

        /**
         * Safe-to-log summary of an attempt. Never include the captcha text or the recognized
         * characters -- they are part of a login credential.
         */
        fun redactForLog(outcome: LoginOutcome): String = when (outcome) {
            is LoginOutcome.Success -> "auto-login ok"
            is LoginOutcome.ManualCaptchaRequired -> "auto-login fallback: ${outcome.reason}"
        }
    }
}
