package com.grokbuild.terminal

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.grokbuild.terminal.databinding.ActivityLauncherBinding
import com.grokbuild.terminal.gui.ChatActivity

/**
 * 启动选择页:一个 App 两种模式。
 *  - 终端模式:运行真实 grok 二进制(MainActivity,全功能 TUI);
 *  - 图形模式:grok 神似的图形聊天(gui.ChatActivity,复刻子集)。
 * 可勾选“记住选择”下次直接进入对应模式。
 */
class LauncherActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLauncherBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 记住的模式:直接跳转,不再显示选择页。
        val sp = getSharedPreferences("grok_launcher", Context.MODE_PRIVATE)
        when (sp.getString("remember_mode", null)) {
            "terminal" -> { go(MainActivity::class.java); return }
            "gui" -> { go(ChatActivity::class.java); return }
        }

        binding = ActivityLauncherBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.cardTerminal.setOnClickListener { pick("terminal", MainActivity::class.java) }
        binding.cardGui.setOnClickListener { pick("gui", ChatActivity::class.java) }
    }

    private fun pick(mode: String, target: Class<*>) {
        if (binding.rememberCheck.isChecked) {
            getSharedPreferences("grok_launcher", Context.MODE_PRIVATE)
                .edit().putString("remember_mode", mode).apply()
        }
        go(target)
    }

    private fun go(target: Class<*>) {
        startActivity(Intent(this, target))
        finish()
    }

    companion object {
        /** 供各模式的菜单“切换模式”调用:清除记忆并回到选择页。 */
        fun backToPicker(ctx: Context) {
            ctx.getSharedPreferences("grok_launcher", Context.MODE_PRIVATE)
                .edit().remove("remember_mode").apply()
            val i = Intent(ctx, LauncherActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ctx.startActivity(i)
        }
    }
}
