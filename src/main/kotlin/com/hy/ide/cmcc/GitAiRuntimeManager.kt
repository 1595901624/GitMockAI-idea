package com.hy.ide.cmcc

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo
import java.awt.Component
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class GitAiRuntimeResult(
    val executable: Path? = null,
    val message: String,
    val success: Boolean,
    val changed: Boolean = false,
)

data class GitAiRuntimeStatus(
    val executable: Path?,
    val isPatched: Boolean,
    val backupCount: Int,
    val message: String,
)

/** Manages the user-installed Git AI binary without changing it until the user explicitly approves it. */
@Service(Service.Level.APP)
class GitAiRuntimeManager {
    private val operationLock = ReentrantLock()
    private val homeDirectory: Path = Paths.get(System.getProperty("user.home"))
    private val backupRoot: Path = homeDirectory.resolve(".gitmockai").resolve("backups")

    /**
     * Ensures that the Git AI executable used by this plugin supports `version-code`.
     * A non-plugin executable is backed up before it is replaced and the replacement is verified.
     */
    fun ensurePatched(
        project: Project?,
        configuredPath: String? = null,
        parentComponent: Component? = null,
        requireOriginalForInstall: Boolean = false,
    ): GitAiRuntimeResult = operationLock.withLock {
        val target = resolveTarget(configuredPath, requireExisting = true)
            ?: return GitAiRuntimeResult(
                message = "未找到 Git AI。请先按官方方式安装 Git AI，或在设置中指定 git-ai 可执行文件路径。",
                success = false,
            )

        if (supportsVersionCode(target)) {
            if (requireOriginalForInstall) {
                return GitAiRuntimeResult(
                    target,
                    "当前 Git AI 已支持 git ai version-code，判定不是原版，无法备份。",
                    success = false,
                )
            }
            return GitAiRuntimeResult(target, "正在使用 GitAI助手版本。", success = true)
        }

        val confirmation = """
            GitAI助手需要使用包含 version-code 命令的 Git AI 版本。

            当前文件：$target
            将先备份当前 Git AI，然后替换为插件内置版本。可随时在设置中恢复原版。
        """.trimIndent()
        if (!confirm(project, parentComponent, "启用 GitAI助手运行时", confirmation, "备份并替换")) {
            return GitAiRuntimeResult(message = "已取消替换 Git AI。", success = false)
        }

        val backup = try {
            backupOriginal(target)
        } catch (exception: Exception) {
            return GitAiRuntimeResult(
                message = "备份原版 Git AI 失败：${exception.message ?: exception.javaClass.simpleName}",
                success = false,
            )
        }

        try {
            stopBackgroundService(target)
            installBundledBinary(target)
            if (!supportsVersionCode(target)) {
                restoreBackup(backup, target)
                return GitAiRuntimeResult(
                    message = "替换后的 Git AI 未通过 version-code 校验，已自动恢复原版。",
                    success = false,
                )
            }
        } catch (exception: Exception) {
            runCatching { restoreBackup(backup, target) }
            return GitAiRuntimeResult(
                message = "替换 Git AI 失败，已尝试恢复原版：${exception.message ?: exception.javaClass.simpleName}",
                success = false,
            )
        }

        GitAiRuntimeResult(target, "已备份原版并启用 GitAI助手版本。", success = true, changed = true)
    }

    fun restoreLatest(
        project: Project?,
        configuredPath: String? = null,
        parentComponent: Component? = null,
    ): GitAiRuntimeResult = operationLock.withLock {
        val target = resolveTarget(configuredPath, requireExisting = false)
            ?: return GitAiRuntimeResult(message = "无法确定 Git AI 的目标路径。", success = false)
        val backup = latestBackup(target)
            ?: return GitAiRuntimeResult(message = "没有找到此 Git AI 的可恢复备份。", success = false)

        val confirmation = """
            将恢复备份的原版 Git AI：
            $backup

            当前 Git AI 文件会被替换。插件版本仍会保留在备份目录中以外，不会删除其他设置或归因数据。
        """.trimIndent()
        if (!confirm(project, parentComponent, "恢复原版 Git AI", confirmation, "恢复原版")) {
            return GitAiRuntimeResult(message = "已取消恢复。", success = false)
        }

        try {
            if (Files.exists(target)) stopBackgroundService(target)
            restoreBackup(backup, target)
        } catch (exception: Exception) {
            return GitAiRuntimeResult(
                message = "恢复原版 Git AI 失败：${exception.message ?: exception.javaClass.simpleName}",
                success = false,
            )
        }
        GitAiRuntimeResult(target, "已恢复原版 Git AI。", success = true, changed = true)
    }

