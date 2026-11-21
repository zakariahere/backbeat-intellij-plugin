package dev.spotidea.player

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

object Notifier {
    private const val GROUP = "Backbeat"
    private val lastShown = ConcurrentHashMap<String, Long>()

    fun info(message: String, project: Project? = null, vararg actions: Pair<String, () -> Unit>) =
        show(message, NotificationType.INFORMATION, project, actions)

    fun warn(message: String, project: Project? = null, vararg actions: Pair<String, () -> Unit>) =
        show(message, NotificationType.WARNING, project, actions)

    /** Like [warn], but stays quiet when the same message was shown within [quietMs]. */
    fun warnThrottled(message: String, quietMs: Long = 30_000, vararg actions: Pair<String, () -> Unit>) {
        val now = System.currentTimeMillis()
        val previous = lastShown.put(message, now)
        if (previous == null || now - previous > quietMs) show(message, NotificationType.WARNING, null, actions)
    }

    private fun show(message: String, type: NotificationType, project: Project?, actions: Array<out Pair<String, () -> Unit>>) {
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
            .createNotification("Backbeat", message, type)
        actions.forEach { (text, run) -> notification.addAction(NotificationAction.createSimpleExpiring(text) { run() }) }
        notification.notify(project)
    }
}
