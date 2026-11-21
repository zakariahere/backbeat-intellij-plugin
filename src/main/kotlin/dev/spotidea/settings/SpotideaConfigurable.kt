package dev.spotidea.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import dev.spotidea.player.PlayerService

class SpotideaConfigurable : BoundConfigurable("Backbeat") {
    private val status = JBLabel()

    override fun createPanel(): DialogPanel {
        val settings = SpotideaSettings.get().state
        val player = PlayerService.get()
        showStatus()

        return panel {
            group("Spotify Account") {
                row("Client ID:") {
                    textField()
                        .bindText({ settings.clientId.orEmpty() }, { settings.clientId = it.trim() })
                        .columns(COLUMNS_LARGE)
                        .comment("From your own app in the <a href='https://developer.spotify.com/dashboard'>Spotify Developer Dashboard</a>.")
                }
                row("Callback port:") {
                    intTextField(1024..65535).bindIntText(settings::callbackPort)
                        .comment("Register <b>http://127.0.0.1:&lt;port&gt;/callback</b> as the Redirect URI of your Spotify app, exactly: http, the IP not localhost, no trailing slash.")
                }
                row {
                    button("Connect") {
                        // Log in with what is typed right now, not with the last applied value.
                        apply()
                        status.text = "Waiting for you to approve in the browser…"
                        player.connect { error -> if (error != null) status.text = error else showStatus() }
                    }
                    button("Disconnect") {
                        player.disconnect()
                        status.text = "Not connected"
                    }
                    cell(status)
                }
            }
            group("While You Code") {
                row {
                    checkBox("Pause the music when a breakpoint is hit")
                        .bindSelected(settings::pauseOnBreakpoint)
                        .comment("It comes back by itself when the program continues. Stepping does not make it stutter.")
                }
                row {
                    checkBox("Start the project's soundtrack when a project opens")
                        .bindSelected(settings::autoPlaySoundtrack)
                        .comment("Pin one with <b>Tools | Backbeat | Use Current Album or Playlist as Project Soundtrack</b>. Never interrupts music that is already playing.")
                }
                row("Focus session:") {
                    intTextField(1..240).bindIntText(settings::focusMinutes).columns(4).gap(RightGap.SMALL)
                    label("minutes of music, then").gap(RightGap.SMALL)
                    intTextField(1..120).bindIntText(settings::breakMinutes).columns(4).gap(RightGap.SMALL)
                    label("minutes of silence")
                }
            }
            group("Controls") {
                row("Volume step:") {
                    intTextField(1..50).bindIntText(settings::volumeStep).columns(4).gap(RightGap.SMALL)
                    label("% per shortcut press or scroll notch")
                }
                row("Status bar text:") {
                    intTextField(10..120).bindIntText(settings::statusBarMaxChars).columns(4).gap(RightGap.SMALL)
                    label("characters at most")
                }
                row {
                    comment("Shortcuts for every action live in <b>Settings | Keymap</b>: search for “Backbeat”.")
                }
            }
        }
    }

    private fun showStatus() {
        status.text = if (PlayerService.get().isConnected) "Connected" else "Not connected"
    }
}