    fun status(configuredPath: String? = null): GitAiRuntimeStatus {
        val target = resolveTarget(configuredPath, requireExisting = false)
        if (target == null || !Files.isRegularFile(target)) {
            return GitAiRuntimeStatus(null, false, 0, "未找到 Git AI。")
        }
        val backupCount = backupsFor(target).size
        val patched = supportsVersionCode(target)
        val message = when {
            patched -> "已启用插件版本；可恢复备份：$backupCount 个"
            backupCount > 0 -> "当前为原版或已更新版本；可恢复备份：$backupCount 个"
            else -> "当前为原版 Git AI，尚未创建备份"
        }
        return GitAiRuntimeStatus(target, patched, backupCount, message)
    }

    private fun resolveTarget(configuredPath: String?, requireExisting: Boolean): Path? {
        configuredPath?.trim()?.takeIf { it.isNotEmpty() }?.let { return normalizedPath(Paths.get(it)) }

        val officialPath = homeDirectory.resolve(".git-ai").resolve("bin").resolve(executableName())
        if (Files.exists(officialPath)) return normalizedPath(officialPath)

        findOnPath()?.let { return normalizedPath(it) }
        return if (requireExisting) null else normalizedPath(officialPath)
    }

    private fun findOnPath(): Path? = runCatching {
        val command = if (SystemInfo.isWindows) listOf("where.exe", "git-ai") else listOf("which", "git-ai")
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        val output = process.inputStream.bufferedReader().use(BufferedReader::readText)
        if (process.exitValue() != 0) return@runCatching null
        output.lineSequence()
            .map(String::trim)
            .firstOrNull { it.isNotEmpty() }
            ?.let(Paths::get)
            ?.takeIf(Files::isRegularFile)
    }.getOrNull()

    private fun normalizedPath(path: Path): Path = try {
        path.toRealPath()
    } catch (_: Exception) {
        path.toAbsolutePath().normalize()
    }

    private fun supportsVersionCode(target: Path): Boolean {
        val result = runBinary(target, listOf("version-code")) ?: return false
        return result.exitCode == 0 && VERSION_CODE_PATTERN.matches(result.output.trim())
    }

    private fun backupOriginal(target: Path): Path {
        require(!supportsVersionCode(target)) {
            "当前 Git AI 已支持 git ai version-code，判定不是原版，无法备份。"
        }
        val contentHash = sha256(target)
        val sourceKey = sha256(target.toString().toByteArray(StandardCharsets.UTF_8)).take(SOURCE_KEY_LENGTH)
        val directory = backupRoot.resolve(contentHash).resolve(sourceKey)
        Files.createDirectories(directory)

        val backup = directory.resolve(target.fileName.toString())
        if (!Files.exists(backup)) Files.copy(target, backup)

        val manifest = Properties().apply {
            setProperty("sourcePath", target.toString())
            setProperty("sha256", contentHash)
            setProperty("backupFile", backup.fileName.toString())
            setProperty("backedUpAt", System.currentTimeMillis().toString())
            setProperty("version", runBinary(target, listOf("--version"))?.output?.trim().orEmpty())
        }
        Files.newOutputStream(directory.resolve(MANIFEST_FILE_NAME)).use { manifest.store(it, "GitMockAI Git AI backup") }
        return backup
    }

