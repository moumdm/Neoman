package com.local.comicreader.library

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.local.comicreader.data.Book
import com.local.comicreader.data.BookFilter
import com.local.comicreader.data.BookScanner
import com.local.comicreader.data.BookSortKey
import com.local.comicreader.data.ComicFormat
import com.local.comicreader.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.Collator
import java.util.Locale

/**
 * 书架状态：书籍列表、封面缓存（文件级持久化）、扫描、排序与筛选。
 * 排序与 lastReadAt 无关：看过的书保留在原位，不再浮到最前。
 * 封面策略：
 * - 已有封面文件 → 直接读取（RAR/7Z/PDF 首次阅读时自动生成）
 * - ZIP/EPUB/图片文件夹 → 轻量打开读第一页
 * - PDF → 轻量渲染第一页（不整本预渲染）
 */
class BookshelfViewModel(app: Application) : AndroidViewModel(app) {

    var books by mutableStateOf<List<Book>>(emptyList())
        private set
    var scanning by mutableStateOf(false)
        private set

    /** 排序 / 筛选状态（持久化到 Prefs） */
    var sortKey by mutableStateOf(Prefs.bookSortKey)
    var sortAsc by mutableStateOf(Prefs.bookSortAsc)
    var filter by mutableStateOf(Prefs.bookFilter)

    /** 应用当前排序 + 筛选后的书架列表（UI 直接使用） */
    val displayBooks: List<Book>
        get() {
            val filtered = when (filter) {
                BookFilter.ALL -> books
                BookFilter.UNREAD -> books.filter { !it.finished }
                BookFilter.FINISHED -> books.filter { it.finished }
            }
            val base = when (sortKey) {
                BookSortKey.ADDED -> filtered.sortedBy { it.addedAt }
                BookSortKey.TITLE -> filtered.sortedWith(compareBy(collator) { it.title })
            }
            return if (sortAsc) base else base.asReversed()
        }

    private val collator = Collator.getInstance(Locale.CHINA)

    private val coverMem = LruCache<String, Bitmap>(24 * 1024 * 1024 / 4)

    init {
        books = Prefs.books
        refresh()
    }

    fun refresh() {
        if (scanning) return
        scanning = true
        viewModelScope.launch {
            val result = BookScanner.scanAll(getApplication())
            val found = result.books.filter { !Prefs.isHidden(it.id) }
            // 合并扫描结果与已有元数据（保留已看完标记、进度、方向设置），
            // 并同步进 Prefs —— 书架数据源始终是 Prefs.books
            val foundIds = found.map { it.id }.toHashSet()
            found.map { f ->
                val old = Prefs.find(f.id)
                if (old != null) old.copy(title = f.title, size = f.size, uri = f.uri, addedAt = old.addedAt.takeIf { it > 0 } ?: f.addedAt)
                else f
            }.forEach { Prefs.upsert(it) }

            // 源文件已消失的书 → 自动清理（修复"删了文件书架还显示"）：
            // - file:// 为外部导入的落地副本，不参与扫描，永远保留；
            // - 读取失败的根目录（授权失效等）下的书跳过，防止整架误删。
            val failedPrefixes = result.failedRoots.map { it.trimEnd('/') }
            val stale = Prefs.books.filter { b ->
                b.id !in foundIds &&
                        !b.uri.startsWith("file://") &&
                        failedPrefixes.none { b.uri.startsWith(it) }
            }
            if (stale.isNotEmpty()) {
                val staleIds = stale.map { it.id }.toSet()
                Prefs.pruneBooks(staleIds)
                deleteArtifacts(staleIds)
            }

            books = Prefs.books
            scanning = false
        }
    }

    fun addRootAndScan(uri: String) {
        Prefs.addRoot(uri)
        refresh()
    }

