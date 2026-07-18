package com.grokbuild.terminal.gui

import com.grokbuild.terminal.R
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.grokbuild.terminal.GrokLauncher
import com.grokbuild.terminal.Prefs
import com.grokbuild.terminal.Providers
import com.grokbuild.terminal.databinding.ActivityChatBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 图形模式:grok 神似的图形界面,后端驱动**真实 grok 引擎**(headless streaming-json)。
 * 软键盘正常唤起(原生 EditText)。与终端模式共用同一套厂商配置(config.toml)。
 */
class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var prefs: Prefs                 // 复用终端模式的 Prefs(同一套 grok 配置)
    private lateinit var driver: GrokDriver
    private val items = mutableListOf<ChatItem>()
    private lateinit var adapter: ChatAdapter
    private val ui = CoroutineScope(Dispatchers.Main)
    private var running: Job? = null

    // 流式:当前 assistant 气泡的下标与累积文本。
    private var streamIndex = -1
    private val streamBuf = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)
        driver = GrokDriver(this, prefs)

        adapter = ChatAdapter(items)
        binding.chatList.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.chatList.adapter = adapter

        binding.menuButton.setOnClickListener { showMenu(it) }
        binding.rootStatus.setOnClickListener { requestRoot() }
        binding.sendButton.setOnClickListener { onSend() }

        binding.input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { onSend(); true } else false
        }
        binding.input.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN && !event.isShiftPressed) {
                onSend(); true
            } else false
        }

        updateStatusLine()
        requestRoot()
        if (!driver.binaryReady()) {
            adapter.add(ChatItem.Error("未找到 grok 原生库(libgrok.so),图形模式无法驱动真实 grok。"))
        } else {
            adapter.add(ChatItem.System("图形模式已就绪——背后驱动的是真实 grok 引擎(完整工具集)。到「设置」选厂商填 API Key 后即可对话。"))
        }
    }

    private fun onSend() {
        val text = binding.input.text.toString().trim()
        if (text.isEmpty() || running?.isActive == true) return

        val p = Providers.byId(prefs.activeProviderId)
        if (prefs.apiKey(p.id).isEmpty() && p.id != "ollama") {
            Toast.makeText(this, R.string.gui_no_api_key_warn, Toast.LENGTH_LONG).show()
            return
        }

        binding.input.setText("")
        addItem(ChatItem.User(text))
        setThinking(true)
        // 为本轮准备一个空的 assistant 气泡,文本流式填充。
        streamBuf.setLength(0)
        streamIndex = -1

        running = ui.launch {
            withContext(Dispatchers.IO) {
                driver.runTurn(text, object : GrokDriver.Sink {
                    override fun onText(chunk: String) = ui.launch { appendStream(chunk) }.let {}
                    override fun onThought(chunk: String) { /* 思考过程暂不展示,保持界面简洁 */ }
                    override fun onEnd(stopReason: String) = ui.launch {
                        if (streamIndex < 0 && streamBuf.isEmpty()) {
                            adapter.add(ChatItem.System("(本轮无文本输出,stop=$stopReason)"))
                        }
                    }.let {}
                    override fun onError(message: String) = ui.launch {
                        addItem(ChatItem.Error(message))
                    }.let {}
                })
            }
            setThinking(false)
        }
    }

    /** 流式把文本追加进当前 assistant 气泡。 */
    private fun appendStream(chunk: String) {
        streamBuf.append(chunk)
        if (streamIndex < 0) {
            adapter.add(ChatItem.Assistant(streamBuf.toString()))
            streamIndex = adapter.lastIndex()
        } else {
            adapter.replaceAt(streamIndex, ChatItem.Assistant(streamBuf.toString()))
        }
        binding.chatList.scrollToPosition(adapter.lastIndex())
    }

    private fun addItem(item: ChatItem) {
        adapter.add(item)
        binding.chatList.scrollToPosition(adapter.lastIndex())
    }

    private fun setThinking(on: Boolean) {
        binding.thinkingBar.visibility = if (on) View.VISIBLE else View.GONE
        binding.sendButton.isEnabled = !on
    }

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.gui_menu_settings)
            menu.add(0, 2, 1, R.string.gui_menu_new_session)
            menu.add(0, 3, 2, R.string.gui_menu_clear)
            menu.add(0, 4, 3, R.string.gui_menu_about)
            menu.add(0, 5, 4, R.string.menu_switch_mode)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    // 复用终端模式的设置页(同一套 grok 配置),含“拉取模型”。
                    1 -> startActivity(Intent(this@ChatActivity, com.grokbuild.terminal.SettingsActivity::class.java))
                    2 -> { driver.reset(); adapter.clear(); adapter.add(ChatItem.System("已开始新会话。")) }
                    3 -> adapter.clear()
                    4 -> startActivity(Intent(this@ChatActivity, com.grokbuild.terminal.AboutActivity::class.java))
                    5 -> com.grokbuild.terminal.LauncherActivity.backToPicker(this@ChatActivity)
                }
                true
            }
            show()
        }
    }

    private fun requestRoot() {
        binding.rootStatus.setText(R.string.gui_status_root_unknown)
        com.grokbuild.terminal.RootManager.requestRoot { state -> runOnUiThread { updateRootStatus(state) } }
    }

    private fun updateRootStatus(state: com.grokbuild.terminal.RootManager.State) {
        val (resId, colorRes) = when (state) {
            com.grokbuild.terminal.RootManager.State.GRANTED -> R.string.gui_status_root_on to R.color.gk_green
            com.grokbuild.terminal.RootManager.State.DENIED -> R.string.gui_status_root_off to R.color.gk_orange
            com.grokbuild.terminal.RootManager.State.UNAVAILABLE -> R.string.gui_status_root_unavailable to R.color.gk_fg_muted
            com.grokbuild.terminal.RootManager.State.UNKNOWN -> R.string.gui_status_root_unknown to R.color.gk_yellow
        }
        binding.rootStatus.setText(resId)
        binding.rootStatus.setTextColor(getColor(colorRes))
    }

    private fun updateStatusLine() {
        val p = Providers.byId(prefs.activeProviderId)
        val model = prefs.model(p.id).ifBlank { p.defaultModel }
        binding.statusText.text = "grok(真引擎) · ${p.display} · $model"
    }

    override fun onResume() {
        super.onResume()
        updateStatusLine()
    }
}
