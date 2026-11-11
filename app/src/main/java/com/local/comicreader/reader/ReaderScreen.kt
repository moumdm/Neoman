package com.local.comicreader.reader

import android.app.Application
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.LastPage
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.viewmodel.compose.viewModel
import com.local.comicreader.KeyBus
import com.local.comicreader.data.Book
import com.local.comicreader.data.PageEffect
import com.local.comicreader.data.PageLayout
import com.local.comicreader.data.ReadBackground
import com.local.comicreader.data.ReadDirection
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max


private enum class Sheet { NONE, SETTINGS, BOOKMARKS }

/**
 * 阅读器全屏界面：
 * 翻页引擎 + 点击分区 + 菜单浮层 + 阅读设置面板 + 书签面板 +
 * 自动翻页/自动滚动 + 音量键翻页 + 亮度/护眼/背景。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReaderScreen(book: Book, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val vm: ReaderViewModel = viewModel(
        key = "reader_${book.id}",
        factory = ReaderVmFactory(
            context.applicationContext as Application,
            book
        )
    )

    var menuOpen by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(Sheet.NONE) }
    var autoPlay by remember { mutableStateOf(false) }
    var autoScroll by remember { mutableStateOf(false) }

    val horizontal = vm.direction != ReadDirection.VERTICAL
    val dual = vm.layout == PageLayout.DUAL && horizontal

    val pagerState = rememberPagerState(initialPage = 0) {
        if (vm.layout == PageLayout.DUAL && vm.direction != ReadDirection.VERTICAL)
            (vm.pageSpecs.size + 1) / 2 else vm.pageSpecs.size
    }
    val listState = rememberLazyListState()

    // ---------- 翻页控制 ----------

    suspend fun scrollToSpec(specIndex: Int, animated: Boolean = true) {
        val idx = specIndex.coerceIn(0, max(0, vm.pageSpecs.lastIndex))
        if (vm.direction == ReadDirection.VERTICAL) {
            if (animated) listState.animateScrollToItem(idx) else listState.scrollToItem(idx)
        } else {
            val target = if (dual) idx / 2 else idx
            if (animated) pagerState.animateScrollToPage(target) else pagerState.scrollToPage(target)
        }
    }

    fun jumpTo(specIndex: Int, animated: Boolean = true) {
        scope.launch { scrollToSpec(specIndex, animated) }
    }

    fun turnPage(forward: Boolean) {
        if (vm.direction == ReadDirection.VERTICAL) {
            jumpTo(vm.currentSpecIndex + if (forward) 1 else -1)
        } else {
            val target = (pagerState.currentPage + if (forward) 1 else -1)
                .coerceIn(0, max(0, pagerState.pageCount - 1))
            scope.launch { pagerState.animateScrollToPage(target) }
        }
    }

    // 点击分区：左右 30% 翻页（方向感知），中间开菜单
    val zoneTap: (Offset) -> Unit = remember(vm.direction) {
        { f: Offset ->
            when {
                vm.direction == ReadDirection.VERTICAL -> menuOpen = !menuOpen
                f.x < 0.30f -> turnPage(vm.direction == ReadDirection.RTL)
                f.x > 0.70f -> turnPage(vm.direction == ReadDirection.LTR)
                else -> menuOpen = !menuOpen
            }
        }
    }

    // 打开后恢复进度：越界/无效进度按比例映射（新书 → 首页），
    // 滚动真正落定后才开门禁记录进度，防止开书瞬间回调把进度清零
    LaunchedEffect(book.id) {
        snapshotFlow { vm.pageSpecs.isNotEmpty() }.filter { it }.first()
        scrollToSpec(vm.restoreSpecIndex(book.progressPage, book.progressTotal), animated = false)
        vm.markRestored()
    }

    // 页面稳定回调：记忆进度 + 预取
    LaunchedEffect(pagerState, vm.direction, vm.layout) {
        if (vm.direction == ReadDirection.VERTICAL) {
            snapshotFlow { if (!listState.isScrollInProgress) listState.firstVisibleItemIndex else null }
                .filterNotNull()
                .collect { vm.onPageSettled(it) }
        } else {
            snapshotFlow { pagerState.settledPage }
                .collect { vm.onPageSettled(if (dual) it * 2 else it) }
        }
    }

    // 方向 / 布局切换后把视图对齐回当前页
    //（RTL 反转与单双页切换都会改变 pager 页号 ↔ spec 序号的映射）
    // 仅在初始恢复完成后才参与对齐，避免与恢复跳转竞争
    LaunchedEffect(vm.direction, vm.layout) {
        if (vm.restored && vm.pageSpecs.isNotEmpty()) scrollToSpec(vm.currentSpecIndex, animated = false)
    }

    // 音量键翻页
    DisposableEffect(vm.settings.volumeKeyPaging) {
        KeyBus.handler = if (vm.settings.volumeKeyPaging) {
            { code: Int ->
                when (code) {
                    KeyEvent.KEYCODE_VOLUME_DOWN -> { turnPage(true); true }
                    KeyEvent.KEYCODE_VOLUME_UP -> { turnPage(false); true }
                    else -> false
                }
            }
        } else null
        onDispose { KeyBus.handler = null }
    }

    // 屏幕常亮 / 亮度
    val window = (context as? android.app.Activity)?.window
    DisposableEffect(vm.settings.keepScreenOn) {
        window?.let { w ->
            if (vm.settings.keepScreenOn) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    DisposableEffect(vm.settings.useCustomBrightness, vm.settings.brightness) {
        window?.let { w ->
            val lp = w.attributes
            lp.screenBrightness = if (vm.settings.useCustomBrightness)
                vm.settings.brightness.coerceIn(0.02f, 1f)
            else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            w.attributes = lp
        }
        onDispose { }
    }

    // 自动翻页
    LaunchedEffect(autoPlay, vm.settings.autoPlaySec) {
        if (!autoPlay || vm.settings.autoPlaySec <= 0) return@LaunchedEffect
        while (true) {
            delay(vm.settings.autoPlaySec * 1000L)
            val atEnd = if (vm.direction == ReadDirection.VERTICAL)
                vm.currentSpecIndex >= vm.pageSpecs.lastIndex
            else pagerState.currentPage >= pagerState.pageCount - 1
            if (atEnd) { autoPlay = false; break }
            turnPage(true)
        }
    }
// 自动滚动（条漫）
    LaunchedEffect(autoScroll, vm.settings.autoScrollSpeed, vm.direction) {
        if (!autoScroll || vm.direction != ReadDirection.VERTICAL || vm.settings.autoScrollSpeed <= 0)
            return@LaunchedEffect
        var last = 0L
        while (true) {
            val t = androidx.compose.runtime.withFrameNanos { it }
            if (last != 0L) {
                val dt = (t - last) / 1e9f
                val deltaPx = (vm.settings.autoScrollSpeed * dt).toInt()
                val before = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                listState.scrollToItem(
                    listState.firstVisibleItemIndex,
                    listState.firstVisibleItemScrollOffset + deltaPx
                )
                val after = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                // 位置不再变化 = 已滚到底
                if (deltaPx > 0 && before == after) { autoScroll = false; break }
            }
            last = t
        }
    }


    BackHandler(enabled = sheet != Sheet.NONE || menuOpen) {
        if (sheet != Sheet.NONE) sheet = Sheet.NONE else menuOpen = false
    }
    BackHandler(enabled = sheet == Sheet.NONE && !menuOpen) { onClose() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {

        ReaderPager(
            vm = vm,
            pagerState = pagerState,
            listState = listState,
            onToggleMenu = { menuOpen = !menuOpen },
            onTapFraction = zoneTap
        )

        // 护眼暖色滤镜
        if (vm.settings.eyeCare) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x2EF5B54A))
            )
        }

        // 加载中 / 错误
        if (vm.loading) {
            Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    Text("正在打开 ${book.title}…", color = Color.White)
                    Text("大文件首次打开需要预处理，请稍候", color = Color(0xFFAAAAAA), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        vm.error?.let { err ->
            Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(err, color = Color.White, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onClose) { Text("返回书架") }
                }
            }
        }

        // ---------- 菜单浮层 ----------
        AnimatedVisibility(
            visible = menuOpen,
            enter = fadeIn(),
            exit = fadeOut()
        ) { TopMenu(vm, onClose, onBookmarks = { sheet = Sheet.BOOKMARKS }, onSettings = { sheet = Sheet.SETTINGS }) }

        AnimatedVisibility(
            visible = menuOpen,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut()
        ) {
            BottomMenu(
                vm = vm,
                autoPlay = autoPlay,
                autoScroll = autoScroll,
                onToggleAutoPlay = {
                    autoPlay = !autoPlay
                    if (autoPlay) menuOpen = false
                },
                onToggleAutoScroll = {
                    autoScroll = !autoScroll
                    if (autoScroll) menuOpen = false
                },
                onSeek = { jumpTo(it) },
                onTurn = { turnPage(it) },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    // ---------- 面板 ----------
    if (sheet == Sheet.SETTINGS) {
        ReaderSettingsSheet(
            vm = vm,
            onDismiss = { sheet = Sheet.NONE }
        )
    }
    if (sheet == Sheet.BOOKMARKS) {
        BookmarksSheet(
            vm = vm,
            onDismiss = { sheet = Sheet.NONE },
            onJump = { jumpTo(it, animated = true) }
        )
    }
}

/** 顶部菜单 */
@Composable
private fun TopMenu(
    vm: ReaderViewModel,
    onBack: () -> Unit,
    onBookmarks: () -> Unit,
    onSettings: () -> Unit
) {
    Surface(color = Color(0xE6141414), tonalElevation = 4.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    vm.book.title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1
                )
                Text(
                    "${vm.currentSpecIndex + 1} / ${vm.pageSpecs.size}  ·  ${vm.book.format.label}",
                    color = Color(0xFFBBBBBB),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            IconButton(onClick = onBookmarks) {
                Icon(Icons.Filled.BookmarkBorder, "书签", tint = Color.White)
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, "阅读设置", tint = Color.White)
            }
        }
    }
}

