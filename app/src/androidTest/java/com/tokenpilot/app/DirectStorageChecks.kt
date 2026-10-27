package com.tokenpilot.app

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import java.io.File
import java.util.Base64
import org.json.JSONObject

/** Runs against a disposable emulator; uses a separate key/file and synthetic credentials only. */
class DirectStorageChecks : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        val store = CodexCredentialStore(targetContext, "codex-storage-check")
        try {
            store.clear()
            check(store.read() == null)
            val claims = """{"exp":2000000000,"https://api.openai.com/auth":{"chatgpt_account_id":"synthetic-account"}}"""
            val access = "e30." + Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray()) + ".synthetic"
            val tokens = parseCodexTokens(JSONObject().put("access_token", access).put("refresh_token", "synthetic-refresh"))
            store.save(tokens)
            val encrypted = File(targetContext.noBackupFilesDir, "codex-storage-check.enc")
            check(!String(encrypted.readBytes(), Charsets.ISO_8859_1).contains("synthetic-refresh"))
            check(store.read()!!.refresh == "synthetic-refresh")
            store.save(CodexTokens(access, "synthetic-rotated", tokens.account, tokens.expires))
            check(store.read()!!.refresh == "synthetic-rotated")
            val altered = encrypted.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            encrypted.writeBytes(altered)
            check(runCatching { store.read() }.isFailure)
            store.clear()
            check(store.read() == null && !encrypted.exists())
            result.putString("stream", "PASS: encrypted storage, reload, rotation, tamper detection, deletion (synthetic data only)\n")
            finish(Activity.RESULT_OK, result)
        } catch (_: Exception) {
            result.putString("stream", "FAIL: credential storage checks\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally { store.clear() }
    }
}
