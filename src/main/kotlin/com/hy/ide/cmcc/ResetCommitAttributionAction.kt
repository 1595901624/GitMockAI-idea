package com.hy.ide.cmcc

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import java.io.File

class ResetCommitAttributionAction : DumbAwareAction() {
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val command = listOf("git", "ai", "checkpoint", "--reset")
                val process = ProcessBuilder(command)
                    .directory(project.basePath?.let(::File))
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val exitCode = process.waitFor()

                if (!project.isDisposed) {
                    val type = if (exitCode == 0) NotificationType.INFORMATION else NotificationType.ERROR
                    val title = if (exitCode == 0) "重置提交归因" else "重置提交归因失败"
                    notify(project, title, formatLog(command, output, exitCode), type)
                }
            } catch (exception: Exception) {
                if (!project.isDisposed) {
                    notify(
                        project,
                        "重置提交归因失败",
                        formatLog(null, exception.message ?: exception.javaClass.simpleName, null),
                        NotificationType.ERROR,
                    )
                }
            }
        }
    }

    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(title, content, type)
            .notify(project)
    }

    private fun formatLog(command: List<String>?, output: String, exitCode: Int?): String {
        val log = buildString {
            command?.let { appendLine("命令：${it.joinToString(" ")}") }
            exitCode?.let { appendLine("退出码：$it") }
            append(output.trim().ifEmpty { "无输出" })
        }
        return StringUtil.escapeXmlEntities(log.take(MAX_LOG_LENGTH))
            .replace("\r\n", "<br>")
            .replace("\n", "<br>")
            .replace("\r", "<br>")
    }

    private companion object {
        const val NOTIFICATION_GROUP_ID = "GitMockAI Notifications"
        const val MAX_LOG_LENGTH = 2_000
    }
}