/** 底部菜单：页码滑条 + 翻页/自动按钮 */
@Composable
private fun BottomMenu(
    vm: ReaderViewModel,
    autoPlay: Boolean,
    autoScroll: Boolean,
    onToggleAutoPlay: () -> Unit,
    onToggleAutoScroll: () -> Unit,
    onSeek: (Int) -> Unit,
    onTurn: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val count = vm.pageSpecs.size
    var dragValue by remember { mutableStateOf<Float?>(null) }
    Surface(color = Color(0xE6141414), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            if (count > 1) {
                Slider(
                    value = dragValue ?: vm.currentSpecIndex.toFloat(),
                    onValueChange = { dragValue = it },
                    onValueChangeFinished = {
                        dragValue?.let { onSeek(it.toInt()) }
                        dragValue = null
                    },
                    valueRange = 0f..(count - 1).toFloat(),
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            // 快捷方向 / 翻页效果切换：按书记忆，直接循环切换
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = true,
                    onClick = {
                        val next = when (vm.direction) {
                            ReadDirection.RTL -> ReadDirection.LTR
                            ReadDirection.LTR -> ReadDirection.VERTICAL
                            ReadDirection.VERTICAL -> ReadDirection.RTL
                        }
                        vm.setDirection(next)
                    },
                    label = {
                        Text(
                            "方向：${vm.direction.label}",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
                if (vm.direction != ReadDirection.VERTICAL) {
                    FilterChip(
                        selected = true,
                        onClick = {
                            val next = when (vm.effect) {
                                PageEffect.SLIDE -> PageEffect.SIMULATION
                                PageEffect.SIMULATION -> PageEffect.FADE
                                PageEffect.FADE -> PageEffect.NONE
                                PageEffect.NONE -> PageEffect.SLIDE
                            }
                            vm.setEffect(next)
                        },
                        label = {
                            Text(
                                "效果：${vm.effect.label}",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { onSeek(0) }) { Icon(Icons.Filled.FirstPage, "首页", tint = Color.White) }
                IconButton(onClick = { onTurn(false) }) { Icon(Icons.Filled.SkipPrevious, "上一页", tint = Color.White) }
                if (vm.direction == ReadDirection.VERTICAL) {
                    IconButton(onClick = onToggleAutoScroll) {
                        Icon(
                            if (autoScroll) Icons.Filled.Pause else Icons.Filled.Speed,
                            "自动滚动",
                            tint = if (autoScroll) Color(0xFFFFC107) else Color.White
                        )
                    }
                }
                IconButton(onClick = onToggleAutoPlay) {
                    Icon(
                        if (autoPlay) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        "自动翻页",
                        tint = if (autoPlay) Color(0xFFFFC107) else Color.White
                    )
                }
                IconButton(onClick = { onTurn(true) }) { Icon(Icons.Filled.SkipNext, "下一页", tint = Color.White) }
                IconButton(onClick = { onSeek(count - 1) }) { Icon(Icons.Filled.LastPage, "末页", tint = Color.White) }
            }
        }
    }
}

// ==================== 阅读设置面板 ====================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderSettingsSheet(
    vm: ReaderViewModel,
    onDismiss: () -> Unit
) {
    val s = vm.settings
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text("阅读设置", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            SettingSection("阅读方向（本书记忆，不影响全局）") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = vm.book.direction == null,
                        onClick = { vm.clearDirection() },
                        label = { Text("跟随全局") }
                    )
                    ReadDirection.entries.forEach { d ->
                        FilterChip(
                            selected = vm.book.direction == d,
                            onClick = { vm.setDirection(d) },
                            label = { Text(d.label) }
                        )
                    }
                }
            }

            SettingSection("页面布局（本书记忆，不影响全局）") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = vm.book.layout == null,
                        enabled = vm.direction != ReadDirection.VERTICAL,
                        onClick = { vm.clearLayout() },
                        label = { Text("跟随全局") }
                    )
                    PageLayout.entries.forEach { l ->
                        FilterChip(
                            selected = vm.book.layout == l,
                            enabled = vm.direction != ReadDirection.VERTICAL,
                            onClick = { vm.setLayout(l) },
                            label = { Text(l.label) }
                        )
                    }
                }
            }

            SettingSection("翻页效果（本书记忆，不影响全局）") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = vm.book.effect == null,
                        onClick = { vm.clearEffect() },
                        label = { Text("跟随全局") }
                    )
                    PageEffect.entries.forEach { e ->
                        FilterChip(
                            selected = vm.book.effect == e,
                            onClick = { vm.setEffect(e) },
                            label = { Text(e.label) }
                        )
                    }
                }
            }

            SettingSection("画面优化") {
                SwitchRow("白边裁剪（自动去除页面空白边）", s.cropBorders) {
                    vm.updateSettings { it.copy(cropBorders = !it.cropBorders) }
                }
                SwitchRow("自动拆页（横幅跨页切为两页）", s.autoSplit) {
                    vm.updateSettings { it.copy(autoSplit = !it.autoSplit) }
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("画面旋转")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 90, 180, 270).forEach { r ->
                            FilterChip(
                                selected = s.rotation == r,
                                onClick = { vm.updateSettings { it.copy(rotation = r) } },
                                label = { Text(if (r == 0) "原向" else "${r}°") }
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("背景颜色")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReadBackground.entries.forEach { bg ->
                            FilterChip(
                                selected = s.background == bg,
                                onClick = { vm.updateSettings { it.copy(background = bg) } },
                                label = { Text(bg.label) }
                            )
                        }
                    }
                }
            }

            SettingSection("交互") {
                SwitchRow("音量键翻页", s.volumeKeyPaging) {
                    vm.updateSettings { it.copy(volumeKeyPaging = !it.volumeKeyPaging) }
                }
                SwitchRow("阅读时屏幕常亮", s.keepScreenOn) {
                    vm.updateSettings { it.copy(keepScreenOn = !it.keepScreenOn) }
                }
            }

            SettingSection("自动阅读") {
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("自动翻页间隔")
                        Text(if (s.autoPlaySec <= 0) "关闭" else "${s.autoPlaySec} 秒")
                    }
                    Slider(
                        value = s.autoPlaySec.toFloat(),
                        onValueChange = {
                            vm.updateSettings { st -> st.copy(autoPlaySec = ((it / 5).toInt() * 5).coerceIn(0, 120)) }
                        },
                        valueRange = 0f..120f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (vm.direction == ReadDirection.VERTICAL) {
                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("自动滚动速度")
                            Text(if (s.autoScrollSpeed <= 0) "关闭" else "${s.autoScrollSpeed} px/s")
                        }
                        Slider(
                            value = s.autoScrollSpeed.toFloat(),
                            onValueChange = {
                                vm.updateSettings { st -> st.copy(autoScrollSpeed = ((it / 100).toInt() * 100).coerceIn(0, 3000)) }
                            },
                            valueRange = 0f..3000f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            SettingSection("显示") {
                SwitchRow("护眼模式（暖色滤镜）", s.eyeCare) {
                    vm.updateSettings { it.copy(eyeCare = !it.eyeCare) }
                }
                SwitchRow("自定义亮度", s.useCustomBrightness) {
                    vm.updateSettings { it.copy(useCustomBrightness = !it.useCustomBrightness) }
                }
                if (s.useCustomBrightness) {
                    Slider(
                        value = s.brightness,
                        onValueChange = { v -> vm.updateSettings { it.copy(brightness = v) } },
                        valueRange = 0.02f..1f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingSection(title: String, content: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
    content()
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = { onChange() })
    }
}

// ==================== 书签面板 ====================

@Composable
private fun BookmarksSheet(
    vm: ReaderViewModel,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("书签", style = MaterialTheme.typography.titleLarge)
                Button(onClick = {
                    vm.addBookmark("第 ${vm.currentSpecIndex + 1} 页")
                }) {
                    Icon(Icons.Filled.Bookmark, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("标记本页")
                }
            }
            Spacer(Modifier.height(12.dp))
            if (vm.bookmarks.isEmpty()) {
                Text(
                    "还没有书签，点击右上角按钮标记当前页",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(vm.bookmarks, key = { it.page }) { bm ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onJump(bm.page); onDismiss() }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Bookmark,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(bm.note)
                                Text(
                                    "第 ${bm.page + 1} 页",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { vm.removeBookmark(bm.page) }) {
                                Icon(Icons.Filled.Delete, "删除", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
