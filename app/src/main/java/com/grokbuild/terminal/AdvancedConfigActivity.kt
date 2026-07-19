package com.grokbuild.terminal

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.grokbuild.terminal.databinding.ActivityAdvancedBinding

/**
 * 高级配置:编辑将原样追加进 ~/.grok/config.toml 的片段。
 * 这是 grok 完整官方配置面的入口——MCP 服务器、权限规则、hooks、skills 目录、[agent] 等都可在此配置。
 */
class AdvancedConfigActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_GrokBuild_Settings)
        super.onCreate(savedInstanceState)
        val binding = ActivityAdvancedBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.advanced_title)
        val prefs = Prefs(this)
        binding.configInput.setText(prefs.extraConfigToml)
        binding.saveConfigButton.setOnClickListener {
            prefs.extraConfigToml = binding.configInput.text.toString()
            GrokLauncher.writeConfig(this, prefs)
            Toast.makeText(this, R.string.advanced_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
