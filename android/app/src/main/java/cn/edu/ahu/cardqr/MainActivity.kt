package cn.edu.ahu.cardqr

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import cn.edu.ahu.cardqr.captcha.CaptchaRecognizer
import cn.edu.ahu.cardqr.data.AutoLogin
import cn.edu.ahu.cardqr.data.CampusCardException
import cn.edu.ahu.cardqr.data.CampusCardSession
import cn.edu.ahu.cardqr.data.CardQr
import cn.edu.ahu.cardqr.data.LoginOutcome
import cn.edu.ahu.cardqr.data.SessionExpiredException
import cn.edu.ahu.cardqr.qr.QrRenderer
import cn.edu.ahu.cardqr.store.SecureStore
import java.util.concurrent.Executors

/**
 * Two states: a credential binding screen (rarely seen) and the campus-card QR code.
 *
 * The behaviour that matters is on launch, and after the portal's session lapses:
 *
 *  1. A stored `JSESSIONID` -> fetch and show the code. No interaction at all.
 *  2. No session but stored credentials -> fetch a captcha, solve it with the on-device model
 *     ([CaptchaRecognizer]), sign in, show the code. Still no interaction.
 *  3. Only if the model cannot read a captcha (or every guess is rejected) does the login form
 *     appear -- and then it is pre-filled with a live captcha image so the user types four
 *     characters instead of everything.
 *
 * The password is only written to [SecureStore] when the user leaves "记住密码" checked; it is
 * sealed with AES-256-GCM under a key that never leaves the Android keystore. Unchecking it
 * (or signing out) erases it.
 */
class MainActivity : Activity() {

    private lateinit var store: SecureStore
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var loginView: View
    private lateinit var cardView: View
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var captchaRow: View
    private lateinit var captchaInput: EditText
    private lateinit var captchaImage: ImageView
    private lateinit var captchaHint: TextView
    private lateinit var rememberPassword: CheckBox
    private lateinit var autoCaptcha: CheckBox
    private lateinit var loginButton: Button
    private lateinit var loginProgress: ProgressBar
    private lateinit var loginError: TextView
    private lateinit var qrImage: ImageView
    private lateinit var qrNumber: TextView
    private lateinit var qrExpiry: TextView
    private lateinit var qrProgress: ProgressBar
    private lateinit var cardMeta: TextView

    private val session = CampusCardSession()
    private var autoRefresh: Runnable? = null
    private var currentPayload: String? = null

    /** Parsed once, on the IO thread: the model is a 357 KiB asset. */
    @Volatile
    private var recognizer: CaptchaRecognizer? = null

    /** Set when automatic solving gave up, so the button switches to manual submission. */
    private var manualFallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = SecureStore(this)

        loginView = findViewById(R.id.loginView)
        cardView = findViewById(R.id.cardView)
        username = findViewById(R.id.username)
        password = findViewById(R.id.password)
        captchaRow = findViewById(R.id.captchaRow)
        captchaInput = findViewById(R.id.captchaInput)
        captchaImage = findViewById(R.id.captchaImage)
        captchaHint = findViewById(R.id.captchaHint)
        rememberPassword = findViewById(R.id.rememberPassword)
        autoCaptcha = findViewById(R.id.autoCaptcha)
        loginButton = findViewById(R.id.loginButton)
        loginProgress = findViewById(R.id.loginProgress)
        loginError = findViewById(R.id.loginError)
        qrImage = findViewById(R.id.qrImage)
        qrNumber = findViewById(R.id.qrNumber)
        qrExpiry = findViewById(R.id.qrExpiry)
        qrProgress = findViewById(R.id.qrProgress)
        cardMeta = findViewById(R.id.cardMeta)

        username.setText(store.read(KEY_LAST_USER) ?: "")
        rememberPassword.isChecked = store.read(KEY_REMEMBER_PASSWORD) != "0"
        autoCaptcha.isChecked = store.read(KEY_AUTO_CAPTCHA) != "0"

        loginButton.setOnClickListener {
            if (manualEntryActive()) submitManualCaptcha() else startAutoLogin()
        }
        captchaImage.setOnClickListener { loadCaptchaOnly() }
        findViewById<Button>(R.id.refreshButton).setOnClickListener { refreshCode() }
        findViewById<Button>(R.id.logoutButton).setOnClickListener { forgetSession() }
        autoCaptcha.setOnCheckedChangeListener { _, checked ->
            if (checked && !manualFallback) {
                showAutoCaptchaUi()
            } else {
                loadCaptchaOnly()
            }
        }

