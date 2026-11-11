package com.local.comicreader.parser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import com.local.comicreader.data.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * PDF 漫画：系统自带 PdfRenderer 渲染（零第三方依赖、完全离线）。
 * 首次打开时把全部页面预渲染到私有缓存目录，之后翻页零延迟，
 * 适配大体积 PDF（渲染在 IO 线程执行、界面显示加载状态）。
 */
class PdfBook(
    private val context: Context,
    private val book: Book
) : PageProvider {

    private val mutex = Mutex()
    private val dir: File = ExtractDir.forBook(context, book)
    private val pageFiles: List<File>

    init {
        val stamp = "${book.uri}|${book.size}"
        val existing = dir.listFiles { f -> f.isFile && f.name != ".done" }?.toList() ?: emptyList()
        if (existing.isNotEmpty() && ExtractDir.isExtracted(dir, stamp)) {
            pageFiles = existing.sortedBy { it.nameWithoutExtension.toIntOrNull() ?: 0 }
        } else {
            existing.forEach { it.delete() }
            val pfd = context.contentResolver.openFileDescriptor(
                android.net.Uri.parse(book.uri), "r")
                ?: throw IllegalStateException("无法打开 PDF")
            val renderer = try { PdfRenderer(pfd) } catch (e: Exception) { pfd.close(); throw e }
            try {
                val pages = mutableListOf<File>()
                for (i in 0 until renderer.pageCount) {
                    val page = renderer.openPage(i)
                    try {
                        val scale = (2048f / maxOf(page.width, page.height)).coerceIn(1f, 4f)
                        val w = (page.width * scale).toInt().coerceAtLeast(1)
                        val h = (page.height * scale).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val f = File(dir, "%05d.png".format(i))
                        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bmp.recycle()
                        pages.add(f)
                    } finally {
                        page.close()
                    }
                }
                pageFiles = pages
                ExtractDir.markerOf(dir).writeText(stamp)
            } finally {
                renderer.close()
                pfd.close()
            }
        }
    }

    override val pageCount: Int get() = pageFiles.size

    override suspend fun sizeAt(index: Int): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        pageFiles.getOrNull(index)?.let { f ->
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, o)
            if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
        }
    }

    override suspend fun pageAt(index: Int, targetLong: Int): Bitmap? = mutex.withLock {
        pageFiles.getOrNull(index)?.let { ImgDecode.decodeFile(it, targetLong) }
    }

    override fun close() {}
}
