package com.grokbuild.gui

import android.content.Context

/**
 * agent 工具循环:复刻 grok-build 的“对话式 coding/运维 agent + 工具循环”内核。
 * 与具体厂商解耦——通过统一 LlmClient 抽象驱动 OpenAI 兼容 / Anthropic 原生两种协议。
 */
class Agent(private val context: Context, private val prefs: Prefs) {

    val messages = mutableListOf<ChatMessage>()

    companion object {
        const val MAX_TURNS = 25

        // 全中文系统提示词(忠实 grok 的编码/运维 agent 身份)。
        val SYSTEM_PROMPT = """
            你是 Grok Build——一个运行在安卓手机上的对话式编码/运维 AI agent。
            始终使用【简体中文】与用户交流。

            工作方式:
            - 你可以自主调用工具来完成任务,拿到结果后继续推理,直到给出最终答复。
            - 可用工具:
              · run_shell(command):在设备上执行一条 shell 命令;
              · read_file(path):读取文件;
              · write_file(path, content):覆盖写文件。
            - 需要查看系统状态、文件、执行操作时,主动调用工具,不要凭空猜测。

            安全:
            - 执行任何【破坏性命令】(删除、`rm -rf`、`dd`、刷写分区、格式化、覆盖系统文件等)之前,
              先用一句话说明该命令的风险与后果,再执行。
            - 命令输出很长时,先给要点摘要。

            风格:简洁、直接、专业;给出可执行的具体步骤。
        """.trimIndent()
    }

    fun buildClient(): LlmClient {
        val p = prefs.active
        val baseUrl = prefs.baseUrl(p.id)
        val apiKey = prefs.apiKey(p.id)
        val model = prefs.model(p.id).ifBlank { p.defaultModel }
        return when (p.protocol) {
            Protocol.ANTHROPIC -> AnthropicClient(baseUrl, apiKey, model)
            Protocol.OPENAI -> {
                val extra = if (p.id == "openrouter")
                    mapOf("HTTP-Referer" to "https://github.com/xai-org/grok-build", "X-Title" to "Grok Build")
                else emptyMap()
                OpenAiClient(baseUrl, apiKey, model, extra)
            }
        }
    }

    /** 处理一条用户输入,跑完整个工具循环。emit 在调用线程回调(UI 层负责切主线程)。 */
    fun runTurn(userText: String, emit: (ChatItem) -> Unit) {
        messages.add(ChatMessage("user", userText))
        val asRoot = prefs.runAsRoot
        val client = buildClient()
        val tools = Tools.toolDefs()

        var turns = 0
        while (turns++ < MAX_TURNS) {
            val result = try {
                client.send(SYSTEM_PROMPT, messages, tools)
            } catch (e: Exception) {
                emit(ChatItem.Error("请求失败:${e.message}"))
                return
            }
            if (result.stopReason == "error") {
                emit(ChatItem.Error(result.assistantText))
                return
            }

            // 记录 assistant 消息(文本 + 工具调用)。
            messages.add(ChatMessage("assistant", result.assistantText, result.toolCalls))
            if (result.assistantText.isNotBlank()) emit(ChatItem.Assistant(result.assistantText))

            if (!result.wantsTool) return // 最终答复

            for (call in result.toolCalls) {
                emit(ChatItem.ToolCall(call.name, call.args.toString()))
                val output = Tools.run(call, asRoot)
                messages.add(ChatMessage("tool", output, toolCallId = call.id, toolName = call.name))
                emit(ChatItem.ToolResult(call.name, output))
            }
            // 继续循环,让模型基于工具结果推理。
        }
        emit(ChatItem.Error("已达到最大工具循环次数($MAX_TURNS),已停止。"))
    }

    fun reset() = messages.clear()
}
