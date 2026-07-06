package com.hy.ide.cmcc

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.ChangesUtil

class MockAIAction : DumbAwareAction() {
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null && selectedFilePath(event) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val filePath = selectedFilePath(event) ?: return

        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification("Mock AI", filePath, NotificationType.INFORMATION)
            .notify(project)
    }

    private fun selectedFilePath(event: AnActionEvent): String? {
        event.getData(CommonDataKeys.VIRTUAL_FILE)?.let { return it.path }
        event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.firstOrNull()?.let { return it.path }

        return event.getData(VcsDataKeys.SELECTED_CHANGES)
            ?.firstOrNull()
            ?.let(ChangesUtil::getFilePath)
            ?.path
    }

    private companion object {
        const val NOTIFICATION_GROUP_ID = "GitMockAI Notifications"
    }
}
