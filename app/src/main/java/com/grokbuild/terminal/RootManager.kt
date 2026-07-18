package com.grokbuild.terminal

import com.topjohnwu.superuser.Shell
import java.io.File

/**
 * Root 管理:用 libsu 请求/检测 root;并提供 su 二进制路径(供以 root 运行 grok)。
 * 无 root 时一切降级为普通用户运行,不崩溃。
 */
object RootManager {

    enum class State { UNKNOWN, GRANTED, DENIED, UNAVAILABLE }

    @Volatile
    var state: State = State.UNKNOWN
        private set

    private val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su",
        "/su/bin/su", "/magisk/.core/bin/su", "/debug_ramdisk/su"
    )

    init {
        // 关闭后台服务、缩短超时,避免阻塞。
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(10)
        )
    }

    fun hasRootBinary(): Boolean = SU_PATHS.any { File(it).exists() }

    fun suPath(): String? = SU_PATHS.firstOrNull { File(it).exists() }

    /** 异步请求 root 并回调最新状态。 */
    fun requestRoot(onResult: (State) -> Unit) {
        if (!hasRootBinary()) {
            state = State.UNAVAILABLE
            onResult(state)
            return
        }
        Shell.getShell { shell ->
            state = if (shell.isRoot) State.GRANTED else State.DENIED
            onResult(state)
        }
    }
}
