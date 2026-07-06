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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom

class MockAIAction : DumbAwareAction() {
    override fun update(event: AnActionEvent) {
        // Newer IDE versions may not expose the selected VCS files while a compact popup is being updated.
        // Hiding the action here would therefore make it disappear completely from the context menu.
        event.presentation.isEnabledAndVisible = event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val filePaths = selectedFilePaths(event)
        if (filePaths.isEmpty()) {
            notify(project, "Mock AI", "未能获取选中的文件，请重新选择后重试。", NotificationType.WARNING)
            return
        }

        val platform = XaiPlatform.current()
        if (platform.resourcePath == null) {
            notify(
                project,
                "Mock AI 暂不支持当前系统",
                "当前仅支持 Windows。",
                NotificationType.WARNING,
            )
            return
        }

        val commandOptions = resolveCommandOptions(MockAISettings.getInstance().snapshot())
        ApplicationManager.getApplication().executeOnPooledThread {
            runXai(project, platform.resourcePath, filePaths, commandOptions)
        }
    }

    private fun runXai(
        project: Project,
        resourcePath: String,
        filePaths: List<String>,
        options: CommandOptions,
    ) {
        try {
            val executable = extractExecutable(resourcePath)
            val command = buildList {
                add(executable.toString())
                addAll(listOf("ai", "checkpoint", "mock_ai"))
                addAll(filePaths)
                options.tool?.let { addAll(listOf("--tool", it)) }
                options.id?.let { addAll(listOf("--id", it)) }
                options.model?.let { addAll(listOf("--model", it)) }
            }

            val process = ProcessBuilder(command)
                .directory(project.basePath?.let(::File))
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            if (!project.isDisposed) {
                val type = if (exitCode == 0) NotificationType.INFORMATION else NotificationType.ERROR
                val title = if (exitCode == 0) "Mock AI 执行日志" else "Mock AI 执行失败"
                notify(project, title, formatLog(output, exitCode), type)
            }
        } catch (exception: Exception) {
            if (!project.isDisposed) {
                val detail = exception.message ?: exception.javaClass.simpleName
                notify(project, "Mock AI 执行失败", formatLog(detail, null), NotificationType.ERROR)
            }
        }
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
            ?.distinctBy { path ->
                val normalizedPath = path.replace('\\', '/')
                if (SystemInfo.isWindows) normalizedPath.lowercase() else normalizedPath
            }
            .orEmpty()
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

    private fun extractExecutable(resourcePath: String): Path {
        extractedExecutable?.let { return it }

        return synchronized(EXTRACTION_LOCK) {
            extractedExecutable?.let { return@synchronized it }

            val directory = Files.createTempDirectory("GitMockAI-")
            val executable = directory.resolve("xai.exe")
            javaClass.getResourceAsStream(resourcePath).use { input ->
                requireNotNull(input) { "找不到插件资源：$resourcePath" }
                Files.copy(input, executable, StandardCopyOption.REPLACE_EXISTING)
            }
            executable.toFile().setExecutable(true)
            directory.toFile().deleteOnExit()
            executable.toFile().deleteOnExit()
            executable.also { extractedExecutable = it }
        }
    }

    private enum class XaiPlatform(val resourcePath: String?) {
        WINDOWS("/bin/win/xai.exe"),
        MACOS(null),
        LINUX(null),
        OTHER(null);

        companion object {
            fun current(): XaiPlatform = when {
                SystemInfo.isWindows -> WINDOWS
                SystemInfo.isMac -> MACOS
                SystemInfo.isLinux -> LINUX
                else -> OTHER
            }
        }
    }

    private data class CommandOptions(
        val tool: String?,
        val id: String?,
        val model: String?,
    )

    private companion object {
        const val NOTIFICATION_GROUP_ID = "GitMockAI Notifications"
        const val CHECKPOINT_ID_BYTES = 12
        const val MAX_LOG_LENGTH = 2_000
        const val HEX_DIGITS = "0123456789abcdef"
        val SECURE_RANDOM = SecureRandom()
        val EXTRACTION_LOCK = Any()

        @Volatile
        var extractedExecutable: Path? = null
    }
}
