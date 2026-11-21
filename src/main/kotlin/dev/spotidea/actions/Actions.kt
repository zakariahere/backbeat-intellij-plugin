package dev.spotidea.actions

import com.intellij.ide.actions.searcheverywhere.SearchEverywhereManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import dev.spotidea.SpotideaIcons
import dev.spotidea.api.PlaybackState
import dev.spotidea.api.RepeatMode
import dev.spotidea.api.SpotifyApi
import dev.spotidea.dev.FocusService
import dev.spotidea.dev.ProjectSoundtrack
import dev.spotidea.player.Notifier
import dev.spotidea.player.PlayerService
import dev.spotidea.search.SpotifySearchContributor
import dev.spotidea.settings.SpotideaConfigurable
import java.awt.datatransfer.StringSelection

/**
 * Base for everything that needs a connected account. Presentations are computed on a background
 * thread (BGT): they only read volatile fields of [PlayerService], never Swing or PSI.
 */
abstract class PlayerAction : DumbAwareAction() {
    protected val player: PlayerService get() = PlayerService.get()

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = player.isConnected && isAvailable(player.state)
        customize(e.presentation, player.state)
    }

    protected open fun isAvailable(state: PlaybackState?): Boolean = true
    protected open fun customize(presentation: Presentation, state: PlaybackState?) {}
}

class PlayPauseAction : PlayerAction() {
    override fun customize(presentation: Presentation, state: PlaybackState?) {
        val playing = state?.isPlaying == true
        presentation.icon = if (playing) SpotideaIcons.Pause else SpotideaIcons.Play
        presentation.text = if (playing) "Pause" else "Play"
    }

    override fun actionPerformed(e: AnActionEvent) = player.togglePlay()
}

class NextTrackAction : PlayerAction() {
    override fun actionPerformed(e: AnActionEvent) = player.next()
}

class PreviousTrackAction : PlayerAction() {
    override fun actionPerformed(e: AnActionEvent) = player.previous()
}

class ToggleShuffleAction : PlayerAction() {
    override fun customize(presentation: Presentation, state: PlaybackState?) {
        val on = state?.shuffle == true
        presentation.icon = if (on) SpotideaIcons.ShuffleOn else SpotideaIcons.Shuffle
        presentation.text = if (on) "Shuffle: On" else "Shuffle: Off"
    }

    override fun actionPerformed(e: AnActionEvent) = player.toggleShuffle()
}

class CycleRepeatAction : PlayerAction() {
    override fun customize(presentation: Presentation, state: PlaybackState?) {
        val mode = state?.repeat ?: RepeatMode.OFF
        presentation.icon = when (mode) {
            RepeatMode.OFF -> SpotideaIcons.Repeat
            RepeatMode.CONTEXT -> SpotideaIcons.RepeatOn
            RepeatMode.TRACK -> SpotideaIcons.RepeatOne
        }
        presentation.text = when (mode) {
            RepeatMode.OFF -> "Repeat: Off"
            RepeatMode.CONTEXT -> "Repeat: All"
            RepeatMode.TRACK -> "Repeat: This Song"
        }
    }

    override fun actionPerformed(e: AnActionEvent) = player.cycleRepeat()
}

class ToggleLikeAction : PlayerAction() {
    override fun isAvailable(state: PlaybackState?) = state?.item != null

    override fun customize(presentation: Presentation, state: PlaybackState?) {
        val liked = player.liked == true
        presentation.icon = if (liked) SpotideaIcons.HeartFilled else SpotideaIcons.Heart
        presentation.text = if (liked) "Remove from Liked Songs" else "Save to Liked Songs"
    }

    override fun actionPerformed(e: AnActionEvent) = player.toggleLike()
}

class VolumeUpAction : PlayerAction() {
    override fun isAvailable(state: PlaybackState?) = state?.device != null
    override fun actionPerformed(e: AnActionEvent) = player.nudgeVolume(+1)
}

class VolumeDownAction : PlayerAction() {
    override fun isAvailable(state: PlaybackState?) = state?.device != null
    override fun actionPerformed(e: AnActionEvent) = player.nudgeVolume(-1)
}

class CopySongLinkAction : PlayerAction() {
    override fun isAvailable(state: PlaybackState?) = state?.item != null

    override fun actionPerformed(e: AnActionEvent) {
        val item = player.state?.item ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(item.webUrl))
        Notifier.info("Copied the link to “${item.name}”.", e.project)
    }
}

/** Opens Search Everywhere directly on the Spotify tab. */
class SearchSpotifyAction : PlayerAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        SearchEverywhereManager.getInstance(project).show(SpotifySearchContributor.ID, null, e)
    }
}

class FocusSessionAction : PlayerAction() {
    override fun customize(presentation: Presentation, state: PlaybackState?) {
        val focus = FocusService.get()
        presentation.icon = if (focus.isRunning) SpotideaIcons.FocusOn else SpotideaIcons.Focus
        presentation.text = if (focus.isRunning) "Stop Focus Session (${focus.remaining()})" else "Start Focus Session"
    }

    override fun actionPerformed(e: AnActionEvent) = FocusService.get().toggle()
}

/** Remembers whatever album or playlist is playing as this project's soundtrack. */
class PinSoundtrackAction : PlayerAction() {
    override fun isAvailable(state: PlaybackState?) = state?.contextUri != null

    override fun update(e: AnActionEvent) {
        super.update(e)
        if (e.project == null) e.presentation.isEnabled = false
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val uri = player.state?.contextUri ?: return
        player.launch {
            val name = SpotifyApi.get().contextName(uri)
            ProjectSoundtrack.get(project).pin(uri, name)
        }
    }
}

class PlaySoundtrackAction : PlayerAction() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        val soundtrack = e.project?.let { ProjectSoundtrack.get(it) }
        e.presentation.isEnabled = e.presentation.isEnabled && soundtrack?.uri != null
        e.presentation.text = soundtrack?.name?.let { "Play Project Soundtrack (${it.take(40)})" } ?: "Play Project Soundtrack"
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { ProjectSoundtrack.get(it) }?.play()
    }
}

class OpenSettingsAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, SpotideaConfigurable::class.java)
    }
}
