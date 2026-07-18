package com.grokbuild.terminal.gui

import com.grokbuild.terminal.R
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/** grok 风格对话渲染:彩色角色标签 + 等宽内容面板;工具条目带深色底与左侧强调。 */
class ChatAdapter(private val items: MutableList<ChatItem>) :
    RecyclerView.Adapter<ChatAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val role: TextView = v.findViewById(R.id.roleLabel)
        val content: TextView = v.findViewById(R.id.contentText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_chat, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ctx = holder.itemView.context
        fun c(id: Int) = ContextCompat.getColor(ctx, id)
        val bgDark = c(R.color.gk_bg_dark)
        val transparent = Color.TRANSPARENT

        when (val item = items[position]) {
            is ChatItem.User -> {
                holder.role.text = ctx.getString(R.string.gui_role_user)
                holder.role.setTextColor(c(R.color.gk_green))
                holder.content.text = item.text
                holder.content.setTextColor(c(R.color.gk_fg_main))
                holder.content.setBackgroundColor(transparent)
            }
            is ChatItem.Assistant -> {
                holder.role.text = ctx.getString(R.string.gui_role_assistant)
                holder.role.setTextColor(c(R.color.gk_blue))
                holder.content.text = item.text
                holder.content.setTextColor(c(R.color.gk_fg_main))
                holder.content.setBackgroundColor(transparent)
            }
            is ChatItem.ToolCall -> {
                holder.role.text = "▶ ${ctx.getString(R.string.gui_role_tool_call)}:${item.name}"
                holder.role.setTextColor(c(R.color.gk_magenta))
                holder.content.text = item.argsJson
                holder.content.setTextColor(c(R.color.gk_fg_sub))
                holder.content.setBackgroundColor(bgDark)
            }
            is ChatItem.ToolResult -> {
                holder.role.text = "◀ ${ctx.getString(R.string.gui_role_tool_result)}:${item.name}"
                holder.role.setTextColor(c(R.color.gk_teal))
                holder.content.text = item.output
                holder.content.setTextColor(c(R.color.gk_fg_muted))
                holder.content.setBackgroundColor(bgDark)
            }
            is ChatItem.System -> {
                holder.role.text = ctx.getString(R.string.gui_role_system)
                holder.role.setTextColor(c(R.color.gk_fg_muted))
                holder.content.text = item.text
                holder.content.setTextColor(c(R.color.gk_fg_muted))
                holder.content.setBackgroundColor(transparent)
            }
            is ChatItem.Error -> {
                holder.role.text = ctx.getString(R.string.gui_role_error)
                holder.role.setTextColor(c(R.color.gk_red))
                holder.content.text = item.text
                holder.content.setTextColor(c(R.color.gk_red))
                holder.content.setBackgroundColor(bgDark)
            }
        }
    }

    fun add(item: ChatItem) {
        items.add(item)
        notifyItemInserted(items.size - 1)
    }

    fun clear() {
        val n = items.size
        items.clear()
        notifyItemRangeRemoved(0, n)
    }
}
