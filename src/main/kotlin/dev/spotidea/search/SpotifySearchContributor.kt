package dev.spotidea.search

import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.util.Processor
import dev.spotidea.api.MediaItem
import dev.spotidea.api.MediaKind
import dev.spotidea.api.SpotifyApi
import dev.spotidea.player.PlayerService
import dev.spotidea.ui.MediaItemRenderer
import java.awt.event.InputEvent
import javax.swing.ListCellRenderer

class SpotifySearchContributorFactory : SearchEverywhereContributorFactory<MediaItem> {
    override fun createContributor(initEvent: AnActionEvent): SearchEverywhereContributor<MediaItem> =
        SpotifySearchContributor(initEvent.project)
}

/**
 * Adds a "Spotify" tab to Search Everywhere (double Shift): type, Enter, music.
 * It deliberately stays out of the "All" tab so that looking for a class never calls Spotify.
 */
class SpotifySearchContributor(private val project: Project?) : SearchEverywhereContributor<MediaItem> {
    override fun getSearchProviderId(): String = ID
    override fun getGroupName(): String = "Spotify"
    override fun getSortWeight(): Int = 2000
    override fun showInFindResults(): Boolean = false
    override fun isShownInSeparateTab(): Boolean = true
    override fun isEmptyPatternSupported(): Boolean = false
    override fun getAdvertisement(): String = "Enter to play, Shift+Enter to add to the queue"

    override fun fetchElements(pattern: String, progressIndicator: ProgressIndicator, consumer: Processor<in MediaItem>) {
        val query = pattern.trim()
        if (query.length < 3 || !PlayerService.get().isConnected || !isSpotifyTabSelected()) return

        // The popup re-queries on every keystroke and cancels the previous run; sitting out a short
        // beat first means only the query the user paused on is ever sent to Spotify.
        repeat(7) {
            progressIndicator.checkCanceled()
            Thread.sleep(50)
        }
        val items = try {
            runBlockingCancellable {
                SpotifyApi.get().search(query, listOf(MediaKind.TRACK, MediaKind.ARTIST, MediaKind.ALBUM, MediaKind.PLAYLIST), limit = 6)
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            return
        }
        for (item in items) {
            progressIndicator.checkCanceled()
            if (!consumer.process(item)) return
        }
    }

    private fun isSpotifyTabSelected(): Boolean {
        val project = project ?: return true
        // The manager throws when queried at the wrong moment; treat "unknown" as selected.
        return runCatching { SearchEverywhereManager.getInstance(project).selectedTabID == ID }.getOrDefault(true)
    }

    override fun processSelectedItem(selected: MediaItem, modifiers: Int, searchText: String): Boolean {
        val player = PlayerService.get()
        if (modifiers and InputEvent.SHIFT_DOWN_MASK != 0 && selected.isPlayableItem) player.addToQueue(selected) else player.play(selected)
        return true
    }

    override fun getElementsRenderer(): ListCellRenderer<in MediaItem> = MediaItemRenderer()

    companion object {
        const val ID = "SpotideaSearch"
    }
}
