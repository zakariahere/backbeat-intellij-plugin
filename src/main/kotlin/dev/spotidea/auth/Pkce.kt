package dev.spotidea.auth

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * PKCE (RFC 7636): lets a desktop app do OAuth without a client secret. We invent a random
 * verifier, send only its SHA-256 hash to the authorize page, then prove we hold the original
 * when swapping the code for tokens. An intercepted code is useless without the verifier.
 */
object Pkce {
    private val random = SecureRandom()
    private val base64 = Base64.getUrlEncoder().withoutPadding()

    fun newVerifier(): String = randomToken(64)

    fun newState(): String = randomToken(16)

    fun challenge(verifier: String): String =
        base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))

    private fun randomToken(bytes: Int): String = base64.encodeToString(ByteArray(bytes).also(random::nextBytes))

    fun formEncode(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

    fun parseQuery(query: String): Map<String, String> = query.split('&').filter { it.isNotEmpty() }.associate {
        val key = it.substringBefore('=')
        val value = it.substringAfter('=', "")
        URLDecoder.decode(key, StandardCharsets.UTF_8) to URLDecoder.decode(value, StandardCharsets.UTF_8)
    }

    fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
