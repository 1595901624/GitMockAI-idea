package com.hy.ide.cmcc

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.ChangesUtil
import java.io.File
import java.security.SecureRandom

open class MockAIAction : DumbAwareAction() {
    protected open val checkpointAgent = "mock_ai"
    protected open val actionTitle = "Mock AI"
    protected open val usesMockAiSettings = true

    override fun update(event: AnActionEvent) {
        // Newer IDE versions may not expose the selected VCS files while a compact popup is being updated.
        // Hiding the action here would therefore make it disappear completely from the context menu.
        event.presentation.isEnabledAndVisible = event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val filePaths = selectedFilePaths(event)
        if (filePaths.isEmpty()) {
            notify(project, actionTitle, "未能获取选中的文件，请重新选择后重试。", NotificationType.WARNING)
            return
        }

        val commandOptions = if (usesMockAiSettings) {
            resolveCommandOptions(MockAISettings.getInstance().snapshot())
        } else {
            CommandOptions()
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            runGitAI(project, filePaths, commandOptions)
        }
    }

    private fun runGitAI(
        project: Project,
        selectedPaths: List<String>,
        options: CommandOptions,
    ) {
        try {
            val filePaths = expandDirectoryPaths(selectedPaths)
            if (filePaths.isEmpty()) {
                if (!project.isDisposed) {
                    notify(project, actionTitle, "所选目录中没有可执行检查点的文件。", NotificationType.WARNING)
                }
                return
            }

            val workingDirectory = project.basePath?.let(::File)
            val logs = mutableListOf<String>()

            val statusCommand = listOf("git", "ai", "bg", "status")
            val statusResult = runCommand(statusCommand, workingDirectory)
            logs += statusResult.format(statusCommand)

            if (!statusResult.isBgReady()) {
                val startCommand = listOf("git", "ai", "bg", "start")
                val startResult = runCommand(startCommand, workingDirectory)
                logs += startResult.format(startCommand)
                if (startResult.exitCode != 0) {
                    if (!project.isDisposed) {
                        notify(
                            project,
                            "$actionTitle 执行失败",
                            formatLog(logs.joinToString("\n\n"), startResult.exitCode),
                            NotificationType.ERROR,
                        )
                    }
                    return
                }
            }

            val checkpointCommand = buildCheckpointCommand(filePaths, options)
            val checkpointResult = runCommand(checkpointCommand, workingDirectory)
            logs += checkpointResult.format(checkpointCommand)
            if (!project.isDisposed) {
                val type = if (checkpointResult.exitCode == 0) NotificationType.INFORMATION else NotificationType.ERROR
                val title = if (checkpointResult.exitCode == 0) "$actionTitle 执行日志" else "$actionTitle 执行失败"
                notify(project, title, formatLog(logs.joinToString("\n\n"), checkpointResult.exitCode), type)
            }
        } catch (exception: Exception) {
            if (!project.isDisposed) {
                val detail = exception.message ?: exception.javaClass.simpleName
                notify(project, "$actionTitle 执行失败", formatLog(detail, null), NotificationType.ERROR)
            }
        }
    }

    private fun buildCheckpointCommand(filePaths: List<String>, options: CommandOptions): List<String> = buildList {
        add("git")
        addAll(listOf("ai", "checkpoint", checkpointAgent))
        addAll(filePaths)
        options.tool?.let { addAll(listOf("--tool", it)) }
        options.id?.let { addAll(listOf("--id", it)) }
        options.model?.let { addAll(listOf("--model", it)) }
    }

    private fun runCommand(command: List<String>, workingDirectory: File?): CommandResult {
        val process = ProcessBuilder(command)
            .directory(workingDirectory)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        return CommandResult(exitCode, output)
    }

    private fun formatLog(output: String, exitCode: Int?): String {
        val log = output.trim().ifEmpty {
            if (exitCode == null) "执行异常，未返回日志。" else "执行完成，退出码：$exitCode"
        }
        return StringUtil.escapeXmlEntities(log.take(MAX_LOG_LENGTH))
            .replace("\r\n", "<br>")
            .replace("\n", "<br>")
            .replace("\r", "<br>")
    }

