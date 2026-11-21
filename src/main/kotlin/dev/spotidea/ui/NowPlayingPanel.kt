package dev.spotidea.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.spotidea.SpotideaIcons
import dev.spotidea.api.Device
import dev.spotidea.api.PlaybackState
import dev.spotidea.api.SpotifyApi
import dev.spotidea.player.PlayerListener
import dev.spotidea.player.PlayerService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.SwingConstants
import javax.swing.Timer

/**
 * Cover, titles, transport buttons, seek bar, device and volume. Used both as the header of the
 * tool window and as the popup mini player of the status bar widget.
 */
class NowPlayingPanel(parent: Disposable, artSize: Int = 84) : JPanel(BorderLayout()), PlayerListener, Disposable {
    private val player = PlayerService.get()
    private val artSize = artSize

    private val art = JBLabel(ArtCache.placeholder(artSize))
    private val title = JBLabel("Nothing playing").apply { font = JBFont.label().biggerOn(2f).asBold() }
    private val artist = JBLabel(" ")
    private val context = JBLabel(" ").apply { foreground = UIUtil.getContextHelpForeground(); font = JBFont.small() }

    private val elapsed = timeLabel()
    private val total = timeLabel()
    private val progress = JSlider(0, 1, 0).apply { isOpaque = false; isFocusable = false }
    private val volume = JSlider(0, 100, 50).apply {
        isOpaque = false
        isFocusable = false
        preferredSize = Dimension(JBUI.scale(90), preferredSize.height)
        toolTipText = "Volume"
    }
    private val deviceLink = ActionLink("No device") { showDevices() }.apply {
        icon = SpotideaIcons.Device
        toolTipText = "Choose where Spotify plays"
    }
    private val toolbar: ActionToolbar

    /** True while we set slider values ourselves, so the change listeners can tell us from the user. */
    private var syncing = false
    private var shownArtUrl: String? = null

    /** Repaints the seek bar between polls; the real position only arrives every few seconds. */
    private val ticker = Timer(500) { tick() }

