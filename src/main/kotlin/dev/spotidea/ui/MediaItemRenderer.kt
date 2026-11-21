package dev.spotidea.ui

import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.spotidea.api.MediaItem
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/**
 * Two-line row: cover, title, subtitle and duration. Typed on `Any?` so the very same renderer
 * also serves the Search Everywhere popup, whose list is not typed.
 */
class MediaItemRenderer : ListCellRenderer<Any?> {
    private val cover = JBLabel()
    private val title = JBLabel()
    private val subtitle = JBLabel().apply { font = JBFont.small() }
    private val duration = JBLabel().apply { font = JBFont.small() }
    private val row = JPanel(BorderLayout(JBUI.scale(10), 0))

    init {
        val texts = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(title)
            add(subtitle)
        }
        listOf(title, subtitle).forEach { it.minimumSize = Dimension(0, 0) }
        row.border = JBUI.Borders.empty(4, 10)
        row.add(cover, BorderLayout.WEST)
        row.add(texts, BorderLayout.CENTER)
        row.add(duration, BorderLayout.EAST)
    }

    override fun getListCellRendererComponent(
        list: JList<out Any?>,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val item = value as? MediaItem
        val focused = isSelected && (list.hasFocus() || cellHasFocus)
        row.background = UIUtil.getListBackground(isSelected, focused)
        val foreground = UIUtil.getListForeground(isSelected, focused)
        val secondary = if (isSelected && focused) foreground else UIUtil.getContextHelpForeground()

        cover.icon = ArtCache.get(item?.thumbUrl ?: item?.imageUrl, COVER) { list.repaint() }
        title.text = item?.name.orEmpty()
        title.foreground = foreground
        subtitle.text = item?.subtitle.orEmpty().ifBlank { " " }
        subtitle.foreground = secondary
        duration.text = item?.takeIf { it.durationMs > 0 }?.let { formatTime(it.durationMs) }.orEmpty()
        duration.foreground = secondary
        return row
    }

    companion object {
        const val COVER = 34
    }
}

/** One-line renderer for combo boxes and small choosers. */
internal fun <T> textRenderer(icon: Icon? = null, text: (T?) -> String): ListCellRenderer<T?> =
    object : SimpleListCellRenderer<T?>() {
        override fun customize(list: JList<out T?>, value: T?, index: Int, selected: Boolean, hasFocus: Boolean) {
            setIcon(icon)
            setText(text(value))
        }
    }
