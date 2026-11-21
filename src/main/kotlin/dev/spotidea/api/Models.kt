package dev.spotidea.api

import com.google.gson.JsonElement
import com.google.gson.JsonObject

enum class MediaKind(val path: String, val label: String) {
    TRACK("track", "Song"),
    EPISODE("episode", "Episode"),
    ALBUM("album", "Album"),
    ARTIST("artist", "Artist"),
    PLAYLIST("playlist", "Playlist");

    /** Something you start as a whole (a "context" in Spotify terms) rather than a single item. */
    val isContext: Boolean get() = this == ALBUM || this == ARTIST || this == PLAYLIST
}

data class MediaItem(
    val kind: MediaKind,
    val uri: String,
    val id: String,
    val name: String,
    val subtitle: String,
    val imageUrl: String? = null,
    val thumbUrl: String? = null,
    val durationMs: Long = 0,
) {
    val webUrl: String get() = "https://open.spotify.com/${kind.path}/$id"
    val isPlayableItem: Boolean get() = kind == MediaKind.TRACK || kind == MediaKind.EPISODE
}

data class Device(
    val id: String?,
    val name: String,
    val type: String,
    val isActive: Boolean,
    val isRestricted: Boolean,
    val volumePercent: Int?,
    val supportsVolume: Boolean,
)

enum class RepeatMode(val api: String) {
    OFF("off"), CONTEXT("context"), TRACK("track");

    fun next(): RepeatMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun of(value: String?): RepeatMode = entries.firstOrNull { it.api == value } ?: OFF
    }
}

data class PlaybackState(
    val device: Device?,
    val item: MediaItem?,
    val isPlaying: Boolean,
    val progressMs: Long,
    val shuffle: Boolean,
    val repeat: RepeatMode,
    val contextUri: String?,
    /** Local clock time at which [progressMs] was true; lets the UI interpolate between polls. */
    val fetchedAt: Long = System.currentTimeMillis(),
) {
    fun progressNow(now: Long = System.currentTimeMillis()): Long {
        val duration = item?.durationMs ?: 0
        val raw = if (isPlaying) progressMs + (now - fetchedAt) else progressMs
        return if (duration > 0) raw.coerceIn(0, duration) else raw.coerceAtLeast(0)
    }
}

/** Turns Spotify's JSON into our small model. Every field is optional: Spotify removes fields without much notice. */
object SpotifyParser {

    fun item(el: JsonElement?): MediaItem? {
        val o = el.objOrNull() ?: return null
        return when (o.str("type")) {
            "track" -> track(o)
            "episode" -> episode(o)
            "album" -> album(o)
            "artist" -> artist(o)
            "playlist" -> playlist(o)
            else -> null
        }
    }

    fun track(o: JsonObject, fallbackAlbum: MediaItem? = null): MediaItem? {
        val uri = o.str("uri") ?: return null
        val album = o.obj("album")
        val artists = artistNames(o)
        val (image, thumb) = images(album)
        return MediaItem(
            kind = MediaKind.TRACK,
            uri = uri,
            id = o.str("id") ?: uri.substringAfterLast(':'),
            name = o.str("name").orEmpty(),
            subtitle = listOfNotNull(artists.ifBlank { null }, album?.str("name") ?: fallbackAlbum?.name)
                .joinToString(" • "),
            imageUrl = image ?: fallbackAlbum?.imageUrl,
            thumbUrl = thumb ?: fallbackAlbum?.thumbUrl,
            durationMs = o.long("duration_ms") ?: 0,
        )
    }

    private fun episode(o: JsonObject): MediaItem? {
        val uri = o.str("uri") ?: return null
        val show = o.obj("show")
        val (image, thumb) = images(o).let { if (it.first == null) images(show) else it }
        return MediaItem(
            kind = MediaKind.EPISODE,
            uri = uri,
            id = o.str("id") ?: uri.substringAfterLast(':'),
            name = o.str("name").orEmpty(),
            subtitle = show?.str("name") ?: "Podcast",
            imageUrl = image,
            thumbUrl = thumb,
            durationMs = o.long("duration_ms") ?: 0,
        )
    }

