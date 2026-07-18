package com.grokbuild.terminal

import android.content.Context
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

/**
 * 负责:
 *  1. 定位随 APK 打包的真实 grok 原生可执行文件(libgrok.so,位于 nativeLibraryDir);
 *  2. 依据当前 provider 生成 `$GROK_HOME/config.toml`(多厂商:OpenAI 兼容 / Anthropic 原生);
 *  3. 组装环境变量并创建终端会话运行 grok TUI(可选经 root shell)。
 */
object GrokLauncher {

    /** grok 二进制以 libgrok.so 形式打包,只有放在 lib 目录才能在 Android 10+ 上被执行。 */
    fun grokBinary(context: Context): File {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        return File(nativeDir, "libgrok.so")
    }

    fun isBinaryPresent(context: Context): Boolean = grokBinary(context).canExecute()

    /** GROK_HOME:配置、auth、日志的家目录,放在应用私有目录。 */
    private fun grokHome(context: Context): File =
        File(context.filesDir, "grokhome").apply { mkdirs() }

    private fun workDir(context: Context): File =
        File(context.filesDir, "workspace").apply { mkdirs() }

    /**
     * 依据激活的 provider 写出真实 grok 能识别的 config.toml。
     * OpenAI 兼容 → api_backend=chat_completions;Anthropic → messages + x-api-key/anthropic-version 头。
     */
    fun writeConfig(context: Context, prefs: Prefs) {
        val p = Providers.byId(prefs.activeProviderId)
        val baseUrl = prefs.baseUrl(p.id).trim()
        val model = prefs.model(p.id).trim().ifEmpty { p.defaultModel }
        val apiKey = prefs.apiKey(p.id).trim()

        val sb = StringBuilder()
        sb.append("# 本文件由 Grok Build 手机版自动生成(移植自 xai-org/grok-build,Apache-2.0)。\n")
        sb.append("# 当前厂商:${p.display}\n\n")
        sb.append("[models]\n")
        sb.append("default = \"${p.id}\"\n\n")

        sb.append("[model.${p.id}]\n")
        sb.append("name = ${tomlStr(p.display)}\n")
        sb.append("model = ${tomlStr(model)}\n")
        if (baseUrl.isNotEmpty()) sb.append("base_url = ${tomlStr(baseUrl)}\n")
        sb.append("api_backend = ${tomlStr(p.backend.toml)}\n")
        sb.append("context_window = 200000\n")

        if (p.isAnthropicNative) {
            // Anthropic 原生:鉴权走 x-api-key + anthropic-version 头(grok 原样发送)。
            if (apiKey.isNotEmpty()) {
                sb.append(
                    "extra_headers = { \"x-api-key\" = ${tomlStr(apiKey)}, " +
                        "\"anthropic-version\" = \"2023-06-01\" }\n"
                )
            } else {
                sb.append("extra_headers = { \"anthropic-version\" = \"2023-06-01\" }\n")
            }
        } else {
            // OpenAI 兼容:Authorization: Bearer <api_key>
            if (apiKey.isNotEmpty()) sb.append("api_key = ${tomlStr(apiKey)}\n")
        }

        File(grokHome(context), "config.toml").writeText(sb.toString())
        writeChineseRules(context)
    }

    /**
     * 路线 A 下 grok 的内建系统提示词为英文;通过 grok 的“项目规则”(AGENTS.md)注入
     * 中文指令,让 agent 始终用简体中文回复,并在破坏性命令前说明风险。
     */
    private fun writeChineseRules(context: Context) {
        val rules = """
            # 会话规则(Grok Build 安卓版)

            - 始终使用**简体中文**与用户交流(除非用户明确要求其它语言)。
            - 你运行在一台安卓手机上,可用的 shell 工具经受限环境(或 root)执行。
            - 执行任何**破坏性命令**(删除文件、刷写分区、格式化、`rm -rf`、`dd`、覆盖系统文件等)
              之前,先用一句话说明该命令的风险与影响,再执行。
            - 命令输出较长时,优先给出要点摘要,避免刷屏。
        """.trimIndent()
        runCatching { File(workDir(context), "AGENTS.md").writeText(rules) }
    }

    /** TOML 字符串转义(基础安全:反斜杠与引号)。 */
    private fun tomlStr(s: String): String {
        val esc = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$esc\""
    }

