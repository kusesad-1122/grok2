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
        binding.advancedButton.setOnClickListener {
            startActivity(android.content.Intent(this, AdvancedConfigActivity::class.java))
        }
        setupChoiceSpinners()
    }

    // 思考程度 / 权限模式的显示名 ↔ 取值。
    private val effortValues = listOf("", "minimal", "low", "medium", "high")
    private val effortLabels = listOf("默认(跟随模型)", "minimal", "low", "medium", "high")
    private val permValues = listOf("yolo", "read-only", "default")
    private val permLabels = listOf("自动放行(推荐,工具直接执行)", "只读(不改文件/不执行)", "默认(交由 grok 权限规则)")

    private fun setupChoiceSpinners() {
        val ea = ArrayAdapter(this, android.R.layout.simple_spinner_item, effortLabels)
        ea.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.effortSpinner.adapter = ea
        binding.effortSpinner.setSelection(effortValues.indexOf(prefs.reasoningEffort).coerceAtLeast(0))

        val pa = ArrayAdapter(this, android.R.layout.simple_spinner_item, permLabels)
        pa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.permissionSpinner.adapter = pa
        binding.permissionSpinner.setSelection(permValues.indexOf(prefs.permissionMode).coerceAtLeast(0))
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
                    val checked = BooleanArray(arr.size) { prefs.favoriteModels(currentId).contains(arr[it]) }
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle(R.string.pick_model)
                        .setMultiChoiceItems(arr, checked) { _, which, isChecked -> checked[which] = isChecked }
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            val chosen = arr.filterIndexed { i, _ -> checked[i] }.toSet()
                            prefs.setFavoriteModels(currentId, chosen)
                            // 当前模型框设为第一个所选,便于立即使用。
                            chosen.firstOrNull()?.let { binding.modelInput.setText(it) }
                            Toast.makeText(
                                this@SettingsActivity,
                                getString(R.string.fav_added, chosen.size), Toast.LENGTH_SHORT
                            ).show()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
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
        prefs.reasoningEffort = effortValues[binding.effortSpinner.selectedItemPosition]
        prefs.permissionMode = permValues[binding.permissionSpinner.selectedItemPosition]
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
