package me.neko.nzhelper.feature.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.neko.nzhelper.core.ai.AiSettings
import me.neko.nzhelper.ui.component.setting.SettingsCard
import me.neko.nzhelper.ui.component.setting.SettingsDivider
import me.neko.nzhelper.ui.component.setting.SettingsItem

private val TONES = listOf(
    "warm" to "温暖",
    "caring" to "贴心",
    "encouraging" to "鼓励",
    "professional" to "专业",
    "humorous" to "幽默",
    "concise" to "简洁"
)
private val LENGTHS = listOf("short" to "简短", "medium" to "适中", "detailed" to "详细")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiConfigScreen(
    onBack: () -> Unit,
    onProviders: () -> Unit
) {
    val context = LocalContext.current
    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    var enabled by remember { mutableStateOf(AiSettings.isEnabled(context)) }
    var tone by remember { mutableStateOf(AiSettings.getPromptTone(context)) }
    var length by remember { mutableStateOf(AiSettings.getPromptLength(context)) }
    var custom by remember { mutableStateOf(AiSettings.getPromptCustom(context)) }
    var showToneDialog by remember { mutableStateOf(false) }
    var showLengthDialog by remember { mutableStateOf(false) }
    var showCustomDialog by remember { mutableStateOf(false) }

    val toggleEnabled: (Boolean) -> Unit = { e ->
        enabled = e
        AiSettings.setEnabled(context, e)
    }
    val providers = remember { AiSettings.getProviders(context) }
    val active = providers.firstOrNull { it.isActive }
    val hasProvider = providers.isNotEmpty()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("AI 健康建议") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = innerPadding.calculateTopPadding() + 16.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SettingsCard {
                    SettingsItem(
                        icon = Icons.Outlined.AutoAwesome,
                        title = "启用 AI 分析",
                        subtitle = if (hasProvider) "AI 根据记录生成个性化健康建议"
                        else "请先添加供应商",
                        enabled = hasProvider,
                        onClick = { if (hasProvider) toggleEnabled(!enabled) },
                        trailingContent = {
                            Switch(
                                checked = enabled && hasProvider,
                                onCheckedChange = { if (hasProvider) toggleEnabled(it) },
                                enabled = hasProvider
                            )
                        }
                    )
                    SettingsDivider()
                    SettingsItem(
                        icon = Icons.Outlined.Dns,
                        title = "管理供应商",
                        subtitle = if (active != null) "${active.model} · ${providers.size} 个供应商"
                        else if (providers.isNotEmpty()) "${providers.size} 个供应商 · 未激活"
                        else "尚未添加",
                        onClick = onProviders
                    )
                }
            }

            item {
                SettingsCard {
                    SettingsItem(
                        icon = Icons.Outlined.Tune,
                        title = "回答口吻",
                        subtitle = TONES.firstOrNull { it.first == tone }?.second ?: tone,
                        enabled = hasProvider,
                        onClick = { showToneDialog = true }
                    )
                    SettingsDivider()
                    SettingsItem(
                        icon = Icons.Outlined.Tune,
                        title = "回答长度",
                        subtitle = LENGTHS.firstOrNull { it.first == length }?.second ?: length,
                        enabled = hasProvider,
                        onClick = { showLengthDialog = true }
                    )
                    SettingsDivider()
                    SettingsItem(
                        icon = Icons.Outlined.AutoAwesome,
                        title = "自定义要求",
                        subtitle = custom.ifBlank { "未设置" },
                        enabled = hasProvider,
                        onClick = { showCustomDialog = true }
                    )
                }
            }
        }
    }

    // 口吻选择
    if (showToneDialog) {
        AlertDialog(
            onDismissRequest = { showToneDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text("回答口吻", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TONES.forEach { (v, label) ->
                        val selected = tone == v
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer.copy(
                                        alpha = 0.4f
                                    )
                                    else Color.Transparent
                                )
                                .clickable { tone = v }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = MaterialTheme.colorScheme.primary,
                                    unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                        alpha = 0.5f
                                    )
                                )
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showToneDialog = false },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large
                ) { Text("确定") }
            }
        )
    }

    // 长度选择
    if (showLengthDialog) {
        AlertDialog(
            onDismissRequest = { showLengthDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text("回答长度", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LENGTHS.forEach { (v, label) ->
                        val selected = length == v
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer.copy(
                                        alpha = 0.4f
                                    )
                                    else Color.Transparent
                                )
                                .clickable { length = v }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = MaterialTheme.colorScheme.primary,
                                    unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                        alpha = 0.5f
                                    )
                                )
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showLengthDialog = false },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large
                ) { Text("确定") }
            }
        )
    }

    // 自定义要求
    if (showCustomDialog) {
        var temp by remember(custom) { mutableStateOf(custom) }
        AlertDialog(
            onDismissRequest = { showCustomDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text("自定义要求", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            },
            text = {
                OutlinedTextField(
                    value = temp,
                    onValueChange = { temp = it },
                    placeholder = { Text("如：多鼓励、少批评、关注作息...") },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        custom = temp
                        showCustomDialog = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large
                ) { Text("确定") }
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            AiSettings.savePrompt(context, tone, length, custom)
        }
    }
}
