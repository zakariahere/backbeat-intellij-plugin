package dev.spotidea.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/** IDE-wide preferences. Tokens are NOT stored here; they live in the IDE password safe (see SpotifyAuth). */
@Service(Service.Level.APP)
@State(name = "SpotideaSettings", storages = [Storage("spotidea.xml")])
class SpotideaSettings : SimplePersistentStateComponent<SpotideaSettings.State>(State()) {

    class State : BaseState() {
        var clientId by string("")
        var callbackPort by property(DEFAULT_PORT)
        var statusBarMaxChars by property(45)
        var volumeStep by property(10)
        var pauseOnBreakpoint by property(false)
        var autoPlaySoundtrack by property(false)
        var focusMinutes by property(25)
        var breakMinutes by property(5)
    }

    val clientId: String get() = state.clientId.orEmpty().trim()

    val redirectUri: String get() = redirectUri(state.callbackPort)

    companion object {
        const val DEFAULT_PORT = 43897

        fun get(): SpotideaSettings = service()

        /** Spotify only accepts loopback redirects as an IP literal; `localhost` is rejected. */
        fun redirectUri(port: Int): String = "http://127.0.0.1:$port/callback"
    }
}
