package com.tokenpilot.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Credentials never enter SharedPreferences, backup, saved UI state, or diagnostic logs. */
internal class CodexCredentialStore(context: Context, private val name: String = "codex-direct") {
    private val file = AtomicFile(File(context.noBackupFilesDir, "$name.enc"))
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key(): SecretKey {
        (keyStore().getKey(name, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(name, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    fun read(): CodexTokens? {
        if (!file.baseFile.exists()) return null
        val bytes = file.readFully()
        require(bytes.size in 29..262144)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        val saved = CodexTokens(json.getString("access_token"), json.getString("refresh_token"), json.getString("account"), 0L)
        return parseCodexTokens(json, saved)
    }

    fun save(tokens: CodexTokens) {
        val json = JSONObject().put("access_token", tokens.access).put("refresh_token", tokens.refresh).put("account", tokens.account)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }

    fun clear() {
        file.delete()
        keyStore().deleteEntry(name)
        check(!file.baseFile.exists())
    }
}
