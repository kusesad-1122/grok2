package com.grokbuild.terminal

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.grokbuild.terminal.databinding.ActivitySettingsBinding

/**
 * 设置页:下拉选择厂商,自动带出默认 base_url/model(可编辑),各厂商 Key 分别保存。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs
    private var currentId: String = "xai"
    private var suppressReload = false

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_GrokBuild_Settings)
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        val names = Providers.PRESETS.map { it.display }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.providerSpinner.adapter = adapter

        currentId = prefs.activeProviderId
        val idx = Providers.PRESETS.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        binding.providerSpinner.setSelection(idx)
        loadProvider(Providers.PRESETS[idx].id)

        binding.providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressReload) return
                // 切换厂商前,先把当前编辑内容存回旧厂商。
                persistCurrentInputs()
                loadProvider(Providers.PRESETS[position].id)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.rootCheck.isChecked = prefs.runAsRoot
        binding.saveButton.setOnClickListener { save() }
    }

    private fun loadProvider(id: String) {
        currentId = id
        val p = Providers.byId(id)
        suppressReload = true
        binding.baseUrlInput.setText(prefs.baseUrl(id))
        binding.modelInput.setText(prefs.model(id))
        binding.apiKeyInput.setText(prefs.apiKey(id))
        binding.providerNote.text = buildString {
            append("协议:")
            append(if (p.isAnthropicNative) "Anthropic 原生 /v1/messages" else "OpenAI 兼容 /v1/chat/completions")
            if (p.note.isNotEmpty()) { append("\n"); append(p.note) }
            if (p.isAnthropicNative) { append("\n"); append(getString(R.string.anthropic_native_note)) }
        }
        suppressReload = false
    }

    private fun persistCurrentInputs() {
        prefs.setBaseUrl(currentId, binding.baseUrlInput.text.toString().trim())
        prefs.setModel(currentId, binding.modelInput.text.toString().trim())
        prefs.setApiKey(currentId, binding.apiKeyInput.text.toString().trim())
    }

    private fun save() {
        persistCurrentInputs()
        prefs.activeProviderId = currentId
        prefs.runAsRoot = binding.rootCheck.isChecked
        // 立即写出 config.toml,便于下次启动 grok 生效。
        GrokLauncher.writeConfig(this, prefs)
        Toast.makeText(this, R.string.saved_toast, Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onPause() {
        super.onPause()
        persistCurrentInputs()
    }
}
