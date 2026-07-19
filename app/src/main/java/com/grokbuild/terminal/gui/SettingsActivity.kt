package com.grokbuild.terminal.gui

import com.grokbuild.terminal.R
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.grokbuild.terminal.databinding.ActivityGuiSettingsBinding

/** 下拉选厂商,自动带出默认 base_url/model(可编辑),各厂商 Key 分别保存。 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGuiSettingsBinding
    private lateinit var prefs: Prefs
    private var currentId = "xai"
    private var suppress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_GrokBuild_Settings)
        super.onCreate(savedInstanceState)
        binding = ActivityGuiSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, Providers.PRESETS.map { it.display })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.providerSpinner.adapter = adapter

        currentId = prefs.activeProviderId
        val idx = Providers.PRESETS.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        binding.providerSpinner.setSelection(idx)
        loadProvider(Providers.PRESETS[idx].id)

        binding.providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppress) return
                persist()
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
        suppress = true
        binding.baseUrlInput.setText(prefs.baseUrl(id))
        binding.modelInput.setText(prefs.model(id))
        binding.apiKeyInput.setText(prefs.apiKey(id))
        binding.providerNote.text = buildString {
            append("协议:")
            append(if (p.protocol == Protocol.ANTHROPIC) "Anthropic 原生 /v1/messages" else "OpenAI 兼容 /v1/chat/completions")
            if (p.note.isNotEmpty()) { append("\n"); append(p.note) }
            if (p.protocol == Protocol.ANTHROPIC) { append("\n"); append(getString(R.string.gui_anthropic_native_note)) }
        }
        suppress = false
    }

    private fun persist() {
        prefs.setBaseUrl(currentId, binding.baseUrlInput.text.toString().trim())
        prefs.setModel(currentId, binding.modelInput.text.toString().trim())
        prefs.setApiKey(currentId, binding.apiKeyInput.text.toString().trim())
    }

    private fun save() {
        persist()
        prefs.activeProviderId = currentId
        prefs.runAsRoot = binding.rootCheck.isChecked
        Toast.makeText(this, R.string.gui_saved_toast, Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onPause() { super.onPause(); persist() }
}
