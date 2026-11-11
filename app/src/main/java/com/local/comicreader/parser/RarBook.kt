package com.local.comicreader.parser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import com.local.comicreader.data.Book
import com.local.comicreader.data.ComicFormat
import com.local.comicreader.data.naturalCompare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * RAR / CBR 解析（junrar 8.1.1，支持 RAR4 + RAR5）。
 * junrar 需要可随机访问的文件，因此首次打开时将压缩包中的图片
 * 一次性预处理到应用私有缓存目录（不占用户可见空间、可自动清理），
 * 之后的打开直接复用，速度与 ZIP 一致。
 */
class RarBook(
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
        // junrar 无法直接从 ContentResolver 的流随机访问，先拷到缓存
        val temp = File(context.cacheDir, "tmp_${book.id}.rar")
        try {
            java.io.BufferedInputStream(
                context.contentResolver.openInputStream(android.net.Uri.parse(book.uri))!!, 256 * 1024
            ).use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output, 256 * 1024) }
            }
            Archive(temp).use { archive ->
                for (fh in archive.fileHeaders) {
                    if (fh.isDirectory) continue
                    val name = fh.fileName.replace('\\', '/')
                        .substringAfterLast('/')
                    if (!ComicFormat.isImageExt(name.substringAfterLast('.', ""))) continue
                    val safe = File(dir, "${fh.hashCode()}_$name")
                    FileOutputStream(safe).use { archive.extractFile(fh, it) }
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
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, o)
            if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
        }
    }

    override suspend fun pageAt(index: Int, targetLong: Int): Bitmap? = withContext(Dispatchers.IO) {
        files.getOrNull(index)?.let { ImgDecode.decodeFile(it, targetLong) }
    }

    override fun close() {}
}
