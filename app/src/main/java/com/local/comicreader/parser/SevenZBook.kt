package com.local.comicreader.parser

import android.content.Context
import android.graphics.Bitmap
import com.local.comicreader.data.Book
import com.local.comicreader.data.ComicFormat
import com.local.comicreader.data.naturalCompare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.FileOutputStream
import android.graphics.BitmapFactory
/**
 * 7Z / CB7 解析（commons-compress）。
 * 与 RAR 相同策略：首次打开将图片条目流式展开到私有缓存目录，
 * 之后复用。大文件预处理在后台线程执行并显示加载进度状态。
 */
class SevenZBook(
    private val context: Context,
    private val book: Book
) : PageProvider {

    private val dir: File = ExtractDir.forBook(context, book)
    private val files: List<File> = ensureExtracted()

    override val pageCount: Int get() = files.size

    private fun ensureExtracted(): List<File> {
        val stamp = "${book.uri}|${book.size}"
        val existing = dir.listFiles { f -> f.isFile && f.name != ".done" }?.toList() ?: emptyList()
        if (existing.isNotEmpty() && ExtractDir.isExtracted(dir, stamp)) {
            return existing.sortedWith { a, b -> naturalCompare(a.name, b.name) }
        }
        existing.forEach { it.delete() }
        val temp = File(context.cacheDir, "tmp_${book.id}.7z")
        try {
            java.io.BufferedInputStream(
                context.contentResolver.openInputStream(android.net.Uri.parse(book.uri))!!, 256 * 1024
            ).use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output, 256 * 1024) }
            }
            SevenZFile(temp).use { szf ->
                var entry: SevenZArchiveEntry? = szf.nextEntry
                while (entry != null) {
                    val e = entry!!
                    if (!e.isDirectory) {
                        val name = e.name.replace('\\', '/').substringAfterLast('/')
                        if (ComicFormat.isImageExt(name.substringAfterLast('.', "")) && e.hasStream()) {
                            val safe = File(dir, name.takeLast(80).replace(Regex("[^\\w.\\-()]"), "_"))
                            val bytes = ByteArray(e.size.coerceAtMost(400L * 1024 * 1024).toInt())
                            var off = 0
                            while (off < bytes.size) {
                                val r = szf.read(bytes, off, bytes.size - off)
                                if (r < 0) break
                                off += r
                            }
                            FileOutputStream(safe).use { it.write(bytes, 0, off) }
                        }
                    }
                    entry = szf.nextEntry
                }
            }
            ExtractDir.markerOf(dir).writeText(stamp)
        } finally {
            temp.delete()
        }
        return (dir.listFiles { f -> f.isFile && f.name != ".done" }?.toList() ?: emptyList())
            .sortedWith { a, b -> naturalCompare(a.name, b.name) }
    }

    override suspend fun sizeAt(index: Int): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        files.getOrNull(index)?.let { f ->
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, o)
            if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
        }
    }

    override suspend fun pageAt(index: Int, targetLong: Int): Bitmap? = withContext(Dispatchers.IO) {
        files.getOrNull(index)?.let { ImgDecode.decodeFile(it, targetLong) }
    }

    override fun close() {}
}
