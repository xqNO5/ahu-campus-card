package cn.edu.ahu.cardqr.data

import cn.edu.ahu.cardqr.net.HttpClient
import org.json.JSONObject

/** Thrown when the stored session is no longer accepted, so the UI must log in again. */
class SessionExpiredException : Exception("登录会话已过期，需要重新登录")

class CampusCardException(message: String, val kind: Kind = Kind.GENERIC) : Exception(message) {
    enum class Kind { NETWORK, CAPTCHA, CREDENTIALS, GENERIC }
}

/** The QR payload the official 一码通 page renders, plus its server timestamp. */
data class CardQr(
    /** Full value to encode: a 20-digit code followed by a server-issued token. */
    val payload: String,
    /** Server-supplied timestamp string (also the cache-buster). */
    val issuedAt: String,
) {
    /** The leading digit run, which the official page also draws as a CODE128 barcode. */
    val numericCode: String get() = payload.takeWhile { it.isDigit() }
    val tokenLength: Int get() = payload.length - numericCode.length
}

/**
 * Talks to the portal that actually mints the campus-card QR code.
 *
 * `adwmh.ahu.edu.cn` is reachable directly from the internet -- no WebVPN and no WeChat --
 * and its flow is:
 *
 *  1. `POST /user/session`      -> a `JSESSIONID` cookie (anonymous)
 *  2. `GET  /remind/authcode`   -> a 4-character captcha image
 *  3. `POST /user/login`        -> binds the account to that session
 *  4. `GET  /xzxcard/qrcode`    -> the full payload (20 digits + token)
 *     `GET  /xzxcard/yue`       -> balance
 *
 * Step 3 only has to happen once: the `JSESSIONID` is stored by the caller and the code can
 * then be fetched without any further interaction, which is what makes "open and scan"
 * possible.
 *
 * Steps 2 and 3 are what [AutoLogin] drives: it solves the captcha on-device so a session that
 * lapsed can be re-established without the user ever seeing the login form.
 */
class CampusCardSession(
    private val http: HttpClient = HttpClient(),
    baseUrl: String = DEFAULT_BASE,
) : LoginApi {

    private val base = baseUrl.trimEnd('/')
    private var cookie: String? = null

    val isAuthenticated: Boolean get() = !cookie.isNullOrEmpty()

    /** Restore a previously saved `JSESSIONID`. */
    fun restoreCookie(value: String?) {
        cookie = value?.takeIf { it.isNotBlank() }
    }

    fun currentCookie(): String? = cookie

    private fun headers(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val h = mutableMapOf(
            "x-requested-with" to "XMLHttpRequest",
            "Accept" to "application/json, text/plain, */*",
        )
        cookie?.let { h["Cookie"] = it }
        h.putAll(extra)
        return h
    }

    private fun absorbCookies(response: cn.edu.ahu.cardqr.net.HttpResponse) {
        absorbCookies(response.setCookies)
    }

    private fun absorbCookies(rawCookies: List<String>) {
        for (raw in rawCookies) {
            val pair = raw.substringBefore(';')
            if (pair.startsWith("JSESSIONID=")) cookie = pair
        }
    }

    private fun getJson(path: String): JSONObject {
        val r = http.request("GET", base + path, null, headers(), false)
        absorbCookies(r)
        if (r.status in 300..399) throw SessionExpiredException()
        if (r.status != 200) throw CampusCardException("HTTP ${r.status}", CampusCardException.Kind.NETWORK)
        return try {
            JSONObject(r.body)
        } catch (_: Exception) {
            throw CampusCardException("服务端返回了非 JSON 内容")
        }
    }

    // -------------------------------------------------------------- 1. session

    /** Create the anonymous session that the captcha and login calls both need. */
    override fun openSession() {
        val r = http.request("POST", "$base/user/session", null, headers(), false)
        absorbCookies(r)
    }

    // -------------------------------------------------------------- 2. captcha

    /** Fetch a fresh 4-character captcha image (JPEG bytes). */
    override fun fetchCaptcha(): ByteArray {
        if (cookie.isNullOrEmpty()) openSession()
        val r = http.getBytes(
            "$base/remind/authcode",
            headers(mapOf("Accept" to "image/*,*/*;q=0.8")),
        )
        absorbCookies(r.setCookies)
        if (r.bytes.size < 64) {
            throw CampusCardException("验证码获取失败", CampusCardException.Kind.CAPTCHA)
        }
        return r.bytes
    }

    // ---------------------------------------------------------------- 3. login

    /**
     * Bind [username]/[password] to the current session.
     *
     * [captcha] is normally read by [AutoLogin] from the local model; it is only typed by the
     * user when recognition is unavailable or keeps being rejected.
     */
    override fun login(username: String, password: String, captcha: String) {
        if (cookie.isNullOrEmpty()) openSession()
        val form = "username=" + enc(username) + "&pwd=" + enc(password) +
            "&flag=0&imgcode=" + enc(captcha)
        val r = http.request(
            "POST",
            "$base/user/login",
            form.toByteArray(Charsets.UTF_8),
            headers(mapOf("Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8")),
            false,
        )
        absorbCookies(r)
        val json = parseOrThrow(r)
        when (json.optInt("code", -1)) {
            10000 -> Unit
            10012 -> throw CampusCardException(
                json.optString("msg").ifEmpty { "图形验证码错误" },
                CampusCardException.Kind.CAPTCHA,
            )
            else -> {
                val msg = json.optString("msg").ifEmpty { "登录失败" }
                val kind = if (msg.contains("密码") || msg.contains("账号")) {
                    CampusCardException.Kind.CREDENTIALS
                } else {
                    CampusCardException.Kind.GENERIC
                }
                throw CampusCardException(msg, kind)
            }
        }
    }

    private fun parseOrThrow(r: cn.edu.ahu.cardqr.net.HttpResponse): JSONObject = try {
        JSONObject(r.body)
    } catch (_: Exception) {
        throw CampusCardException("登录响应异常 (HTTP ${r.status})")
    }

    // ------------------------------------------------------------- 4. the code

    /** The full QR payload. Throws [SessionExpiredException] when the session lapsed. */
    fun fetchQrCode(): CardQr {
        // The official page sends a cache-busting timestamp query parameter.
        val stamp = System.currentTimeMillis()
        val json = getJson("/xzxcard/qrcode?t=$stamp")
        if (json.optInt("code", -1) != 10000) {
            throw CampusCardException(json.optString("msg").ifEmpty { "取码失败" })
        }
        val payload = json.optString("object")
        if (payload.isBlank()) throw CampusCardException("服务端未返回二维码内容")
        return CardQr(payload, json.optString("msg"))
    }

    /** Remaining balance, or null when unavailable. */
    fun fetchBalance(): Double? = try {
        val json = getJson("/xzxcard/yue")
        if (json.optInt("code", -1) == 10000) json.optDouble("object", Double.NaN)
            .takeIf { !it.isNaN() } else null
    } catch (_: SessionExpiredException) {
        throw SessionExpiredException()
    } catch (_: Exception) {
        null
    }

    /** Cheap validity probe used on launch before showing the code screen. */
    fun hasValidSession(): Boolean {
        if (cookie.isNullOrEmpty()) return false
        return try {
            fetchQrCode()
            true
        } catch (_: SessionExpiredException) {
            false
        } catch (_: Exception) {
            // Network hiccups should not force a re-login.
            true
        }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    companion object {
        const val DEFAULT_BASE = "https://adwmh.ahu.edu.cn"
    }
}
