package cn.edu.ahu.cardqr

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import cn.edu.ahu.cardqr.data.CampusCardException
import cn.edu.ahu.cardqr.data.CampusCardSession
import cn.edu.ahu.cardqr.data.CardQr
import cn.edu.ahu.cardqr.data.SessionExpiredException
import cn.edu.ahu.cardqr.qr.QrRenderer
import cn.edu.ahu.cardqr.store.SecureStore
import java.util.concurrent.Executors

/**
 * Two states: a one-time credential + captcha binding, and the campus-card QR code.
 *
 * The important behaviour is on launch: if a session was stored previously, the code is
 * fetched and displayed immediately with no interaction at all. Only when the server rejects
 * the stored session does the app fall back to asking for credentials and a captcha.
 */
class MainActivity : Activity() {

    private lateinit var store: SecureStore
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var loginView: View
    private lateinit var cardView: View
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var captchaInput: EditText
    private lateinit var captchaImage: ImageView
    private lateinit var remember: CheckBox
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = SecureStore(this)

        loginView = findViewById(R.id.loginView)
        cardView = findViewById(R.id.cardView)
        username = findViewById(R.id.username)
        password = findViewById(R.id.password)
        captchaInput = findViewById(R.id.captchaInput)
        captchaImage = findViewById(R.id.captchaImage)
        remember = findViewById(R.id.remember)
        loginButton = findViewById(R.id.loginButton)
        loginProgress = findViewById(R.id.loginProgress)
        loginError = findViewById(R.id.loginError)
        qrImage = findViewById(R.id.qrImage)
        qrNumber = findViewById(R.id.qrNumber)
        qrExpiry = findViewById(R.id.qrExpiry)
        qrProgress = findViewById(R.id.qrProgress)
        cardMeta = findViewById(R.id.cardMeta)

        username.setText(store.read(KEY_LAST_USER) ?: "")
        remember.isChecked = store.read(KEY_REMEMBER) != "0"

        loginButton.setOnClickListener { doLogin() }
        captchaImage.setOnClickListener { loadCaptcha() }
        findViewById<Button>(R.id.refreshButton).setOnClickListener { refreshCode() }
        findViewById<Button>(R.id.logoutButton).setOnClickListener { forgetSession() }

        // Restore the session and go straight to the code; no captcha, no typing.
        session.restoreCookie(store.read(KEY_SESSION))
        if (session.isAuthenticated) {
            showCardView()
            refreshCode()
        } else {
            prepareLogin()
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

    // ---------------------------------------------------------------- login flow

    /** Show the login form and fetch a captcha for the user to read. */
    private fun prepareLogin() {
        loginView.visibility = View.VISIBLE
        cardView.visibility = View.GONE
        setLoginBusy(false)
        loadCaptcha()
    }

    private fun loadCaptcha() {
        io.execute {
            try {
                session.openSession()
                val bytes = session.fetchCaptcha()
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ui.post { bitmap?.let { captchaImage.setImageBitmap(it) } }
            } catch (e: Exception) {
                ui.post { showLoginError(getString(R.string.error_captcha, e.message ?: "")) }
            }
        }
    }

    private fun doLogin() {
        val user = username.text.toString().trim()
        val pass = password.text.toString()
        val captcha = captchaInput.text.toString().trim()
        if (user.isEmpty() || pass.isEmpty()) {
            showLoginError(getString(R.string.error_credentials))
            return
        }
        if (captcha.length != 4) {
            showLoginError(getString(R.string.error_captcha, "请输入 4 位验证码"))
            return
        }

        setLoginBusy(true)
        hideLoginError()
        io.execute {
            try {
                session.login(user, pass, captcha)
                // Persist so the next launch needs nothing at all.
                store.write(KEY_SESSION, session.currentCookie())
                store.write(KEY_LAST_USER, user)
                store.write(KEY_REMEMBER, if (remember.isChecked) "1" else "0")
                ui.post {
                    setLoginBusy(false)
                    captchaInput.setText("")
                    showCardView()
                    refreshCode()
                }
            } catch (e: Exception) {
                ui.post {
                    setLoginBusy(false)
                    val msg = when ((e as? CampusCardException)?.kind) {
                        CampusCardException.Kind.CAPTCHA -> getString(R.string.error_captcha, e.message ?: "")
                        CampusCardException.Kind.CREDENTIALS -> getString(R.string.error_credentials)
                        CampusCardException.Kind.NETWORK -> getString(R.string.error_network)
                        else -> getString(R.string.error_generic, e.message ?: "")
                    }
                    showLoginError(msg)
                    // A wrong captcha invalidates the session: get a new one.
                    loadCaptcha()
                }
            }
        }
    }

    private fun forgetSession() {
        stopAutoRefresh()
        store.remove(KEY_SESSION)
        session.restoreCookie(null)
        currentPayload = null
        qrImage.setImageDrawable(null)
        qrNumber.text = ""
        cardMeta.text = ""
        qrExpiry.text = ""
        prepareLogin()
    }

    // ------------------------------------------------------------------ QR code

    private fun refreshCode() {
        if (!session.isAuthenticated) {
            prepareLogin()
            return
        }
        showQrBusy(true)
        io.execute {
            try {
                val qr = session.fetchQrCode()
                val balance = runCatching { session.fetchBalance() }.getOrNull()
                val bitmap = QrRenderer.render(qr.payload, 720)
                ui.post {
                    showQrBusy(false)
                    renderCode(qr, bitmap, balance)
                }
                startAutoRefresh()
            } catch (e: SessionExpiredException) {
                ui.post {
                    store.remove(KEY_SESSION)
                    showQrBusy(false)
                    showLoginError(getString(R.string.error_session_expired))
                    prepareLogin()
                }
            } catch (e: Exception) {
                ui.post {
                    showQrBusy(false)
                    qrNumber.text = getString(R.string.error_generic, e.message ?: "取码失败")
                }
                startAutoRefresh()
            }
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

    private fun showCardView() {
        loginView.visibility = View.GONE
        cardView.visibility = View.VISIBLE
        setLoginBusy(false)
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

    private fun setLoginBusy(busy: Boolean) {
        loginButton.isEnabled = !busy
        loginButton.text = if (busy) getString(R.string.login_loading) else getString(R.string.login_button)
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
        /** The portal's own refresh cadence for the rotating token. */
        const val REFRESH_INTERVAL_MS = 60_000L
        const val KEY_SESSION = "adwmh_session"
        const val KEY_LAST_USER = "last_user"
        const val KEY_REMEMBER = "remember"
    }
}
