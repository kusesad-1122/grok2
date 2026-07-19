package com.grokbuild.terminal.gui

import com.grokbuild.terminal.R
import android.content.Context

/** 协议风格。 */
enum class Protocol { OPENAI, ANTHROPIC }

data class Provider(
    val id: String,
    val display: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val protocol: Protocol,
    val note: String = ""
)

object Providers {
    val PRESETS: List<Provider> = listOf(
        Provider("xai", "xAI (Grok)", "https://api.x.ai/v1", "grok-4.5", Protocol.OPENAI, "原项目默认厂商"),
        Provider("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o", Protocol.OPENAI),
        Provider("anthropic", "Anthropic (Claude)", "https://api.anthropic.com/v1", "claude-3-5-sonnet-latest", Protocol.ANTHROPIC, "Anthropic 原生协议"),
        Provider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "anthropic/claude-3.5-sonnet", Protocol.OPENAI),
        Provider("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", Protocol.OPENAI),
        Provider("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.0-flash", Protocol.OPENAI, "Gemini 的 OpenAI 兼容端点"),
        Provider("ollama", "Ollama(本地)", "http://127.0.0.1:11434/v1", "llama3.1", Protocol.OPENAI, "本地明文 http,无需 Key"),
        Provider("custom", "自定义", "", "", Protocol.OPENAI, "自行填写(OpenAI 兼容)"),
    )
    fun byId(id: String): Provider = PRESETS.firstOrNull { it.id == id } ?: PRESETS.first()
}

/** 每个厂商各自保存 base_url / api_key / model。 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("grok_gui_prefs", Context.MODE_PRIVATE)

    var activeProviderId: String
        get() = sp.getString("active_provider", "xai") ?: "xai"
        set(v) = sp.edit().putString("active_provider", v).apply()

    var runAsRoot: Boolean
        get() = sp.getBoolean("run_as_root", false)
        set(v) = sp.edit().putBoolean("run_as_root", v).apply()

    fun baseUrl(id: String): String = sp.getString("base_url_$id", null) ?: Providers.byId(id).defaultBaseUrl
    fun setBaseUrl(id: String, v: String) = sp.edit().putString("base_url_$id", v).apply()

    fun model(id: String): String = sp.getString("model_$id", null) ?: Providers.byId(id).defaultModel
    fun setModel(id: String, v: String) = sp.edit().putString("model_$id", v).apply()

    fun apiKey(id: String): String = sp.getString("api_key_$id", "") ?: ""
    fun setApiKey(id: String, v: String) = sp.edit().putString("api_key_$id", v).apply()

    val active: Provider get() = Providers.byId(activeProviderId)
}
