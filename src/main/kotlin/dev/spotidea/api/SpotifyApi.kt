package dev.spotidea.api

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import dev.spotidea.auth.Pkce
import dev.spotidea.auth.SpotifyAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class SpotifyException(val status: Int, val reason: String?, message: String) : Exception(message) {
    val isNoActiveDevice: Boolean get() = status == 404 && (reason == "NO_ACTIVE_DEVICE" || message.orEmpty().contains("No active device", true))
    val isPremiumRequired: Boolean get() = status == 403 && (reason == "PREMIUM_REQUIRED" || message.orEmpty().contains("premium", true))
    val isRateLimited: Boolean get() = status == 429
}

/** Thin, typed wrapper over the Spotify Web API endpoints the plugin uses. */
@Service(Service.Level.APP)
class SpotifyApi {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    /** Set from a 429 response; until then we fail fast instead of making the rate limit worse. */
    @Volatile private var rateLimitedUntil = 0L

    // ---- Playback ---------------------------------------------------------------------------

    suspend fun playback(): PlaybackState? =
        call("GET", "/me/player", mapOf("additional_types" to "track,episode")).objOrNull()?.let(SpotifyParser::playback)

    suspend fun devices(): List<Device> =
        call("GET", "/me/player/devices").objOrNull()?.arr("devices").orEmpty()
            .mapNotNull { it.objOrNull()?.let(SpotifyParser::device) }

    /** Resumes playback when called without arguments, otherwise starts the given context or track list. */
    suspend fun play(
        contextUri: String? = null,
        uris: List<String>? = null,
        offsetUri: String? = null,
        deviceId: String? = null,
    ) {
        val body = JsonObject()
        contextUri?.let { body.addProperty("context_uri", it) }
        uris?.let { list -> body.add("uris", JsonArray().apply { list.forEach(::add) }) }
        offsetUri?.let { body.add("offset", JsonObject().apply { addProperty("uri", it) }) }
        call("PUT", "/me/player/play", mapOf("device_id" to deviceId), body.takeIf { it.size() > 0 })
    }

    suspend fun pause() { call("PUT", "/me/player/pause") }
    suspend fun next() { call("POST", "/me/player/next") }
    suspend fun previous() { call("POST", "/me/player/previous") }
    suspend fun seek(positionMs: Long) { call("PUT", "/me/player/seek", mapOf("position_ms" to positionMs.toString())) }
    suspend fun volume(percent: Int) { call("PUT", "/me/player/volume", mapOf("volume_percent" to percent.coerceIn(0, 100).toString())) }
    suspend fun shuffle(on: Boolean) { call("PUT", "/me/player/shuffle", mapOf("state" to on.toString())) }
    suspend fun repeat(mode: RepeatMode) { call("PUT", "/me/player/repeat", mapOf("state" to mode.api)) }

    suspend fun transfer(deviceId: String, play: Boolean) {
        val body = JsonObject().apply {
            add("device_ids", JsonArray().apply { add(deviceId) })
            addProperty("play", play)
        }
        call("PUT", "/me/player", body = body)
    }

    suspend fun queue(): List<MediaItem> =
        call("GET", "/me/player/queue").objOrNull()?.arr("queue").orEmpty().mapNotNull { SpotifyParser.item(it) }

    suspend fun addToQueue(uri: String) { call("POST", "/me/player/queue", mapOf("uri" to uri)) }

    // ---- Search and browsing ----------------------------------------------------------------

    /** Development-mode apps are capped at 10 results per type per request (February 2026 change). */
    suspend fun search(query: String, kinds: List<MediaKind>, limit: Int = SEARCH_PAGE, offset: Int = 0): List<MediaItem> {
        val root = call(
            "GET", "/search",
            mapOf(
                "q" to query,
                "type" to kinds.joinToString(",") { it.path },
                "limit" to limit.coerceIn(1, SEARCH_PAGE).toString(),
                "offset" to offset.toString(),
            )
        ).objOrNull() ?: return emptyList()
        return SpotifyParser.searchResults(root, kinds)
    }

    suspend fun myPlaylists(offset: Int = 0): List<MediaItem> =
        call("GET", "/me/playlists", page(offset)).objOrNull()?.arr("items").orEmpty()
            .mapNotNull { it.objOrNull()?.let(SpotifyParser::playlist) }

    suspend fun playlistItems(playlistId: String, offset: Int = 0): List<MediaItem> =
        call("GET", "/playlists/$playlistId/items", page(offset)).objOrNull()?.let(SpotifyParser::playlistEntries).orEmpty()

