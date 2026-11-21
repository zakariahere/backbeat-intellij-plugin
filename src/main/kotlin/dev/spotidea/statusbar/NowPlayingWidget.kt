package dev.spotidea.statusbar

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.spotidea.SpotideaIcons
import dev.spotidea.api.PlaybackState
import dev.spotidea.dev.FocusService
import dev.spotidea.player.PlayerListener
import dev.spotidea.player.PlayerService
import dev.spotidea.settings.SpotideaSettings
import dev.spotidea.ui.NowPlayingPanel
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.SwingUtilities

class NowPlayingWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = NowPlayingWidget.ID
    override fun getDisplayName(): String = "Backbeat Now Playing"
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = NowPlayingWidget(project)
}

/**
 * "♫ Song · Artist" in the status bar. Click for a mini player, middle-click to play/pause,
 * scroll to change the volume. In a focus session it also shows the countdown.
 */
class NowPlayingWidget(private val project: Project) : CustomStatusBarWidget, PlayerListener {
    private val player = PlayerService.get()
    private val label = JBLabel().apply {
        border = JBUI.Borders.empty(0, 6)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    init {
        label.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                when {
                    SwingUtilities.isMiddleMouseButton(e) -> player.togglePlay()
                    !SwingUtilities.isLeftMouseButton(e) -> Unit
                    player.isConnected -> showMiniPlayer()
                    else -> ToolWindowManager.getInstance(project).getToolWindow("Backbeat")?.activate(null)
                }
            }
        })
        label.addMouseWheelListener { e -> if (e.wheelRotation != 0) player.nudgeVolume(if (e.wheelRotation < 0) 1 else -1) }
    }

    override fun ID(): String = ID

    override fun getComponent(): JComponent = label

    override fun install(statusBar: StatusBar) {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(PlayerListener.TOPIC, this)
        render()
    }

    override fun playbackChanged(state: PlaybackState?) = render()
    override fun connectionChanged(connected: Boolean) = render()
    override fun focusChanged() = render()

    private fun render() {
        val state = player.state
        val item = state?.item
        val focus = FocusService.get()
        val timer = focus.remaining()?.let { if (focus.phase == FocusService.Phase.BREAK) "Break $it" else it }

        val song = item?.let {
            val artist = it.subtitle.substringBefore(" • ")
            StringUtil.shortenTextWithEllipsis(
                if (artist.isBlank()) it.name else "${it.name} · $artist",
                SpotideaSettings.get().state.statusBarMaxChars.coerceIn(10, 120), 0,
            )
        }
        label.icon = when {
            focus.isRunning -> SpotideaIcons.FocusOn
            state?.isPlaying == true -> SpotideaIcons.Note
            item != null -> SpotideaIcons.Pause
            else -> SpotideaIcons.Note
        }
        label.text = listOfNotNull(timer, song).joinToString("   ")
        label.toolTipText = when {
            !player.isConnected -> "Backbeat: click to connect Spotify"
            item == null -> "Backbeat: nothing playing. Click for the player"
            else -> "<html><b>${StringUtil.escapeXmlEntities(item.name)}</b><br>${StringUtil.escapeXmlEntities(item.subtitle)}" +
                "<br><br>Click: mini player &nbsp; Middle-click: play/pause &nbsp; Scroll: volume</html>"
        }
    }

    private fun showMiniPlayer() {
        val holder = Disposer.newDisposable("Backbeat mini player")
        val panel = NowPlayingPanel(holder, artSize = 72)
        panel.preferredSize = Dimension(JBUI.scale(360), panel.preferredSize.height)
        val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, panel.preferredFocus)
            .setRequestFocus(true)
            .setFocusable(true)
            .setMovable(true)
            .setCancelOnClickOutside(true)
            .createPopup()
        Disposer.register(popup, holder)
        val size = panel.preferredSize
        // Anchor the popup's bottom-right corner just above the widget.
        popup.show(RelativePoint(label, Point(label.width - size.width, -size.height - JBUI.scale(6))))
    }

    override fun dispose() = Unit

    companion object {
        const val ID = "SpotideaNowPlaying"
    }
}
