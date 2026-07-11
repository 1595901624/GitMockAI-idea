package com.hy.ide.cmcc

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.FlowLayout
import java.security.SecureRandom
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.text.AbstractDocument
import javax.swing.text.AttributeSet
import javax.swing.text.DocumentFilter

class MockAIConfigurable : Configurable {
    private var panel: JPanel? = null
    private lateinit var toolMode: ComboBox<ToolMode>
    private lateinit var customTool: JBTextField
    private lateinit var idMode: ComboBox<IdMode>
    private lateinit var fixedId: JBTextField
    private lateinit var modelMode: ComboBox<ModelMode>
    private lateinit var customModel: JBTextField
    private lateinit var gitAiPath: TextFieldWithBrowseButton
    private lateinit var runtimeStatus: JBLabel

    override fun getDisplayName(): String = "Mock AI"

    override fun createComponent(): JComponent {
        toolMode = ComboBox(ToolMode.entries.toTypedArray())
        customTool = JBTextField().apply {
            emptyText.text = "工具名称，最长 $MAX_TOOL_LENGTH 位"
            installLengthFilter(MAX_TOOL_LENGTH)
        }
        idMode = ComboBox(IdMode.entries.toTypedArray())
        fixedId = JBTextField().apply {
            emptyText.text = "固定会话 ID，最长 $MAX_ID_LENGTH 位"
            installLengthFilter(MAX_ID_LENGTH)
        }
        modelMode = ComboBox(ModelMode.entries.toTypedArray())
        customModel = JBTextField().apply {
            emptyText.text = "例如 GLM5.2"
        }
        gitAiPath = TextFieldWithBrowseButton(
            JBTextField().apply {
                emptyText.text = "当前是默认路径：${GitAiRuntimeManager.getInstance().defaultExecutablePath()}"
            },
        ).apply {
            toolTipText = "留空时使用 ~/.git-ai/bin/git-ai（Windows 为 git-ai.exe）"
            addBrowseFolderListener(
                "选择 Git AI 可执行文件",
                "请选择 git-ai（Windows 为 git-ai.exe）可执行文件。",
                null,
                FileChooserDescriptorFactory.createSingleFileDescriptor(),
            )
        }
        runtimeStatus = JBLabel("正在检测 Git AI 运行时…")

        toolMode.addActionListener { updateInputStates() }
        idMode.addActionListener {
            if (idMode.selectedItem == IdMode.FIXED) fixedId.text = randomFixedId()
            updateInputStates()
        }
        modelMode.addActionListener { updateInputStates() }

        return JPanel(GridBagLayout()).also { root ->
            root.border = JBUI.Borders.empty(12)
            addSectionTitle(root, 0, "运行时")
            addRow(root, 1, "Git AI 路径：", JBLabel("可选"), gitAiPath)
            addWideRow(root, 2, "运行时状态：", runtimeStatus)
            addWideRow(root, 3, "运行时操作：", runtimeButtons())
            addWideRow(
                root,
                4,
                "注意：",
                JBLabel("使用插件版本的 git-ai 文件后，Trae、Qoder、iHomeCoder 的 hook 功能将失效。"),
            )
            addSectionTitle(root, 5, "命令")
            addRow(root, 6, "工具（tool）：", toolMode, customTool)
            addRow(root, 7, "会话 ID（id）：", idMode, fixedId)
            addRow(root, 8, "大模型（model）：", modelMode, customModel)
            root.add(
                JPanel(),
                GridBagConstraints().apply {
                    gridx = 0
                    gridy = 9
                    gridwidth = 3
                    weightx = 1.0
                    weighty = 1.0
                    fill = GridBagConstraints.BOTH
                },
            )
            panel = root
            reset()
            refreshRuntimeStatus()
        }
    }

    override fun isModified(): Boolean {
        if (panel == null) return false
        return currentSnapshot() != MockAISettings.getInstance().snapshot()
    }

    @Throws(ConfigurationException::class)
    override fun apply() {
        val snapshot = currentSnapshot()
        validate(snapshot)
        MockAISettings.getInstance().update(snapshot)
    }

    override fun reset() {
        if (panel == null) return
        val settings = MockAISettings.getInstance().snapshot()
        toolMode.selectedItem = settings.toolMode
        customTool.text = settings.customTool
        idMode.selectedItem = settings.idMode
        fixedId.text = settings.fixedId
        modelMode.selectedItem = settings.modelMode
        customModel.text = settings.customModel
        gitAiPath.text = settings.gitAiPath
        updateInputStates()
        refreshRuntimeStatus()
    }

    override fun disposeUIResources() {
        panel = null
    }

    private fun currentSnapshot(): MockAISettingsSnapshot = MockAISettingsSnapshot(
        toolMode = toolMode.selectedItem as ToolMode,
        customTool = customTool.text.trim(),
        idMode = idMode.selectedItem as IdMode,
        fixedId = fixedId.text.trim(),
        modelMode = modelMode.selectedItem as ModelMode,
        customModel = customModel.text.trim(),
        gitAiPath = gitAiPath.text.trim(),
    )

    private fun validate(settings: MockAISettingsSnapshot) {
        if (settings.toolMode == ToolMode.CUSTOM && settings.customTool.isEmpty()) {
            throw ConfigurationException("选择自定义工具时，工具名称不能为空。")
        }
        if (settings.customTool.length > MAX_TOOL_LENGTH) {
            throw ConfigurationException("工具名称不能超过 $MAX_TOOL_LENGTH 位。")
        }
        if (settings.idMode == IdMode.FIXED && settings.fixedId.isEmpty()) {
            throw ConfigurationException("选择固定会话 ID 时，ID 不能为空。")
        }
        if (settings.fixedId.length > MAX_ID_LENGTH) {
            throw ConfigurationException("会话 ID 不能超过 $MAX_ID_LENGTH 位。")
        }
        if (settings.modelMode == ModelMode.CUSTOM && settings.customModel.isEmpty()) {
            throw ConfigurationException("选择自定义大模型时，模型名称不能为空。")
        }
    }

