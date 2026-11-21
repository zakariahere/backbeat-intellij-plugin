package dev.spotidea

import com.google.gson.JsonParser
import dev.spotidea.api.MediaKind
import dev.spotidea.api.PlaybackState
import dev.spotidea.api.RepeatMode
import dev.spotidea.api.SpotifyParser
import dev.spotidea.auth.Pkce
import dev.spotidea.settings.SpotideaSettings
import dev.spotidea.ui.formatTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreLogicTest {
    private fun json(text: String) = JsonParser.parseString(text).asJsonObject

    @Test
    fun `pkce challenge matches the RFC 7636 test vector`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun `pkce verifier is long, url safe and random`() {
        val verifier = Pkce.newVerifier()
        assertTrue(verifier.length in 43..128)
        assertTrue(verifier.matches(Regex("[A-Za-z0-9_-]+")))
        assertNotEquals(verifier, Pkce.newVerifier())
    }

    @Test
    fun `query strings round trip through encoding`() {
        val params = mapOf("q" to "daft punk & friends", "uri" to "spotify:track:1")
        assertEquals(params, Pkce.parseQuery(Pkce.formEncode(params)))
        assertEquals(mapOf("code" to "abc", "state" to ""), Pkce.parseQuery("code=abc&state="))
    }

    @Test
    fun `redirect uri uses the loopback ip literal`() {
        assertEquals("http://127.0.0.1:43897/callback", SpotideaSettings.redirectUri(43897))
    }

    @Test
    fun `parses a playback snapshot`() {
        val state = SpotifyParser.playback(
            json(
                """
                {"device":{"id":"d1","name":"Desk","type":"Computer","is_active":true,"volume_percent":40,"supports_volume":true},
                 "repeat_state":"context","shuffle_state":true,"progress_ms":1500,"is_playing":true,
                 "context":{"uri":"spotify:playlist:p1"},
                 "item":{"type":"track","uri":"spotify:track:t1","id":"t1","name":"Song","duration_ms":200000,
                         "artists":[{"name":"A"},{"name":"B"}],
                         "album":{"name":"LP","images":[{"url":"big"},{"url":"mid"},{"url":"small"}]}}}
                """
            )
        )
        assertTrue(state.isPlaying)
        assertTrue(state.shuffle)
        assertEquals(RepeatMode.CONTEXT, state.repeat)
        assertEquals("spotify:playlist:p1", state.contextUri)
        assertEquals("Desk", state.device?.name)
        assertEquals(40, state.device?.volumePercent)
        val item = state.item!!
        assertEquals(MediaKind.TRACK, item.kind)
        assertEquals("A, B • LP", item.subtitle)
        assertEquals("big", item.imageUrl)
        assertEquals("small", item.thumbUrl)
        assertEquals("https://open.spotify.com/track/t1", item.webUrl)
    }

    @Test
    fun `tolerates missing and null fields`() {
        val state = SpotifyParser.playback(json("""{"device":null,"item":null,"is_playing":false,"progress_ms":null}"""))
        assertNull(state.item)
        assertNull(state.device)
        assertFalse(state.isPlaying)
        assertEquals(0, state.progressMs)
        assertEquals(RepeatMode.OFF, state.repeat)
    }

    @Test
    fun `playlist entries accept both the new item key and the old track key`() {
        val track = """{"type":"track","uri":"spotify:track:%s","name":"n","artists":[]}"""
        val entries = SpotifyParser.playlistEntries(
            json("""{"items":[{"item":${track.format("new")}},{"track":${track.format("old")}},{"item":null},null]}""")
        )
        assertEquals(listOf("spotify:track:new", "spotify:track:old"), entries.map { it.uri })
    }

    @Test
    fun `search results keep the requested order and skip nulls`() {
        val root = json(
            """
            {"artists":{"items":[{"type":"artist","uri":"spotify:artist:a","id":"a","name":"Art"}]},
             "tracks":{"items":[null,{"type":"track","uri":"spotify:track:t","id":"t","name":"T","artists":[]}]},
             "playlists":{"items":[null]}}
            """
        )
        val found = SpotifyParser.searchResults(root, listOf(MediaKind.TRACK, MediaKind.ARTIST, MediaKind.PLAYLIST))
        assertEquals(listOf(MediaKind.TRACK, MediaKind.ARTIST), found.map { it.kind })
    }

    @Test
    fun `progress is interpolated while playing and clamped to the duration`() {
        val item = SpotifyParser.item(json("""{"type":"track","uri":"spotify:track:t","name":"T","duration_ms":10000}"""))
        val playing = PlaybackState(null, item, true, 4000, false, RepeatMode.OFF, null, fetchedAt = 1_000)
        assertEquals(6000, playing.progressNow(now = 3_000))
        assertEquals(10000, playing.progressNow(now = 60_000))
        assertEquals(4000, playing.copy(isPlaying = false).progressNow(now = 60_000))
    }

    @Test
    fun `repeat cycles off, all, one`() {
        assertEquals(RepeatMode.CONTEXT, RepeatMode.OFF.next())
        assertEquals(RepeatMode.TRACK, RepeatMode.CONTEXT.next())
        assertEquals(RepeatMode.OFF, RepeatMode.TRACK.next())
    }

    @Test
    fun `time formatting`() {
        assertEquals("0:00", formatTime(0))
        assertEquals("3:07", formatTime(187_000))
        assertEquals("1:02:03", formatTime(3_723_000))
        assertEquals("0:00", formatTime(-5))
    }
}
