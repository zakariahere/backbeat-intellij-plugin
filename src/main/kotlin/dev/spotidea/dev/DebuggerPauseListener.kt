package dev.spotidea.dev

import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManagerListener
import dev.spotidea.player.PlayerService

/**
 * Pauses the music when a breakpoint is hit and brings it back when the program runs again.
 * The setting is checked inside [PlayerService], so toggling it takes effect mid-session.
 */
class DebuggerPauseListener : XDebuggerManagerListener {
    override fun processStarted(debugProcess: XDebugProcess) {
        debugProcess.session.addSessionListener(object : XDebugSessionListener {
            override fun sessionPaused() = PlayerService.get().debuggerPaused()
            override fun sessionResumed() = PlayerService.get().debuggerResumed()
            override fun sessionStopped() = PlayerService.get().debuggerResumed()
        })
    }
}
