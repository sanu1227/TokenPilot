package com.tokenpilot.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One lock for all screens: rotating refresh tokens and logout must never race. */
internal object CodexSession {
    private val mutex = Mutex()
    private val api = DirectCodexApi()
    private var loginGeneration = 0L
    @Synchronized fun newAttempt(): Long = ++loginGeneration
    @Synchronized fun cancelLogin() { loginGeneration++ }

    suspend fun isConnected(context: Context): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val connected = CodexCredentialStore(context).read() != null
            if (!connected) UsageNotifications.clearSnapshot(context)
            connected
        }
    }

    suspend fun begin(): DeviceLogin = withContext(Dispatchers.IO) { api.begin() }

    suspend fun poll(context: Context, device: DeviceLogin, attempt: Long): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val tokens = api.poll(device) ?: return@withContext false
            var saved = false
            try {
                currentCoroutineContext().ensureActive()
                synchronized(this@CodexSession) {
                    if (attempt == loginGeneration) {
                        // Remove any previous account/PC snapshot before activating this account.
                        UsageNotifications.clearSnapshot(context)
                        CodexCredentialStore(context).save(tokens)
                        saved = true
                    }
                }
                saved
            } finally {
                if (!saved) try { api.revoke(tokens) } catch (_: Exception) { /* No credentials retained locally. */ }
            }
        }
    }

    suspend fun usage(context: Context): Snapshot = mutex.withLock {
        // Complete a rotating refresh and its atomic save even when a screen disappears.
        withContext(Dispatchers.IO + NonCancellable) {
            val store = CodexCredentialStore(context)
            var tokens = store.read() ?: throw CodexFailure("먼저 Codex에 로그인해 주세요.", true)
            var refreshed = false
            if (tokens.expires <= System.currentTimeMillis() + 60_000) {
                tokens = api.refresh(tokens)
                store.save(tokens)
                refreshed = true
            }
            val snapshot = try { api.usage(tokens) }
            catch (error: CodexFailure) {
                if (!error.loginRequired || refreshed) throw error
                tokens = api.refresh(tokens)
                store.save(tokens)
                api.usage(tokens)
            }
            UsageNotifications.saveSnapshot(context, snapshot)
            snapshot
        }
    }

    suspend fun logout(context: Context): Boolean {
        cancelLogin()
        return mutex.withLock {
            withContext(Dispatchers.IO + NonCancellable) {
                val store = CodexCredentialStore(context)
                val tokens = try { store.read() } catch (_: Exception) { null }
                store.clear()
                UsageNotifications.clearSnapshot(context)
                if (tokens == null) false else try { api.revoke(tokens) } catch (_: Exception) { false }
            }
        }
    }
}
