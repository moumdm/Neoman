package com.local.comicreader.parser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.local.comicreader.data.Book
import com.local.comicreader.data.ComicFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * 漫画页提供者：为阅读器提供按序编号的页面位图。
 * 所有实现均为纯本地解析，不联网、不解包到用户可见目录。
 */
interface PageProvider {
    val pageCount: Int

    /** 本书是长图条漫（单张长图切片），应自动使用条漫方向阅读 */
    val isWebtoon: Boolean get() = false

    /** 第 index 页的原始像素尺寸（用于自动拆页判断） */
    suspend fun sizeAt(index: Int): Pair<Int, Int>?

    /** 解码第 index 页，targetLong 为期望的长边像素上限 */
    suspend fun pageAt(index: Int, targetLong: Int): Bitmap?

    fun close()
}

/** 按格式分发打开器 */
object ComicOpener {

    suspend fun open(context: Context, book: Book): PageProvider = withContext(Dispatchers.IO) {
        when (book.format) {
            ComicFormat.ZIP -> ZipBook(context, book.uri, null)
            ComicFormat.EPUB -> EpubBook(context, book)
            ComicFormat.RAR -> RarBook(context, book)
            ComicFormat.SEVEN_Z -> SevenZBook(context, book)
            ComicFormat.PDF -> PdfBook(context, book)
            ComicFormat.MOBI -> MobiBook(context, book)
            ComicFormat.IMAGES -> openImageSource(context, book)
        }
    }

    private suspend fun openImageSource(context: Context, book: Book): PageProvider {
        val uri = android.net.Uri.parse(book.uri)
        val last = uri.lastPathSegment ?: ""
        return if (ComicFormat.fromName(last) == ComicFormat.IMAGES) {
            SingleImageBook(context, book.uri)
        } else {
            FolderBook(context, book.uri)
        }
    }
}

/** 解码工具：按目标尺寸采样，避免大图 OOM */
object ImgDecode {

    /** 目标长边：屏幕长边 2 倍，夹在 2048~4096，兼顾 4 倍缩放的清晰度 */
    fun targetLongSide(context: Context): Int {
        val dm = context.resources.displayMetrics
        val longest = max(dm.widthPixels, dm.heightPixels)
        return (longest * 2).coerceIn(2048, 4096)
    }

    fun decodeBytes(bytes: ByteArray, targetLong: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetLong)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    fun decodeFile(file: File, targetLong: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetLong)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    fun boundsOf(bytes: ByteArray): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }

    private fun sampleSize(w: Int, h: Int, target: Int): Int {
        var sample = 1
        val half = target / 2
        while (max(w, h) / (sample * 2) >= half && sample < 64) sample *= 2
        return sample
    }
}

/** RAR / 7Z 一次性预处理的工作目录 */
object ExtractDir {
    fun forBook(context: Context, book: Book): File {
        val dir = File(context.cacheDir, "extract/${book.id}")
        dir.mkdirs()
        return dir
    }

    fun markerOf(dir: File): File = File(dir, ".done")

    fun isExtracted(dir: File, stamp: String): Boolean =
        markerOf(dir).takeIf { it.exists() }?.readText() == stamp
}

/** 全局页位图缓存（跨页滑动/预取复用，按字节计容量） */
class BitmapPageCache(maxBytes: Long) {
    private val cache = object : LruCache<String, Bitmap>(maxBytes.toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val inFlight = HashMap<String, Mutex>()
    private val mapMutex = Mutex()

    fun get(key: String): Bitmap? = synchronized(this) { cache.get(key) }

    fun put(key: String, bmp: Bitmap) = synchronized(this) { cache.put(key, bmp) }

    fun clear() = synchronized(this) { cache.evictAll() }

    /** 同一 key 并发只加载一次 */
    suspend fun load(key: String, loader: suspend () -> Bitmap?): Bitmap? {
        val m = mapMutex.withLock { inFlight.getOrPut(key) { Mutex() } }
        return m.withLock {
            get(key) ?: loader()?.also { put(key, it) }
        }.also { mapMutex.withLock { inFlight.remove(key) } }
    }
}
