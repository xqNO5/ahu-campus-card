package cn.edu.ahu.cardqr

import android.app.Activity
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
import cn.edu.ahu.cardqr.data.AhuClient
import cn.edu.ahu.cardqr.data.AhuException
import cn.edu.ahu.cardqr.data.CampusCard
import cn.edu.ahu.cardqr.data.CredentialStore
import cn.edu.ahu.cardqr.data.ManualPayload
import cn.edu.ahu.cardqr.data.PayCode
import cn.edu.ahu.cardqr.data.SecureStore
import cn.edu.ahu.cardqr.qr.QrRenderer
import java.util.concurrent.Executors

/**
 * Single screen with two states: credential entry, and the campus-card QR code.
 *
 * All network work runs on a small executor and results are posted back to the main
 * thread; the activity never blocks the UI thread on I/O.
 */
class MainActivity : Activity() {

    private lateinit var store: CredentialStore
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var loginView: View
    private lateinit var cardView: View
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var remember: CheckBox
    private lateinit var loginButton: Button
    private lateinit var loginProgress: ProgressBar
    private lateinit var loginError: TextView
    private lateinit var qrImage: ImageView
    private lateinit var qrNumber: TextView
    private lateinit var qrExpiry: TextView
    private lateinit var qrProgress: ProgressBar
    private lateinit var cardMeta: TextView
    private lateinit var manualPayload: EditText
    private lateinit var manualStatus: TextView

    private var client: AhuClient? = null
    private var currentCard: CampusCard? = null
    private var autoRefresh: Runnable? = null

    /** When set, this pasted payload is rendered instead of polling the server. */
    private var manualValue: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = SecureStore(this)

        loginView = findViewById(R.id.loginView)
        cardView = findViewById(R.id.cardView)
        username = findViewById(R.id.username)
        password = findViewById(R.id.password)
        remember = findViewById(R.id.remember)
        loginButton = findViewById(R.id.loginButton)
        loginProgress = findViewById(R.id.loginProgress)
        loginError = findViewById(R.id.loginError)
        qrImage = findViewById(R.id.qrImage)
        qrNumber = findViewById(R.id.qrNumber)
        qrExpiry = findViewById(R.id.qrExpiry)
        qrProgress = findViewById(R.id.qrProgress)
        cardMeta = findViewById(R.id.cardMeta)
        manualPayload = findViewById(R.id.manualPayload)
        manualStatus = findViewById(R.id.manualStatus)

        username.setText(store.lastUsername() ?: "")
        loginButton.setOnClickListener { startLogin() }
        findViewById<Button>(R.id.refreshButton).setOnClickListener { loadPayCode() }
        findViewById<Button>(R.id.logoutButton).setOnClickListener { logout() }
        findViewById<Button>(R.id.manualButton).setOnClickListener { applyManualPayload() }
        findViewById<Button>(R.id.manualClearButton).setOnClickListener { clearManualPayload() }