    /** 组装环境变量数组。 */
    private fun buildEnv(context: Context, prefs: Prefs): Array<String> {
        val home = grokHome(context)
        val p = Providers.byId(prefs.activeProviderId)
        val apiKey = prefs.apiKey(p.id).trim()
        val nativeDir = context.applicationInfo.nativeLibraryDir

        val env = linkedMapOf(
            "HOME" to home.absolutePath,
            "GROK_HOME" to home.absolutePath,
            "TMPDIR" to (File(context.cacheDir, "tmp").apply { mkdirs() }).absolutePath,
            "PREFIX" to context.applicationInfo.dataDir,
            "PATH" to "$nativeDir:/system/bin:/system/xbin",
            "LD_LIBRARY_PATH" to nativeDir,
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor",
            "LANG" to "en_US.UTF-8",
            // 移动端无浏览器 OAuth,靠 API Key / config.toml;关闭遥测与自动打开面板。
            "GROK_TELEMETRY_ENABLED" to "0",
            "GROK_OPEN_DASHBOARD_AT_STARTUP" to "0",
            "GROK_FOLDER_TRUST" to "0"
        )

        // 让 grok 的全局 XAI_API_KEY 回退也拿到 key(xAI / OpenAI 兼容厂商)。
        if (apiKey.isNotEmpty()) {
            if (p.id == "xai") {
                env["XAI_API_KEY"] = apiKey
            }
            if (p.isAnthropicNative) {
                // 供 env_key 回退链使用。
                env["ANTHROPIC_AUTH_TOKEN"] = apiKey
            }
        }
        return env.map { "${it.key}=${it.value}" }.toTypedArray()
    }

    /**
     * 创建并启动运行 grok 的终端会话。
     * @param asRoot 为 true 且设备已 root 时,经 `su -c` 以 root 身份运行 grok。
     */
    fun createSession(
        context: Context,
        prefs: Prefs,
        client: TerminalSessionClient,
        asRoot: Boolean
    ): TerminalSession {
        writeConfig(context, prefs)
        val bin = grokBinary(context)
        val env = buildEnv(context, prefs)
        val cwd = workDir(context).absolutePath

        val shellPath: String
        val args: Array<String>
        if (asRoot && RootManager.hasRootBinary()) {
            // 以 root 运行:su -c "exec libgrok.so"
            shellPath = RootManager.suPath() ?: "su"
            args = arrayOf(shellPath, "-c", "exec ${shellQuote(bin.absolutePath)}")
        } else {
            shellPath = bin.absolutePath
            args = arrayOf(bin.absolutePath)
        }

        return TerminalSession(shellPath, cwd, args, env, /*transcriptRows=*/2000, client)
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    // ── 图形模式:用真 grok 的 headless streaming-json 驱动 ──────────────
    /**
     * 构建 headless 单轮进程:`grok -p <prompt> --output-format streaming-json --yolo
     * [--resume <sid>] --rules <中文规则>`。GUI 前端读取其 JSON 事件流渲染,
     * 后端就是真实 grok 引擎(它自己的完整工具集 + 权限,--yolo 自动放行)。
     * 与终端模式共用同一份 config.toml / 环境 / GROK_HOME(即同一套厂商配置与会话)。
     */
    fun buildHeadlessProcess(
        context: Context,
        prefs: Prefs,
        prompt: String,
        resumeSessionId: String?
    ): ProcessBuilder {
        writeConfig(context, prefs)
        val bin = grokBinary(context)
        val args = mutableListOf(
            bin.absolutePath,
            "-p", prompt,
            "--output-format", "streaming-json",
            "--yolo", // 自动放行工具执行(headless 无交互权限弹窗)
            "--rules", "始终使用简体中文回复;执行删除/刷写/格式化等破坏性命令前先用一句话说明风险。"
        )
        val model = prefs.model(prefs.activeProviderId).trim()
        if (model.isNotEmpty()) { args.add("-m"); args.add(model) }
        if (!resumeSessionId.isNullOrEmpty()) { args.add("--resume"); args.add(resumeSessionId) }

        val pb = ProcessBuilder(args)
        pb.directory(workDir(context))
        pb.redirectErrorStream(false)
        val envMap = pb.environment()
        // 用我们组装的 env 覆盖(ProcessBuilder 默认继承当前进程环境)。
        for (kv in buildEnv(context, prefs)) {
            val idx = kv.indexOf('=')
            if (idx > 0) envMap[kv.substring(0, idx)] = kv.substring(idx + 1)
        }
        return pb
    }

    fun isBinaryReady(context: Context): Boolean = grokBinary(context).canExecute()
}
