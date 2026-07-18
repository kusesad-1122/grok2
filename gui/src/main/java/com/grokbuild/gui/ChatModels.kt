package com.grokbuild.gui

/** 对话流条目。每一次工具调用/结果都是独立可见条目,过程透明。 */
sealed class ChatItem {
    data class User(val text: String) : ChatItem()
    data class Assistant(val text: String) : ChatItem()
    data class ToolCall(val name: String, val argsJson: String) : ChatItem()
    data class ToolResult(val name: String, val output: String) : ChatItem()
    data class System(val text: String) : ChatItem()
    data class Error(val text: String) : ChatItem()
}
