package dev.spotidea.player

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import dev.spotidea.api.Device
import dev.spotidea.api.MediaItem
import dev.spotidea.api.PlaybackState
import dev.spotidea.api.RepeatMode
import dev.spotidea.api.SpotifyApi
import dev.spotidea.api.SpotifyException
import dev.spotidea.auth.AuthException
import dev.spotidea.auth.SpotifyAuth
import dev.spotidea.settings.SpotideaSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI

/**
 * The single source of truth for "what is Spotify doing right now".
 *
 * It polls the Web API, publishes snapshots on [PlayerListener.TOPIC], and turns user intent
 * (play, skip, seek...) into API calls. Every UI in the plugin is a thin view over this service.
 */
@Service(Service.Level.APP)
class PlayerService(private val cs: CoroutineScope) {
    private val api get() = SpotifyApi.get()
    private val auth get() = SpotifyAuth.get()

    @Volatile var state: PlaybackState? = null
        private set

    /** Whether the current track is in the user's library; null while unknown. */
    @Volatile var liked: Boolean? = null
        private set

    @Volatile var connecting = false
        private set

    private var connectJob: Job? = null
    private var seekJob: Job? = null
    private var volumeJob: Job? = null
    private var debuggerResumeJob: Job? = null
    @Volatile private var pausedByDebugger = false

    init {
        cs.launch {
            runCatching { auth.restore() }
            publishConnection()
            while (true) {
                val wait = if (auth.connected) {
                    refreshQuietly()
                    when {
                        !ApplicationManager.getApplication().isActive -> 15_000L
                        state?.isPlaying == true -> 3_000L
                        else -> 6_000L
                    }
                } else 5_000L
                delay(wait)
            }
        }
    }

    val isConnected: Boolean get() = auth.connected

    /** Runs [block] on the plugin's background scope; UI code uses this for one-off loads. */
    fun launch(block: suspend CoroutineScope.() -> Unit): Job = cs.launch(block = block)

    // ---- Account ----------------------------------------------------------------------------

    fun connect(onDone: (error: String?) -> Unit = {}) {
        if (connecting) return
        connecting = true
        connectJob = cs.launch {
            val error = try {
                auth.login()
                null
            } catch (e: CancellationException) {
                // cancelConnect(): the login listener has already let go of the callback port.
                connecting = false
                onEdt { onDone("Login cancelled.") }
                throw e
            } catch (e: Exception) {
                if (e !is AuthException) thisLogger().warn("Spotify login failed", e)
                e.message ?: "Login failed"
            }
            connecting = false
            publishConnection()
            if (error == null) refreshQuietly()
            onEdt { onDone(error) }
        }
    }

    /** Stops waiting for the browser, e.g. after Spotify showed an error page instead of redirecting back. */
    fun cancelConnect() {
        connectJob?.cancel()
    }

    fun disconnect() {
        cs.launch {
            auth.logout()
            publish(null)
            publishConnection()
        }
    }

    // ---- Transport controls -----------------------------------------------------------------

    fun togglePlay() {
        val playing = state?.isPlaying == true
        command({ it.copy(isPlaying = !playing, progressMs = it.progressNow(), fetchedAt = System.currentTimeMillis()) }) {
            if (playing) api.pause() else api.play()
        }
    }

    fun pause() {
        if (state?.isPlaying != true) return
        command({ it.copy(isPlaying = false, progressMs = it.progressNow(), fetchedAt = System.currentTimeMillis()) }) { api.pause() }
    }

    fun resume() {
        if (state?.isPlaying == true) return
        command({ it.copy(isPlaying = true, fetchedAt = System.currentTimeMillis()) }) { api.play() }
    }

    fun next() = command { api.next() }

    /** Like every music player: the first press restarts the song, a second one goes back. */
    fun previous() = command {
        if ((state?.progressNow() ?: 0) > 4_000) api.seek(0) else api.previous()
    }

    fun toggleShuffle() {
        val on = state?.shuffle != true
        command({ it.copy(shuffle = on) }) { api.shuffle(on) }
    }

    fun cycleRepeat() {
        val mode = (state?.repeat ?: RepeatMode.OFF).next()
        command({ it.copy(repeat = mode) }) { api.repeat(mode) }
    }

    /** Sliders fire many events while dragging; only the last one within 150 ms reaches Spotify. */
    fun seek(positionMs: Long) {
        state?.let { publish(it.copy(progressMs = positionMs, fetchedAt = System.currentTimeMillis())) }
        seekJob?.cancel()
        seekJob = cs.launch {
            delay(150)
            guarded { api.seek(positionMs) }
            delay(SETTLE_MS)
            refreshQuietly()
        }
    }

    fun setVolume(percent: Int) {
        val value = percent.coerceIn(0, 100)
        state?.let { s -> publish(s.copy(device = s.device?.copy(volumePercent = value))) }
        volumeJob?.cancel()
        volumeJob = cs.launch {
            delay(150)
            guarded { api.volume(value) }
        }
    }

    fun nudgeVolume(direction: Int) {
        val device = state?.device ?: return
        if (!device.supportsVolume) {
            Notifier.warnThrottled("${device.name} does not allow remote volume control.")
            return
        }
        setVolume((device.volumePercent ?: 50) + direction * SpotideaSettings.get().state.volumeStep)
    }

    fun toggleLike() {
        val item = state?.item ?: return
        val target = liked != true
        liked = target
        republish()
        cs.launch {
            guarded { api.setSaved(item.uri, target) }
            refreshLiked(item.uri)
        }
    }

    // ---- Starting music ---------------------------------------------------------------------