    fun album(o: JsonObject): MediaItem? {
        val uri = o.str("uri") ?: return null
        val (image, thumb) = images(o)
        val year = o.str("release_date")?.take(4)
        return MediaItem(
            kind = MediaKind.ALBUM,
            uri = uri,
            id = o.str("id") ?: uri.substringAfterLast(':'),
            name = o.str("name").orEmpty(),
            subtitle = listOfNotNull("Album", artistNames(o).ifBlank { null }, year).joinToString(" • "),
            imageUrl = image,
            thumbUrl = thumb,
        )
    }

    fun artist(o: JsonObject): MediaItem? {
        val uri = o.str("uri") ?: return null
        val (image, thumb) = images(o)
        return MediaItem(
            kind = MediaKind.ARTIST,
            uri = uri,
            id = o.str("id") ?: uri.substringAfterLast(':'),
            name = o.str("name").orEmpty(),
            subtitle = "Artist",
            imageUrl = image,
            thumbUrl = thumb,
        )
    }

    fun playlist(o: JsonObject): MediaItem? {
        val uri = o.str("uri") ?: return null
        val (image, thumb) = images(o)
        val owner = o.obj("owner")?.str("display_name")
        return MediaItem(
            kind = MediaKind.PLAYLIST,
            uri = uri,
            id = o.str("id") ?: uri.substringAfterLast(':'),
            name = o.str("name").orEmpty(),
            subtitle = listOfNotNull("Playlist", owner).joinToString(" • "),
            imageUrl = image,
            thumbUrl = thumb,
        )
    }

    fun device(o: JsonObject): Device = Device(
        id = o.str("id"),
        name = o.str("name") ?: "Unknown device",
        type = o.str("type") ?: "Unknown",
        isActive = o.bool("is_active") ?: false,
        isRestricted = o.bool("is_restricted") ?: false,
        volumePercent = o.int("volume_percent"),
        supportsVolume = o.bool("supports_volume") ?: (o.int("volume_percent") != null),
    )

    fun playback(o: JsonObject): PlaybackState = PlaybackState(
        device = o.obj("device")?.let(::device),
        item = item(o.get("item")),
        isPlaying = o.bool("is_playing") ?: false,
        progressMs = o.long("progress_ms") ?: 0,
        shuffle = o.bool("shuffle_state") ?: false,
        repeat = RepeatMode.of(o.str("repeat_state")),
        contextUri = o.obj("context")?.str("uri"),
    )

    /** Search results come back as one paging object per type; flatten them in the order the caller asked for. */
    fun searchResults(root: JsonObject, kinds: List<MediaKind>): List<MediaItem> = kinds.flatMap { kind ->
        root.obj(kind.path + "s")?.arr("items").orEmpty().mapNotNull { item(it) }
    }

    /**
     * Playlist entries wrap the track. Since the February 2026 API change the wrapper key is `item`;
     * older (extended quota) apps still receive `track`, so accept both.
     */
    fun playlistEntries(root: JsonObject): List<MediaItem> = root.arr("items").mapNotNull { entry ->
        val o = entry.objOrNull() ?: return@mapNotNull null
        item(o.get("item") ?: o.get("track"))
    }

    private fun artistNames(o: JsonObject): String =
        o.arr("artists").mapNotNull { it.objOrNull()?.str("name") }.joinToString(", ")

    /** Spotify lists images largest first. Returns (largest, smallest). */
    private fun images(o: JsonObject?): Pair<String?, String?> {
        val urls = o?.arr("images").orEmpty().mapNotNull { it.objOrNull()?.str("url") }
        return urls.firstOrNull() to urls.lastOrNull()
    }
}

internal fun JsonElement?.objOrNull(): JsonObject? = if (this != null && isJsonObject) asJsonObject else null

internal fun JsonObject.obj(key: String): JsonObject? = get(key).objOrNull()

internal fun JsonObject.arr(key: String): List<JsonElement> =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.toList() ?: emptyList()

internal fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

internal fun JsonObject.long(key: String): Long? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

internal fun JsonObject.int(key: String): Int? = long(key)?.toInt()

internal fun JsonObject.bool(key: String): Boolean? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