    /**
     * 批量移除书架图书。
     * @param deleteSource true 时先尝试删除源文件：成功的走自动清理（文件已不存在），
     *                    失败的（多为旧目录仅授予了读权限）仍移出书架并隐藏防复活
     */
    fun removeBooks(ids: Set<String>, deleteSource: Boolean, onResult: (String) -> Unit) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val pruned = mutableListOf<String>()   // 源文件已删 → 不进隐藏名单，文件回来可重新上架
            val removed = mutableListOf<String>()  // 常规移除 → 隐藏防扫描复活
            var srcDeleted = 0
            var srcFailed = 0
            for (id in ids) {
                val b = Prefs.find(id) ?: continue
                if (deleteSource) {
                    if (deleteSourceFile(b)) {
                        pruned += id
                        srcDeleted++
                    } else {
                        removed += id
                        srcFailed++
                    }
                } else {
                    removed += id
                }
            }
            Prefs.removeBooks(removed)
            Prefs.pruneBooks(pruned)
            deleteArtifacts(ids)
            books = Prefs.books
            val msg = buildString {
                append("已移除 ${ids.size} 本")
                if (deleteSource && srcDeleted > 0) append("，源文件已删 $srcDeleted 个")
                if (srcFailed > 0) append("，$srcFailed 个源文件删除失败（旧目录无写入权限，重新导入文件夹后即可删除）")
            }
            onResult(msg)
        }
    }

    /** 删除源文件：SAF 文档用 DocumentsContract，外部导入副本直接删文件 */
    private suspend fun deleteSourceFile(book: Book): Boolean = withContext(Dispatchers.IO) {
        val uri = android.net.Uri.parse(book.uri)
        if (uri.scheme == "file") {
            val f = File(uri.path ?: return@withContext false)
            f.exists() && f.delete()
        } else {
            try {
                android.provider.DocumentsContract.deleteDocument(
                    getApplication<Application>().contentResolver, uri)
            } catch (_: Exception) { false }
        }
    }

    /** 清理封面与预处理缓存 */
    private suspend fun deleteArtifacts(ids: Set<String>) = withContext(Dispatchers.IO) {
        val ctx = getApplication<Application>()
        val covers = File(ctx.cacheDir, "covers")
        ids.forEach { id ->
            covers.listFiles()?.forEach { if (it.name.startsWith("${id}_")) it.delete() }
            File(ctx.cacheDir, "extract/$id").deleteRecursively()
        }
    }

    /** 批量切换已看完标记：全部已看完 → 全部取消，否则标记为已看完 */
    fun toggleFinished(ids: Set<String>, onResult: (String) -> Unit) {
        if (ids.isEmpty()) return
        val allFinished = ids.all { Prefs.find(it)?.finished == true }
        ids.forEach { Prefs.setBookFinished(it, !allFinished) }
        books = Prefs.books
        onResult(
            if (allFinished) "已将 ${ids.size} 本标记为未观看"
            else "已将 ${ids.size} 本标记为已看完"
        )
    }

    fun setSort(key: BookSortKey, asc: Boolean) {
        sortKey = key; sortAsc = asc
        Prefs.bookSortKey = key; Prefs.bookSortAsc = asc
    }

    fun applyFilter(f: BookFilter) {
        filter = f
        Prefs.bookFilter = f
    }

    /** 轻量同步：阅读返回后仅重读元数据（不重新扫描磁盘） */
    fun resync() {
        books = Prefs.books
    }

    /** 书籍从阅读器返回后刷新进度显示 */
    fun syncBook(book: Book) {
        Prefs.find(book.id)?.let { fresh ->
            books = books.map { if (it.id == fresh.id) fresh else it }
        }
    }

    suspend fun cover(context: Context, book: Book): Bitmap? = withContext(Dispatchers.IO) {
        coverMem.get(book.id) ?: loadCoverSync(context, book)?.also { coverMem.put(book.id, it) }
    }

    private fun loadCoverSync(context: Context, book: Book): Bitmap? = runCatching {
        val file = coverFileOf(context, book)
        if (file.exists()) {
            BitmapFactory.decodeFile(file.absolutePath)?.takeIf { it.width > 0 }?.let { return@runCatching it }
        }
        val generated: Bitmap? = when (book.format) {
            ComicFormat.ZIP, ComicFormat.EPUB -> lightZipCover(context, book)
            ComicFormat.PDF -> lightPdfCover(context, book)
            ComicFormat.IMAGES -> lightImageCover(context, book)
            else -> null // RAR/7Z/MOBI 首次阅读时生成
        }
        if (generated != null) {
            runCatching {
                FileOutputStream(file).use { generated.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            }
        }
        generated
    }.getOrNull()

    private fun coverFileOf(context: Context, book: Book): File {
        val dir = File(context.cacheDir, "covers")
        dir.mkdirs()
        return File(dir, "${book.id}_${book.size}.jpg")
    }

    private fun lightZipCover(context: Context, book: Book): Bitmap? = runCatching {
        val uri = android.net.Uri.parse(book.uri)
        context.contentResolver.openInputStream(uri)?.let { raw ->
            val zis = java.util.zip.ZipInputStream(java.io.BufferedInputStream(raw, 256 * 1024))
            zis.use {
                var e = it.nextEntry
                while (e != null) {
                    val name = e.name.substringAfterLast('/').lowercase()
                    if (ComicFormat.IMAGES.exts.any { name.endsWith(it) }) {
                        val bytes = it.readBytes()
                        return@use decodeSmall(bytes)
                    }
                    e = it.nextEntry
                }
            }
            null
        }
    }.getOrNull()

    private fun lightImageCover(context: Context, book: Book): Bitmap? = runCatching {
        androidx.documentfile.provider.DocumentFile.fromTreeUri(context, android.net.Uri.parse(book.uri))
            ?.listFiles()
            ?.firstOrNull { it.isFile && ComicFormat.fromName(it.name) == ComicFormat.IMAGES }
            ?.let { f ->
                context.contentResolver.openInputStream(f.uri)?.use { decodeSmall(it.readBytes()) }
            }
    }.getOrNull()

    private fun lightPdfCover(context: Context, book: Book): Bitmap? = runCatching {
        val pfd = context.contentResolver.openFileDescriptor(android.net.Uri.parse(book.uri), "r") ?: return@runCatching null
        var bmp: Bitmap? = null
        try {
            val renderer = PdfRenderer(pfd)
            try {
                val page = renderer.openPage(0)
                try {
                    val k = 500f / maxOf(page.width, page.height)
                    val w = (page.width * k).toInt().coerceAtLeast(1)
                    val h = (page.height * k).toInt().coerceAtLeast(1)
                    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    b.eraseColor(android.graphics.Color.WHITE)
                    page.render(b, null, android.graphics.Matrix().apply { setScale(k, k) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp = b
                } finally { page.close() }
            } finally { renderer.close() }
        } finally { pfd.close() }
        bmp
    }.getOrNull()

    private fun decodeSmall(bytes: ByteArray): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (o.outWidth / (sample * 2) >= 250 && o.outHeight / (sample * 2) >= 250) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    /** 清理全部缓存（提取页/封面），返回释放的字节数 */
    suspend fun clearCaches(): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        val extract = File(getApplication<Application>().cacheDir, "extract")
        val covers = File(getApplication<Application>().cacheDir, "covers")
        listOf(extract, covers).forEach { dir ->
            dir.listFiles()?.forEach { f ->
                freed += f.length()
                f.delete()
            }
        }
        coverMem.evictAll()
        freed
    }
}