    suspend fun albumTracks(album: MediaItem, offset: Int = 0): List<MediaItem> =
        call("GET", "/albums/${album.id}/tracks", page(offset)).objOrNull()?.arr("items").orEmpty()
            .mapNotNull { it.objOrNull()?.let { o -> SpotifyParser.track(o, fallbackAlbum = album) } }

    suspend fun savedTracks(offset: Int = 0): List<MediaItem> =
        call("GET", "/me/tracks", page(offset)).objOrNull()?.let(SpotifyParser::playlistEntries).orEmpty()

    suspend fun recentlyPlayed(): List<MediaItem> =
        call("GET", "/me/player/recently-played", mapOf("limit" to "50")).objOrNull()
            ?.let(SpotifyParser::playlistEntries).orEmpty().distinctBy { it.uri }

    /** Display name for a context URI such as `spotify:playlist:...`; falls back to the kind when unknown. */
    suspend fun contextName(uri: String): String {
        val parts = uri.split(':')
        val kind = parts.getOrNull(1) ?: return uri
        val id = parts.getOrNull(2) ?: return kind
        val path = when (kind) {
            "playlist" -> "/playlists/$id"
            "album" -> "/albums/$id"
            "artist" -> "/artists/$id"
            "show" -> "/shows/$id"
            else -> return kind.replaceFirstChar(Char::uppercase)
        }
        return runCatching { call("GET", path).objOrNull()?.str("name") }.getOrNull()
            ?: kind.replaceFirstChar(Char::uppercase)
    }

    suspend fun userName(): String? = call("GET", "/me").objOrNull()?.let { it.str("display_name") ?: it.str("id") }

    // ---- Library (unified /me/library endpoints, addressed by URI) ---------------------------

    suspend fun isSaved(uri: String): Boolean {
        val result = call("GET", "/me/library/contains", mapOf("uris" to uri))
        return result?.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull()
            ?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
    }

    suspend fun setSaved(uri: String, saved: Boolean) {
        call(if (saved) "PUT" else "DELETE", "/me/library", mapOf("uris" to uri))
    }

    // ---- Transport --------------------------------------------------------------------------

    private fun page(offset: Int) = mapOf("limit" to PAGE.toString(), "offset" to offset.toString())

    private suspend fun call(
        method: String,
        path: String,
        query: Map<String, String?> = emptyMap(),
        body: JsonElement? = null,
    ): JsonElement? {
        val wait = rateLimitedUntil - System.currentTimeMillis()
        if (wait > 0) throw SpotifyException(429, "RATE_LIMITED", "Spotify asked us to slow down for ${wait / 1000 + 1}s")

        val auth = SpotifyAuth.get()
        var response = send(method, path, query, body, auth.token())
        if (response.statusCode() == 401) {
            // The access token expired early or was revoked; one forced refresh, then give up.
            response = send(method, path, query, body, auth.token(forceRefresh = true))
        }
        val status = response.statusCode()
        val text = response.body().orEmpty()
        if (status == 429) {
            val seconds = response.headers().firstValue("Retry-After").orElse("5").toLongOrNull() ?: 5
            rateLimitedUntil = System.currentTimeMillis() + seconds.coerceIn(1, 600) * 1000
        }
        // Player commands sometimes answer 2xx with a non-JSON body, so parsing must never throw.
        val json = if (text.isBlank()) null else runCatching { JsonParser.parseString(text) }.getOrNull()
        if (status !in 200..299) {
            val error = json.objOrNull()?.obj("error")
            throw SpotifyException(
                status = status,
                reason = error?.str("reason"),
                message = error?.str("message") ?: json.objOrNull()?.str("error_description") ?: "Spotify answered HTTP $status",
            )
        }
        return json
    }

    private suspend fun send(
        method: String,
        path: String,
        query: Map<String, String?>,
        body: JsonElement?,
        token: String,
    ): HttpResponse<String> = withContext(Dispatchers.IO) {
        val params = query.filterValues { it != null }.mapValues { it.value!! }
        val url = BASE + path + if (params.isEmpty()) "" else "?" + Pkce.formEncode(params)
        val publisher = when {
            body != null -> HttpRequest.BodyPublishers.ofString(body.toString())
            else -> HttpRequest.BodyPublishers.noBody()
        }
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .method(method, publisher)
            .build()
        http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    companion object {
        private const val BASE = "https://api.spotify.com/v1"
        const val PAGE = 50
        const val SEARCH_PAGE = 10

        fun get(): SpotifyApi = service()
    }
}
