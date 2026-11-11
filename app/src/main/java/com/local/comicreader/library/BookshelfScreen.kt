package com.local.comicreader.library

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.local.comicreader.data.Book
import com.local.comicreader.data.BookFilter
import com.local.comicreader.data.BookSortKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 书架：封面网格、导入文件夹（SAF，持久授权）、进度显示、
 * 批量管理（多选移除 / 删除源文件 / 批量标记已看完）、排序与筛选。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    gridState: LazyGridState = rememberLazyGridState(),
    onOpenBook: (Book) -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val vm: BookshelfViewModel = viewModel()

    // 批量管理模式：长按书籍或从菜单进入
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var confirmRemove by remember { mutableStateOf(false) }
    var toastMsg by remember { mutableStateOf<String?>(null) }

    // 从阅读器返回时轻量同步进度
    LaunchedEffect(Unit) { vm.resync() }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // 同时持久化读写权限：读用于扫描，写用于"删除源文件"
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }.onFailure {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            vm.addRootAndScan(uri.toString())
        }
    }

    fun toggleSelect(id: String) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
        if (selectedIds.isEmpty()) selectionMode = false
    }

    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    fun performRemove(ids: Set<String>, deleteSource: Boolean) {
        vm.removeBooks(ids, deleteSource) { msg -> toastMsg = msg }
        exitSelection()
        confirmRemove = false
    }

    // 批量模式下系统返回键 = 退出批量而不是离开应用
    BackHandler(enabled = selectionMode) { exitSelection() }

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("已选 ${selectedIds.size} 本") },
                    navigationIcon = {
                        IconButton(onClick = { exitSelection() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "退出批量管理")
                        }
                    },
                    actions = {
                        // 全选 / 取消全选（按当前筛选后的列表）
                        IconButton(onClick = {
                            selectedIds =
                                if (vm.displayBooks.isNotEmpty() && selectedIds.size == vm.displayBooks.size)
                                    emptySet()
                                else vm.displayBooks.map { it.id }.toSet()
                            if (selectedIds.isEmpty()) selectionMode = false
                        }) { Icon(Icons.Filled.SelectAll, "全选/取消全选") }
                        // 批量标记已看完 / 未看完
                        IconButton(onClick = {
                            val ids = selectedIds
                            vm.toggleFinished(ids) { msg -> toastMsg = msg }
                            exitSelection()
                        }) { Icon(Icons.Filled.MenuBook, "标记已看完/未看完") }
                        // 移除 / 删除
                        IconButton(
                            onClick = { if (selectedIds.isNotEmpty()) confirmRemove = true },
                            enabled = selectedIds.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Filled.Delete, "移除",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text("Newman · 书架") },
                    actions = {
                        if (vm.scanning) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(12.dp))
                        }
                        // 筛选分类：全部 / 未观看 / 已看完
                        var filterMenuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { filterMenuOpen = true }) {
                            Icon(Icons.Filled.FilterList, "筛选分类")
                        }
                        DropdownMenu(expanded = filterMenuOpen, onDismissRequest = { filterMenuOpen = false }) {
                            BookFilter.entries.forEach { f ->
                                DropdownMenuItem(
                                    text = { Text(f.label) },
                                    leadingIcon = {
                                        if (vm.filter == f) Icon(Icons.Filled.Check, null)
                                    },
                                    onClick = { vm.applyFilter(f); filterMenuOpen = false }
                                )
                            }
                        }
                        // 排序：首字母 / 添加时间 × 正序 / 倒序
                        var sortMenuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { sortMenuOpen = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, "排序方式")
                        }
                        DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("首字母 正序 (A→Z)") },
                                leadingIcon = {
                                    if (vm.sortKey == BookSortKey.TITLE && vm.sortAsc) Icon(Icons.Filled.Check, null)
                                },
                                onClick = { vm.setSort(BookSortKey.TITLE, true); sortMenuOpen = false }
                            )
                            DropdownMenuItem(
                                text = { Text("首字母 倒序 (Z→A)") },
                                leadingIcon = {
                                    if (vm.sortKey == BookSortKey.TITLE && !vm.sortAsc) Icon(Icons.Filled.Check, null)
                                },
                                onClick = { vm.setSort(BookSortKey.TITLE, false); sortMenuOpen = false }
                            )
                            DropdownMenuItem(
                                text = { Text("添加时间 正序（早→晚）") },
                                leadingIcon = {
                                    if (vm.sortKey == BookSortKey.ADDED && vm.sortAsc) Icon(Icons.Filled.Check, null)
                                },
                                onClick = { vm.setSort(BookSortKey.ADDED, true); sortMenuOpen = false }
                            )
                            DropdownMenuItem(
                                text = { Text("添加时间 倒序（晚→早）") },
                                leadingIcon = {
                                    if (vm.sortKey == BookSortKey.ADDED && !vm.sortAsc) Icon(Icons.Filled.Check, null)
                                },
                                onClick = { vm.setSort(BookSortKey.ADDED, false); sortMenuOpen = false }
                            )
                        }
                        var menuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "更多") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("导入漫画文件夹") },
                                leadingIcon = { Icon(Icons.Filled.FolderOpen, null) },
                                onClick = { menuOpen = false; importLauncher.launch(null) }
                            )
                            DropdownMenuItem(
                                text = { Text("批量管理") },
                                leadingIcon = { Icon(Icons.Filled.SelectAll, null) },
                                onClick = { menuOpen = false; selectionMode = true; selectedIds = emptySet() }
                            )
                            DropdownMenuItem(
                                text = { Text("刷新书库") },
                                leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                                onClick = { menuOpen = false; vm.refresh() }
                            )
                            DropdownMenuItem(
                                text = { Text("设置") },
                                leadingIcon = { Icon(Icons.Filled.Settings, null) },
                                onClick = { menuOpen = false; onOpenSettings() }
                            )
                        }
                    }
                )
            }
        }
    ) { padding ->
        if (vm.books.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.MenuBook,
                        null,
                        Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("书架还是空的", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "导入本地漫画文件夹，或从文件管理器打开漫画文件\n完全离线阅读，不联网、不耗流量",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    androidx.compose.material3.Button(onClick = { importLauncher.launch(null) }) {
                        Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("选择漫画文件夹")
                    }
                }
            }
        } else if (vm.displayBooks.isEmpty()) {
            // 有书但当前筛选下为空
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "当前筛选（${vm.filter.label}）下没有漫画",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(150.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().padding(padding)
            ) {
                items(vm.displayBooks, key = { it.id }) { book ->
                    BookCard(
                        vm = vm,
                        book = book,
                        selectionMode = selectionMode,
                        selected = book.id in selectedIds,
                        onClick = {
                            if (selectionMode) toggleSelect(book.id) else onOpenBook(book)
                        },
                        onLongPress = {
                            if (selectionMode) toggleSelect(book.id)
                            else { selectionMode = true; selectedIds = setOf(book.id) }
                        }
                    )
                }
            }
        }
    }

    // 移除确认（单本 / 批量共用）：可选"同时删除源文件"
    if (confirmRemove && selectedIds.isNotEmpty()) {
        RemoveBooksDialog(
            count = selectedIds.size,
            onDismiss = { confirmRemove = false },
            onConfirm = { deleteSource -> performRemove(selectedIds, deleteSource) }
        )
    }

    toastMsg?.let { msg ->
        LaunchedEffect(msg) {
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            toastMsg = null
        }
    }
}

