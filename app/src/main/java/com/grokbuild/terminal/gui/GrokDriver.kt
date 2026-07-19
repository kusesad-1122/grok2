package com.grokbuild.terminal.gui

import android.content.Context
import com.grokbuild.terminal.GrokLauncher
import com.grokbuild.terminal.Prefs
import org.json.JSONObject
import java.io.BufferedReader

/**
 * 图形模式的后端:驱动**真实 grok 二进制**的 headless streaming-json 模式。
 *
 * 每一轮把用户输入交给 `grok -p ... --output-format streaming-json --yolo`,
 * 逐行解析其 JSON 事件流({type:text|thought|end|error})。grok 用它自己的完整
 * 工具集在设备上真正干活(--yolo 自动放行),我们只把结果流式渲染到图形界面。
 * 与终端模式共用同一份 config.toml / GROK_HOME,即同一套厂商配置与会话历史。
 */
class GrokDriver(private val context: Context, private val prefs: Prefs) {

    /** grok 返回的会话 ID,用于下一轮 `--resume` 续接对话。 */
    @Volatile var sessionId: String? = null

    interface Sink {
        fun onText(chunk: String)
        fun onThought(chunk: String)
        fun onEnd(stopReason: String)
        fun onError(message: String)
    }

    fun binaryReady(): Boolean = GrokLauncher.isBinaryReady(context)

    /** 阻塞式运行一轮(在 IO 线程调用)。 */
    fun runTurn(prompt: String, sink: Sink) {
        if (!binaryReady()) {
            sink.onError("未找到 grok 原生库(libgrok.so),无法驱动真实 grok 引擎。")
            return
        }
        val proc = try {
            GrokLauncher.buildHeadlessProcess(context, prefs, prompt, sessionId).start()
        } catch (e: Exception) {
            sink.onError("启动 grok 失败:${e.message}")
            return
        }

        // 读 stderr(用于错误诊断),避免管道阻塞。
        val stderr = StringBuilder()
        val errThread = Thread {
            try {
                proc.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }

        try {
            val reader: BufferedReader = proc.inputStream.bufferedReader()
            reader.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || !trimmed.startsWith("{")) return@forEachLine
                val obj = try { JSONObject(trimmed) } catch (_: Exception) { return@forEachLine }
                when (obj.optString("type")) {
                    "text" -> sink.onText(obj.optString("data"))
                    "thought" -> sink.onThought(obj.optString("data"))
                    "error" -> sink.onError(obj.optString("message", "未知错误"))
                    "end" -> {
                        val sid = obj.optString("sessionId", "")
                        if (sid.isNotEmpty()) sessionId = sid
                        sink.onEnd(obj.optString("stopReason", "end"))
                    }
                }
            }
            val code = proc.waitFor()
            errThread.join(500)
            if (code != 0) {
                val msg = stderr.toString().trim().ifEmpty { "grok 退出码 $code" }
                sink.onError(msg)
            }
        } catch (e: Exception) {
            sink.onError("读取 grok 输出失败:${e.message}")
        } finally {
            try { proc.destroy() } catch (_: Exception) { }
        }
    }

    /** 新会话:清除续接 ID。 */
    fun reset() { sessionId = null }
}