    private fun updateInputStates() {
        customTool.isEnabled = toolMode.selectedItem == ToolMode.CUSTOM
        fixedId.isEnabled = idMode.selectedItem == IdMode.FIXED
        customModel.isEnabled = modelMode.selectedItem == ModelMode.CUSTOM
    }

    private fun addRow(
        root: JPanel,
        row: Int,
        label: String,
        mode: JComponent,
        input: JComponent,
    ) {
        root.add(
            JBLabel(label),
            GridBagConstraints().apply {
                gridx = 0
                gridy = row
                anchor = GridBagConstraints.WEST
                insets = JBUI.insets(0, 0, 8, 8)
            },
        )
        root.add(
            mode,
            GridBagConstraints().apply {
                gridx = 1
                gridy = row
                anchor = GridBagConstraints.WEST
                insets = JBUI.insets(0, 0, 8, 8)
            },
        )
        root.add(
            input,
            GridBagConstraints().apply {
                gridx = 2
                gridy = row
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                insets = JBUI.insetsBottom(8)
            },
        )
    }

    private fun addWideRow(root: JPanel, row: Int, label: String, component: JComponent) {
        root.add(
            JBLabel(label),
            GridBagConstraints().apply {
                gridx = 0
                gridy = row
                anchor = GridBagConstraints.NORTHWEST
                insets = JBUI.insets(0, 0, 8, 8)
            },
        )
        root.add(
            component,
            GridBagConstraints().apply {
                gridx = 1
                gridy = row
                gridwidth = 2
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                insets = JBUI.insetsBottom(8)
            },
        )
    }

    private fun addSectionTitle(root: JPanel, row: Int, title: String) {
        root.add(
            TitledSeparator(title),
            GridBagConstraints().apply {
                gridx = 0
                gridy = row
                gridwidth = 3
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                insets = JBUI.insets(4, 0, 10, 0)
            },
        )
    }

    private fun runtimeButtons(): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        add(JButton("安装/修复插件版本").apply {
            addActionListener {
                runRuntimeTask {
                    GitAiRuntimeManager.getInstance().ensurePatched(
                        null,
                        gitAiPath.text.trim(),
                        panel,
                        requireOriginalForInstall = true,
                    )
                }
            }
        })
        add(JButton("恢复原版 Git AI").apply {
            margin = JBUI.insets(0, 8)
            addActionListener {
                runRuntimeTask {
                    GitAiRuntimeManager.getInstance().restoreLatest(null, gitAiPath.text.trim(), panel)
                }
            }
        })
        add(JButton("刷新状态").apply {
            margin = JBUI.insets(0, 8)
            addActionListener { refreshRuntimeStatus() }
        })
    }

    private fun runRuntimeTask(task: () -> GitAiRuntimeResult) {
        runtimeStatus.text = "正在执行…"
        val settingsPanel = panel
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = task()
            ApplicationManager.getApplication().invokeLater({
                if (panel === settingsPanel) {
                    runtimeStatus.text = result.message
                    if (result.success) refreshRuntimeStatus()
                }
            }, ModalityState.any())
        }
    }

    private fun randomFixedId(): String {
        val bytes = ByteArray(RANDOM_ID_BYTES)
        SECURE_RANDOM.nextBytes(bytes)
        return buildString(RANDOM_ID_BYTES * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX_DIGITS[value ushr 4])
                append(HEX_DIGITS[value and 0x0f])
            }
        }
    }

    private fun refreshRuntimeStatus() {
        if (!::runtimeStatus.isInitialized) return
        val configuredPath = if (::gitAiPath.isInitialized) gitAiPath.text.trim() else ""
        val settingsPanel = panel
        ApplicationManager.getApplication().executeOnPooledThread {
            val status = GitAiRuntimeManager.getInstance().status(configuredPath)
            ApplicationManager.getApplication().invokeLater({
                if (panel === settingsPanel) {
                    runtimeStatus.text = status.message
                    runtimeStatus.toolTipText = status.executable?.toString()
                }
            }, ModalityState.any())
        }
    }

    private fun JBTextField.installLengthFilter(maxLength: Int) {
        (document as AbstractDocument).documentFilter = MaxLengthDocumentFilter(maxLength)
    }

    private class MaxLengthDocumentFilter(private val maxLength: Int) : DocumentFilter() {
        override fun insertString(fb: FilterBypass, offset: Int, string: String?, attr: AttributeSet?) {
            replace(fb, offset, 0, string, attr)
        }

        override fun replace(
            fb: FilterBypass,
            offset: Int,
            length: Int,
            text: String?,
            attrs: AttributeSet?,
        ) {
            val value = text.orEmpty()
            val available = maxLength - (fb.document.length - length)
            if (available <= 0) return
            super.replace(fb, offset, length, value.take(available), attrs)
        }
    }

    private companion object {
        const val MAX_TOOL_LENGTH = 20
        const val MAX_ID_LENGTH = 128
        const val RANDOM_ID_BYTES = 12
        const val HEX_DIGITS = "0123456789abcdef"
        val SECURE_RANDOM = SecureRandom()
    }
}