        // Restore the session and go straight to the code; no captcha, no typing.
        session.restoreCookie(store.read(KEY_SESSION))
        when {
            session.isAuthenticated -> {
                showCardView()
                refreshCode()
            }
            hasStoredCredentials() -> startSilentLogin()
            else -> prepareLogin(null, null)
        }
    }

    override fun onResume() {
        super.onResume()
        if (cardView.visibility == View.VISIBLE) startAutoRefresh()
    }

    override fun onPause() {
        super.onPause()
        stopAutoRefresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAutoRefresh()
        io.shutdownNow()
    }

    // ------------------------------------------------------------- the solver

    /**
     * Load the captcha model once. Called from the IO thread only, because parsing the asset
     * is not free.
     */
    private fun recognizer(): CaptchaRecognizer {
        recognizer?.let { return it }
        val created = CaptchaRecognizer(this)
        recognizer = created
        return created
    }

    /** Null when the model is unusable, which sends the flow straight to manual entry. */
    private fun solver(): ((ByteArray) -> String?)? {
        val model = recognizer()
        return if (model.isAvailable) ({ bytes -> model.recognize(bytes) }) else null
    }

    private fun autoLogin(): AutoLogin = AutoLogin(session, solver())

    private fun hasStoredCredentials(): Boolean =
        !store.read(KEY_LAST_USER).isNullOrBlank() && !store.read(KEY_PASSWORD).isNullOrBlank()

    // --------------------------------------------------------------- login UI

    private fun manualEntryActive(): Boolean = manualFallback || !autoCaptcha.isChecked

    /** Captcha is solved locally: hide the field and say so. */
    private fun showAutoCaptchaUi() {
        manualFallback = false
        captchaRow.visibility = View.GONE
        captchaHint.text = getString(R.string.login_captcha_auto)
        loginButton.text = getString(R.string.login_button)
        hideLoginError()
    }

    /** Show a captcha for the user to read. [bytes] is reused when the flow already fetched one. */
    private fun showManualCaptchaUi(bytes: ByteArray?, message: String?) {
        manualFallback = true
        captchaRow.visibility = View.VISIBLE
        captchaHint.text = getString(R.string.login_captcha_hint)
        loginButton.text = getString(R.string.login_button)
        captchaInput.setText("")
        setLoginBusy(false)
        if (message != null) showLoginError(message) else hideLoginError()
        if (bytes != null && bytes.isNotEmpty()) {
            renderCaptcha(bytes)
        } else {
            loadCaptchaOnly()
        }
    }

    /**
     * The starting state of the login screen. Nothing is requested until the user (or the
     * silent path) actually needs a captcha.
     */
    private fun prepareLogin(fallback: LoginOutcome.ManualCaptchaRequired?, message: String?) {
        loginView.visibility = View.VISIBLE
        cardView.visibility = View.GONE
        setLoginBusy(false)
        username.setText(store.read(KEY_LAST_USER) ?: "")
        when {
            fallback != null -> showManualCaptchaUi(
                fallback.captchaImage,
                message ?: getString(captchaFallbackMessage(fallback.reason)),
            )
            autoCaptcha.isChecked -> {
                showAutoCaptchaUi()
                if (message != null) showLoginError(message)
            }
            else -> showManualCaptchaUi(null, message)
        }
    }

    private fun showCardView() {
        loginView.visibility = View.GONE
        cardView.visibility = View.VISIBLE
        setLoginBusy(false)
    }

    // ------------------------------------------------------------- silent path

    /**
     * Launch path 2: a session cookie is gone but credentials were stored, so sign in without
     * showing anything. The user sees the QR code appear, not a login form.
     */
    private fun startSilentLogin() {
        showLoginView()
        hideLoginError()
        setLoginBusy(true, getString(R.string.login_auto_signing_in))
        val user = store.read(KEY_LAST_USER).orEmpty()
        val pass = store.read(KEY_PASSWORD).orEmpty()
        runAutoLogin(user, pass, silent = true)
    }

    private fun showLoginView() {
        loginView.visibility = View.VISIBLE
        cardView.visibility = View.GONE
        captchaRow.visibility = View.GONE
        captchaHint.text = getString(R.string.login_captcha_auto)
    }

    // ------------------------------------------------------------ auto solving

    /** Manual login attempt, i.e. the user typed the four characters. */
    private fun submitManualCaptcha() {
        val user = username.text.toString().trim()
        val pass = password.text.toString()
        val captcha = captchaInput.text.toString().trim()
        if (user.isEmpty() || pass.isEmpty()) {
            showLoginError(getString(R.string.error_credentials))
            return
        }
        if (captcha.length != AutoLogin.CAPTCHA_LENGTH) {
            showLoginError(getString(R.string.login_captcha_needed))
            return
        }

        setLoginBusy(true)
        hideLoginError()
        io.execute {
            try {
                session.login(user, pass, captcha)
                ui.post { onLoginSucceeded(user, pass) }
            } catch (e: Exception) {
                ui.post {
                    setLoginBusy(false)
                    showLoginError(describe(e))
                    // A rejected captcha is consumed by the server.
                    captchaInput.setText("")
                    loadCaptchaOnly()
                }
            }
        }
    }

    /** Automatic login attempt: the captcha is solved on-device. */
    private fun startAutoLogin() {
        val user = username.text.toString().trim()
        val pass = password.text.toString()
        if (user.isEmpty() || pass.isEmpty()) {
            showLoginError(getString(R.string.error_credentials))
            return
        }
        setLoginBusy(true)
        hideLoginError()
        runAutoLogin(user, pass, silent = false)
    }

    /**
     * Runs [AutoLogin] on the IO thread and reports the outcome on the UI thread.
     *
     * @param silent true when this happens without the user asking (launch or session expiry);
     *        a failure then falls back to the form instead of an error banner.
     */
    private fun runAutoLogin(user: String, pass: String, silent: Boolean) {
        io.execute {
            val outcome = try {
                autoLogin().login(user, pass)
            } catch (e: Exception) {
                Log.w(TAG, "自动登录失败: ${e.javaClass.simpleName}")
                ui.post { onLoginFailed(e, silent) }
                return@execute
            }
            Log.i(TAG, AutoLogin.redactForLog(outcome))
            ui.post {
                when (outcome) {
                    is LoginOutcome.Success -> onLoginSucceeded(user, pass)
                    is LoginOutcome.ManualCaptchaRequired -> onManualCaptchaRequired(outcome, silent)
                }
            }
        }
    }

    private fun onManualCaptchaRequired(outcome: LoginOutcome.ManualCaptchaRequired, silent: Boolean) {
        if (silent) {
            // Do not shout about a background re-login; just present the form, ready to use.
            prepareLogin(outcome, null)
        } else {
            showManualCaptchaUi(outcome.captchaImage, getString(captchaFallbackMessage(outcome.reason)))
        }
    }

    private fun onLoginSucceeded(user: String, pass: String) {
        store.write(KEY_SESSION, session.currentCookie())
        store.write(KEY_LAST_USER, user)
        // The password is only stored when the user asked for it; unchecking erases it.
        if (rememberPassword.isChecked) {
            store.write(KEY_PASSWORD, pass)
        } else {
            store.remove(KEY_PASSWORD)
        }
        store.write(KEY_REMEMBER_PASSWORD, if (rememberPassword.isChecked) "1" else "0")
        store.write(KEY_AUTO_CAPTCHA, if (autoCaptcha.isChecked) "1" else "0")
        password.setText("")
        captchaInput.setText("")
        manualFallback = false
        showCardView()
        refreshCode()
    }

    private fun onLoginFailed(e: Exception, silent: Boolean) {
        setLoginBusy(false)
        if (silent) {
            // The stored credentials may simply be wrong now; let the user fix them.
            prepareLogin(null, getString(R.string.error_auto_login_failed, describe(e)))
        } else {
            showLoginError(describe(e))
        }
    }

    private fun captchaFallbackMessage(reason: LoginOutcome.ManualCaptchaRequired.Reason): Int =
        when (reason) {
            LoginOutcome.ManualCaptchaRequired.Reason.MODEL_UNAVAILABLE ->
                R.string.error_captcha_model_unavailable
            LoginOutcome.ManualCaptchaRequired.Reason.RECOGNITION_FAILED ->
                R.string.error_captcha_auto_failed
            LoginOutcome.ManualCaptchaRequired.Reason.REJECTED ->
                R.string.error_captcha_rejected
        }

    private fun describe(e: Exception): String = when ((e as? CampusCardException)?.kind) {
        CampusCardException.Kind.CAPTCHA -> getString(R.string.error_captcha)
        CampusCardException.Kind.CREDENTIALS -> getString(R.string.error_credentials)
        CampusCardException.Kind.NETWORK -> getString(R.string.error_network)
        else -> getString(R.string.error_generic, e.message ?: e.javaClass.simpleName)
    }

    // ------------------------------------------------------------------ captcha

    /** Fetch a captcha image and show it, for manual reading. */
    private fun loadCaptchaOnly() {
        manualFallback = true
        loginButton.text = getString(R.string.login_button)
        io.execute {
            try {
                val bytes = session.fetchCaptcha()
                ui.post { renderCaptcha(bytes) }
            } catch (e: Exception) {
                ui.post { showLoginError(describe(e)) }
            }
        }
    }

    private fun renderCaptcha(bytes: ByteArray) {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { captchaImage.setImageBitmap(it) }
    }

    private fun forgetSession() {
        stopAutoRefresh()
        store.remove(KEY_SESSION)
        store.remove(KEY_PASSWORD)
        session.restoreCookie(null)
        currentPayload = null
        qrImage.setImageDrawable(null)
        qrNumber.text = ""
        cardMeta.text = ""
        qrExpiry.text = ""
        prepareLogin(null, null)
    }

    // ------------------------------------------------------------------ QR code

    private fun refreshCode() {
        if (!session.isAuthenticated) {
            prepareLogin(null, null)
            return
        }
        showQrBusy(true)
        io.execute {
            var qr: CardQr? = null
            var failure: Exception? = null
            var fallback: LoginOutcome.ManualCaptchaRequired? = null
            try {
                qr = session.fetchQrCode()
            } catch (e: SessionExpiredException) {
                // The stored session lapsed. Rebuild it silently when credentials are stored;
                // this is the case the on-device captcha model exists for.
                when (val outcome = silentRelogin()) {
                    is LoginOutcome.Success -> {
                        store.write(KEY_SESSION, session.currentCookie())
                        try {
                            qr = session.fetchQrCode()
                        } catch (retry: Exception) {
                            failure = retry
                        }
                    }
                    is LoginOutcome.ManualCaptchaRequired -> {
                        failure = e
                        fallback = outcome
                    }
                    null -> failure = e
                }
            } catch (e: Exception) {
                failure = e
            }

            val code = qr
            val bitmap = code?.let { QrRenderer.render(it.payload, 720) }
            val balance = if (code != null) runCatching { session.fetchBalance() }.getOrNull() else null
            val expired = failure
            ui.post {
                showQrBusy(false)
                if (code != null && bitmap != null) {
                    renderCode(code, bitmap, balance)
                    startAutoRefresh()
                } else if (expired is SessionExpiredException) {
                    store.remove(KEY_SESSION)
                    prepareLogin(fallback, getString(R.string.error_session_expired))
                } else {
                    qrNumber.text = getString(R.string.error_generic, expired?.message ?: "取码失败")
                    startAutoRefresh()
                }
            }
        }
    }

    /**
     * Sign in again with the stored credentials, on the IO thread. Returns null when nothing
     * is stored, i.e. when the user really does have to log in by hand.
     */
    private fun silentRelogin(): LoginOutcome? {
        val user = store.read(KEY_LAST_USER)?.takeIf { it.isNotBlank() } ?: return null
        val pass = store.read(KEY_PASSWORD)?.takeIf { it.isNotBlank() } ?: return null
        return try {
            autoLogin().login(user, pass)
        } catch (e: Exception) {
            Log.w(TAG, "静默重登失败: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun renderCode(qr: CardQr, bitmap: android.graphics.Bitmap, balance: Double?) {
        currentPayload = qr.payload
        qrImage.setImageBitmap(bitmap)
        qrImage.visibility = View.VISIBLE
        qrProgress.visibility = View.GONE
        // The official page shows the 20-digit part under the code.
        qrNumber.text = qr.numericCode
        cardMeta.text = buildString {
            append(getString(R.string.card_balance)).append(": ")
            append(if (balance != null) String.format("%.2f 元", balance) else "—")
            append("   ").append(getString(R.string.card_issued)).append(": ").append(qr.issuedAt)
        }
        qrExpiry.text = getString(R.string.card_auto_refresh)
    }

    // -------------------------------------------------------------- auto refresh

    private fun startAutoRefresh() {
        stopAutoRefresh()
        val task = Runnable {
            if (!isFinishing && session.isAuthenticated) {
                refreshCode()
                autoRefresh?.let { ui.postDelayed(it, REFRESH_INTERVAL_MS) }
            }
        }
        autoRefresh = task
        ui.postDelayed(task, REFRESH_INTERVAL_MS)
    }

    private fun stopAutoRefresh() {
        autoRefresh?.let { ui.removeCallbacks(it) }
        autoRefresh = null
    }

    // --------------------------------------------------------------------- view

    private fun setLoginBusy(busy: Boolean, label: String? = null) {
        loginButton.isEnabled = !busy
        loginButton.text = if (busy) {
            label ?: getString(R.string.login_loading)
        } else {
            getString(R.string.login_button)
        }
        loginProgress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun showQrBusy(busy: Boolean) {
        qrProgress.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) qrImage.visibility = View.INVISIBLE
    }

    private fun showLoginError(message: String) {
        loginError.text = message
        loginError.visibility = View.VISIBLE
    }

    private fun hideLoginError() {
        loginError.visibility = View.GONE
    }

    private companion object {
        const val TAG = "MainActivity"

        /** The portal's own refresh cadence for the rotating token. */
        const val REFRESH_INTERVAL_MS = 60_000L
        const val KEY_SESSION = "adwmh_session"
        const val KEY_LAST_USER = "last_user"
        const val KEY_PASSWORD = "account_password"
        const val KEY_REMEMBER_PASSWORD = "remember_password"
        const val KEY_AUTO_CAPTCHA = "auto_captcha"
    }
}
