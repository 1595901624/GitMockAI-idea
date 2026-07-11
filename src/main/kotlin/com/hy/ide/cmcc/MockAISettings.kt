package com.hy.ide.cmcc

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

enum class ToolMode(val displayName: String) {
    DEFAULT_TRAE("默认（trae）"),
    OMIT("不传"),
    CUSTOM("自定义");

    override fun toString(): String = displayName
}

enum class IdMode(val displayName: String) {
    RANDOM("随机生成（默认）"),
    OMIT("不传"),
    FIXED("固定值");

    override fun toString(): String = displayName
}

enum class ModelMode(val displayName: String) {
    OMIT("不传（默认）"),
    CUSTOM("自定义");

    override fun toString(): String = displayName
}

data class MockAISettingsSnapshot(
    val toolMode: ToolMode,
    val customTool: String,
    val idMode: IdMode,
    val fixedId: String,
    val modelMode: ModelMode,
    val customModel: String,
    val gitAiPath: String,
)

@Service(Service.Level.APP)
@State(name = "GitMockAISettings", storages = [Storage("GitMockAI.xml")])
class MockAISettings : PersistentStateComponent<MockAISettings.SettingsState> {
    private var settingsState = SettingsState()

    override fun getState(): SettingsState = settingsState

    override fun loadState(state: SettingsState) {
        settingsState = state
    }

    fun snapshot(): MockAISettingsSnapshot = MockAISettingsSnapshot(
        toolMode = settingsState.toolMode.toEnumOrDefault(ToolMode.DEFAULT_TRAE),
        customTool = settingsState.customTool,
        idMode = settingsState.idMode.toEnumOrDefault(IdMode.RANDOM),
        fixedId = settingsState.fixedId,
        modelMode = settingsState.modelMode.toEnumOrDefault(ModelMode.OMIT),
        customModel = settingsState.customModel,
        gitAiPath = settingsState.gitAiPath,
    )

    fun update(snapshot: MockAISettingsSnapshot) {
        settingsState.toolMode = snapshot.toolMode.name
        settingsState.customTool = snapshot.customTool
        settingsState.idMode = snapshot.idMode.name
        settingsState.fixedId = snapshot.fixedId
        settingsState.modelMode = snapshot.modelMode.name
        settingsState.customModel = snapshot.customModel
        settingsState.gitAiPath = snapshot.gitAiPath
    }

    class SettingsState {
        var toolMode: String = ToolMode.DEFAULT_TRAE.name
        var customTool: String = ""
        var idMode: String = IdMode.RANDOM.name
        var fixedId: String = ""
        var modelMode: String = ModelMode.OMIT.name
        var customModel: String = ""
        var gitAiPath: String = ""
    }

    companion object {
        fun getInstance(): MockAISettings =
            ApplicationManager.getApplication().getService(MockAISettings::class.java)
    }
}

private inline fun <reified T : Enum<T>> String.toEnumOrDefault(default: T): T =
    enumValues<T>().firstOrNull { it.name == this } ?: default
