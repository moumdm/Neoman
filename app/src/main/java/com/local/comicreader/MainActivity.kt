package com.local.comicreader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import com.local.comicreader.data.Book
import com.local.comicreader.data.ComicFormat
import com.local.comicreader.data.Prefs
import com.local.comicreader.library.BookshelfScreen
import com.local.comicreader.reader.ReaderScreen
import com.local.comicreader.settings.SettingsScreen
import com.local.comicreader.ui.NewmanTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** 物理按键总线：Activity 收到音量键后转发给阅读器 */
object KeyBus {
    @Volatile
    var handler: ((Int) -> Boolean)? = null

    fun dispatch(keyCode: Int): Boolean = handler?.invoke(keyCode) ?: false
}

class MainActivity : ComponentActivity() {

    // 属性级状态：singleTask 模式下 onNewIntent 也需要更新它
    private var currentBookId by mutableStateOf<String?>(null)
    private var showSettings by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Prefs.init(this)
        handleViewIntent(intent)

        setContent {
            NewmanTheme(themeMode = Prefs.settings.themeMode) {
                // 书架网格滚动状态提升到常驻组合层：
                // 进出阅读器 / 设置页后返回书架，位置不再回到顶部
                val shelfState = rememberLazyGridState()
                val book = currentBookId?.let { Prefs.find(it) }
                when {
                    showSettings -> SettingsScreen(onBack = { showSettings = false })
                    book != null -> ReaderHost(book = book, onClose = { currentBookId = null })
                    else -> BookshelfScreen(
                        gridState = shelfState,
                        onOpenBook = { currentBookId = it.id },
                        onOpenSettings = { showSettings = true }
                    )
                }
            }
        }
    }
//原创作者Joein
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    /**
     * 处理文件管理器等外部入口（ACTION_VIEW）：
     * 1. 用 DISPLAY_NAME / MIME 正确识别格式（content uri 路径段只是数字 ID，不含文件名）；
     * 2. 复制到应用私有目录 —— ACTION_VIEW 的临时授权重启即失效，落地副本可永久打开；
     * 3. 复制完成后自动进入阅读器。
     */
    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        lifecycleScope.launch {
            val imported = withContext(Dispatchers.IO) { importExternal(uri) }
            if (imported != null) {
                Prefs.upsert(imported)
                currentBookId = imported.id
            } else {
                Toast.makeText(
                    this@MainActivity,
                    "无法导入该文件：格式不支持或读取失败",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun importExternal(uri: Uri): Book? = runCatching {
        val resolver = contentResolver
        val name = displayNameOf(uri) ?: return@runCatching null
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
        val fmt = ComicFormat.fromName(name) ?: ComicFormat.fromMime(mime) ?: return@runCatching null

        val size = runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        }.getOrDefault(0L)

        // 落地到私有目录（已存在且大小一致则跳过，避免重复复制）
        val id = "ext_${Book.idOf(uri.toString())}"
        val safeName = name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        val dir = File(filesDir, "import").apply { mkdirs() }
        val dest = File(dir, "${id}_$safeName")
        if (!dest.exists() || (size > 0 && dest.length() != size)) {
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output, 64 * 1024) }
            } ?: return@runCatching null
        }

        Book(
            id = id,
            uri = Uri.fromFile(dest).toString(),
            title = name.substringBeforeLast('.').ifEmpty { "未命名" },
            format = fmt,
            size = if (size > 0) size else dest.length(),
            addedAt = System.currentTimeMillis()
        )
    }.getOrNull()

    /** content uri 用 query 取真实文件名；file uri 直接取路径末段 */
    private fun displayNameOf(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment?.substringAfterLast('/')
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (KeyBus.dispatch(keyCode)) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        KeyBus.handler = null
        super.onDestroy()
    }
}

/**
 * 阅读器会话宿主：每次进入阅读都创建独立的 ViewModelStore，离开阅读器即销毁。
 * 修复的问题：ViewModel 挂在 Activity 级 Store 上时，关闭阅读器后仍缓存旧实例，
 * 下次打开同一本书会复用旧的全局设置快照 —— 表现为"全局设置改了不生效，
 * 必须清后台重开"。会话化之后，每次打开都重新读取 Prefs 最新设置。
 */
@Composable
private fun ReaderHost(book: Book, onClose: () -> Unit) {
    key(book.id) {
        val owner = remember { SessionVmOwner() }
        DisposableEffect(owner) {
            onDispose { owner.store.clear() }
        }
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
            ReaderScreen(book = book, onClose = onClose)
        }
    }
}

private class SessionVmOwner : ViewModelStoreOwner {
    val store = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = store
}
