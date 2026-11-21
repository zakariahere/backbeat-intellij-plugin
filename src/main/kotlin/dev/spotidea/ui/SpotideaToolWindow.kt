package dev.spotidea.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.spotidea.api.MediaKind
import dev.spotidea.api.PlaybackState
import dev.spotidea.api.SpotifyApi
import dev.spotidea.player.PlayerListener
import dev.spotidea.player.PlayerService
import dev.spotidea.settings.SpotideaSettings
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

class SpotideaToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val root = SpotideaRootPanel(project, toolWindow.disposable)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(root, "", false))
        val actions = ActionManager.getInstance()
        toolWindow.setTitleActions(
            listOfNotNull(actions.getAction("Spotidea.FocusSession"), actions.getAction("Spotidea.OpenSettings"))
        )
    }
}

/** Swaps between the first-run setup card and the player depending on whether an account is connected. */
class SpotideaRootPanel(project: Project, parent: Disposable) : JPanel(CardLayout()), PlayerListener {
    private val cards = layout as CardLayout
    private val setup = SetupPanel()
    private val playerCard = PlayerCard(project, parent)

    init {
        add(setup, SETUP)
        add(playerCard, PLAYER)
        ApplicationManager.getApplication().messageBus.connect(parent).subscribe(PlayerListener.TOPIC, this)
        connectionChanged(PlayerService.get().isConnected)
    }

    override fun connectionChanged(connected: Boolean) {
        cards.show(this, if (connected) PLAYER else SETUP)
        if (connected) playerCard.onShown()
    }

    private companion object {
        const val SETUP = "setup"
        const val PLAYER = "player"
    }
}

/** Walks a new user through creating their own Spotify app; the only manual step in the plugin. */
class SetupPanel : JPanel(BorderLayout()) {
    private val settings = SpotideaSettings.get()
    private val clientId = JBTextField(settings.clientId)
    private val status = JBLabel(" ").apply { foreground = UIUtil.getContextHelpForeground() }
    private val connect = JButton("Connect to Spotify")

    init {
        connect.addActionListener { if (PlayerService.get().connecting) PlayerService.get().cancelConnect() else startLogin() }
        clientId.addActionListener { if (!PlayerService.get().connecting) startLogin() }
        clientId.emptyText.text = "Paste your Client ID"

        // Tool windows are narrow: every paragraph wraps to the width it is given instead of a fixed column.
        val wrap = MAX_LINE_LENGTH_WORD_WRAP
        val form = panel {
            row { label("Connect your Spotify").bold() }
            row {
                text(
                    "<b>Keep the Spotify desktop app open.</b> Backbeat is a remote control: it tells Spotify " +
                        "what to play, and Spotify makes the sound. Spotify Premium is required.",
                    maxLineLength = wrap,
                )
            }
            row {
                text(
                    "Spotify caps every third-party app at a handful of users, so Backbeat runs on " +
                        "<b>your own</b> free Client ID. It takes about a minute, once.",
                    maxLineLength = wrap,
                )
            }
            row {
                text(
                    "1. Open the <a href='https://developer.spotify.com/dashboard'>Spotify Developer Dashboard</a> " +
                        "and choose <b>Create app</b>.",
                    maxLineLength = wrap,
                )
            }
            row {
                text(
                    "2. Paste this as the <b>Redirect URI</b>, click <b>Add</b>, then <b>Save</b>. It must match " +
                        "exactly: <b>http</b> not https, <b>127.0.0.1</b> not localhost, no trailing slash.",
                    maxLineLength = wrap,
                )
            }
            row {
                textField().applyToComponent { text = settings.redirectUri; isEditable = false }.align(AlignX.FILL).resizableColumn()
                button("Copy") { CopyPasteManager.getInstance().setContents(StringSelection(settings.redirectUri)) }
            }
            row { text("3. Tick <b>Web API</b>, save, then paste the app's <b>Client ID</b> here:", maxLineLength = wrap) }
            row { cell(clientId).align(AlignX.FILL) }
            row { cell(connect) }
            row { cell(status) }
            row {
                comment(
                    "Your browser opens for the login. No password or secret ever touches the IDE; the session token " +
                        "is kept in the IDE password safe.<br><br>Remote control (play, skip, seek) needs Spotify Premium. " +
                        "That is Spotify's rule for every third-party player.",
                    maxLineLength = wrap,
                )
            }
        }
        form.border = JBUI.Borders.empty(14, 16)
        add(form, BorderLayout.CENTER)
    }

    private fun startLogin() {
        val id = clientId.text.trim()
        if (id.isEmpty()) {
            status.text = "Paste your Client ID first."
            return
        }
        settings.state.clientId = id
        // The button turns into Cancel: if Spotify shows an error page it never redirects back to us.
        connect.text = "Cancel"
        status.text = "Waiting for you to approve in the browser…"
        PlayerService.get().connect { error ->
            connect.text = "Connect to Spotify"
            status.text = error ?: " "
        }
    }
}

