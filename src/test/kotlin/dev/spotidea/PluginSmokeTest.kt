package dev.spotidea

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBList
import dev.spotidea.api.Device
import dev.spotidea.api.MediaItem
import dev.spotidea.api.MediaKind
import dev.spotidea.api.PlaybackState
import dev.spotidea.api.RepeatMode
import dev.spotidea.dev.FocusService
import dev.spotidea.dev.ProjectSoundtrack
import dev.spotidea.player.PlayerService
import dev.spotidea.search.SpotifySearchContributor
import dev.spotidea.settings.SpotideaConfigurable
import dev.spotidea.statusbar.NowPlayingWidgetFactory
import dev.spotidea.ui.MediaBrowser
import dev.spotidea.ui.MediaItemRenderer
import dev.spotidea.ui.NowPlayingPanel
import dev.spotidea.ui.PlayerCard
import dev.spotidea.ui.SetupPanel
import dev.spotidea.ui.SpotideaRootPanel
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JComponent

/**
 * Boots the plugin inside a headless IDE and builds every piece of UI, so a typo in plugin.xml or a
 * broken layout fails the build instead of a user's IDE. Also writes PNG previews to build/ui-preview.
 */
class PluginSmokeTest : BasePlatformTestCase() {

    private val song = MediaItem(
        MediaKind.TRACK, "spotify:track:1", "1", "Around the World", "Daft Punk • Homework", durationMs = 429_000,
    )

    fun `test every action and group is registered`() {
        val ids = listOf(
            "PlayPause", "Next", "Previous", "Shuffle", "Repeat", "Like", "VolumeUp", "VolumeDown", "Search",
            "CopyLink", "FocusSession", "PinSoundtrack", "PlaySoundtrack", "OpenSettings", "PlayerControls", "Menu",
        )
        ids.forEach { assertNotNull("Action Spotidea.$it is missing", ActionManager.getInstance().getAction("Spotidea.$it")) }
    }

    fun `test starts disconnected and shows the setup card`() {
        assertFalse(PlayerService.get().isConnected)
        val root = SpotideaRootPanel(project, testRootDisposable)
        val visible = root.components.single { it.isVisible }
        assertTrue("Expected the setup card, got ${visible.javaClass.simpleName}", visible is SetupPanel)
        snapshot(root, "setup", 380, 520)
    }

    fun `test player card builds and shows a song`() {
        val card = PlayerCard(project, testRootDisposable)
        val header = findAll(card).filterIsInstance<NowPlayingPanel>().single()
        header.playbackChanged(
            PlaybackState(
                device = Device("d", "Zak's Desktop", "Computer", true, false, 60, true),
                item = song, isPlaying = true, progressMs = 95_000, shuffle = true,
                repeat = RepeatMode.CONTEXT, contextUri = "spotify:album:x",
            )
        )
        val texts = findAll(card).filterIsInstance<javax.swing.JLabel>().mapNotNull { it.text }
        assertTrue(texts.contains("Around the World"))
        assertTrue(texts.contains("Daft Punk"))
        assertTrue(texts.contains("7:09"))
        snapshot(card, "player", 380, 620)
    }

    fun `test media browser loads, pages and renders rows`() {
        val browser = MediaBrowser(project)
        val pages = mutableListOf<Int>()
        browser.show(MediaBrowser.View(paged = true) { offset ->
            pages += offset
            if (offset == 0) listOf(song, song.copy(uri = "spotify:album:2", kind = MediaKind.ALBUM, name = "Discovery", subtitle = "Album • Daft Punk • 2001", durationMs = 0))
            else emptyList()
        })
        val list = findAll(browser).filterIsInstance<JBList<*>>().single()
        waitUntil { list.model.size == 2 }
        assertEquals(0, pages.first())

        val row = MediaItemRenderer().getListCellRendererComponent(list, list.model.getElementAt(0), 0, true, true)
        val labels = findAll(row).filterIsInstance<javax.swing.JLabel>().mapNotNull { it.text }
        assertTrue(labels.containsAll(listOf("Around the World", "Daft Punk • Homework", "7:09")))
        snapshot(browser, "browser", 380, 200)
    }

    fun `test settings page builds and is unmodified`() {
        val configurable = SpotideaConfigurable()
        try {
            assertNotNull(configurable.createComponent())
            assertFalse(configurable.isModified)
            snapshot(configurable.createComponent()!!, "settings", 760, 520)
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun `test status bar widget builds`() {
        val widget = NowPlayingWidgetFactory().createWidget(project)
        try {
            assertNotNull((widget as CustomStatusBarWidget).component)
        } finally {
            Disposer.dispose(widget)
        }
    }

    fun `test search everywhere stays silent while disconnected`() {
        val found = mutableListOf<MediaItem>()
        SpotifySearchContributor(project).fetchElements("daft punk", EmptyProgressIndicator()) { found.add(it) }
        assertTrue(found.isEmpty())
    }

    fun `test project soundtrack is remembered`() {
        val soundtrack = ProjectSoundtrack.get(project)
        assertNull(soundtrack.uri)
        soundtrack.pin("spotify:playlist:abc", "Deep Focus")
        assertEquals("spotify:playlist:abc", soundtrack.uri)
        assertEquals("Deep Focus", soundtrack.name)
    }

    fun `test focus timer counts down and stops`() {
        val focus = FocusService.get()
        assertNull(focus.remaining())
        focus.startFocus()
        try {
            assertEquals(FocusService.Phase.FOCUS, focus.phase)
            assertTrue(focus.remaining()!!.matches(Regex("\\d+:\\d\\d")))
        } finally {
            focus.stop()
        }
        assertFalse(focus.isRunning)
    }

    // ---- helpers ----------------------------------------------------------------------------

    private fun findAll(root: Component): List<Component> =
        listOf(root) + ((root as? Container)?.components?.flatMap { findAll(it) } ?: emptyList())

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for the UI to update")
            Thread.sleep(25)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        }
    }

    /** Lays the component out off-screen and paints it into build/ui-preview/<name>.png. */
    private fun snapshot(component: JComponent, name: String, width: Int, height: Int) {
        component.size = Dimension(width, height)
        // Twice: wrapped text only knows its height after a first pass has told it its width.
        repeat(2) { layoutDeep(component) }
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = com.intellij.util.ui.UIUtil.getPanelBackground()
        g.fillRect(0, 0, width, height)
        component.paint(g)
        g.dispose()
        val out = File("build/ui-preview/$name.png")
        out.parentFile.mkdirs()
        ImageIO.write(image, "png", out)
    }

    private fun layoutDeep(c: Component) {
        if (c is Container) {
            c.doLayout()
            c.components.forEach(::layoutDeep)
        }
    }
}
