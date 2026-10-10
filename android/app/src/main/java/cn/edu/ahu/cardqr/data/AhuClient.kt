package cn.edu.ahu.cardqr.data

import cn.edu.ahu.cardqr.crypto.CasDes
import cn.edu.ahu.cardqr.crypto.WebVpnHostCrypt
import cn.edu.ahu.cardqr.net.HttpClient
import cn.edu.ahu.cardqr.net.HttpResponse
import org.json.JSONObject
import java.net.URLDecoder

class AhuException(message: String, val kind: Kind = Kind.GENERIC) : Exception(message) {
    enum class Kind { CREDENTIALS, NETWORK, NO_CARD, GENERIC }
}

/** One campus card plus its electronic accounts. */
data class CampusCard(
    val account: String,
    val name: String,
    /** Balance in fen (the API reports cents). */
    val balanceFen: Long,
)

/** The rotating payment code that gets rendered as a QR image. */
data class PayCode(
    val value: String,
    val expiresSeconds: Int,
    val account: String,
)

/**
 * Accepts a payload the user pasted from the official page.
 *
 * The official 一码通 renderer draws the *whole* value returned by its code endpoint: a
 * 20-digit code followed by a server-issued token (observed 20 + 94 characters, which is
 * QR version 6 at EC level L). This client can only mint the 20-digit half, so a pasted
 * full payload is passed through unchanged and validated for shape.
 */
object ManualPayload {

    /** Result of inspecting a pasted value. */
    data class Inspection(
        val value: String,
        val digitsPrefix: Int,
        val tokenLength: Int,
        val valid: Boolean,
        val problem: String? = null,
    )

    private val ALLOWED = Regex("^[0-9A-Za-z+/=_\\-]+$")

    fun inspect(raw: String): Inspection {
        val value = raw.trim().replace("\\s".toRegex(), "")
        val digits = value.takeWhile { it.isDigit() }.length
        val token = value.length - digits
        if (value.isEmpty()) {
            return Inspection(value, 0, 0, false, "内容为空")
        }
        if (!ALLOWED.matches(value)) {
            return Inspection(value, digits, token, false, "包含非法字符（应为数字 + base64 字符）")
        }
        if (digits < 10) {
            return Inspection(value, digits, token, false, "前缀数字不足 10 位，可能不是校园卡取码内容")
        }
        return Inspection(value, digits, token, true)
    }
}

/**
 * Whole client for the AHU campus card.
 *
 * The chain, verified end to end against the live service:
 *
 *  1. `POST /cas/device` with a 3DES blob, then `POST /cas/login`, then follow the ticket
 *     through `/login?cas_login=true` -- this authenticates the WebVPN tunnel.
 *  2. `GET ycard.ahu.edu.cn/berserker-auth/cas/login/neusoftCas?targetUrl=...` reuses the
 *     CAS session and hands back a `synjones-auth` JWT with no WeChat involvement.
 *  3. `GET /berserker-app/ykt/tsm/queryCard` and
 *     `GET /berserker-app/ykt/tsm/batchGetBarCodeGet` return the card and its pay codes.
 *
 * Every upstream request goes through the WebVPN tunnel, so URLs are rewritten with
 * [WebVpnHostCrypt.tunnel] and the `wengine_vpn_ticket` cookie does the routing.
 */
