package com.grokbuild.terminal.gui

import com.grokbuild.terminal.R
import android.util.Base64
import com.topjohnwu.superuser.Shell

/**
 * agent 的三个工具,经 shell(可选 root)落地。
 * 命令构造做成纯函数,便于单元测试引用/ base64 逻辑,不依赖设备。
 */
object Tools {

    /** 单引号包裹并转义,防止命令注入。 */
    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** write_file 用 base64 传输任意内容,避免特殊字符注入。 */
    fun buildWriteCommand(path: String, content: String): String {
        val b64 = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        // 用 base64 -d 解码写入;路径单引号转义。
        return "printf %s ${shellQuote(b64)} | base64 -d > ${shellQuote(path)}"
    }

    fun buildReadCommand(path: String): String = "cat ${shellQuote(path)}"

    data class ExecResult(val exitCode: Int, val output: String)

    /** 以(root 或普通)shell 执行一条命令,返回退出码 + 合并的 stdout/stderr。 */
    fun exec(command: String, asRoot: Boolean): ExecResult {
        return try {
            val shell = if (asRoot && RootManager.state == RootManager.State.GRANTED) {
                Shell.getShell()
            } else {
                // 非 root:用普通 sh。
                Shell.getShell()
            }
            val res = shell.newJob().add(command).to(ArrayList(), null).exec()
            val out = (res.out ?: emptyList()).joinToString("\n")
            ExecResult(res.code, out)
        } catch (e: Exception) {
            ExecResult(-1, "执行失败:${e.message}")
        }
    }

    // ── 三个工具的定义(供模型调用)────────────────────────────────
    fun toolDefs(): List<ToolDef> = listOf(
        ToolDef(
            name = "run_shell",
            description = "在安卓设备上执行一条 shell 命令,返回退出码与合并后的 stdout/stderr。执行删除/刷写/格式化等破坏性命令前,先用一句话说明风险。",
            parameters = mapOf("command" to ParamSpec("string", "要执行的 shell 命令"))
        ),
        ToolDef(
            name = "read_file",
            description = "读取指定路径文件的文本内容。",
            parameters = mapOf("path" to ParamSpec("string", "文件绝对路径"))
        ),
        ToolDef(
            name = "write_file",
            description = "把内容覆盖写入指定路径(内部用 base64 传输,支持任意内容)。",
            parameters = mapOf(
                "path" to ParamSpec("string", "文件绝对路径"),
                "content" to ParamSpec("string", "要写入的完整内容")
            )
        ),
    )

    /** 执行一次工具调用,返回给模型的结果文本。 */
    fun run(call: ToolCall, asRoot: Boolean): String {
        return when (call.name) {
            "run_shell" -> {
                val cmd = call.args.optString("command")
                if (cmd.isBlank()) return "错误:缺少 command 参数"
                val r = exec(cmd, asRoot)
                "退出码:${r.exitCode}\n输出:\n${r.output}"
            }
            "read_file" -> {
                val path = call.args.optString("path")
                if (path.isBlank()) return "错误:缺少 path 参数"
                val r = exec(buildReadCommand(path), asRoot)
                if (r.exitCode == 0) r.output else "读取失败(退出码 ${r.exitCode}):\n${r.output}"
            }
            "write_file" -> {
                val path = call.args.optString("path")
                val content = call.args.optString("content")
                if (path.isBlank()) return "错误:缺少 path 参数"
                val r = exec(buildWriteCommand(path, content), asRoot)
                if (r.exitCode == 0) "已写入 $path(${content.toByteArray().size} 字节)" else "写入失败(退出码 ${r.exitCode}):\n${r.output}"
            }
            else -> "错误:未知工具 ${call.name}"
        }
    }
}
