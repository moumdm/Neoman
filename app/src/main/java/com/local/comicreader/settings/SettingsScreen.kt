package com.local.comicreader.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.local.comicreader.data.PageEffect
import com.local.comicreader.data.PageLayout
import com.local.comicreader.data.Prefs
import com.local.comicreader.data.ReadBackground
import com.local.comicreader.data.ReadDirection
import com.local.comicreader.data.ThemeMode
import com.local.comicreader.library.BookshelfViewModel
import kotlinx.coroutines.launch
import androidx.compose.material3.Snackbar

/**
 * 全局设置：默认阅读偏好（新打开书籍的初始值）+ 主题 + 缓存管理。
 * 阅读中的临时调整在阅读器设置面板里进行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: BookshelfViewModel = viewModel()
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(Prefs.settings) }
    var toast by remember { mutableStateOf<String?>(null) }

    fun update(transform: (com.local.comicreader.data.ReaderSettings) -> com.local.comicreader.data.ReaderSettings) {
        settings = transform(settings)
        Prefs.settings = settings
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text("外观", style = MaterialTheme.typography.titleMedium)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ThemeMode.entries.forEach { m ->
                    FilterChip(
                        selected = settings.themeMode == m,
                        onClick = { update { it.copy(themeMode = m) } },
                        label = { Text(m.label) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("默认阅读方向", style = MaterialTheme.typography.titleMedium)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ReadDirection.entries.forEach { d ->
                    FilterChip(
                        selected = settings.direction == d,
                        onClick = { update { it.copy(direction = d) } },
                        label = { Text(d.label) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("默认页面布局", style = MaterialTheme.typography.titleMedium)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PageLayout.entries.forEach { l ->
                    FilterChip(
                        selected = settings.layout == l,
                        onClick = { update { it.copy(layout = l) } },
                        label = { Text(l.label) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("默认翻页效果", style = MaterialTheme.typography.titleMedium)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PageEffect.entries.forEach { e ->
                    FilterChip(
                        selected = settings.pageEffect == e,
                        onClick = { update { it.copy(pageEffect = e) } },
                        label = { Text(e.label) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("阅读默认", style = MaterialTheme.typography.titleMedium)
            SettingSwitch("白边裁剪", settings.cropBorders) { update { it.copy(cropBorders = !it.cropBorders) } }
            SettingSwitch("自动拆页（横幅跨页）", settings.autoSplit) { update { it.copy(autoSplit = !it.autoSplit) } }
            SettingSwitch("音量键翻页", settings.volumeKeyPaging) { update { it.copy(volumeKeyPaging = !it.volumeKeyPaging) } }
            SettingSwitch("阅读时屏幕常亮", settings.keepScreenOn) { update { it.copy(keepScreenOn = !it.keepScreenOn) } }
            SettingSwitch("护眼模式", settings.eyeCare) { update { it.copy(eyeCare = !it.eyeCare) } }
            SettingSwitch("自定义亮度", settings.useCustomBrightness) { update { it.copy(useCustomBrightness = !it.useCustomBrightness) } }
            if (settings.useCustomBrightness) {
                Slider(
                    value = settings.brightness,
                    onValueChange = { v -> settings = settings.copy(brightness = v); Prefs.settings = settings },
                    valueRange = 0.02f..1f
                )
            }

            Spacer(Modifier.height(24.dp))
            Text("存储", style = MaterialTheme.typography.titleMedium)
            Text(
                "RAR / 7Z / PDF 漫画首次打开时会预处理到应用私有缓存目录以加速后续阅读。可随时清理，下次打开自动重建。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            Button(onClick = {
                scope.launch {
                    val freed = vm.clearCaches()
                    toast = "已清理缓存 ${(freed / 1024 / 1024)} MB"
                }
            }) { Text("清理缓存") }

            Spacer(Modifier.height(24.dp))
            Text("关于", style = MaterialTheme.typography.titleMedium)
            Text(
                "Newman v1.1.2\n本地漫画阅读器 · 完全离线\n支持 JPG/PNG/WebP/GIF/BMP · PDF · EPUB · MOBI(实验) · ZIP/CBZ · RAR/CBR · 7Z/CB7\n联系作者：1499871642@qq.com",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            Spacer(Modifier.height(40.dp))
        }
    }

    toast?.let { msg ->
        Snackbar(modifier = Modifier.padding(16.dp)) { Text(msg) }
        LaunchedEffectOnce { toast = null }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = { onChange() })
    }
}

@Composable
private fun LaunchedEffectOnce(block: suspend () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2500)
        block()
    }
}
