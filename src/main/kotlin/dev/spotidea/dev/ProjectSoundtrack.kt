package dev.spotidea.dev

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.spotidea.player.Notifier
import dev.spotidea.player.PlayerService
import dev.spotidea.settings.SpotideaSettings
import kotlinx.coroutines.delay

/**
 * Remembers one album/playlist/artist per project: "this codebase sounds like this".
 * Lives in workspace.xml, which is per-user and normally not committed.
 */
@Service(Service.Level.PROJECT)
@State(name = "SpotideaSoundtrack", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ProjectSoundtrack(private val project: Project) :
    SimplePersistentStateComponent<ProjectSoundtrack.State>(State()) {

    class State : BaseState() {
        var uri by string()
        var name by string()
    }

    val uri: String? get() = state.uri?.takeIf { it.isNotBlank() }
    val name: String? get() = state.name

    fun pin(uri: String, name: String) {
        state.uri = uri
        state.name = name
        Notifier.info("“$name” is now the soundtrack of ${project.name}.", project, "Play Now" to { play() })
    }

    fun play(): Boolean {
        val target = uri ?: return false
        PlayerService.get().playContext(target)
        return true
    }

    companion object {
        fun get(project: Project): ProjectSoundtrack = project.service()
    }
}

/** Optionally starts the project's soundtrack when the project opens, but never over music already playing. */
class SoundtrackStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!SpotideaSettings.get().state.autoPlaySoundtrack) return
        val soundtrack = ProjectSoundtrack.get(project)
        if (soundtrack.uri == null) return
        val player = PlayerService.get()
        // Give the first poll time to land so we know whether something is already playing.
        for (attempt in 1..10) {
            if (player.isConnected && player.state != null) break
            delay(500)
        }
        if (player.isConnected && player.state?.isPlaying != true) soundtrack.play()
    }
}
