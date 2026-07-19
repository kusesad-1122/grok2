package com.grokbuild.terminal

import android.content.Context

/**
 * 多厂商 provider 预设。
 *
 * 真实 grok 二进制原生支持 base_url + api_key + model 的 provider 体系:
 * 通过 `~/.grok/config.toml` 的 `[model.<name>]` 配置,`api_backend` 可选
 * "chat_completions"(OpenAI 兼容)/ "responses" / "messages"(Anthropic 原生)。
 * 因此本 App 无需自己实现协议,只把用户在设置页选择/填写的 provider 落成 config.toml。
 */
enum class ApiBackend(val toml: String) {
    CHAT_COMPLETIONS("chat_completions"), // OpenAI 兼容 /v1/chat/completions
    MESSAGES("messages")                  // Anthropic 原生 /v1/messages
}

data class Provider(
    val id: String,            // 内部键(也作为 config.toml 里的 model section 名)
    val display: String,       // 显示名
    val defaultBaseUrl: String,
    val defaultModel: String,
    val backend: ApiBackend,
    val note: String = ""
) {
    val isAnthropicNative: Boolean get() = backend == ApiBackend.MESSAGES
}

object Providers {

    // 预设厂商表(默认值可在设置页编辑;模型 ID 会随时间变化,失效时手动填写)
    val PRESETS: List<Provider> = listOf(
        Provider(
            "xai", "xAI (Grok)",
            "https://api.x.ai/v1", "grok-4.5",
            ApiBackend.CHAT_COMPLETIONS, "原项目默认厂商"
        ),
        Provider(
            "openai", "OpenAI",
            "https://api.openai.com/v1", "gpt-4o",
            ApiBackend.CHAT_COMPLETIONS
        ),
        Provider(
            "anthropic", "Anthropic (Claude)",
            "https://api.anthropic.com/v1", "claude-3-5-sonnet-latest",
            ApiBackend.MESSAGES,
            "使用 Anthropic 原生协议(api_backend=messages),鉴权用 x-api-key + anthropic-version 头"
        ),
        Provider(
            "openrouter", "OpenRouter",
            "https://openrouter.ai/api/v1", "anthropic/claude-3.5-sonnet",
            ApiBackend.CHAT_COMPLETIONS
        ),
        Provider(
            "deepseek", "DeepSeek",
            "https://api.deepseek.com/v1", "deepseek-chat",
            ApiBackend.CHAT_COMPLETIONS
        ),
        Provider(
            "gemini", "Google Gemini",
            "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.0-flash",
            ApiBackend.CHAT_COMPLETIONS, "使用 Gemini 的 OpenAI 兼容端点"
        ),
        Provider(
            "ollama", "Ollama(本地)",
            "http://127.0.0.1:11434/v1", "llama3.1",
            ApiBackend.CHAT_COMPLETIONS, "本地明文 http,无需 API Key"
        ),
        Provider(
            "custom", "自定义",
            "", "",
            ApiBackend.CHAT_COMPLETIONS, "自行填写 base_url 与模型(OpenAI 兼容)"
        ),
    )

    fun byId(id: String): Provider = PRESETS.firstOrNull { it.id == id } ?: PRESETS.first()
}

/**
 * 每个厂商各自保存 base_url / api_key / model;并记录当前激活厂商与是否 root 运行。
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("grok_build_prefs", Context.MODE_PRIVATE)

    var activeProviderId: String
        get() = sp.getString("active_provider", "xai") ?: "xai"
        set(v) = sp.edit().putString("active_provider", v).apply()

    var runAsRoot: Boolean
        get() = sp.getBoolean("run_as_root", false)
        set(v) = sp.edit().putBoolean("run_as_root", v).apply()

    var fontSizeSp: Int
        get() = sp.getInt("font_size", 14)
        set(v) = sp.edit().putInt("font_size", v).apply()

    fun baseUrl(id: String): String =
        sp.getString("base_url_$id", null) ?: Providers.byId(id).defaultBaseUrl
    fun setBaseUrl(id: String, v: String) = sp.edit().putString("base_url_$id", v).apply()

    fun model(id: String): String =
        sp.getString("model_$id", null) ?: Providers.byId(id).defaultModel
    fun setModel(id: String, v: String) = sp.edit().putString("model_$id", v).apply()

    fun apiKey(id: String): String = sp.getString("api_key_$id", "") ?: ""
    fun setApiKey(id: String, v: String) = sp.edit().putString("api_key_$id", v).apply()

    // ── Agent 高级设置(真 grok 支持,现予以暴露)──────────────────────

    /** 思考程度:""(默认)/ minimal / low / medium / high。CLI --reasoning-effort。 */
    var reasoningEffort: String
        get() = sp.getString("reasoning_effort", "") ?: ""
        set(v) = sp.edit().putString("reasoning_effort", v).apply()

    /** 权限模式:yolo(自动放行)/ read-only / default。图形模式经 CLI 生效。 */
    var permissionMode: String
        get() = sp.getString("permission_mode", "yolo") ?: "yolo"
        set(v) = sp.edit().putString("permission_mode", v).apply()

    /** 每个厂商的“常用模型”集合,便于在对话里快速切换。 */
    fun favoriteModels(id: String): List<String> =
        (sp.getStringSet("fav_models_$id", emptySet()) ?: emptySet()).sorted()
    fun setFavoriteModels(id: String, models: Set<String>) =
        sp.edit().putStringSet("fav_models_$id", models).apply()
    fun addFavoriteModels(id: String, models: Collection<String>) {
        val cur = (sp.getStringSet("fav_models_$id", emptySet()) ?: emptySet()).toMutableSet()
        cur.addAll(models); setFavoriteModels(id, cur)
    }

    /**
     * 高级:用户自定义的 config.toml 附加片段(原样追加进 ~/.grok/config.toml)。
     * 这是 grok 完整配置面的入口——可写 [mcp_servers.*]、[permission]、hooks、
     * skills 目录、[agent] 等任意官方配置。
     */
    var extraConfigToml: String
        get() = sp.getString("extra_config_toml", "") ?: ""
        set(v) = sp.edit().putString("extra_config_toml", v).apply()
}
