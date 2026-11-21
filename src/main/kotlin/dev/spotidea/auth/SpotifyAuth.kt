package dev.spotidea.auth

import com.google.gson.JsonParser
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.BrowserUtil
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import dev.spotidea.api.long
import dev.spotidea.api.objOrNull
import dev.spotidea.api.str
import dev.spotidea.settings.SpotideaSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlin.coroutines.coroutineContext

class AuthException(message: String) : Exception(message)

/**
 * Owns the Spotify session: the browser login (Authorization Code + PKCE), the short-lived access
 * token kept in memory, and the long-lived refresh token kept in the IDE password safe.
 */
@Service(Service.Level.APP)
class SpotifyAuth {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val mutex = Mutex()
    private val credentials = CredentialAttributes(generateServiceName("Backbeat", "spotify-refresh-token"))

    @Volatile private var accessToken: String? = null
    @Volatile private var expiresAt = 0L
    @Volatile private var restored = false

    /** True once we hold a refresh token, i.e. the user has connected their account. */
    @Volatile var connected = false
        private set

    /** Reads the saved session from the password safe. Cheap to call repeatedly. */
    suspend fun restore() {
        if (restored) return
        mutex.withLock {
            if (restored) return
            connected = withContext(Dispatchers.IO) { PasswordSafe.instance.getPassword(credentials) } != null
            restored = true
        }
    }

