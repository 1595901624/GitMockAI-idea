package com.hy.ide.cmcc

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
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

        toolMode.addActionListener { updateInputStates() }
        idMode.addActionListener { updateInputStates() }
        modelMode.addActionListener { updateInputStates() }

        return JPanel(GridBagLayout()).also { root ->
            root.border = JBUI.Borders.empty(12)
            addRow(root, 0, "工具（tool）：", toolMode, customTool)
            addRow(root, 1, "会话 ID（id）：", idMode, fixedId)
            addRow(root, 2, "大模型（model）：", modelMode, customModel)
            root.add(
                JPanel(),
                GridBagConstraints().apply {
                    gridx = 0
                    gridy = 3
                    gridwidth = 3
                    weightx = 1.0
                    weighty = 1.0
                    fill = GridBagConstraints.BOTH
                },
            )
            panel = root
            reset()
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
        updateInputStates()
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
    }
}
