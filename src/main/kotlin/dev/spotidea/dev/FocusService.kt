package dev.spotidea.dev

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import dev.spotidea.player.Notifier
import dev.spotidea.player.PlayerListener
import dev.spotidea.player.PlayerService
import dev.spotidea.settings.SpotideaSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A Pomodoro timer wired to the music: it plays while you focus and goes quiet for the break,
 * so the silence itself tells you to stand up.
 */
@Service(Service.Level.APP)
class FocusService(private val cs: CoroutineScope) {
    enum class Phase { IDLE, FOCUS, BREAK }

    @Volatile var phase = Phase.IDLE
        private set
    @Volatile private var endsAt = 0L
    private var job: Job? = null

    val isRunning: Boolean get() = phase != Phase.IDLE

    /** "24:59" while a timer runs, otherwise null. */
    fun remaining(): String? {
        if (phase == Phase.IDLE) return null
        val seconds = ((endsAt - System.currentTimeMillis()).coerceAtLeast(0) + 999) / 1000
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    fun toggle() = if (isRunning) stop() else startFocus()

    fun startFocus() {
        val minutes = SpotideaSettings.get().state.focusMinutes.coerceIn(1, 240)
        PlayerService.get().resume()
        startPhase(Phase.FOCUS, minutes) {
            PlayerService.get().pause()
            val pause = SpotideaSettings.get().state.breakMinutes.coerceIn(1, 120)
            Notifier.info(
                "Focus session done. Music paused: take $pause minutes.", null,
                "Skip Break" to { startFocus() },
                "Stop" to { stop() },
            )
            startBreak(pause)
        }
    }

    private fun startBreak(minutes: Int) = startPhase(Phase.BREAK, minutes) {
        phase = Phase.IDLE
        publish()
        Notifier.info("Break is over. Ready for another round?", null, "Start Focus" to { startFocus() })
    }

    fun stop() {
        job?.cancel()
        job = null
        phase = Phase.IDLE
        publish()
    }

    private fun startPhase(newPhase: Phase, minutes: Int, onFinished: () -> Unit) {
        job?.cancel()
        phase = newPhase
        endsAt = System.currentTimeMillis() + minutes * 60_000L
        publish()
        job = cs.launch {
            while (System.currentTimeMillis() < endsAt) {
                delay(1_000)
                publish()
            }
            onFinished()
        }
    }

    private fun publish() = ApplicationManager.getApplication().invokeLater(
        { ApplicationManager.getApplication().messageBus.syncPublisher(PlayerListener.TOPIC).focusChanged() },
        ModalityState.any(),
    )

    companion object {
        fun get(): FocusService = service()
    }
}