    init {
        border = JBUI.Borders.empty(10, 12, 6, 12)
        isOpaque = false

        val texts = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.emptyLeft(12)
            add(title)
            add(javax.swing.Box.createVerticalStrut(JBUI.scale(3)))
            add(artist)
            add(javax.swing.Box.createVerticalStrut(JBUI.scale(3)))
            add(context)
        }
        // A zero minimum width lets long titles shrink into an ellipsis instead of widening the panel.
        listOf(title, artist, context).forEach { it.minimumSize = Dimension(0, it.preferredSize.height) }

        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(art, BorderLayout.WEST)
            add(texts, BorderLayout.CENTER)
        }

        val group = ActionManager.getInstance().getAction("Spotidea.PlayerControls") as ActionGroup
        toolbar = ActionManager.getInstance().createActionToolbar("SpotideaPlayer", group, true).apply {
            targetComponent = this@NowPlayingPanel
            component.isOpaque = false
            component.border = JBUI.Borders.empty()
        }
        val controls = JPanel(FlowLayout(FlowLayout.CENTER, 0, 0)).apply {
            isOpaque = false
            add(toolbar.component)
        }

        val seekRow = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(elapsed, BorderLayout.WEST)
            add(progress, BorderLayout.CENTER)
            add(total, BorderLayout.EAST)
        }

        val volumeBox = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(JBLabel(SpotideaIcons.Volume))
            add(volume)
        }
        val deviceRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(deviceLink, BorderLayout.WEST)
            add(volumeBox, BorderLayout.EAST)
        }

        val lower = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.emptyTop(8)
            add(controls)
            add(seekRow)
            add(deviceRow)
        }
        add(header, BorderLayout.NORTH)
        add(lower, BorderLayout.CENTER)

        wireSliders()
        com.intellij.openapi.util.Disposer.register(parent, this)
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(PlayerListener.TOPIC, this)
        render(player.state)
        ticker.start()
    }

    private fun timeLabel() = JBLabel("0:00", SwingConstants.CENTER).apply {
        font = JBFont.small()
        foreground = UIUtil.getContextHelpForeground()
        // Fixed width so the seek bar does not jitter as digits change.
        preferredSize = Dimension(JBUI.scale(38), preferredSize.height)
    }

    private fun wireSliders() {
        progress.addChangeListener {
            if (syncing) return@addChangeListener
            elapsed.text = formatTime(progress.value.toLong())
            if (!progress.valueIsAdjusting) player.seek(progress.value.toLong())
        }
        volume.addChangeListener {
            if (!syncing && !volume.valueIsAdjusting) player.setVolume(volume.value)
        }
        // Swing sliders only step towards a click; music players jump straight to it.
        jumpToClick(progress)
        jumpToClick(volume)
    }

    private fun jumpToClick(slider: JSlider) {
        slider.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!slider.isEnabled || slider.width <= 0) return
                val inset = JBUI.scale(8)
                val fraction = ((e.x - inset).toDouble() / (slider.width - 2 * inset)).coerceIn(0.0, 1.0)
                slider.value = slider.minimum + (fraction * (slider.maximum - slider.minimum)).toInt()
            }
        })
    }

    override fun playbackChanged(state: PlaybackState?) = render(state)

    private fun render(state: PlaybackState?) {
        val item = state?.item
        title.text = item?.name ?: if (player.isConnected) "Nothing playing" else "Not connected"
        title.toolTipText = item?.name
        artist.text = item?.subtitle?.substringBefore(" • ")?.ifBlank { " " } ?: " "
        context.text = item?.subtitle?.substringAfter(" • ", "")?.ifBlank { " " } ?: " "

        if (item?.imageUrl != shownArtUrl || art.icon == null) {
            shownArtUrl = item?.imageUrl
            art.icon = ArtCache.get(item?.imageUrl, artSize) {
                if (shownArtUrl == item?.imageUrl) art.icon = ArtCache.get(item?.imageUrl, artSize) {}
            }
        }

        syncing = true
        try {
            val duration = (item?.durationMs ?: 0).toInt()
            progress.isEnabled = item != null && duration > 0
            progress.maximum = maxOf(duration, 1)
            total.text = formatTime(duration.toLong())

            val device = state?.device
            deviceLink.text = device?.name ?: "No active device"
            volume.isEnabled = device?.supportsVolume == true
            if (!volume.valueIsAdjusting) device?.volumePercent?.let { volume.value = it }
        } finally {
            syncing = false
        }
        tick()
        toolbar.updateActionsAsync()
    }

    private fun tick() {
        if (progress.valueIsAdjusting) return
        val position = player.state?.progressNow() ?: 0
        syncing = true
        try {
            progress.value = position.toInt()
        } finally {
            syncing = false
        }
        elapsed.text = formatTime(position)
    }

    private fun showDevices() {
        player.launch {
            val devices = runCatching { SpotifyApi.get().devices() }.getOrDefault(emptyList())
            ApplicationManager.getApplication().invokeLater({ showDevicePopup(devices) }, ModalityState.any())
        }
    }

    private fun showDevicePopup(devices: List<Device>) {
        if (!isShowing) return
        if (devices.isEmpty()) {
            JBPopupFactory.getInstance()
                .createMessage("No devices online. Open Spotify on this computer or your phone.")
                .showUnderneathOf(deviceLink)
            return
        }
        JBPopupFactory.getInstance().createPopupChooserBuilder(devices)
            .setTitle("Play On")
            .setRenderer(textRenderer(SpotideaIcons.Device) { device ->
                device?.let { it.name + (if (it.isActive) "  (playing here)" else "") + "  —  " + it.type }.orEmpty()
            })
            .setItemChosenCallback { player.transferTo(it) }
            .createPopup()
            .showUnderneathOf(deviceLink)
    }

    val preferredFocus: JComponent get() = toolbar.component

    override fun dispose() {
        ticker.stop()
    }
}
