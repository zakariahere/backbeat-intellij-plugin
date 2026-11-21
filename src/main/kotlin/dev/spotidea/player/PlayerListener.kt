package dev.spotidea.player

import com.intellij.util.messages.Topic
import dev.spotidea.api.PlaybackState

/** Application-wide events. Always delivered on the EDT, so listeners may touch Swing directly. */
interface PlayerListener {
    /** New playback snapshot (null: nothing is playing anywhere, or we are disconnected). */
    fun playbackChanged(state: PlaybackState?) {}

    /** The user connected or disconnected their Spotify account. */
    fun connectionChanged(connected: Boolean) {}

    /** The focus timer started, ticked, or stopped. */
    fun focusChanged() {}

    companion object {
        @JvmField
        val TOPIC: Topic<PlayerListener> = Topic.create("Backbeat player", PlayerListener::class.java)
    }
}
