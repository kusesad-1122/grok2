package com.grokbuild.gui

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容协议:POST {base_url}/chat/completions。
 * tools 为 [{type:function,function:{name,description,parameters}}];
 * 响应从 choices[0].message(含 tool_calls)解析。
 */
class OpenAiClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val extraHeaders: Map<String, String> = emptyMap()
) : LlmClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    companion object {
        /** JSON Schema 参数对象。 */
        fun toolsJson(tools: List<ToolDef>): JSONArray {
            val arr = JSONArray()
            for (t in tools) {
                val props = JSONObject()
                val required = JSONArray()
                for ((name, spec) in t.parameters) {
                    props.put(name, JSONObject().put("type", spec.type).put("description", spec.description))
                    required.put(name)
                }
                val params = JSONObject()
                    .put("type", "object")
                    .put("properties", props)
                    .put("required", required)
                val fn = JSONObject()
                    .put("name", t.name).put("description", t.description).put("parameters", params)
                arr.put(JSONObject().put("type", "function").put("function", fn))
            }
            return arr
        }

        fun messagesJson(system: String, messages: List<ChatMessage>): JSONArray {
            val arr = JSONArray()
            if (system.isNotBlank()) {
                arr.put(JSONObject().put("role", "system").put("content", system))
            }
            for (m in messages) {
                when (m.role) {
                    "assistant" -> {
                        val o = JSONObject().put("role", "assistant")
                        o.put("content", if (m.content.isBlank()) JSONObject.NULL else m.content)
                        if (m.toolCalls.isNotEmpty()) {
                            val tc = JSONArray()
                            for (c in m.toolCalls) {
                                tc.put(
                                    JSONObject().put("id", c.id).put("type", "function")
                                        .put("function", JSONObject().put("name", c.name).put("arguments", c.args.toString()))
                                )
                            }
                            o.put("tool_calls", tc)
                        }
                        arr.put(o)
                    }
                    "tool" -> arr.put(
                        JSONObject().put("role", "tool").put("tool_call_id", m.toolCallId).put("content", m.content)
                    )
                    else -> arr.put(JSONObject().put("role", m.role).put("content", m.content))
                }
            }
            return arr
        }

        fun buildRequestBody(model: String, system: String, messages: List<ChatMessage>, tools: List<ToolDef>): JSONObject {
            val body = JSONObject()
                .put("model", model)
                .put("messages", messagesJson(system, messages))
            if (tools.isNotEmpty()) {
                body.put("tools", toolsJson(tools))
                body.put("tool_choice", "auto")
            }
            return body
        }

        /** 解析 choices[0].message。 */
        fun parseResponse(json: JSONObject): LlmResult {
            val choices = json.optJSONArray("choices") ?: JSONArray()
            if (choices.length() == 0) {
                val err = json.optJSONObject("error")?.optString("message")
                return LlmResult(err ?: "空响应", emptyList(), "error")
            }
            val choice = choices.getJSONObject(0)
            val msg = choice.optJSONObject("message") ?: JSONObject()
            val text = msg.optString("content", "")
            val calls = mutableListOf<ToolCall>()
            val tcs = msg.optJSONArray("tool_calls")
            if (tcs != null) {
                for (i in 0 until tcs.length()) {
                    val c = tcs.getJSONObject(i)
                    val fn = c.optJSONObject("function") ?: continue
                    val argsStr = fn.optString("arguments", "{}")
                    val args = try { JSONObject(argsStr) } catch (_: Exception) { JSONObject() }
                    calls.add(ToolCall(c.optString("id", "call_$i"), fn.optString("name"), args))
                }
            }
            val finish = choice.optString("finish_reason", "stop")
            return LlmResult(text, calls, if (calls.isNotEmpty()) "tool_calls" else finish)
        }
    }

    override fun send(system: String, messages: List<ChatMessage>, tools: List<ToolDef>): LlmResult {
        val body = buildRequestBody(model, system, messages, tools)
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val reqBuilder = Request.Builder().url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (apiKey.isNotBlank()) reqBuilder.header("Authorization", "Bearer $apiKey")
        for ((k, v) in extraHeaders) reqBuilder.header(k, v)

        http.newCall(reqBuilder.build()).execute().use { resp ->
            val raw = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                return LlmResult("HTTP ${resp.code}:$raw", emptyList(), "error")
            }
            return parseResponse(JSONObject(raw))
        }
    }
}
