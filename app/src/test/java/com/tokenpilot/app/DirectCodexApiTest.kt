package com.tokenpilot.app

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DirectCodexApiTest {
    private fun tokens(refresh: String = "fake-refresh") = JSONObject().put("access_token", jwt())
        .put("refresh_token", refresh)
    private fun jwt() = "eyJhbGciOiJSUzI1NiJ9." + Base64.getUrlEncoder().withoutPadding().encodeToString(
        """{"exp":2000000000,"https://api.openai.com/auth":{"chatgpt_account_id":"test-account"}}""".toByteArray()) + ".test-signature"
    private val quota = """{"rate_limit":{"primary_window":{"used_percent":15,"limit_window_seconds":604800,"reset_at":2000000000},"secondary_window":null}}"""

    @Test fun weeklyPrimaryDoesNotBecomeFiveHourQuota() {
        val snapshot = parseCodexUsage(JSONObject(quota), 123L)
        assertEquals(1, snapshot.windows.size)
        assertEquals(RemoteWindow("codex", 10080, 85.0, 2000000000000), snapshot.windows.single())
        assertNull(displayWindows(snapshot).first { it.minutes == 300L }.percent)
        assertEquals(123L, snapshot.checked)
    }

    @Test fun bothWindowsAdditionalBucketsAndZeroArePreserved() {
        val response = JSONObject("""{"rate_limit":{"primary_window":{"used_percent":100,"limit_window_seconds":18000,"reset_at":2000000000},"secondary_window":{"used_percent":40,"limit_window_seconds":604800,"reset_at":2000100000}},"additional_rate_limits":[{"metered_feature":"other","rate_limit":{"primary_window":{"used_percent":5,"limit_window_seconds":18000,"reset_at":2000200000}}}]}""")
        val result = parseCodexUsage(response, 1)
        assertEquals(listOf(0.0, 60.0, 95.0), result.windows.map { it.percent })
        assertEquals(listOf(300L, 10080L, 300L), result.windows.map { it.minutes })
        assertEquals("앱 확인", quotaValue(result, 300, 1))
    }

    @Test fun absentValuesStayUnknownAndInvalidResponsesFail() {
        assertTrue(parseCodexUsage(JSONObject("""{"rate_limit":null}"""), 1).windows.isEmpty())
        val unknown = parseCodexUsage(JSONObject("""{"rate_limit":{"primary_window":{}}}"""), 1).windows.single()
        assertNull(unknown.percent)
        assertNull(unknown.reset)
        for (body in listOf("{}", """{"rate_limit":"invalid"}""", """{"rate_limit":{"primary_window":{"reset_at":9223372036854775807}}}""")) {
            assertThrows(Exception::class.java) { parseCodexUsage(JSONObject(body), 1) }
        }
    }

    @Test fun deviceFlowUsesFixedOriginsAndOnlySendsAccessTokenToQuotaService() {
        val urls = mutableListOf<String>()
        var pending = true
        val api = DirectCodexApi { url, body, headers ->
            urls.add(url)
            when {
                url.endsWith("/usercode") -> {
                    assertEquals(CODEX_CLIENT_ID, JSONObject(body!!).getString("client_id"))
                    CodexHttpResult(200, """{"device_auth_id":"test-id","usercode":"TEST-CODE","interval":"5"}""")
                }
                url.endsWith("/deviceauth/token") -> {
                    assertEquals("test-id", JSONObject(body!!).getString("device_auth_id"))
                    if (pending) { pending = false; CodexHttpResult(403, "pending") }
                    else CodexHttpResult(200, """{"authorization_code":"test+code","code_verifier":"test-verifier"}""")
                }
                url.endsWith("/oauth/token") -> {
                    assertTrue(body!!.contains("code=test%2Bcode"))
                    assertTrue(body.contains("redirect_uri=https%3A%2F%2Fauth.openai.com%2Fdeviceauth%2Fcallback"))
                    assertFalse(headers.containsKey("Authorization"))
                    CodexHttpResult(200, tokens().toString())
                }
                else -> {
                    assertEquals("https://chatgpt.com/backend-api/wham/usage", url)
                    assertNull(body)
                    assertEquals("Bearer ${jwt()}", headers["Authorization"])
                    assertEquals("test-account", headers["ChatGPT-Account-Id"])
                    CodexHttpResult(200, quota)
                }
            }
        }
        val device = api.begin()
        assertEquals(5000L, device.intervalMs)
        assertNull(api.poll(device))
        val session = api.poll(device)!!
        assertEquals(2000000000000L, session.expires)
        assertEquals(85.0, api.usage(session).windows.single().percent!!, 0.0)
        assertTrue(urls.all { !it.contains("test+code") && !it.contains("fake-refresh") })
    }

    @Test fun refreshRotatesCredentialsAndLogoutRevokesRefreshToken() {
        val old = parseCodexTokens(tokens())
        val api = DirectCodexApi { url, body, headers ->
            assertFalse(headers.containsKey("Authorization"))
            val request = JSONObject(body!!)
            if (url.endsWith("/oauth/token")) {
                assertEquals("fake-refresh", request.getString("refresh_token"))
                assertEquals("refresh_token", request.getString("grant_type"))
                CodexHttpResult(200, tokens("rotated-refresh").toString())
            } else {
                assertEquals("https://auth.openai.com/oauth/revoke", url)
                assertEquals("rotated-refresh", request.getString("token"))
                CodexHttpResult(200, "")
            }
        }
        val next = api.refresh(old)
        assertEquals("rotated-refresh", next.refresh)
        assertTrue(api.revoke(next))
        assertFalse(next.toString().contains("rotated-refresh"))
    }

    @Test fun errorsNeverExposeResponseBodiesAndUnauthorizedRequestsRequireLogin() {
        for (status in listOf(401, 403, 429, 500, 302)) {
            val api = DirectCodexApi { _, _, _ -> CodexHttpResult(status, "fake-secret-token") }
            val error = assertThrows(CodexFailure::class.java) { api.usage(parseCodexTokens(tokens())) }
            assertEquals(status == 401, error.loginRequired)
            assertFalse(error.safeMessage.contains("fake-secret-token"))
        }
    }

    @Test fun malformedCodeAndHeaderInjectionAreRejected() {
        val api = DirectCodexApi { _, _, _ -> CodexHttpResult(200, """{"device_auth_id":"test-id","user_code":"<script>","interval":"0"}""") }
        assertThrows(Exception::class.java) { api.begin() }
        val claims = JSONObject().put("exp", 2000000000).put("https://api.openai.com/auth", JSONObject().put("chatgpt_account_id", "test\r\nInjected: value"))
        val bad = "e30." + Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toString().toByteArray()) + ".test"
        assertThrows(Exception::class.java) { parseCodexTokens(tokens().put("access_token", bad)) }
    }

    @Test fun accountMetadataCanComeFromTokenExchangeIdentityToken() {
        val access = "e30." + Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":2000000000}""".toByteArray()) + ".test"
        val response = tokens().put("access_token", access).put("id_token", jwt())
        val first = parseCodexTokens(response)
        assertEquals("test-account", first.account)
        // Later refreshes may omit identity metadata; the same registration retains its account.
        val renewed = parseCodexTokens(JSONObject().put("access_token", access), first)
        assertEquals(first.account, renewed.account)
        assertEquals(first.refresh, renewed.refresh)
    }

    @Test fun refreshCannotSilentlySwitchAccounts() {
        val otherClaims = """{"exp":2000000000,"https://api.openai.com/auth":{"chatgpt_account_id":"different-account"}}"""
        val other = "e30." + Base64.getUrlEncoder().withoutPadding().encodeToString(otherClaims.toByteArray()) + ".test"
        val api = DirectCodexApi { _, _, _ -> CodexHttpResult(200, tokens("rotated").put("access_token", other).toString()) }
        val failure = assertThrows(CodexFailure::class.java) { api.refresh(parseCodexTokens(tokens())) }
        assertTrue(failure.loginRequired)
    }
}
