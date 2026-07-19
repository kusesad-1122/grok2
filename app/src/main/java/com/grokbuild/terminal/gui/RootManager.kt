package com.grokbuild.terminal.gui

import com.grokbuild.terminal.R
import com.topjohnwu.superuser.Shell
import java.io.File

/** Root 检测/请求(libsu)。无 root 时工具优雅失败,不崩溃。 */
object RootManager {
    enum class State { UNKNOWN, GRANTED, DENIED, UNAVAILABLE }

    @Volatile var state: State = State.UNKNOWN; private set

    private val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su",
        "/su/bin/su", "/magisk/.core/bin/su", "/debug_ramdisk/su"
    )

    init {
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create().setFlags(Shell.FLAG_REDIRECT_STDERR).setTimeout(15)
        )
    }

    fun hasRootBinary(): Boolean = SU_PATHS.any { File(it).exists() }

    fun requestRoot(onResult: (State) -> Unit) {
        if (!hasRootBinary()) { state = State.UNAVAILABLE; onResult(state); return }
        Shell.getShell { shell ->
            state = if (shell.isRoot) State.GRANTED else State.DENIED
            onResult(state)
        }
    }
}
