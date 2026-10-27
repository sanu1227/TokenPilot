package com.tokenpilot.app

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import org.json.JSONObject

// Personal compatibility integration, not a stable public quota API.
// Protocol: openai/codex @ 6fe77fc12346591b8037a8704640e40196b19044 (see docs/direct-login.md).
internal const val CODEX_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
internal const val CODEX_LOGIN_URL = "https://auth.openai.com/codex/device"
private const val AUTH = "https://auth.openai.com"
private const val USAGE = "https://chatgpt.com/backend-api/wham/usage"

// Deliberately not data classes: generated toString must not expose credentials.
internal class DeviceLogin(val id: String, val code: String, val intervalMs: Long)
internal class CodexTokens(val access: String, val refresh: String, val account: String, val expires: Long)
internal class CodexHttpResult(val status: Int, val body: String)
internal class CodexFailure(val safeMessage: String, val loginRequired: Boolean = false) : Exception(safeMessage)

internal class DirectCodexApi(
    private val request: (String, String?, Map<String, String>) -> CodexHttpResult = ::codexHttp,
) {
    private fun post(url: String, json: JSONObject) = request(url, json.toString(), mapOf("Content-Type" to "application/json"))

    fun begin(): DeviceLogin {
        val result = post("$AUTH/api/accounts/deviceauth/usercode", JSONObject().put("client_id", CODEX_CLIENT_ID))
        if (result.status != 200) fail(result.status)
        val json = JSONObject(result.body)
        val code = json.optString("user_code").ifBlank { json.getString("usercode") }
        require(code.matches(Regex("[A-Za-z0-9-]{4,32}")))
        val id = json.getString("device_auth_id").also { require(it.isNotBlank() && it.length <= 4096) }
        val interval = json.optString("interval", "5").toLongOrNull() ?: 5L
        return DeviceLogin(id, code, interval.coerceIn(5, 60) * 1000)
    }

    fun poll(device: DeviceLogin): CodexTokens? {
        val result = post("$AUTH/api/accounts/deviceauth/token", JSONObject()
            .put("device_auth_id", device.id).put("user_code", device.code))
        if (result.status == 403 || result.status == 404) return null // Pending in the Codex device protocol.
        if (result.status != 200) fail(result.status)
        val grant = JSONObject(result.body)
        val body = mapOf("grant_type" to "authorization_code", "client_id" to CODEX_CLIENT_ID,
            "code" to grant.getString("authorization_code"), "code_verifier" to grant.getString("code_verifier"),
            "redirect_uri" to "$AUTH/deviceauth/callback").entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val exchange = request("$AUTH/oauth/token", body, mapOf("Content-Type" to "application/x-www-form-urlencoded"))
        if (exchange.status != 200) fail(exchange.status, exchanging = true)
        return parseCodexTokens(JSONObject(exchange.body))
    }

    fun refresh(previous: CodexTokens): CodexTokens {
        val result = post("$AUTH/oauth/token", JSONObject().put("client_id", CODEX_CLIENT_ID)
            .put("grant_type", "refresh_token").put("refresh_token", previous.refresh))
        if (result.status != 200) fail(result.status, exchanging = true)
        return parseCodexTokens(JSONObject(result.body), previous).also {
            if (it.account != previous.account) throw CodexFailure("계정이 변경되었습니다. 다시 로그인해 주세요.", true)
        }
    }

    fun usage(tokens: CodexTokens): Snapshot {
        val result = request(USAGE, null, mapOf("Authorization" to "Bearer ${tokens.access}",
            "ChatGPT-Account-Id" to tokens.account, "Accept" to "application/json"))
        if (result.status != 200) fail(result.status)
        return parseCodexUsage(JSONObject(result.body), System.currentTimeMillis())
    }

    fun revoke(tokens: CodexTokens): Boolean = post("$AUTH/oauth/revoke", JSONObject()
        .put("token", tokens.refresh).put("token_type_hint", "refresh_token").put("client_id", CODEX_CLIENT_ID)).status in 200..299

    private fun fail(status: Int, exchanging: Boolean = false): Nothing = throw when {
        status == 401 || (exchanging && status == 400) -> CodexFailure("로그인이 만료되었거나 취소되었습니다. 다시 로그인해 주세요.", true)
        status == 403 -> CodexFailure("OpenAI에서 접근을 허용하지 않았습니다. 계정 권한이나 연결 상태를 확인해 주세요.")
        status == 429 -> CodexFailure("요청이 많습니다. 잠시 후 다시 시도해 주세요.")
        else -> CodexFailure("OpenAI 연결에 실패했습니다. 잠시 후 다시 시도해 주세요. (HTTP $status)")
    }
}

