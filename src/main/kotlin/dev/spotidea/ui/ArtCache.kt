package dev.spotidea.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import dev.spotidea.SpotideaIcons
import dev.spotidea.player.PlayerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.swing.Icon

/** Loads cover art off the UI thread and keeps a small LRU of ready-to-paint icons. */
object ArtCache {
    private const val MAX_ENTRIES = 250

    private val cache = object : LinkedHashMap<String, Icon>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Icon>?) = size > MAX_ENTRIES
    }
    private val waiting = ConcurrentHashMap<String, MutableList<() -> Unit>>()

    /**
     * Returns the cover immediately when cached. Otherwise returns a placeholder, starts the
     * download, and calls [onLoaded] on the EDT once the real cover can be fetched with another call.
     */
    fun get(url: String?, size: Int, onLoaded: () -> Unit): Icon {
        if (url.isNullOrBlank()) return placeholder(size)
        val key = "$size|$url"
        synchronized(cache) { cache[key] }?.let { return it }

        var isFirst = false
        waiting.compute(key) { _, callbacks ->
            if (callbacks == null) {
                isFirst = true
                mutableListOf(onLoaded)
            } else {
                if (callbacks.size < 8) callbacks.add(onLoaded)
                callbacks
            }
        }
        if (isFirst) {
            PlayerService.get().launch {
                val image = withContext(Dispatchers.IO) { runCatching { ImageIO.read(URI(url).toURL()) }.getOrNull() }
                // A failed download is cached as the placeholder, otherwise every repaint would retry it.
                val icon = if (image != null) CoverIcon(shrink(image, size), size) else placeholder(size)
                synchronized(cache) { cache[key] = icon }
                val callbacks = waiting.remove(key).orEmpty()
                if (image != null) {
                    ApplicationManager.getApplication().invokeLater({ callbacks.forEach { it() } }, ModalityState.any())
                }
            }
        }
        return placeholder(size)
    }

    fun placeholder(size: Int): Icon = PlaceholderIcon(size)

    /** Pre-scales to 2x the logical size: sharp on HiDPI screens, small enough to cache by the hundred. */
    private fun shrink(source: BufferedImage, size: Int): BufferedImage {
        val target = (JBUI.scale(size) * 2).coerceAtMost(maxOf(source.width, source.height))
        val result = BufferedImage(target, target, BufferedImage.TYPE_INT_ARGB)
        val g = result.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        // Centre-crop to a square so non-square playlist mosaics are not squashed.
        val side = minOf(source.width, source.height)
        val sx = (source.width - side) / 2
        val sy = (source.height - side) / 2
        g.drawImage(source, 0, 0, target, target, sx, sy, sx + side, sy + side, null)
        g.dispose()
        return result
    }

    private fun radius(size: Int): Float = JBUI.scale(if (size >= 64) 8 else 4).toFloat()

    private class CoverIcon(private val image: BufferedImage, private val size: Int) : Icon {
        override fun getIconWidth() = JBUI.scale(size)
        override fun getIconHeight() = JBUI.scale(size)

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                val r = radius(size)
                g2.clip(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), iconWidth.toFloat(), iconHeight.toFloat(), r, r))
                g2.drawImage(image, x, y, iconWidth, iconHeight, null)
            } finally {
                g2.dispose()
            }
        }
    }

    private class PlaceholderIcon(private val size: Int) : Icon {
        override fun getIconWidth() = JBUI.scale(size)
        override fun getIconHeight() = JBUI.scale(size)

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val r = radius(size)
                g2.color = BACKGROUND
                g2.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), iconWidth.toFloat(), iconHeight.toFloat(), r, r))
                val note = SpotideaIcons.Note
                note.paintIcon(c, g2, x + (iconWidth - note.iconWidth) / 2, y + (iconHeight - note.iconHeight) / 2)
            } finally {
                g2.dispose()
            }
        }
    }

    private val BACKGROUND = JBColor(0xE4E6EB, 0x3A3D44)
}

internal fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0)) / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
