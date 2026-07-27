package me.neko.nzhelper.feature.ai.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.neko.nzhelper.core.ai.AiAnalyzer
import me.neko.nzhelper.core.ai.AiProvider
import me.neko.nzhelper.core.ai.AiSettings

private val API_FORMATS = listOf("OpenAI" to "OpenAI 兼容", "Anthropic" to "Anthropic")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiProviderEditDialog(
    provider: AiProvider,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(provider.name) }
    var baseUrl by remember { mutableStateOf(provider.baseUrl) }
    var apiKey by remember { mutableStateOf(provider.apiKey) }
    var apiFormat by remember { mutableStateOf(provider.apiFormat) }
    var model by remember { mutableStateOf(provider.model) }
    var isActive by remember { mutableStateOf(provider.isActive) }
    var keyVisible by remember { mutableStateOf(false) }

    var models by remember { mutableStateOf(provider.cachedModels) }
    var modelExpanded by remember { mutableStateOf(false) }
    var formatExpanded by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }

    fun doTest() {
        testing = true
        testResult = null
        testOk = false
        scope.launch {
            val result = AiAnalyzer.fetchModels(baseUrl, apiKey)
            testing = false
            result.fold(
                onSuccess = { list ->
                    models = list
                    testOk = true
                    testResult = "连接成功，找到 ${list.size} 个模型"
                    if (model.isBlank() || model !in list) {
                        model = list.firstOrNull() ?: model
                    }
                },
                onFailure = { e ->
                    testResult = "连接失败：${e.message ?: "未知错误"}"
                }
            )
        }
    }

    val isNew = provider.id.isEmpty()
    val saveEnabled = name.isNotBlank() && baseUrl.isNotBlank() && apiKey.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "添加供应商" else "编辑供应商") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    placeholder = { Text("如 OpenAI、DeepSeek、通义千问") },
                    singleLine = true,
                    isError = name.isBlank() && name.isNotEmpty(),
                    supportingText = if (name.isBlank() && name.isNotEmpty()) {
                        { Text("名称不能为空") }
                    } else null,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it; testOk = false },
                    label = { Text("Base URL") },
                    placeholder = { Text("https://api.openai.com/v1") },
                    singleLine = true,
                    isError = baseUrl.isBlank() && baseUrl.isNotEmpty(),
                    supportingText = if (baseUrl.isBlank() && baseUrl.isNotEmpty()) {
                        { Text("Base URL 不能为空") }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; testOk = false },
                    label = { Text("API Key") },
                    placeholder = { Text("") },
                    singleLine = true,
                    isError = apiKey.isBlank() && apiKey.isNotEmpty(),
                    supportingText = if (apiKey.isBlank() && apiKey.isNotEmpty()) {
                        { Text("API Key 不能为空") }
                    } else null,
                    visualTransformation = if (keyVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { keyVisible = !keyVisible }) {
                            Icon(
                                imageVector = if (keyVisible) Icons.Outlined.VisibilityOff
                                else Icons.Outlined.Visibility,
                                contentDescription = if (keyVisible) "隐藏" else "显示"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))

                ExposedDropdownMenuBox(
                    expanded = formatExpanded,
                    onExpandedChange = { formatExpanded = it }
                ) {
                    OutlinedTextField(
                        value = API_FORMATS.firstOrNull { it.first == apiFormat }?.second
                            ?: apiFormat,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("API 格式") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = formatExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = formatExpanded,
                        onDismissRequest = { formatExpanded = false }
                    ) {
                        API_FORMATS.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    apiFormat = value
                                    formatExpanded = false
                                    testOk = false
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                OutlinedButton(
                    onClick = { doTest() },
                    enabled = !testing && baseUrl.isNotBlank() && apiKey.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("测试中...")
                    } else {
                        Text("测试连接")
                    }
                }
                if (testResult != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (testOk) {
                            Icon(
                                Icons.Outlined.CheckCircle, null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            testResult!!, style = MaterialTheme.typography.bodySmall,
                            color = if (testOk) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                    }
                }

                if (models.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    ExposedDropdownMenuBox(
                        expanded = modelExpanded,
                        onExpandedChange = { modelExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = model,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("模型") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(
                            expanded = modelExpanded,
                            onDismissRequest = { modelExpanded = false }
                        ) {
                            models.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m) },
                                    onClick = { model = m; modelExpanded = false }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val p = AiProvider(
                        id = provider.id.ifBlank { java.util.UUID.randomUUID().toString().take(8) },
                        name = name,
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        apiFormat = apiFormat,
                        model = model,
                        isActive = isActive,
                        cachedModels = models
                    )
                    AiSettings.saveProvider(context, p)
                    onSaved()
                },
                enabled = saveEnabled
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