    /** A valid access token, refreshing it first when it is about to expire. */
    suspend fun token(forceRefresh: Boolean = false): String {
        restore()
        if (!forceRefresh) currentToken()?.let { return it }
        return mutex.withLock {
            if (!forceRefresh) currentToken()?.let { return@withLock it }
            val refresh = withContext(Dispatchers.IO) { PasswordSafe.instance.getPassword(credentials) }
                ?: throw AuthException("Not connected to Spotify")
            val clientId = requireClientId()
            try {
                requestTokens(mapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to clientId))
            } catch (e: TokenRejected) {
                // The refresh token was revoked or belongs to another client ID: the session is gone.
                clearSession()
                throw AuthException("Your Spotify session expired. Please connect again.")
            }
        }
    }

    private fun currentToken(): String? = accessToken?.takeIf { System.currentTimeMillis() < expiresAt - 60_000 }

    /** Runs the whole browser login. Suspends until the user approves, denies, or the wait times out. */
    suspend fun login() {
        val settings = SpotideaSettings.get()
        val clientId = requireClientId()
        val redirectUri = settings.redirectUri
        val verifier = Pkce.newVerifier()
        val state = Pkce.newState()
        val authorizeUrl = AUTHORIZE_URL + "?" + Pkce.formEncode(
            mapOf(
                "client_id" to clientId,
                "response_type" to "code",
                "redirect_uri" to redirectUri,
                "code_challenge_method" to "S256",
                "code_challenge" to Pkce.challenge(verifier),
                "state" to state,
                "scope" to SCOPES.joinToString(" "),
            )
        )
        val code = withContext(Dispatchers.IO) {
            awaitCallback(settings.state.callbackPort, state) { BrowserUtil.browse(authorizeUrl) }
        }
        mutex.withLock {
            try {
                requestTokens(
                    mapOf(
                        "grant_type" to "authorization_code",
                        "code" to code,
                        "redirect_uri" to redirectUri,
                        "client_id" to clientId,
                        "code_verifier" to verifier,
                    )
                )
            } catch (e: TokenRejected) {
                throw AuthException("Spotify rejected the login: ${e.message}")
            }
        }
    }

    suspend fun logout() = mutex.withLock { clearSession() }

    private suspend fun clearSession() {
        accessToken = null
        expiresAt = 0
        connected = false
        withContext(Dispatchers.IO) { PasswordSafe.instance.setPassword(credentials, null) }
    }

    private fun requireClientId(): String = SpotideaSettings.get().clientId.ifBlank {
        throw AuthException("Add your Spotify Client ID first (Settings | Tools | Backbeat)")
    }

    private class TokenRejected(message: String) : Exception(message)

    /** Calls the token endpoint and stores the result. Caller must hold [mutex]. */
    private suspend fun requestTokens(form: Map<String, String>): String = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(Pkce.formEncode(form)))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        val json = runCatching { JsonParser.parseString(response.body()) }.getOrNull().objOrNull()
        if (response.statusCode() !in 200..299 || json == null) {
            val detail = json?.str("error_description") ?: json?.str("error") ?: "HTTP ${response.statusCode()}"
            if (response.statusCode() in 400..499) throw TokenRejected(detail)
            throw AuthException("Spotify login service is unavailable ($detail)")
        }
        val token = json.str("access_token") ?: throw AuthException("Spotify returned no access token")
        accessToken = token
        expiresAt = System.currentTimeMillis() + (json.long("expires_in") ?: 3600) * 1000
        // Spotify rotates refresh tokens for PKCE clients: always keep the newest one.
        json.str("refresh_token")?.let { PasswordSafe.instance.setPassword(credentials, it) }
        connected = true
        token
    }

    /**
     * Listens on the loopback interface for Spotify's redirect and returns the authorization code.
     * A plain socket keeps this free of any HTTP server dependency; it only ever answers one page.
     */
    private suspend fun awaitCallback(port: Int, expectedState: String, onListening: () -> Unit): String {
        val server = try {
            ServerSocket(port, 5, InetAddress.getByName("127.0.0.1"))
        } catch (e: BindException) {
            throw AuthException("Port $port is busy. Pick another callback port in Settings | Tools | Backbeat (and update the Redirect URI in your Spotify app).")
        }
        server.use {
            server.soTimeout = 500
            onListening()
            val deadline = System.currentTimeMillis() + LOGIN_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                coroutineContext.ensureActive()
                val socket = try {
                    server.accept()
                } catch (e: SocketTimeoutException) {
                    continue
                }
                socket.use {
                    socket.soTimeout = 3000
                    val requestLine = runCatching {
                        socket.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1).readLine()
                    }.getOrNull().orEmpty()
                    // e.g. "GET /callback?code=...&state=... HTTP/1.1"
                    val target = requestLine.split(' ').getOrNull(1).orEmpty()
                    if (!target.startsWith("/callback")) {
                        respond(socket, 404, "Not found", "Nothing here.")
                        return@use
                    }
                    val params = Pkce.parseQuery(target.substringAfter('?', ""))
                    val error = params["error"]
                    val code = params["code"]
                    when {
                        params["state"] != expectedState ->
                            respond(socket, 400, "Login ignored", "This login link did not come from your IDE.")
                        error != null -> {
                            respond(socket, 200, "Login cancelled", "Spotify said: $error. You can close this tab.")
                            throw AuthException(if (error == "access_denied") "Login was cancelled" else "Spotify login failed: $error")
                        }
                        code != null -> {
                            respond(socket, 200, "You're connected", "Head back to your IDE. You can close this tab.")
                            return code
                        }
                        else -> respond(socket, 400, "Login failed", "Spotify sent no authorization code.")
                    }
                }
            }
        }
        throw AuthException("Timed out waiting for the Spotify login")
    }

    private fun respond(socket: java.net.Socket, status: Int, title: String, message: String) {
        val safe = message.replace("&", "&amp;").replace("<", "&lt;")
        val html = """
            <!doctype html><html><head><meta charset="utf-8"><title>Backbeat</title></head>
            <body style="font-family:system-ui,sans-serif;background:#16181d;color:#e6e6e6;display:flex;align-items:center;justify-content:center;height:100vh;margin:0">
            <div style="text-align:center"><h1 style="font-weight:600">$title</h1><p style="color:#9aa0a6">$safe</p></div>
            </body></html>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)
        runCatching {
            val out = socket.getOutputStream()
            out.write(
                ("HTTP/1.1 $status OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n")
                    .toByteArray(StandardCharsets.ISO_8859_1)
            )
            out.write(html)
            out.flush()
        }.onFailure { thisLogger().debug("Could not answer the login callback", it) }
    }

    companion object {
        private const val AUTHORIZE_URL = "https://accounts.spotify.com/authorize"
        private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
        private const val LOGIN_TIMEOUT_MS = 180_000L

        val SCOPES = listOf(
            "user-read-playback-state",
            "user-modify-playback-state",
            "user-read-currently-playing",
            "user-read-recently-played",
            "user-library-read",
            "user-library-modify",
            "playlist-read-private",
            "playlist-read-collaborative",
        )

        fun get(): SpotifyAuth = service()
    }
}
