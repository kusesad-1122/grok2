package com.grokbuild.gui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.grokbuild.gui.databinding.ActivityChatBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** grok 神似的图形对话界面。软键盘正常唤起(原生 EditText),不依赖 PC 键盘。 */
class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var prefs: Prefs
    private lateinit var agent: Agent
    private val items = mutableListOf<ChatItem>()
    private lateinit var adapter: ChatAdapter
    private val ui = CoroutineScope(Dispatchers.Main)
    private var running: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)
        agent = Agent(this, prefs)

        adapter = ChatAdapter(items)
        binding.chatList.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.chatList.adapter = adapter

        binding.menuButton.setOnClickListener { showMenu(it) }
        binding.rootStatus.setOnClickListener { requestRoot() }
        binding.sendButton.setOnClickListener { onSend() }

        // 硬件回车/输入法发送键:发送;Shift+Enter 换行。
        binding.input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { onSend(); true } else false
        }
        binding.input.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN && !event.isShiftPressed) {
                onSend(); true
            } else false
        }

        updateStatusLine()
        requestRoot()
        adapter.add(ChatItem.System("欢迎使用 Grok Build(GUI 版)。用中文直接下指令即可。到「设置」选择厂商并填入 API Key。"))
    }

    private fun onSend() {
        val text = binding.input.text.toString().trim()
        if (text.isEmpty()) return
        if (running?.isActive == true) return

        val p = prefs.active
        if (prefs.apiKey(p.id).isEmpty() && p.id != "ollama") {
            Toast.makeText(this, R.string.no_api_key_warn, Toast.LENGTH_LONG).show()
            return
        }

        binding.input.setText("")
        addItem(ChatItem.User(text))
        setThinking(true)

        running = ui.launch {
            withContext(Dispatchers.IO) {
                agent.runTurn(text) { item ->
                    ui.launch { addItem(item) }
                }
            }
            setThinking(false)
        }
    }

    private fun addItem(item: ChatItem) {
        adapter.add(item)
        binding.chatList.scrollToPosition(items.size - 1)
    }

    private fun setThinking(on: Boolean) {
        binding.thinkingBar.visibility = if (on) View.VISIBLE else View.GONE
        binding.sendButton.isEnabled = !on
    }

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.menu_settings)
            menu.add(0, 2, 1, R.string.menu_new_session)
            menu.add(0, 3, 2, R.string.menu_clear)
            menu.add(0, 4, 3, R.string.menu_about)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> startActivity(Intent(this@ChatActivity, SettingsActivity::class.java))
                    2 -> { agent.reset(); adapter.clear(); adapter.add(ChatItem.System("已开始新会话。")) }
                    3 -> adapter.clear()
                    4 -> startActivity(Intent(this@ChatActivity, AboutActivity::class.java))
                }
                true
            }
            show()
        }
    }

    private fun requestRoot() {
        binding.rootStatus.setText(R.string.status_root_unknown)
        RootManager.requestRoot { state -> runOnUiThread { updateRootStatus(state) } }
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
        val p = prefs.active
        val model = prefs.model(p.id).ifBlank { p.defaultModel }
        val proto = if (p.protocol == Protocol.ANTHROPIC) "Anthropic原生" else "OpenAI兼容"
        binding.statusText.text = "grok · ${p.display} · $model · $proto"
    }

    override fun onResume() {
        super.onResume()
        updateStatusLine()
    }
}
