package com.grokbuild.gui

import org.json.JSONObject

/** 工具参数规格。 */
data class ParamSpec(val type: String, val description: String)

/** 中立的工具定义(与厂商无关)。 */
data class ToolDef(
    val name: String,
    val description: String,
    val parameters: Map<String, ParamSpec>
)

/** 一次工具调用(模型发起)。 */
data class ToolCall(
    val id: String,
    val name: String,
    val args: JSONObject
)

/** 中立会话消息。role ∈ system/user/assistant/tool。 */
data class ChatMessage(
    val role: String,
    val content: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    // role == "tool" 时:
    val toolCallId: String = "",
    val toolName: String = ""
)

/** 一轮模型返回。 */
data class LlmResult(
    val assistantText: String,
    val toolCalls: List<ToolCall>,
    val stopReason: String
) {
    val wantsTool: Boolean get() = toolCalls.isNotEmpty()
}

/** 统一的中立客户端抽象:agent 工具循环与具体厂商协议解耦。 */
interface LlmClient {
    /** 发送一轮请求(阻塞式;在 IO 线程调用)。 */
    fun send(system: String, messages: List<ChatMessage>, tools: List<ToolDef>): LlmResult
}
