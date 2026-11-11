package com.local.comicreader.parser

import android.content.Context
import android.graphics.Bitmap
import com.local.comicreader.data.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.DataInputStream

/**
 * MOBI（实验性）：PDB 记录魔数扫描。
 * 漫画转换来的 MOBI 通常把整页图片作为独立记录存储，
 * 扫描记录头部的 JPEG/PNG/GIF/WebP 魔数即可按序提取。
 * 注意：AZW3(KF8) / 复杂排版 MOBI 可能提取不全。
 */
class MobiBook(
    private val context: Context,
    private val book: Book
) : PageProvider {

    private val records: List<ByteArray>

    init {
        val bytes = context.contentResolver.openInputStream(android.net.Uri.parse(book.uri))
            ?.use { it.readBytes() }
            ?: throw IllegalStateException("无法打开 MOBI 文件")
        records = extractImageRecords(bytes)
    }

    override val pageCount: Int get() = records.size

    private fun extractImageRecords(bytes: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        try {
            val din = DataInputStream(ByteArrayInputStream(bytes))
            val header = ByteArray(78)
            din.readFully(header)
            val numRecords = ((header[76].toInt() and 0xFF) shl 8) or (header[77].toInt() and 0xFF)
            val recs = IntArray(numRecords + 1)
            for (i in 0 until numRecords) {
                val off = din.readInt()
                din.skipBytes(4) // attributes(1) + uniqueId(3)
                recs[i] = off
            }
            recs[numRecords] = bytes.size
            for (i in 0 until numRecords) {
                val from = recs[i]
                val to = recs[i + 1]
                if (from < 0 || to <= from || to > bytes.size || to - from < 64) continue
                if (isImageMagic(bytes, from)) {
                    out.add(bytes.copyOfRange(from, to))
                }
            }
        } catch (_: Exception) { }
        return out
    }

    private fun isImageMagic(b: ByteArray, off: Int): Boolean {
        if (off + 12 > b.size) return false
        val b0 = b[off].toInt() and 0xFF
        val b1 = b[off + 1].toInt() and 0xFF
        val b2 = b[off + 2].toInt() and 0xFF
        val b3 = b[off + 3].toInt() and 0xFF
        // JPEG / PNG / GIF / BMP / WebP(RIFF....WEBP)
        return (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) ||
                (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) ||
                (b0 == 0x47 && b1 == 0x49 && b2 == 0x46 && b3 == 0x38) ||
                (b0 == 0x42 && b1 == 0x4D) ||
                (b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46 &&
                        b[off + 8].toInt() == 0x57 && b[off + 9].toInt() == 0x45)
    }

    override suspend fun sizeAt(index: Int): Pair<Int, Int>? =
        records.getOrNull(index)?.let { ImgDecode.boundsOf(it) }

    override suspend fun pageAt(index: Int, targetLong: Int): Bitmap? = withContext(Dispatchers.IO) {
        records.getOrNull(index)?.let { ImgDecode.decodeBytes(it, targetLong) }
    }

    override fun close() {}
}
