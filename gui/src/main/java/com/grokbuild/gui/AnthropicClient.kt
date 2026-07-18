package com.grokbuild.gui

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Anthropic 原生协议:POST {base_url}/messages。
 * 顶层 system;messages 用 content blocks(text/tool_use/tool_result,tool_result 放在 user 消息里);
 * tools 为 [{name,description,input_schema}];必须带 max_tokens;
 * 响应从 content[] 解析 text 与 tool_use,stop_reason=="tool_use" 时继续循环。
 */
class AnthropicClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val maxTokens: Int = 4096
) : LlmClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    companion object {
        fun toolsJson(tools: List<ToolDef>): JSONArray {
            val arr = JSONArray()
            for (t in tools) {
                val props = JSONObject()
                val required = JSONArray()
                for ((name, spec) in t.parameters) {
                    props.put(name, JSONObject().put("type", spec.type).put("description", spec.description))
                    required.put(name)
                }
                val schema = JSONObject().put("type", "object").put("properties", props).put("required", required)
                arr.put(JSONObject().put("name", t.name).put("description", t.description).put("input_schema", schema))
            }
            return arr
        }

        /**
         * 中立消息 → Anthropic messages。assistant 的 text + tool_use 合并为一条 assistant;
         * role=="tool" 的结果聚合进随后的一条 user 消息(tool_result blocks)。
         */
        fun messagesJson(messages: List<ChatMessage>): JSONArray {
            val arr = JSONArray()
            var i = 0
            while (i < messages.size) {
                val m = messages[i]
                when (m.role) {
                    "user" -> {
                        arr.put(JSONObject().put("role", "user")
                            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", m.content))))
                        i++
                    }
                    "assistant" -> {
                        val blocks = JSONArray()
                        if (m.content.isNotBlank()) blocks.put(JSONObject().put("type", "text").put("text", m.content))
                        for (c in m.toolCalls) {
                            blocks.put(JSONObject().put("type", "tool_use").put("id", c.id)
                                .put("name", c.name).put("input", c.args))
                        }
                        arr.put(JSONObject().put("role", "assistant").put("content", blocks))
                        i++
                    }
                    "tool" -> {
                        // 把连续的 tool 结果聚合到一条 user 消息。
                        val blocks = JSONArray()
                        while (i < messages.size && messages[i].role == "tool") {
                            val t = messages[i]
                            blocks.put(JSONObject().put("type", "tool_result")
                                .put("tool_use_id", t.toolCallId).put("content", t.content))
                            i++
                        }
                        arr.put(JSONObject().put("role", "user").put("content", blocks))
                    }
                    else -> i++ // system 单独处理
                }
            }
            return arr
        }

        fun buildRequestBody(model: String, maxTokens: Int, system: String, messages: List<ChatMessage>, tools: List<ToolDef>): JSONObject {
            val body = JSONObject()
                .put("model", model)
                .put("max_tokens", maxTokens)
                .put("messages", messagesJson(messages))
            if (system.isNotBlank()) body.put("system", system)
            if (tools.isNotEmpty()) body.put("tools", toolsJson(tools))
            return body
        }

        fun parseResponse(json: JSONObject): LlmResult {
            if (json.has("error")) {
                return LlmResult(json.optJSONObject("error")?.optString("message") ?: "错误", emptyList(), "error")
            }
            val content = json.optJSONArray("content") ?: JSONArray()
            val sb = StringBuilder()
            val calls = mutableListOf<ToolCall>()
            for (i in 0 until content.length()) {
                val blk = content.getJSONObject(i)
                when (blk.optString("type")) {
                    "text" -> sb.append(blk.optString("text"))
                    "tool_use" -> calls.add(
                        ToolCall(blk.optString("id", "toolu_$i"), blk.optString("name"), blk.optJSONObject("input") ?: JSONObject())
                    )
                }
            }
            val stop = json.optString("stop_reason", "end_turn")
            return LlmResult(sb.toString(), calls, stop)
        }
    }

    override fun send(system: String, messages: List<ChatMessage>, tools: List<ToolDef>): LlmResult {
        val body = buildRequestBody(model, maxTokens, system, messages, tools)
        val url = baseUrl.trimEnd('/') + "/messages"
        val req = Request.Builder().url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .build()
        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string() ?: ""
            if (!resp.isSuccessful) return LlmResult("HTTP ${resp.code}:$raw", emptyList(), "error")
            return parseResponse(JSONObject(raw))
        }
    }
}