    private fun selectedFilePaths(event: AnActionEvent): List<String> {
        // These keys overlap, and some IDE versions expose all changes through fallback keys.
        // Use the first non-empty source so only the explicit tree selection is executed.
        val candidates = listOf(
            event.getData(VcsDataKeys.CHANGE_LEAD_SELECTION)
                ?.map { ChangesUtil.getFilePath(it).path }
                .orEmpty(),
            event.getData(VcsDataKeys.SELECTED_CHANGES)
                ?.map { ChangesUtil.getFilePath(it).path }
                .orEmpty(),
            event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
                ?.map { it.path }
                .orEmpty(),
            event.getData(VcsDataKeys.FILE_PATHS)
                ?.map { it.path }
                .orEmpty(),
            event.getData(VcsDataKeys.VIRTUAL_FILES)
                ?.map { it.path }
                .orEmpty(),
            listOfNotNull(event.getData(CommonDataKeys.VIRTUAL_FILE)?.path),
            listOfNotNull(event.getData(VcsDataKeys.FILE_PATH)?.path),
        )

        return candidates.firstOrNull { it.isNotEmpty() }
            ?.distinctBy(::pathKey)
            .orEmpty()
    }

    /** Expands a selected directory into its files so checkpoint receives one path per file. */
    private fun expandDirectoryPaths(paths: List<String>): List<String> = paths
        .flatMap { path ->
            val file = File(path)
            if (!file.isDirectory) {
                listOf(path)
            } else {
                file.walkTopDown()
                    .onEnter { directory -> directory.name != GIT_DIRECTORY_NAME }
                    .filter { it.isFile }
                    .map { it.path }
                    .sorted()
                    .toList()
            }
        }
        .distinctBy(::pathKey)

    private fun pathKey(path: String): String {
        val normalizedPath = path.replace('\\', '/')
        return if (SystemInfo.isWindows) normalizedPath.lowercase() else normalizedPath
    }

    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(title, content, type)
            .notify(project)
    }

    private fun randomCheckpointId(): String {
        val bytes = ByteArray(CHECKPOINT_ID_BYTES)
        SECURE_RANDOM.nextBytes(bytes)
        return buildString(CHECKPOINT_ID_BYTES * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX_DIGITS[value ushr 4])
                append(HEX_DIGITS[value and 0x0f])
            }
        }
    }

    private fun resolveCommandOptions(settings: MockAISettingsSnapshot): CommandOptions = CommandOptions(
        tool = when (settings.toolMode) {
            ToolMode.DEFAULT_TRAE -> "trae"
            ToolMode.OMIT -> null
            ToolMode.CUSTOM -> settings.customTool
        },
        id = when (settings.idMode) {
            IdMode.RANDOM -> randomCheckpointId()
            IdMode.OMIT -> null
            IdMode.FIXED -> settings.fixedId
        },
        model = when (settings.modelMode) {
            ModelMode.OMIT -> null
            ModelMode.CUSTOM -> settings.customModel
        },
    )

    private data class CommandOptions(
        val tool: String? = null,
        val id: String? = null,
        val model: String? = null,
    )

    private data class CommandResult(
        val exitCode: Int,
        val output: String,
    ) {
        fun isBgReady(): Boolean {
            if (exitCode != 0) return false

            val normalizedOutput = output.lowercase()
            return BAD_BG_STATUS_KEYWORDS.none { it in normalizedOutput }
        }

        fun format(command: List<String>): String {
            val trimmedOutput = output.trim()
            val content = if (trimmedOutput.isEmpty()) "无输出" else trimmedOutput
            return "命令：${command.joinToString(" ") { it.displayArgument() }}\n退出码：$exitCode\n$content"
        }

        private fun String.displayArgument(): String =
            if (isEmpty() || any(Char::isWhitespace) || contains('"')) {
                "\"${replace("\"", "\\\"")}\""
            } else {
                this
            }
    }

    private companion object {
        const val NOTIFICATION_GROUP_ID = "GitMockAI Notifications"
        const val CHECKPOINT_ID_BYTES = 12
        const val MAX_LOG_LENGTH = 2_000
        const val HEX_DIGITS = "0123456789abcdef"
        const val GIT_DIRECTORY_NAME = ".git"
        val BAD_BG_STATUS_KEYWORDS = listOf(
            "not running",
            "not started",
            "stopped",
            "inactive",
            "failed",
            "error",
            "未运行",
            "未启动",
            "已停止",
            "停止",
            "失败",
            "错误",
            "异常",
        )
        val SECURE_RANDOM = SecureRandom()
    }
}