        // A stored credential set lets returning users go straight to their code.
        store.load()?.let {
            username.setText(it.username)
            password.setText(it.password)
            startLogin()
        }
    }

    override fun onResume() {
        super.onResume()
        // The pay code rotates, so refresh whenever the screen comes back to the foreground.
        if (client != null && currentCard != null) loadPayCode()
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

    // ------------------------------------------------------------- auto refresh

    /**
     * Re-fetch the code periodically so the displayed value stays current while the screen
     * is visible. [REFRESH_INTERVAL_MS] is deliberately well under the server's shortest
     * advertised validity so an expired code is never shown.
     */
    private fun startAutoRefresh() {
        stopAutoRefresh()
        val task = Runnable {
            if (!isFinishing && client != null) {
                loadPayCode()
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

    // --------------------------------------------------------------------- login

    private fun startLogin() {
        val user = username.text.toString().trim()
        val pass = password.text.toString()
        if (user.isEmpty() || pass.isEmpty()) {
            showLoginError(getString(R.string.error_credentials))
            return
        }
        setLoginBusy(true)
        hideLoginError()

        io.execute {
            try {
                val c = AhuClient(user, pass)
                c.login()
                c.cardToken()
                client = c
                if (remember.isChecked) store.save(user, pass) else store.clear()
                store.rememberUsername(user)
                ui.post { showCardView() }
                loadPayCode()
            } catch (e: Exception) {
                val kind = (e as? AhuException)?.kind
                ui.post {
                    setLoginBusy(false)
                    showLoginError(
                        when (kind) {
                            AhuException.Kind.CREDENTIALS -> getString(R.string.error_credentials)
                            AhuException.Kind.NETWORK -> getString(R.string.error_network)
                            else -> getString(R.string.error_generic, e.message ?: e.javaClass.simpleName)
                        }
                    )
                }
            }
        }
    }

    private fun logout() {
        stopAutoRefresh()
        store.clear()
        client = null
        currentCard = null
        qrImage.setImageDrawable(null)
        qrNumber.text = ""
        qrExpiry.text = ""
        password.setText("")
        cardView.visibility = View.GONE
        loginView.visibility = View.VISIBLE
        setLoginBusy(false)
    }

    // ----------------------------------------------------------------- pay code

    private fun loadPayCode() {
        val c = client ?: return

        // A pasted payload takes precedence and is rendered verbatim.
        manualValue?.let { renderValue(it, null) ; return }

        showQrBusy(true)
        io.execute {
            try {
                val (card, code) = c.fetchFirstPayCode()
                currentCard = card
                val bitmap = QrRenderer.render(code.value, 720)
                ui.post {
                    qrImage.setImageBitmap(bitmap)
                    qrImage.visibility = View.VISIBLE
                    qrProgress.visibility = View.GONE
                    qrNumber.text = code.value
                    cardMeta.text = buildString {
                        append(getString(R.string.card_account)).append(": ").append(card.account)
                        append("   ").append(getString(R.string.card_balance)).append(": ")
                        append(String.format("%.2f", card.balanceFen / 100.0)).append(" 元")
                    }
                    qrExpiry.text = getString(R.string.card_expires_in) + ": " + describe(code)
                }
                startAutoRefresh()
            } catch (e: Exception) {
                ui.post {
                    showQrBusy(false)
                    qrNumber.text = getString(R.string.error_generic, e.message ?: "取码失败")
                }
                // Keep retrying on the normal cadence rather than giving up.
                startAutoRefresh()
            }
        }
    }

    private fun describe(code: PayCode): String =
        if (code.expiresSeconds >= 3600) "${code.expiresSeconds / 3600} 小时"
        else "${code.expiresSeconds} 秒"

    // ------------------------------------------------------- manual payload

    /**
     * Render a payload the user pasted from the official page.
     *
     * The official 一码通 draws a value this client cannot mint (a 20-digit code plus a
     * server-issued token), so a pasted copy is the only way to display that exact code.
     */
    private fun applyManualPayload() {
        val raw = manualPayload.text.toString()
        val check = ManualPayload.inspect(raw)
        if (!check.valid) {
            manualStatus.visibility = View.VISIBLE
            manualStatus.text = getString(R.string.manual_invalid, check.problem ?: "未知原因")
            return
        }
        manualValue = check.value
        stopAutoRefresh()
        renderValue(check.value, null)
        manualStatus.visibility = View.VISIBLE
        manualStatus.text = getString(R.string.manual_active, check.value.length)
    }

    private fun clearManualPayload() {
        manualValue = null
        manualPayload.setText("")
        manualStatus.visibility = View.VISIBLE
        manualStatus.text = getString(R.string.manual_auto)
        loadPayCode()
    }

    /**
     * Draw [value] as a QR code. [code] supplies the expiry footer when the value came from
     * the API; a pasted payload has no server-provided lifetime.
     */
    private fun renderValue(value: String, code: PayCode?) {
        val bitmap = QrRenderer.render(value, 720)
        qrImage.setImageBitmap(bitmap)
        qrImage.visibility = View.VISIBLE
        qrProgress.visibility = View.GONE
        qrNumber.text = value
        if (code != null) {
            qrExpiry.text = getString(R.string.card_expires_in) + ": " + describe(code)
        } else {
            qrExpiry.text = getString(R.string.manual_active, value.length)
        }
    }

    // --------------------------------------------------------------------- view

    private fun showCardView() {
        loginView.visibility = View.GONE
        cardView.visibility = View.VISIBLE
        setLoginBusy(false)
    }

    private fun setLoginBusy(busy: Boolean) {
        loginButton.isEnabled = !busy
        loginButton.text = if (busy) getString(R.string.login_loading) else getString(R.string.login_button)
        loginProgress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun showQrBusy(busy: Boolean) {
        qrProgress.visibility = if (busy) View.VISIBLE else View.GONE
        qrImage.visibility = if (busy) View.INVISIBLE else View.VISIBLE
    }

    private fun showLoginError(message: String) {
        loginError.text = message
        loginError.visibility = View.VISIBLE
    }

    private fun hideLoginError() {
        loginError.visibility = View.GONE
    }

    private companion object {
        /** Comfortably shorter than the server's advertised code validity. */
        const val REFRESH_INTERVAL_MS = 30_000L
    }
}
