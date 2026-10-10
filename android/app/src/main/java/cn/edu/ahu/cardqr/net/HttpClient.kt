package cn.edu.ahu.cardqr.net

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Minimal HTTP response. */
data class HttpResponse(
    val status: Int,
    val body: String,
    val location: String?,
    val contentType: String?,
    /** Every `Set-Cookie` header value, in order. */
    val setCookies: List<String> = emptyList(),
) {
    val bodyBytes: ByteArray get() = body.toByteArray(Charsets.UTF_8)
}

/**
 * Small HTTP client on top of [HttpURLConnection], with cookies and redirects handled
 * explicitly by the caller.
 *
 * Two deliberate choices:
 *
 *  * **No `CookieHandler`.** The portal's session cookie is `HttpOnly`, and a process-wide
 *    `CookieManager` also swallows the `Set-Cookie` header before callers can read it. Since
 *    the session has to be persisted and replayed under our control (and survives app
 *    restarts), cookies are read from the headers and sent back by hand.
 *  * **No automatic redirects.** The WebVPN and the SSO chain return `Location` headers that
 *    must be re-tunnelled before being followed.
 */
class HttpClient {

    var userAgent: String = DEFAULT_UA

    /**
     * Perform a request without following redirects.
     *
     * The WebVPN and the card SSO both return `Location` headers that must be re-tunnelled
     * before being followed, so redirect handling is explicit at the call site.
     */
    fun raw(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        timeoutMs: Int = 30_000,
    ): HttpResponse = request(method, url, body, headers, false, timeoutMs)

    /**
     * Follow redirects, re-tunnelling each `Location` through the WebVPN.
     *
     * A tunnelled `Location` either arrives already absolute and on the VPN host, or as a
     * bare upstream URL; [tunnel] normalises both into a usable next hop.
     */
    fun getFollowing(
        url: String,
        tunnel: (String) -> String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 30_000,
    ): HttpResponse {
        var current = url
        var response = raw("GET", current, headers, null, timeoutMs)
        var hops = 0
        while (response.status in 300..399 && hops < 12) {
            val location = response.location ?: break
            current = nextHop(current, location, tunnel)
            response = raw("GET", current, headers, null, timeoutMs)
            hops++
        }
        return response
    }

    private fun nextHop(current: String, location: String, tunnel: (String) -> String): String {
        val absolute = try {
            java.net.URI(current).resolve(location).toString()
        } catch (_: Exception) {
            location
        }
        // Already a tunnelled URL on the VPN host: keep as-is.
        if (absolute.contains("/https/")) return absolute
        return tunnel(absolute)
    }

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        followRedirects: Boolean = false,
        timeoutMs: Int = 30_000,
    ): HttpResponse = request("GET", url, null, headers, followRedirects, timeoutMs)

    fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        followRedirects: Boolean = false,
        timeoutMs: Int = 30_000,
    ): HttpResponse {
        val body = form.entries.joinToString("&") {
            "${enc(it.key)}=${enc(it.value)}"
        }.toByteArray(Charsets.UTF_8)
        val h = HashMap(headers)
        h["Content-Type"] = "application/x-www-form-urlencoded; charset=UTF-8"
        return request("POST", url, body, h, followRedirects, timeoutMs)
    }

    fun request(
        method: String,
        url: String,
        body: ByteArray?,
        headers: Map<String, String>,
        followRedirects: Boolean = false,
        timeoutMs: Int = 30_000,
    ): HttpResponse {
        var current = url
        var hops = 0
        while (true) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                instanceFollowRedirects = false
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                for ((k, v) in headers) setRequestProperty(k, v)
                if (body != null) {
                    doOutput = true
                    setFixedLengthStreamingMode(body.size)
                    outputStream.use { it.write(body) }
                }
            }

            val status = conn.responseCode
            val location = conn.getHeaderField("Location")
            val contentType = conn.getHeaderField("Content-Type")
            // A JSESSIONID session is HttpOnly, so HttpURLConnection's own cookie store never
            // sees it; read every Set-Cookie header directly and let the caller forward them.
            val cookies = conn.headerFields["Set-Cookie"] ?: emptyList()
            val stream: InputStream? = if (status >= 400) conn.errorStream else conn.inputStream
            val bytes = stream?.let { readAll(it) } ?: ByteArray(0)
            conn.disconnect()

            if (!followRedirects || status !in 300..399 || location == null || hops >= 10) {
                return HttpResponse(
                    status = status,
                    body = bytes.toString(Charsets.UTF_8),
                    location = location,
                    contentType = contentType,
                    setCookies = cookies,
                )
            }
            current = java.net.URI(current).resolve(location).toString()
            hops++
        }
    }

    /** Binary GET (e.g. the captcha image) returning bytes plus any cookies. */
    fun getBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 30_000,
    ): BinaryResponse {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", userAgent)
            for ((k, v) in headers) setRequestProperty(k, v)
        }
        val status = conn.responseCode
        val cookies = conn.headerFields["Set-Cookie"] ?: emptyList()
        val stream = if (status >= 400) conn.errorStream else conn.inputStream
        val bytes = stream?.readBytes() ?: ByteArray(0)
        conn.disconnect()
        return BinaryResponse(status, bytes, cookies)
    }

    /** Binary payload with the headers callers need. */
    data class BinaryResponse(val status: Int, val bytes: ByteArray, val setCookies: List<String>) {
        override fun equals(other: Any?): Boolean =
            other is BinaryResponse && status == other.status && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = 31 * status + bytes.contentHashCode()
    }

    private fun readAll(stream: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        stream.use {
            while (true) {
                val n = it.read(chunk)
                if (n <= 0) break
                buffer.write(chunk, 0, n)
            }
        }
        return buffer.toByteArray()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /** A WeChat in-app browser UA; some portal pages gate purely on this. */
        const val WECHAT_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7 Build/TQ3A.230805.001; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.0.0 " +
                "Mobile Safari/537.36 MMWEBID[0] MicroMessenger/8.0.47.2560(0x28002F35) " +
                "WeChat/arm64 Weixin NetType/WIFI Language/zh_CN ABI/arm64"
    }
}
