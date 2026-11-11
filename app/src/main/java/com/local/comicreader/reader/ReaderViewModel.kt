package com.local.comicreader.reader

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.local.comicreader.data.Book
import com.local.comicreader.data.Bookmark
import com.local.comicreader.data.PageEffect
import com.local.comicreader.data.PageLayout
import com.local.comicreader.data.PagePart
import com.local.comicreader.data.PageSpec
import com.local.comicreader.data.Prefs
import com.local.comicreader.data.ReadDirection
import com.local.comicreader.data.ReaderSettings
import com.local.comicreader.parser.BitmapPageCache
import com.local.comicreader.parser.ComicOpener
import com.local.comicreader.parser.ImgDecode
import com.local.comicreader.parser.PageProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 阅读器状态中枢：打开书籍、构建页面序列（含自动拆页）、
 * 位图管线缓存（解码→裁剪→拆页→旋转）、进度与书签持久化。
 */
class ReaderViewModel(
    app: Application,
    val initialBook: Book
) : ViewModel() {

    private val context = app

    var book by mutableStateOf(initialBook)
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    var pageSpecs by mutableStateOf<List<PageSpec>>(emptyList())
    var currentSpecIndex by mutableIntStateOf(0)
    var bookmarks by mutableStateOf<List<Bookmark>>(emptyList())
        private set

    /** 全局设置（阅读中即时生效） */
    var settings by mutableStateOf(Prefs.settings)
        private set

    /** 本书生效方向/布局/翻页效果：书内记忆优先；长图（单图切片）自动用条漫方向适屏宽 */
    val direction: ReadDirection
        get() = book.direction ?: if (webtoon) ReadDirection.VERTICAL else settings.direction
    val layout: PageLayout get() = book.layout ?: settings.layout
    val effect: PageEffect get() = book.effect ?: settings.pageEffect

    /** 打开后探测：本书是否为长图条漫 */
    var webtoon by mutableStateOf(false)
        private set

    /** 初始进度恢复是否已完成：完成前的页面稳定回调一律不落盘，
     *  防止开书瞬间的回调(第0页)把已保存的阅读进度覆盖清零 */
    @Volatile
    var restored: Boolean = false
        private set

    private var provider: PageProvider? = null
    private val target = ImgDecode.targetLongSide(app)

    /** 预览解码目标：约一屏长边，快速翻页时先出图 */
    private val previewTarget = (target / 2).coerceAtLeast(1024)

    // 页位图缓存：按设备堆内存取 64~192MB。大缓存是快速翻页流畅的关键，
    // 100~200 页的书连续翻页时相邻页全部命中缓存，不再反复解码
    private val cache = BitmapPageCache(
        (Runtime.getRuntime().maxMemory() / 4).coerceIn(64L * 1024 * 1024, 192L * 1024 * 1024)
    )

    init {
        open(initialBook)
    }

    /**
     * 计算恢复位置：保存的页号在当前序列内直接使用；
     * 越界（页面序列总数变化过，如开过自动拆页）且知道旧总数时按比例映射，
     * 映射不到或新书一律回首页，绝不落到最后一页。
     */
    fun restoreSpecIndex(savedPage: Int, savedTotal: Int): Int {
        val n = pageSpecs.size
        if (n <= 0) return 0
        return when {
            savedPage in 0 until n -> savedPage
            savedPage > 0 && savedTotal > 0 ->
                ((savedPage.toDouble() / savedTotal) * n).toInt().coerceIn(0, n - 1)
            else -> 0
        }
    }

    private fun open(book: Book) {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val p = withContext(Dispatchers.IO) { ComicOpener.open(context, book) }
                provider = p
                webtoon = p.isWebtoon
                this@ReaderViewModel.book = book
                val raw = (0 until p.pageCount).map { PageSpec(it) }
                pageSpecs = raw
                currentSpecIndex = restoreSpecIndex(book.progressPage, book.progressTotal)
                bookmarks = Prefs.bookmarks(book.id)
                loading = false
                saveCoverIfNeeded(p, book)
                if (settings.autoSplit) probeSizes(p, book)
            } catch (e: Exception) {
                error = "打开失败：${e.message ?: e.javaClass.simpleName}"
                loading = false
            }
        }
    }

    /** 后台探测各页原始尺寸，构建拆页序列 */
    private suspend fun probeSizes(p: PageProvider, book: Book) {
        withContext(Dispatchers.IO) {
            val result = mutableListOf<PageSpec>()
            for (spec in pageSpecs) {
                val size = try { p.sizeAt(spec.srcIndex) } catch (_: Exception) { null }
                if (size != null && ImageOps.needsSplit(size.first, size.second)) {
                    result.add(PageSpec(spec.srcIndex, PagePart.FIRST))
                    result.add(PageSpec(spec.srcIndex, PagePart.SECOND))
                } else result.add(spec)
            }
            if (result.size != pageSpecs.size) {
                val oldSrc = pageSpecs.getOrNull(currentSpecIndex)?.srcIndex ?: 0
                val newIndex = result.indexOfFirst { it.srcIndex == oldSrc }.takeIf { it >= 0 } ?: currentSpecIndex
                withContext(Dispatchers.Main) {
                    pageSpecs = result
                    currentSpecIndex = newIndex
                }
            }
        }
    }

    private fun saveCoverIfNeeded(p: PageProvider, book: Book) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val f = coverFile(context, book)
                if (f.exists()) return@launch
                val bmp = p.pageAt(0, 480) ?: return@launch
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                bmp.recycle()
            } catch (_: Exception) { }
        }
    }

    private fun fullKey(spec: PageSpec) =
        "${book.id}:${spec.srcIndex}:${spec.part}:${settings.pipelineHash}"

    /** 页面位图管线（带缓存与并发去重），供每个页槽调用 */
    suspend fun loadBitmap(spec: PageSpec): Bitmap? {
        val p = provider ?: return null
        return cache.load(fullKey(spec)) {
            val raw = p.pageAt(spec.srcIndex, target) ?: return@load null
            process(raw, spec)
        }
    }

    /** 该页的高清位图是否已在缓存中 */
    fun cachedFull(spec: PageSpec): Bitmap? = cache.get(fullKey(spec))

    /** 两段式加载第一段：低分辨率快速预览（约一屏大小），出图后再后台换高清 */
    suspend fun loadPreview(spec: PageSpec): Bitmap? {
        val p = provider ?: return null
        val key = "${fullKey(spec)}:pv"
        return cache.load(key) {
            val raw = p.pageAt(spec.srcIndex, previewTarget) ?: return@load null
            process(raw, spec)
        }
    }

    /** 预取相邻页（前 3 后 1，并行解码），快速翻页零等待 */
    fun prefetchNeighbors() {
        val specs = pageSpecs
        if (specs.isEmpty()) return
        val cur = currentSpecIndex
        for (d in -1..3) {
            val i = cur + d
            if (i in specs.indices && i != cur) {
                val spec = specs[i]
                viewModelScope.launch { loadBitmap(spec) }
            }
        }
    }

    private fun process(raw: Bitmap, spec: PageSpec): Bitmap {
        var bmp = raw
        if (settings.cropBorders) {
            val trimmed = ImageOps.trimWhiteEdges(bmp)
            if (trimmed !== bmp && trimmed != null) { bmp.recycle(); bmp = trimmed }
        }
        if (spec.part != PagePart.FULL) {
            val half = ImageOps.splitHalf(bmp, spec.part == PagePart.SECOND, direction == ReadDirection.RTL)
            if (half !== bmp) { bmp.recycle(); bmp = half }
        }
        if (settings.rotation != 0) {
            val rotated = ImageOps.rotate(bmp, settings.rotation)
            if (rotated !== bmp) { bmp.recycle(); bmp = rotated }
        }
        return bmp
    }

    /** 翻页落定回调：更新进度并持久化（初始恢复完成前不落盘） */
    fun onPageSettled(specIndex: Int) {
        if (specIndex < 0 || specIndex >= pageSpecs.size) return
        currentSpecIndex = specIndex
        if (restored) {
            Prefs.saveProgress(book.id, specIndex, pageSpecs.size)
            prefetchNeighbors()
        }
    }

    /** 初始进度恢复完成：此后开始正常记录进度 */
    fun markRestored() {
        restored = true
    }

    // ---------------- 设置更新 ----------------

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        settings = transform(settings).also { Prefs.settings = it }
    }

    // 方向 / 布局按书记忆：只写本书，不联动全局设置，
    // 单独设置过的书不再受全局设置影响；clear 恢复跟随全局
    fun setDirection(d: ReadDirection) {
        book = book.copy(direction = d)
        Prefs.setBookDirection(book.id, d)
    }

    fun clearDirection() {
        book = book.copy(direction = null)
        Prefs.setBookDirection(book.id, null)
    }

    fun setLayout(l: PageLayout) {
        book = book.copy(layout = l)
        Prefs.setBookLayout(book.id, l)
    }

    fun clearLayout() {
        book = book.copy(layout = null)
        Prefs.setBookLayout(book.id, null)
    }

    // 翻页效果按书记忆：只写本书，clear 恢复跟随全局
    fun setEffect(e: PageEffect) {
        book = book.copy(effect = e)
        Prefs.setBookEffect(book.id, e)
    }

    fun clearEffect() {
        book = book.copy(effect = null)
        Prefs.setBookEffect(book.id, null)
    }

    // ---------------- 书签 ----------------

    fun addBookmark(note: String) {
        val bm = Bookmark(currentSpecIndex, note, System.currentTimeMillis())
        Prefs.addBookmark(book.id, bm)
        bookmarks = Prefs.bookmarks(book.id)
    }

    fun removeBookmark(page: Int) {
        Prefs.removeBookmark(book.id, page)
        bookmarks = Prefs.bookmarks(book.id)
    }

    override fun onCleared() {
        // 兜底：关闭阅读器时再落盘一次当前位置
        if (restored && pageSpecs.isNotEmpty()) {
            Prefs.saveProgress(book.id, currentSpecIndex, pageSpecs.size)
        }
        provider?.close()
        provider = null
    }

    companion object {
        fun coverFile(context: android.content.Context, book: Book): File {
            val dir = File(context.cacheDir, "covers")
            dir.mkdirs()
            return File(dir, "${book.id}_${book.size}.jpg")
        }
    }
}

class ReaderVmFactory(
    private val app: Application,
    private val book: Book
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ReaderViewModel(app, book) as T
}
