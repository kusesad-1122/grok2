package com.grokbuild.terminal

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.grokbuild.terminal.databinding.ActivitySettingsBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        binding.fetchModelsButton.setOnClickListener { fetchModels() }
    }

    private val ui = CoroutineScope(Dispatchers.Main)

    /** 拉取当前厂商的可用模型列表,弹窗选择后填入模型框。 */
    private fun fetchModels() {
        val baseUrl = binding.baseUrlInput.text.toString().trim()
        val apiKey = binding.apiKeyInput.text.toString().trim()
        val p = Providers.byId(currentId)
        if (apiKey.isEmpty() && p.id != "ollama") {
            Toast.makeText(this, R.string.fetch_need_key, Toast.LENGTH_SHORT).show()
            return
        }
        binding.fetchModelsButton.isEnabled = false
        Toast.makeText(this, R.string.fetching_models, Toast.LENGTH_SHORT).show()
        ui.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ModelFetcher.fetch(baseUrl, apiKey, p.isAnthropicNative) }
            }
            binding.fetchModelsButton.isEnabled = true
            result.onSuccess { models ->
                if (models.isEmpty()) {
                    Toast.makeText(this@SettingsActivity, R.string.no_models, Toast.LENGTH_LONG).show()
                } else {
                    val arr = models.toTypedArray()
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle(R.string.pick_model)
                        .setItems(arr) { _, which -> binding.modelInput.setText(arr[which]) }
                        .show()
                }
            }.onFailure { e ->
                Toast.makeText(
                    this@SettingsActivity,
                    getString(R.string.fetch_failed, e.message ?: "未知错误"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
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
