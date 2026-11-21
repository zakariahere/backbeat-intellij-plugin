package dev.spotidea.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.JBMenuItem
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.spotidea.api.MediaItem
import dev.spotidea.api.MediaKind
import dev.spotidea.api.SpotifyApi
import dev.spotidea.api.SpotifyException
import dev.spotidea.dev.ProjectSoundtrack
import dev.spotidea.player.PlayerService
import kotlinx.coroutines.CancellationException
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

/**
 * A lazily paged list of Spotify things that knows how to play them and how to drill into an
 * album or playlist (with a Back link). Search, Library and Queue are all instances of this.
 */
class MediaBrowser(private val project: Project) : JPanel(BorderLayout()) {

    /**
     * One screen of the browser.
     * @param contextUri when set, tracks are started *inside* this album/playlist so playback continues naturally
     * @param paged whether [load] should be called again with a larger offset when the user scrolls to the end
     */
    class View(
        val title: String? = null,
        val contextUri: String? = null,
        val paged: Boolean = false,
        val emptyText: String = "Nothing here",
        val load: suspend (offset: Int) -> List<MediaItem>,
    )

    private val player = PlayerService.get()
    private val model = CollectionListModel<MediaItem>()
    private val list = JBList(model)
    private val backLink = ActionLink("← Back") { root?.let { show(it) } }
    private val playAllLink = ActionLink("Play") { current?.contextUri?.let(player::playContext) }
    private val titleLabel = JBLabel().apply { font = JBFont.label().asBold() }
    private val header = JPanel(BorderLayout(JBUI.scale(8), 0))

    private var root: View? = null
    private var current: View? = null
    private var loading = false
    private var exhausted = false

    /** Bumped on every navigation so a slow response for an old screen is dropped, not rendered. */
    private var generation = 0