class AhuClient(
    private val username: String,
    private val password: String,
    private val vpnHost: String = "wvpn.ahu.edu.cn",
    private val http: HttpClient = HttpClient(),
) {

    private var synjonesToken: String? = null
    private val root get() = "https://$vpnHost"

    // ------------------------------------------------------------------ 1. WebVPN

    fun login() {
        http.userAgent = HttpClient.WECHAT_UA
        // /login answers with a 302 into the CAS form, so follow it.
        val page = http.getFollowing("$root/login", ::tunnel)
        if (page.status != 200) throw AhuException("无法打开登录页 (HTTP ${page.status})", AhuException.Kind.NETWORK)
        val body = page.body

        val action = findLoginAction(page.body)
            ?: throw AhuException("登录页结构变化：未找到 CAS 表单")
        val hidden = hiddenFields(page.body)
        val lt = hidden["lt"] ?: throw AhuException("登录页未返回 lt 令牌")

        val casBase = action.substringBefore('?').substringBeforeLast('/')
        val blob = CasDes.strEnc(username + password + lt, "1", "2", "3")

        val device = http.postForm(
            "$root$casBase/device",
            mapOf(
                "ul" to username.length.toString(),
                "pl" to password.length.toString(),
                "rsa" to blob,
                "method" to "login",
            ),
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "$root/login",
                "Origin" to root,
            ),
        )
        val verdict = device.body.trim()
        if (!verdict.contains("\"ok\"")) {
            when {
                verdict.contains("nf") || verdict.contains("err") ->
                    throw AhuException("学号或密码错误", AhuException.Kind.CREDENTIALS)
                verdict.contains("unbind") ->
                    throw AhuException("该账号需要先绑定手机号才能登录")
                else ->
                    throw AhuException("登录校验失败：${verdict.take(120)}")
            }
        }

        val form = HashMap(hidden)
        form["un"] = username
        form["pd"] = password
        form["ul"] = username.length.toString()
        form["pl"] = password.length.toString()
        form["rsa"] = blob
        form.putIfAbsent("_eventId", "submit")
        form.putIfAbsent("execution", "e1s1")

        val submit = http.postForm(
            "$root$action",
            form,
            headers = mapOf("Referer" to "$root/login", "Origin" to root),
        )
        val ticketUrl = submit.location
            ?: throw AhuException("CAS 未签发票据 (HTTP ${submit.status})")

        // A brand-new ticket can be refused for a moment, so retry the exchange.
        var last: HttpResponse? = null
        for (attempt in 0 until 4) {
            last = http.get(ticketUrl, followRedirects = true)
            if (last.status == 200 && !last.body.contains("非法")) return
            Thread.sleep(700L * (attempt + 1))
        }
        throw AhuException(
            "票据换取失败 (HTTP ${last?.status}); 连续登录过于频繁时会触发，请稍后重试",
            AhuException.Kind.NETWORK,
        )
    }

    // ------------------------------------------------------------- 2. card SSO

    /** Fetch (and cache) the `synjones-auth` JWT via the school CAS, no WeChat needed. */
    fun cardToken(forceRefresh: Boolean = false): String {
        synjonesToken?.let { if (!forceRefresh) return it }

        val target = "https%3A%2F%2Fycard.ahu.edu.cn%2Fberserker-base%2Fredirect" +
            "%3Ftype%3Durl%26url%3Dhttps%253A%252F%252Fycard.ahu.edu.cn%252Fplat"
        var next = "$root/https/${WebVpnHostCrypt.hostHash("ycard.ahu.edu.cn")}" +
            "/berserker-auth/cas/login/neusoftCas?targetUrl=$target"

        for (hop in 0 until 12) {
            // Inspect each hop's Location rather than following it, because the token
            // arrives as a query parameter on a Location header.
            val r = http.get(next)
            val location = r.location ?: break
            val absolute = if (location.startsWith("http")) location else root + location
            extractSynjonesAuth(absolute)?.let {
                synjonesToken = it
                return it
            }
            next = retunnel(absolute)
        }
        throw AhuException("未能取得校园卡登录令牌（SSO 链路中断）")
    }

    // ------------------------------------------------------ 3. card and pay code

    private fun cardApiGet(path: String, params: Map<String, String> = emptyMap()): JSONObject {
        val token = cardToken()
        val query = (params + mapOf("synAccessSource" to AGENT_TYPE))
            .entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
        val url = tunnel("https://ycard.ahu.edu.cn$path?$query")
        val r = http.get(
            url,
            headers = mapOf(
                "synjones-auth" to "bearer $token",
                "synAccessSource" to AGENT_TYPE,
                "Referer" to tunnel("https://ycard.ahu.edu.cn/plat/"),
            ),
        )
        if (r.status != 200) throw AhuException("校园卡接口返回 HTTP ${r.status}")
        return try {
            JSONObject(r.body)
        } catch (e: Exception) {
            throw AhuException("校园卡接口返回了非 JSON 内容")
        }
    }

    fun queryCards(): List<CampusCard> {
        val json = cardApiGet("/berserker-app/ykt/tsm/queryCard")
        val data = json.optJSONObject("data") ?: return emptyList()
        val cards = data.optJSONArray("card") ?: return emptyList()
        val out = ArrayList<CampusCard>()
        for (i in 0 until cards.length()) {
            val c = cards.optJSONObject(i) ?: continue
            val account = c.optString("account")
            if (account.isEmpty()) continue

            // The portal shows (db_balance + unsettle_amount) / 100, but for accounts whose
            // balance lives in the electronic sub-accounts that total is 0 -- so fall back
            // to summing `accinfo` (which is where the real money sits).
            val cardFen = c.optLong("db_balance", 0L) + c.optLong("unsettle_amount", 0L)
            val balanceFen = if (cardFen != 0L) cardFen else sumAccInfoFen(c)

            out.add(CampusCard(account, c.optString("cardname").ifEmpty { "校园卡" }, balanceFen))
        }
        return out
    }

    private fun sumAccInfoFen(card: JSONObject): Long {
        val accInfo = card.optJSONArray("accinfo") ?: return 0L
        var total = 0L
        for (i in 0 until accInfo.length()) {
            val acc = accInfo.optJSONObject(i) ?: continue
            total += acc.optLong("balance", 0L)
        }
        return total
    }

    /**
     * Fetch the rotating payment code. [payAccount] is the card's account number, which the
     * portal itself passes as both `account` and `payacc` with `paytype=1`.
     *
     * Note on payload shape: the official 一码通 page renders a *longer* value than the one
     * this endpoint returns -- a 20-digit code followed by a server-issued base64 token
     * (observed: 20 digits + 96 chars, QR version 6 / EC level L). That token comes from a
     * different, captcha-gated endpoint and cannot be minted here, so this client renders
     * whatever the code endpoint returns and additionally accepts a pasted full payload
     * (see [cn.edu.ahu.cardqr.data.ManualPayload]).
     */
    fun fetchPayCode(payAccount: String, codeType: String? = null): PayCode {
        val params = mutableMapOf(
            "account" to payAccount,
            "payacc" to payAccount,
            "paytype" to "1",
        )
        if (!codeType.isNullOrEmpty()) params["codeType"] = codeType
        val json = cardApiGet("/berserker-app/ykt/tsm/batchGetBarCodeGet", params)
        val data = json.optJSONObject("data")
            ?: throw AhuException("取码失败：响应缺少 data")
        val errmsg = data.optString("errmsg")
        val codes = data.optJSONArray("barcode")
        if (codes == null || codes.length() == 0) {
            throw AhuException(errmsg.ifEmpty { "取码失败：服务端未返回条码" })
        }
        return PayCode(
            value = codes.optString(0),
            expiresSeconds = data.optInt("expires", 60),
            account = data.optString("account").ifEmpty { payAccount },
        )
    }

    /** Convenience: first card's pay code, refreshing the SSO token once on rejection. */
    fun fetchFirstPayCode(): Pair<CampusCard, PayCode> {
        val cards = queryCards()
        val card = cards.firstOrNull() ?: throw AhuException("没有查询到校园卡", AhuException.Kind.NO_CARD)
        return try {
            card to fetchPayCode(card.account)
        } catch (e: AhuException) {
            if (e.message?.contains("越权") == true || e.message?.contains("登录") == true) {
                cardToken(forceRefresh = true)
                card to fetchPayCode(card.account)
            } else {
                throw e
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    fun tunnel(upstream: String): String = WebVpnHostCrypt.tunnel(vpnHost, upstream)

    /** Re-tunnel a Location value that the VPN handed back in already-tunnelled form. */
    private fun retunnel(url: String): String {
        if (url.contains(vpnHost)) return url
        return tunnel(url)
    }

    private fun extractSynjonesAuth(url: String): String? {
        val q = url.substringAfter('?', "")
        if (q.isEmpty()) return null
        for (pair in q.split('&')) {
            val k = pair.substringBefore('=')
            if (k != "synjones-auth") continue
            return URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
        }
        return null
    }

    private fun findLoginAction(html: String): String? {
        val patterns = listOf(
            Regex("""<form[^>]*id="loginForm"[^>]*action="([^"]+)""""),
            Regex("""<form[^>]*action="([^"]*cas/login[^"]*)""""),
        )
        for (p in patterns) {
            val m = p.find(html) ?: continue
            return m.groupValues[1].replace("&amp;", "&")
        }
        return null
    }

    private fun hiddenFields(html: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (tag in Regex("""<input[^>]*>""").findAll(html)) {
            val t = tag.value
            if (!t.contains("hidden")) continue
            val name = Regex("""name="([^"]+)"""").find(t)?.groupValues?.get(1) ?: continue
            val value = Regex("""value="([^"]*)"""").find(t)?.groupValues?.get(1) ?: ""
            out[name] = value
        }
        return out
    }

    companion object {
        const val AGENT_TYPE = "app"
    }
}
