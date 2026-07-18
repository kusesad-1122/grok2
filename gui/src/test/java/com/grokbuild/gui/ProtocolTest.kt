package com.grokbuild.gui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 两种协议的请求体序列化与响应解析单测,以及工具命令引用单测。 */
class ProtocolTest {

    private val tools = listOf(
        ToolDef("run_shell", "执行命令", mapOf("command" to ParamSpec("string", "命令")))
    )

    // ── OpenAI 兼容 ────────────────────────────────────────────────
    @Test
    fun openai_request_body_shape() {
        val msgs = listOf(ChatMessage("user", "你好"))
        val body = OpenAiClient.buildRequestBody("gpt-4o", "系统", msgs, tools)
        assertEquals("gpt-4o", body.getString("model"))
        val arr = body.getJSONArray("messages")
        assertEquals("system", arr.getJSONObject(0).getString("role"))
        assertEquals("你好", arr.getJSONObject(1).getString("content"))
        val t = body.getJSONArray("tools").getJSONObject(0)
        assertEquals("function", t.getString("type"))
        assertEquals("run_shell", t.getJSONObject("function").getString("name"))
        assertEquals("auto", body.getString("tool_choice"))
    }

    @Test
    fun openai_parse_tool_call() {
        val resp = JSONObject("""
            {"choices":[{"message":{"content":"好的","tool_calls":[
              {"id":"call_1","type":"function","function":{"name":"run_shell","arguments":"{\"command\":\"ls\"}"}}
            ]},"finish_reason":"tool_calls"}]}
        """.trimIndent())
        val r = OpenAiClient.parseResponse(resp)
        assertTrue(r.wantsTool)
        assertEquals("run_shell", r.toolCalls[0].name)
        assertEquals("ls", r.toolCalls[0].args.getString("command"))
    }

    // ── Anthropic 原生 ────────────────────────────────────────────
    @Test
    fun anthropic_request_body_shape() {
        val msgs = listOf(ChatMessage("user", "你好"))
        val body = AnthropicClient.buildRequestBody("claude-3-5-sonnet-latest", 4096, "系统", msgs, tools)
        assertEquals("系统", body.getString("system"))
        assertEquals(4096, body.getInt("max_tokens"))
        val first = body.getJSONArray("messages").getJSONObject(0)
        assertEquals("user", first.getString("role"))
        assertEquals("text", first.getJSONArray("content").getJSONObject(0).getString("type"))
        val tool = body.getJSONArray("tools").getJSONObject(0)
        assertEquals("run_shell", tool.getString("name"))
        assertTrue(tool.has("input_schema"))
    }

    @Test
    fun anthropic_tool_result_goes_in_user_message() {
        val msgs = listOf(
            ChatMessage("user", "跑一下"),
            ChatMessage("assistant", "", listOf(ToolCall("toolu_1", "run_shell", JSONObject().put("command", "ls")))),
            ChatMessage("tool", "退出码:0", toolCallId = "toolu_1", toolName = "run_shell")
        )
        val arr = AnthropicClient.messagesJson(msgs)
        val last = arr.getJSONObject(arr.length() - 1)
        assertEquals("user", last.getString("role"))
        assertEquals("tool_result", last.getJSONArray("content").getJSONObject(0).getString("type"))
        assertEquals("toolu_1", last.getJSONArray("content").getJSONObject(0).getString("tool_use_id"))
    }

    @Test
    fun anthropic_parse_tool_use() {
        val resp = JSONObject("""
            {"content":[{"type":"text","text":"我来看看"},
              {"type":"tool_use","id":"toolu_9","name":"run_shell","input":{"command":"pwd"}}],
             "stop_reason":"tool_use"}
        """.trimIndent())
        val r = AnthropicClient.parseResponse(resp)
        assertEquals("tool_use", r.stopReason)
        assertEquals("run_shell", r.toolCalls[0].name)
        assertEquals("pwd", r.toolCalls[0].args.getString("command"))
    }

    // ── 工具命令引用 ─────────────────────────────────────────────
    @Test
    fun shell_quote_escapes_single_quotes() {
        assertEquals("'a'\\''b'", Tools.shellQuote("a'b"))
        assertEquals("'/sdcard/x y'", Tools.shellQuote("/sdcard/x y"))
    }

    @Test
    fun read_command_quotes_path() {
        assertEquals("cat '/etc/hosts'", Tools.buildReadCommand("/etc/hosts"))
    }
}