/** 移除确认对话框：默认仅从书架移除；勾选后连源文件一起删除 */
@Composable
private fun RemoveBooksDialog(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: (deleteSource: Boolean) -> Unit
) {
    var deleteSource by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (deleteSource) "删除源文件" else "从书架移除") },
        text = {
            Column {
                Text(
                    if (deleteSource) "将删除已选 $count 本漫画的源文件，并从书架移除记录。\n此操作不可恢复！"
                    else "仅从书架列表移除已选的 $count 本漫画，不会删除源文件。"
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = deleteSource) { deleteSource = it }
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = deleteSource, onCheckedChange = { deleteSource = it })
                    Text("同时删除源文件", Modifier.padding(start = 4.dp))
                }
                if (deleteSource) {
                    Text(
                        "源文件将从磁盘删除，无法恢复，请谨慎操作",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(deleteSource) }) {
                Text(if (deleteSource) "删除" else "移除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    vm: BookshelfViewModel,
    book: Book,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val context = LocalContext.current
    val cover by produceState<android.graphics.Bitmap?>(null, book.id, book.size) {
        value = withContext(Dispatchers.IO) {
            vm.cover(context, book)
        }
    }

    Card(
        modifier = Modifier
            .aspectRatio(0.72f)
            .then(
                if (selected) Modifier.border(
                    width = 3.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(12.dp)
                ) else Modifier
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Box(Modifier.fillMaxSize()) {
            val bmp = cover
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    Modifier.fillMaxSize().background(Color(0xFF2A2E3A)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.MenuBook,
                            null,
                            Modifier.size(48.dp),
                            tint = Color(0xFF8A90A0)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            book.format.label,
                            color = Color(0xFFB8BDC9),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
            // 批量模式：右上角选择圈
            if (selectionMode) {
                Icon(
                    imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = if (selected) "已选中" else "未选中",
                    tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(24.dp)
                        .background(Color(0x66000000), CircleShape)
                        .padding(2.dp)
                )
            }
            // 已看完角标（左上角）
            if (book.finished) {
                Text(
                    "已看完",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .background(Color(0xCC2E7D32))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            // 底部渐变信息条
            Column(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color(0x99000000))
                    .padding(8.dp)
            ) {
                Text(
                    book.title,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (book.progressTotal > 0) {
                    Text(
                        "已读 ${book.progressPage + 1}/${book.progressTotal}",
                        color = Color(0xFFCCCCCC),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}