internal fun parseCodexTokens(json: JSONObject, previous: CodexTokens? = null): CodexTokens {
    val access = json.getString("access_token").also { require(it.length in 1..65536 && it.none(Char::isWhitespace)) }
    val refresh = json.optString("refresh_token").ifBlank { previous?.refresh.orEmpty() }.also { require(it.isNotBlank()) }
    // Read routing/expiry metadata only from tokens delivered by the fixed TLS token endpoint.
    // This is not local identity verification; the quota service validates the bearer token.
    val claims = JSONObject(String(Base64.getUrlDecoder().decode(access.split('.').also { require(it.size == 3) }[1]), Charsets.UTF_8))
    val account = claims.optJSONObject("https://api.openai.com/auth")?.optString("chatgpt_account_id")
        ?.takeIf { it.isNotBlank() } ?: json.optString("id_token").takeIf { it.isNotBlank() }?.let {
            val identityClaims = JSONObject(String(Base64.getUrlDecoder().decode(it.split('.')[1]), Charsets.UTF_8))
            identityClaims.optJSONObject("https://api.openai.com/auth")?.optString("chatgpt_account_id")?.takeIf { value -> value.isNotBlank() }
        } ?: previous?.account ?: error("Missing account routing metadata")
    require(account.length <= 256 && account.all { it.code in 33..126 })
    val expires = Math.multiplyExact(claims.getLong("exp"), 1000L)
    require(expires > 0)
    return CodexTokens(access, refresh, account, expires)
}

internal fun parseCodexUsage(json: JSONObject, checked: Long): Snapshot {
    require(json.has("rate_limit")) { "Unrecognized quota response" }
    val rows = mutableListOf<RemoteWindow>()
    fun bucket(id: String, limit: JSONObject?) {
        require(id.isNotBlank() && id.length <= 256)
        if (limit == null) return
        for (key in listOf("primary_window", "secondary_window")) {
            if (limit.isNull(key)) continue
            val window = limit.getJSONObject(key)
            val used = if (window.isNull("used_percent")) null else (window.get("used_percent") as Number).toDouble()
                .also { require(it.isFinite()) }
            val seconds = if (window.isNull("limit_window_seconds")) null else window.getLong("limit_window_seconds").also { require(it > 0) }
            val reset = if (window.isNull("reset_at")) null else Math.multiplyExact(window.getLong("reset_at"), 1000L).also { require(it >= 0) }
            rows.add(RemoteWindow(id, seconds?.takeIf { it % 60L == 0L }?.div(60), used?.let { (100 - it).coerceIn(0.0, 100.0) }, reset))
        }
    }
    bucket("codex", if (json.isNull("rate_limit")) null else json.getJSONObject("rate_limit"))
    if (!json.isNull("additional_rate_limits")) {
        val additional = json.getJSONArray("additional_rate_limits")
        for (i in 0 until additional.length()) {
            val item = additional.getJSONObject(i)
            bucket(item.getString("metered_feature"), if (item.isNull("rate_limit")) null else item.getJSONObject("rate_limit"))
        }
    }
    return Snapshot(checked, rows)
}

private fun codexHttp(url: String, body: String?, headers: Map<String, String>): CodexHttpResult {
    val uri = URI(url)
    require(uri.scheme == "https" && uri.host in setOf("auth.openai.com", "chatgpt.com") && uri.userInfo == null)
    val connection = uri.toURL().openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 10000
        connection.readTimeout = 20000
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.setRequestProperty("User-Agent", "TokenPilot/0.2 (personal Android compatibility client)")
        headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
        if (body != null) {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val bytes = ByteArrayOutputStream()
        stream?.use {
            val buffer = ByteArray(8192)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                require(bytes.size() + count <= 1_048_576)
                bytes.write(buffer, 0, count)
            }
        }
        return CodexHttpResult(status, bytes.toString("UTF-8"))
    } finally { connection.disconnect() }
}
