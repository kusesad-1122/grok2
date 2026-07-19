package com.grokbuild.terminal

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 拉取厂商可用模型列表(GET {base_url}/models)。
 * OpenAI 兼容与 Anthropic 都提供 /v1/models,返回 {"data":[{"id":...}]}。
 * 让用户不必手填模型 ID。
 */
object ModelFetcher {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 返回模型 ID 列表;失败抛异常(带可读信息)。在 IO 线程调用。 */
    fun fetch(baseUrl: String, apiKey: String, isAnthropic: Boolean): List<String> {
        val url = baseUrl.trimEnd('/') + "/models"
        val b = Request.Builder().url(url).get()
        if (isAnthropic) {
            b.header("x-api-key", apiKey)
            b.header("anthropic-version", "2023-06-01")
        } else if (apiKey.isNotBlank()) {
            b.header("Authorization", "Bearer $apiKey")
        }
        http.newCall(b.build()).execute().use { resp ->
            val raw = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            val json = JSONObject(raw)
            val arr = json.optJSONArray("data") ?: json.optJSONArray("models")
            ?: throw RuntimeException("响应无 data 字段")
            val ids = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val id = item.optString("id", item.optString("name", ""))
                if (id.isNotEmpty()) ids.add(id)
            }
            ids.sort()
            return ids
        }
    }
}