    init {
        list.cellRenderer = MediaItemRenderer()
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.emptyText.text = ""
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) list.selectedValue?.let(::activate)
            }

            override fun mousePressed(e: MouseEvent) = maybePopup(e)
            override fun mouseReleased(e: MouseEvent) = maybePopup(e)
        })
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> list.selectedValue?.let(::activate)
                    KeyEvent.VK_BACK_SPACE, KeyEvent.VK_LEFT -> if (header.isVisible) root?.let { show(it) }
                }
            }
        })

        val scroll = ScrollPaneFactory.createScrollPane(list, true)
        scroll.verticalScrollBar.addAdjustmentListener { e ->
            val bar = scroll.verticalScrollBar
            if (!e.valueIsAdjusting && bar.value + bar.visibleAmount >= bar.maximum - JBUI.scale(80)) loadMore()
        }

        header.border = JBUI.Borders.empty(4, 10)
        header.add(backLink, BorderLayout.WEST)
        header.add(titleLabel, BorderLayout.CENTER)
        header.add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply { isOpaque = false; add(playAllLink) }, BorderLayout.EAST)
        header.isVisible = false

        add(header, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
    }

    /** Shows a top-level screen (search results, playlists...). */
    fun show(view: View) {
        root = view
        open(view, isRoot = true)
    }

    fun clear(emptyText: String) {
        generation++
        root = null
        current = null
        header.isVisible = false
        model.removeAll()
        list.setPaintBusy(false)
        list.emptyText.text = emptyText
    }

    fun reload() {
        current?.let { open(it, isRoot = it === root) }
    }

    fun focusList() {
        list.requestFocusInWindow()
        if (list.selectedIndex < 0 && model.size > 0) list.selectedIndex = 0
    }

    private fun open(view: View, isRoot: Boolean) {
        generation++
        current = view
        loading = false
        exhausted = false
        model.removeAll()
        header.isVisible = !isRoot
        titleLabel.text = view.title.orEmpty()
        playAllLink.isVisible = view.contextUri != null
        loadMore()
    }

    private fun loadMore() {
        val view = current ?: return
        if (loading || exhausted) return
        loading = true
        val offset = model.size
        val requested = generation
        list.setPaintBusy(true)
        list.emptyText.text = "Loading…"
        player.launch {
            val result = try {
                Result.success(view.load(offset))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            ApplicationManager.getApplication().invokeLater({
                if (requested != generation) return@invokeLater
                loading = false
                list.setPaintBusy(false)
                result.onSuccess { items ->
                    // De-duplicate: Spotify's offsets can shift while paging through a live queue.
                    val known = model.items.mapTo(HashSet()) { it.uri }
                    val fresh = items.filter { known.add(it.uri) }
                    model.add(fresh)
                    exhausted = !view.paged || fresh.isEmpty()
                    list.emptyText.text = view.emptyText
                }.onFailure { error ->
                    exhausted = true
                    list.emptyText.text = describe(error)
                }
            }, ModalityState.any())
        }
    }

    private fun describe(error: Throwable): String = when {
        error is SpotifyException && error.status == 403 -> "Spotify does not share this list with third-party apps"
        error is SpotifyException && error.isRateLimited -> "Spotify asked us to slow down. Try again in a moment"
        else -> error.message ?: "Could not load"
    }

    private fun activate(item: MediaItem) {
        when (item.kind) {
            MediaKind.TRACK, MediaKind.EPISODE -> playFrom(item)
            MediaKind.ARTIST -> player.playContext(item.uri)
            MediaKind.ALBUM, MediaKind.PLAYLIST -> drillInto(item)
        }
    }

    /**
     * Starts [item] so that music keeps going afterwards: inside its album/playlist when we know
     * it, otherwise followed by the rest of the visible list.
     */
    private fun playFrom(item: MediaItem) {
        val contextUri = current?.contextUri
        if (contextUri != null && item.kind == MediaKind.TRACK) {
            player.playContext(contextUri, offsetUri = item.uri)
            return
        }
        val playable = model.items.filter { it.isPlayableItem }
        val index = playable.indexOfFirst { it.uri == item.uri }
        player.playUris(if (index < 0) listOf(item.uri) else playable.drop(index).map { it.uri })
    }

    private fun drillInto(item: MediaItem) {
        val api = SpotifyApi.get()
        val view = when (item.kind) {
            MediaKind.ALBUM -> View(item.name, item.uri, paged = true, emptyText = "This album has no tracks") { api.albumTracks(item, it) }
            else -> View(
                item.name, item.uri, paged = true,
                emptyText = "Spotify only lists tracks of playlists you own. Press Play to listen anyway.",
            ) { api.playlistItems(item.id, it) }
        }
        open(view, isRoot = false)
    }

    private fun maybePopup(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val index = list.locationToIndex(e.point)
        if (index < 0 || !list.getCellBounds(index, index).contains(e.point)) return
        list.selectedIndex = index
        val item = model.getElementAt(index)

        val menu = JBPopupMenu()
        fun entry(text: String, run: () -> Unit) = menu.add(JBMenuItem(text).apply { addActionListener { run() } })

        entry(if (item.isPlayableItem) "Play" else "Play ${item.kind.label}") {
            if (item.isPlayableItem) playFrom(item) else player.playContext(item.uri)
        }
        if (item.isPlayableItem) entry("Add to Queue") { player.addToQueue(item) }
        if (item.kind == MediaKind.ALBUM || item.kind == MediaKind.PLAYLIST) entry("Show Tracks") { drillInto(item) }
        if (item.kind.isContext) {
            entry("Use as Soundtrack for This Project") { ProjectSoundtrack.get(project).pin(item.uri, item.name) }
        }
        menu.addSeparator()
        entry("Copy Link") { CopyPasteManager.getInstance().setContents(StringSelection(item.webUrl)) }
        entry("Open in Browser") { BrowserUtil.browse(item.webUrl) }
        menu.show(list, e.x, e.y)
    }
}
