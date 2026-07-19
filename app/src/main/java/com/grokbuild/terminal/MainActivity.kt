package com.grokbuild.terminal

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.grokbuild.terminal.databinding.ActivityMainBinding
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient

/**
 * 终端宿主主界面:内嵌 TerminalView 运行真实 grok TUI。
 * 同时实现 TerminalSessionClient(会话回调)与 TerminalViewClient(视图/输入回调)。
 */
class MainActivity : AppCompatActivity(), TerminalSessionClient, TerminalViewClient {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var session: TerminalSession? = null

    private var ctrlLatch = false
    private var altLatch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.terminalView.setTerminalViewClient(this)
        binding.terminalView.setTextSize(spToPx(prefs.fontSizeSp))
        binding.terminalView.setTypeface(Typeface.MONOSPACE)

        buildExtraKeys()
        binding.menuButton.setOnClickListener { showMenu(it) }
        binding.rootStatus.setOnClickListener { requestRoot() }

        updateStatusLine()
        requestRoot()

        if (!GrokLauncher.isBinaryPresent(this)) {
            Toast.makeText(this, R.string.binary_missing, Toast.LENGTH_LONG).show()
        }
        startGrok()
    }

    // ── grok 会话生命周期 ──────────────────────────────────────────────
    private fun startGrok() {
        session?.finishIfRunning()
        val warnNoKey = prefs.apiKey(prefs.activeProviderId).isEmpty() &&
            prefs.activeProviderId != "ollama"
        session = GrokLauncher.createSession(
            this, prefs, this,
            asRoot = prefs.runAsRoot && RootManager.state == RootManager.State.GRANTED
        )
        binding.terminalView.attachSession(session)
        binding.terminalView.setTerminalViewClient(this)
        // 启动后自动获焦并唤起键盘,省去用户手动点。
        binding.terminalView.post { showKeyboard() }
        if (warnNoKey) {
            Toast.makeText(this, R.string.no_api_key_warn, Toast.LENGTH_LONG).show()
        }
    }

    private fun restartGrok() {
        Toast.makeText(this, R.string.grok_starting, Toast.LENGTH_SHORT).show()
        startGrok()
    }

    private fun requestRoot() {
        binding.rootStatus.setText(R.string.status_root_unknown)
        RootManager.requestRoot { state ->
            runOnUiThread { updateRootStatus(state) }
        }
    }

    private fun updateRootStatus(state: RootManager.State) {
        val (resId, colorRes) = when (state) {
            RootManager.State.GRANTED -> R.string.status_root_on to R.color.gk_green
            RootManager.State.DENIED -> R.string.status_root_off to R.color.gk_orange
            RootManager.State.UNAVAILABLE -> R.string.status_root_unavailable to R.color.gk_fg_muted
            RootManager.State.UNKNOWN -> R.string.status_root_unknown to R.color.gk_yellow
        }
        binding.rootStatus.setText(resId)
        binding.rootStatus.setTextColor(getColor(colorRes))
    }

    private fun updateStatusLine() {
        val p = Providers.byId(prefs.activeProviderId)
        val model = prefs.model(p.id).ifEmpty { p.defaultModel }
        binding.statusText.text = "grok · ${p.display} · $model"
    }

    // ── 顶部菜单 ────────────────────────────────────────────────────────
    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.menu_settings)
            menu.add(0, 2, 1, R.string.menu_new_session)
            menu.add(0, 3, 2, R.string.menu_restart)
            menu.add(0, 4, 3, R.string.menu_paste)
            menu.add(0, 5, 4, R.string.menu_font_bigger)
            menu.add(0, 6, 5, R.string.menu_font_smaller)
            menu.add(0, 7, 6, R.string.menu_about)
            menu.add(0, 8, 7, R.string.menu_switch_mode)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                    2, 3 -> restartGrok()
                    4 -> pasteFromSystemClipboard()
                    5 -> changeFont(+1)
                    6 -> changeFont(-1)
                    7 -> startActivity(Intent(this@MainActivity, AboutActivity::class.java))
                    8 -> LauncherActivity.backToPicker(this@MainActivity)
                }
                true
            }
            show()
        }
    }

    private fun changeFont(delta: Int) {
        prefs.fontSizeSp = (prefs.fontSizeSp + delta).coerceIn(8, 30)
        binding.terminalView.setTextSize(spToPx(prefs.fontSizeSp))
    }

    private fun spToPx(sp: Int): Int = (sp * resources.displayMetrics.scaledDensity).toInt()

    override fun onResume() {
        super.onResume()
        // 从设置页返回:配置可能变化,刷新状态行(重启由设置页提示)。
        updateStatusLine()
    }

    // ── TUI 触屏辅助键 ─────────────────────────────────────────────────
    private fun buildExtraKeys() {
        data class Key(val label: String, val action: () -> Unit)
        val esc = { sendBytes(byteArrayOf(27)) }
        val tab = { sendBytes(byteArrayOf(9)) }
        val enter = { sendBytes(byteArrayOf(13)) }
        val keys = listOf(
            Key("⌨") { showKeyboard() },
            Key("ESC") { esc() },
            Key("TAB") { tab() },
            Key("CTRL") { ctrlLatch = !ctrlLatch; refreshLatchButtons() },
            Key("ALT") { altLatch = !altLatch; refreshLatchButtons() },
            Key("↑") { sendEsc("[A") },
            Key("↓") { sendEsc("[B") },
            Key("←") { sendEsc("[D") },
            Key("→") { sendEsc("[C") },
            Key("⏎") { enter() },
            Key("/") { sendText("/") },
            Key("|") { sendText("|") },
            Key("-") { sendText("-") },
            Key("^C") { sendBytes(byteArrayOf(3)) },
            Key("^D") { sendBytes(byteArrayOf(4)) },
            Key("^L") { sendBytes(byteArrayOf(12)) },
        )
        binding.extraKeys.removeAllViews()
        for (k in keys) {
            val b = Button(this)
            b.text = k.label
            b.isAllCaps = false
            b.typeface = Typeface.MONOSPACE
            b.textSize = 13f
            b.minWidth = 0
            b.minimumWidth = 0
            b.setPadding(24, 8, 24, 8)
            b.setTextColor(getColor(R.color.gk_fg_main))
            b.setBackgroundColor(getColor(R.color.gk_bg_highlight))
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(4, 0, 4, 0)
            b.layoutParams = lp
            b.tag = k.label
            b.setOnClickListener { k.action() }
            binding.extraKeys.addView(b)
        }
    }

    private fun refreshLatchButtons() {
        for (i in 0 until binding.extraKeys.childCount) {
            val b = binding.extraKeys.getChildAt(i) as? Button ?: continue
            val on = (b.tag == "CTRL" && ctrlLatch) || (b.tag == "ALT" && altLatch)
            b.setBackgroundColor(getColor(if (on) R.color.gk_blue_deep else R.color.gk_bg_highlight))
        }
    }

    private fun sendEsc(seq: String) = sendBytes((byteArrayOf(27) + seq.toByteArray()))

    private fun sendText(text: String) {
        val s = session ?: return
        if (ctrlLatch && text.length == 1) {
            val c = text[0].uppercaseChar()
            if (c in 'A'..'Z') {
                s.write(byteArrayOf((c - 'A' + 1).toByte()), 0, 1)
                ctrlLatch = false; refreshLatchButtons()
                return
            }
        }
        val bytes = if (altLatch) byteArrayOf(27) + text.toByteArray() else text.toByteArray()
        altLatch = false; refreshLatchButtons()
        s.write(bytes, 0, bytes.size)
    }

    private fun sendBytes(bytes: ByteArray) {
        session?.write(bytes, 0, bytes.size)
    }

    private fun pasteFromSystemClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        sendText(text)
    }

    // ── TerminalViewClient ─────────────────────────────────────────────
    override fun onScale(scale: Float): Float {
        // 双指缩放调字号。
        if (scale < 0.9f || scale > 1.1f) {
            changeFont(if (scale > 1f) +1 else -1)
        }
        return 1.0f
    }

    override fun onSingleTapUp(e: MotionEvent) {
        showKeyboard()
    }

    /** 可靠地唤起软键盘:先获焦,再 showSoftInput,失败则 toggle 兜底。 */
    private fun showKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
        binding.terminalView.isFocusable = true
        binding.terminalView.isFocusableInTouchMode = true
        binding.terminalView.requestFocus()
        val shown = imm.showSoftInput(
            binding.terminalView,
            android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT
        )
        if (!shown) {
            // 某些机型 showSoftInput 首次返回 false,用 toggle 强制唤起。
            imm.toggleSoftInput(
                android.view.inputmethod.InputMethodManager.SHOW_FORCED, 0
            )
        }
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = ctrlLatch
    override fun readAltKey(): Boolean = altLatch
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
    override fun onEmulatorSet() {
        // 应用 groknight 配色到终端。
        binding.terminalView.setBackgroundColor(getColor(R.color.gk_bg_term))
    }

    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}

    // ── TerminalSessionClient ──────────────────────────────────────────
    override fun onTextChanged(changedSession: TerminalSession) {
        if (session == changedSession) binding.terminalView.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {}

    override fun onSessionFinished(finishedSession: TerminalSession) {
        val code = finishedSession.exitStatus
        runOnUiThread {
            Toast.makeText(this, getString(R.string.grok_exited, code), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("grok", text ?: ""))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        pasteFromSystemClipboard()
    }

    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
    override fun getTerminalCursorStyle(): Int? = null

    override fun onDestroy() {
        super.onDestroy()
        session?.finishIfRunning()
    }
}