/** Now Playing on top, then Search / Library / Queue tabs. */
class PlayerCard(private val project: Project, parent: Disposable) : JPanel(BorderLayout()), PlayerListener {
    private val api get() = SpotifyApi.get()
    private val tabs = JBTabbedPane()
    private val searchField = SearchTextField(false)
    private val kindFilter = ComboBox(arrayOf<MediaKind?>(null, MediaKind.TRACK, MediaKind.ALBUM, MediaKind.ARTIST, MediaKind.PLAYLIST))
    private val searchResults = MediaBrowser(project)
    private val librarySource = ComboBox(LibrarySource.entries.toTypedArray())
    private val library = MediaBrowser(project)
    private val queue = MediaBrowser(project)

    /** Waits for a pause in typing before searching; every keystroke would burn through the rate limit. */
    private val searchDebounce = Alarm(Alarm.ThreadToUse.SWING_THREAD, parent)
    private var libraryLoaded = false
    private var queueTrackUri: String? = null

    private enum class LibrarySource(val label: String) {
        PLAYLISTS("Your Playlists"), LIKED("Liked Songs"), RECENT("Recently Played")
    }

    init {
        add(NowPlayingPanel(parent), BorderLayout.NORTH)

        kindFilter.renderer = textRenderer { kind -> kind?.let { it.label + "s" } ?: "Everything" }
        kindFilter.addActionListener { runSearch() }
        searchField.textEditor.emptyText.text = "Search songs, albums, artists, playlists"
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                searchDebounce.cancelAllRequests()
                searchDebounce.addRequest({ runSearch() }, 350)
            }
        })
        searchField.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_DOWN || e.keyCode == KeyEvent.VK_ENTER) searchResults.focusList()
            }
        })
        searchResults.clear("Type to search Spotify")
        val searchBar = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            border = JBUI.Borders.empty(6, 10)
            add(searchField, BorderLayout.CENTER)
            add(kindFilter, BorderLayout.EAST)
        }
        tabs.addTab("Search", JPanel(BorderLayout()).apply {
            add(searchBar, BorderLayout.NORTH)
            add(searchResults, BorderLayout.CENTER)
        })

        librarySource.renderer = textRenderer { it?.label.orEmpty() }
        librarySource.addActionListener { loadLibrary() }
        tabs.addTab("Library", JPanel(BorderLayout()).apply {
            add(JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(6, 10); add(librarySource, BorderLayout.CENTER) }, BorderLayout.NORTH)
            add(library, BorderLayout.CENTER)
        })
        tabs.addTab("Queue", queue)
        tabs.addChangeListener { onTabChanged() }
        add(tabs, BorderLayout.CENTER)

        ApplicationManager.getApplication().messageBus.connect(parent).subscribe(PlayerListener.TOPIC, this)
    }

    fun onShown() {
        libraryLoaded = false
        onTabChanged()
    }

    fun focusSearch() {
        tabs.selectedIndex = 0
        searchField.textEditor.requestFocusInWindow()
    }

    private fun onTabChanged() {
        when (tabs.selectedIndex) {
            1 -> if (!libraryLoaded) loadLibrary()
            2 -> loadQueue()
        }
    }

    private fun runSearch() {
        val query = searchField.text.trim()
        if (query.length < 2) {
            searchResults.clear("Type to search Spotify")
            return
        }
        val kind = kindFilter.selectedItem as MediaKind?
        searchResults.show(
            if (kind == null) {
                // Mixed results cannot be paged coherently, so show the best few of each kind.
                MediaBrowser.View(emptyText = "No results for “$query”") {
                    api.search(query, listOf(MediaKind.TRACK, MediaKind.ARTIST, MediaKind.ALBUM, MediaKind.PLAYLIST), limit = 6)
                }
            } else {
                MediaBrowser.View(paged = true, emptyText = "No results for “$query”") { offset ->
                    api.search(query, listOf(kind), offset = offset)
                }
            }
        )
    }

    private fun loadLibrary() {
        libraryLoaded = true
        library.show(
            when (librarySource.selectedItem as LibrarySource) {
                LibrarySource.PLAYLISTS -> MediaBrowser.View(paged = true, emptyText = "No playlists yet") { api.myPlaylists(it) }
                LibrarySource.LIKED -> MediaBrowser.View(paged = true, emptyText = "No liked songs yet") { api.savedTracks(it) }
                LibrarySource.RECENT -> MediaBrowser.View(emptyText = "Nothing played recently") { api.recentlyPlayed() }
            }
        )
    }

    private fun loadQueue() {
        queueTrackUri = PlayerService.get().state?.item?.uri
        queue.show(MediaBrowser.View(emptyText = "The queue is empty") { api.queue() })
    }

    /** The queue moves whenever the song changes; refresh it, but only while someone is looking. */
    override fun playbackChanged(state: PlaybackState?) {
        if (tabs.selectedIndex == 2 && isShowing && state?.item?.uri != queueTrackUri) loadQueue()
    }
}

