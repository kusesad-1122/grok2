package com.grokbuild.terminal.gui

import com.grokbuild.terminal.R
import android.graphics.Typeface
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** 关于页:移植出处与许可证。 */
class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_GrokBuild_Settings)
        super.onCreate(savedInstanceState)
        val rev = try {
            assets.open("GROK_BUILD_SOURCE_REV").bufferedReader().use { it.readText().trim() }
        } catch (_: Exception) { "(未知)" }
        val tv = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(getColor(R.color.gk_fg_main))
            setPadding(40, 40, 40, 40)
            text = getString(R.string.gui_about_body) + "\n\n" + getString(R.string.gui_about_source_rev, rev)
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.gk_bg_main))
            addView(tv)
        })
    }
}