    /** Plays an album, playlist or artist; with [offsetUri] it starts at that track inside it. */
    fun playContext(contextUri: String, offsetUri: String? = null) = command { api.play(contextUri = contextUri, offsetUri = offsetUri) }

    /** Plays loose tracks in order (used where Spotify gives us no context, e.g. search results). */
    fun playUris(uris: List<String>) {
        if (uris.isNotEmpty()) command { api.play(uris = uris.take(MAX_URIS)) }
    }

    fun play(item: MediaItem) = if (item.kind.isContext) playContext(item.uri) else playUris(listOf(item.uri))

    fun addToQueue(item: MediaItem) = command {
        api.addToQueue(item.uri)
        Notifier.info("Queued “${item.name}”")
    }

    fun transferTo(device: Device) {
        val id = device.id ?: return
        command { api.transfer(id, play = state?.isPlaying ?: true) }
    }

    // ---- Debugger integration ---------------------------------------------------------------

    /** Called when a breakpoint is hit: silence while you think. */
    fun debuggerPaused() {
        debuggerResumeJob?.cancel()
        if (!SpotideaSettings.get().state.pauseOnBreakpoint || state?.isPlaying != true) return
        pausedByDebugger = true
        pause()
    }

    /**
     * Called when the program runs again. Stepping resumes and re-pauses within milliseconds,
     * so we wait a moment before bringing the music back to avoid stuttering on every F8.
     */
    fun debuggerResumed() {
        if (!pausedByDebugger) return
        debuggerResumeJob?.cancel()
        debuggerResumeJob = cs.launch {
            delay(1_500)
            pausedByDebugger = false
            resume()
        }
    }

    // ---- Internals --------------------------------------------------------------------------

    fun refresh() {
        cs.launch { refreshQuietly() }
    }

    /**
     * Runs a user command: shows the expected result at once, calls Spotify, then re-reads the truth.
     * Spotify needs a few hundred milliseconds before its own state reflects a command.
     */
    private fun command(optimistic: ((PlaybackState) -> PlaybackState)? = null, block: suspend () -> Unit) {
        if (optimistic != null) state?.let { publish(optimistic(it)) }
        cs.launch {
            guarded(block)
            delay(SETTLE_MS)
            refreshQuietly()
        }
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            try {
                block()
            } catch (e: SpotifyException) {
                if (!e.isNoActiveDevice || !wakeUpDevice()) throw e
                block()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report(e)
        }
    }

    /**
     * Spotify forgets the "active device" after a while of silence. If a device is still online
     * we hand playback to it so the command can be retried; returns false when there is none.
     */
    private suspend fun wakeUpDevice(): Boolean {
        val devices = api.devices().filter { !it.isRestricted && it.id != null }
        val target = devices.firstOrNull { it.type.equals("Computer", true) } ?: devices.firstOrNull() ?: return false
        api.transfer(target.id!!, play = false)
        delay(600)
        return true
    }

    private fun report(e: Exception) {
        when {
            e is SpotifyException && e.isNoActiveDevice -> Notifier.warnThrottled(
                "No Spotify device is online. Open Spotify on this computer or your phone, then try again.",
                actions = arrayOf("Open Spotify" to ::openSpotifyApp),
            )
            e is SpotifyException && e.isPremiumRequired -> Notifier.warnThrottled(
                "Spotify only lets Premium accounts control playback from other apps. Now Playing and search still work.",
                quietMs = 3_600_000,
            )
            e is SpotifyException && e.isRateLimited -> thisLogger().info("Spotify rate limit: ${e.message}")
            e is AuthException -> {
                Notifier.warnThrottled(e.message ?: "Not connected to Spotify")
                publishConnection()
            }
            e is SpotifyException -> Notifier.warnThrottled("Spotify: ${e.message}")
            else -> {
                thisLogger().info("Spotify request failed", e)
                Notifier.warnThrottled("Could not reach Spotify (${e.javaClass.simpleName}). Check your connection.", quietMs = 120_000)
            }
        }
    }

    private suspend fun refreshQuietly() {
        try {
            val fresh = api.playback()
            val previousUri = state?.item?.uri
            val uri = fresh?.item?.uri
            if (uri != previousUri) {
                liked = null
                if (uri != null) cs.launch { refreshLiked(uri) }
            }
            publish(fresh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthException) {
            publish(null)
            publishConnection()
        } catch (e: Exception) {
            // Polling failures are routine (offline, sleep, rate limit); keep the last known state.
            thisLogger().debug("Playback poll failed", e)
        }
    }

    private suspend fun refreshLiked(uri: String) {
        val saved = try {
            api.isSaved(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        if (state?.item?.uri == uri) {
            liked = saved
            republish()
        }
    }

    private fun republish() = publish(state)

    private fun publish(newState: PlaybackState?) {
        state = newState
        onEdt { ApplicationManager.getApplication().messageBus.syncPublisher(PlayerListener.TOPIC).playbackChanged(newState) }
    }

    private fun publishConnection() {
        val connected = auth.connected
        onEdt { ApplicationManager.getApplication().messageBus.syncPublisher(PlayerListener.TOPIC).connectionChanged(connected) }
    }

    /** ModalityState.any(): the Settings dialog is modal and must still see connection updates. */
    private fun onEdt(block: () -> Unit) = ApplicationManager.getApplication().invokeLater(block, ModalityState.any())

    companion object {
        private const val SETTLE_MS = 450L
        private const val MAX_URIS = 100

        fun get(): PlayerService = service()

        /** `spotify:` is the desktop app's URL scheme; fall back to the web player without it. */
        fun openSpotifyApp() {
            val opened = runCatching {
                Desktop.getDesktop().browse(URI("spotify:"))
            }.isSuccess
            if (!opened) BrowserUtil.browse("https://open.spotify.com")
        }
    }
}