    private fun installBundledBinary(target: Path) {
        val parent = target.parent ?: error("Git AI 路径没有父目录：$target")
        Files.createDirectories(parent)
        val resourceName = bundledResourceName()
        val tempFile = Files.createTempFile(parent, ".gitmockai-", ".tmp")
        try {
            javaClass.classLoader.getResourceAsStream("lib/$resourceName")?.use { input ->
                Files.newOutputStream(tempFile).use { output -> input.copyTo(output) }
            } ?: error("插件中缺少当前平台的 Git AI 文件：lib/$resourceName")
            makeExecutable(tempFile)
            moveReplacing(tempFile, target)
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    private fun restoreBackup(backup: Path, target: Path) {
        val parent = target.parent ?: error("Git AI 路径没有父目录：$target")
        Files.createDirectories(parent)
        val tempFile = Files.createTempFile(parent, ".gitmockai-restore-", ".tmp")
        try {
            Files.copy(backup, tempFile, StandardCopyOption.REPLACE_EXISTING)
            makeExecutable(tempFile)
            moveReplacing(tempFile, target)
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    private fun moveReplacing(source: Path, target: Path) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun makeExecutable(path: Path) {
        val posixView = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
        if (posixView != null) {
            Files.setPosixFilePermissions(
                path,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE,
                ),
            )
        }
    }

    private fun stopBackgroundService(target: Path) {
        runBinary(target, listOf("bg", "shutdown"))
    }

    private fun runBinary(target: Path, arguments: List<String>): ProcessResult? = runCatching {
        val process = ProcessBuilder(listOf(target.toString()) + arguments)
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        val output = process.inputStream.bufferedReader().use(BufferedReader::readText)
        ProcessResult(process.exitValue(), output)
    }.getOrNull()

    private fun backupsFor(target: Path): List<Path> {
        if (!Files.isDirectory(backupRoot)) return emptyList()
        return Files.walk(backupRoot, BACKUP_SEARCH_DEPTH).use { paths ->
            paths.iterator().asSequence()
                .filter { it.fileName.toString() == MANIFEST_FILE_NAME }
                .mapNotNull { manifest -> backupFromManifest(manifest, target) }
                .toList()
        }
    }

    private fun latestBackup(target: Path): Path? = backupsFor(target)
        .maxByOrNull { Files.getLastModifiedTime(it).toMillis() }

    private fun backupFromManifest(manifest: Path, target: Path): Path? = runCatching {
        val properties = Properties()
        Files.newInputStream(manifest).use(properties::load)
        if (properties.getProperty("sourcePath") != target.toString()) return@runCatching null
        manifest.parent.resolve(properties.getProperty("backupFile")).takeIf(Files::isRegularFile)
    }.getOrNull()

    private fun sha256(path: Path): String = sha256(Files.readAllBytes(path))

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun bundledResourceName(): String {
        val platform = when {
            SystemInfo.isWindows -> "windows"
            SystemInfo.isMac -> "macos"
            SystemInfo.isLinux -> "linux"
            else -> error("不支持的操作系统：${SystemInfo.OS_NAME}")
        }
        return "git-ai-$platform-${architecture()}${if (SystemInfo.isWindows) ".exe" else ""}"
    }

    private fun architecture(): String = when (System.getProperty("os.arch").lowercase()) {
        "amd64", "x86_64", "x64" -> "x64"
        "aarch64", "arm64" -> "arm64"
        else -> error("不支持的系统架构：${System.getProperty("os.arch")}")
    }

    private fun executableName(): String = if (SystemInfo.isWindows) "git-ai.exe" else "git-ai"

    private fun confirm(
        project: Project?,
        parentComponent: Component?,
        title: String,
        message: String,
        confirmText: String,
    ): Boolean {
        var accepted = false
        val showDialog = {
            val dialog = MessageDialogBuilder.yesNo(title, message)
                .yesText(confirmText)
                .noText("取消")
                .icon(Messages.getWarningIcon())
            accepted = if (parentComponent != null) {
                dialog.ask(parentComponent)
            } else {
                dialog.ask(project)
            }
        }
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) showDialog() else application.invokeAndWait(showDialog, ModalityState.any())
        return accepted
    }

    private data class ProcessResult(val exitCode: Int, val output: String)

    companion object {
        const val COMMAND_TIMEOUT_SECONDS = 10L
        const val SOURCE_KEY_LENGTH = 16
        const val MANIFEST_FILE_NAME = "manifest.properties"
        const val BACKUP_SEARCH_DEPTH = 3
        val VERSION_CODE_PATTERN = Regex("\\d+")

        fun getInstance(): GitAiRuntimeManager =
            ApplicationManager.getApplication().getService(GitAiRuntimeManager::class.java)
    }
}
