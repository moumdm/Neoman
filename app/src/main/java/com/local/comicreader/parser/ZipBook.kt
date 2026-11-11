package com.local.comicreader.parser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.local.comicreader.data.ComicFormat
import com.local.comicreader.data.naturalCompare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * ZIP / CBZ 直读：不落地解压，按需读取。
 *
 * 随机访问模式（默认）：解析 zip 中央目录拿到每个条目的数据偏移，
 * 打开书籍无需顺序解压扫描整包，跳页 / 回翻任意页 O(1) 直接定位；
 * 数据源不可 seek 或解析失败时自动回退为顺序流模式。
 *
 * @param entryOverride EPUB 按阅读顺序传入条目名列表；null 时按文件名自然排序
 */
open class ZipBook(
    private val context: Context,
    private val uri: String,
    private val entryOverride: List<String>?
) : PageProvider {

    /** 中央目录条目：method 0=存储 8=deflate */
    private class Entry(
        val name: String,
        val method: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val dataOffset: Long
    )

    private var channel: FileChannel? = null
    private var pfd: ParcelFileDescriptor? = null
    private val randomByName: Map<String, Entry>?

    protected val entryNames: List<String>
    override val pageCount: Int get() = entryNames.size

    // ---- 顺序流回退模式的状态 ----
    private val ioMutex = Mutex()
    private var stream: ZipInputStream? = null
    private var currentName: String? = null
    private var consumedCurrent = false

    init {
        val cd = tryParseCentralDirectory()
        randomByName = cd?.associateBy { it.name }
        entryNames = if (cd != null) namesFromDirectory(cd) else scanEntries()
    }

    /** 由中央目录构建阅读顺序（EPUB 按 spine；普通包按文件名自然排序） */
    private fun namesFromDirectory(cd: List<Entry>): List<String> =
        if (entryOverride != null) {
            entryOverride.filter { o -> cd.any { it.name == o } }
        } else {
            cd.asSequence()
                .map { it.name }
                .filter { ComicFormat.isImageExt(it.substringAfterLast('.', "")) }
                .sortedWith(::naturalCompare)
                .toList()
        }

    /** 解析 zip 中央目录：末尾找 EOCD → 读 CD 条目 → 实读本地头定位数据区 */
    private fun tryParseCentralDirectory(): List<Entry>? {
        val p = try {
            context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")
        } catch (_: Exception) { null } ?: return null
        var ch: FileChannel? = null
        try {
            val c = FileInputStream(p.fileDescriptor).channel
            ch = c
            val entries = parseDirectory(c) ?: return null
            channel = c        // 成功：保留 channel 与 pfd 供随机读取
            pfd = p
            return entries
        } catch (_: Exception) {
            try { ch?.close() } catch (_: Exception) {}
            try { p.close() } catch (_: Exception) {}
            return null
        }
    }

    private fun parseDirectory(ch: FileChannel): List<Entry>? {
        val size = ch.size()
        if (size < 22) return null
        val tailLen = minOf(size, 66_022L).toInt()
        val tail = ByteBuffer.allocate(tailLen)
        var got = 0
        while (got < tailLen) {
            val r = ch.read(tail, size - tailLen + got)
            if (r < 0) return null
            got += r
        }
        val arr = tail.array()
        var eocd = -1
        var i = arr.size - 22
        while (i >= 0) {
            if (arr[i] == 0x50.toByte() && arr[i + 1] == 0x4B.toByte() &&
                arr[i + 2] == 0x05.toByte() && arr[i + 3] == 0x06.toByte()) { eocd = i; break }
            i--
        }
        if (eocd < 0) return null
        val eb = ByteBuffer.wrap(arr).order(ByteOrder.LITTLE_ENDIAN)
        eb.position(eocd + 4)
        if ((eb.short.toInt() and 0xFFFF) != 0) return null   // 多分卷不支持
        eb.position(eocd + 10)
        val totalEntries = eb.short.toInt() and 0xFFFF
        val cdSize = eb.int.toLong() and 0xFFFFFFFFL
        val cdOffset = eb.int.toLong() and 0xFFFFFFFFL
        if (cdOffset <= 0 || cdSize <= 0 || cdOffset == 0xFFFFFFFFL || cdSize > 512L * 1024 * 1024)
            return null

        val cd = ByteBuffer.allocate(cdSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        var read = 0L
        while (read < cdSize) {
            val r = ch.read(cd, cdOffset + read)
            if (r < 0) return null
            read += r
        }
        cd.flip()
        val entries = mutableListOf<Entry>()
        repeat(totalEntries) {
            if (cd.remaining() < 46) return null
            if (cd.int != 0x02014b50) return null
            cd.short; cd.short               // made by / needed
            cd.short                         // flags
            val method = cd.short.toInt() and 0xFFFF
            cd.short; cd.short               // time / date
            cd.int                           // crc
            val compSize = cd.int.toLong() and 0xFFFFFFFFL
            val uncompSize = cd.int.toLong() and 0xFFFFFFFFL
            val nameLen = cd.short.toInt() and 0xFFFF
            val extraLen = cd.short.toInt() and 0xFFFF
            val commentLen = cd.short.toInt() and 0xFFFF
            cd.short; cd.short; cd.int       // disk start / int attr / ext attr
            val localOffset = cd.int.toLong() and 0xFFFFFFFFL
            val nameBytes = ByteArray(nameLen)
            cd.get(nameBytes)
            val name = String(nameBytes, Charsets.UTF_8).replace('\\', '/')
            cd.position(cd.position() + extraLen + commentLen)
            if (compSize <= 0 || compSize > 512L * 1024 * 1024) return@repeat
            // 本地头的 name/extra 长度可能与中央目录不同，必须实读本地头
            val lh = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
            var got2 = 0
            while (got2 < 30) {
                val r = ch.read(lh, localOffset + got2)
                if (r < 0) return null
                got2 += r
            }
            lh.flip()
            if (lh.int != 0x04034b50) return null
            lh.position(26)
            val lhNameLen = lh.short.toInt() and 0xFFFF
            val lhExtraLen = lh.short.toInt() and 0xFFFF
            entries.add(Entry(name, method, compSize, uncompSize, localOffset + 30 + lhNameLen + lhExtraLen))
        }
        if (entries.isEmpty()) return null
        if (entryOverride != null) {
            // spine 条目未全部命中（编码异常等）→ 回退顺序流，保证 EPUB 正确性
            val names = entries.map { it.name }.toHashSet()
            if (entryOverride.any { it !in names }) return null
        }
        return entries
    }

    /** 定位并读取单个条目（随机访问模式，channel 上互斥） */
    private fun readEntry(e: Entry): ByteArray? {
        return try {
            val ch = channel ?: return null
            val raw = ByteArray(e.compressedSize.toInt())
            val bb = ByteBuffer.wrap(raw)
            var off = 0L
            while (off < e.compressedSize) {
                val r = ch.read(bb, e.dataOffset + off)
                if (r < 0) return null
                off += r
            }
            when (e.method) {
                0 -> raw
                8 -> {
                    val inf = Inflater(true)
                    inf.setInput(raw)
                    val out = ByteArray(e.uncompressedSize.toInt().coerceAtLeast(1))
                    var filled = 0
                    while (filled < out.size && !inf.finished()) {
                        val n = inf.inflate(out, filled, out.size - filled)
                        if (n == 0) {
                            if (inf.needsInput() || inf.needsDictionary()) break
                        } else filled += n
                    }
                    inf.end()
                    if (filled == out.size) out else out.copyOf(filled)
                }
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private fun openNewStream(): ZipInputStream {
        val input: InputStream = context.contentResolver.openInputStream(Uri.parse(uri))
            ?: throw IllegalStateException("无法打开文件流")
        return ZipInputStream(BufferedInputStream(input, 256 * 1024))
    }

    /** 顺序流模式：解压扫描全包列出条目 */
    private fun scanEntries(): List<String> {
        val names = mutableListOf<String>()
        openNewStream().use { zis ->
            var e: ZipEntry? = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    val n = e.name.replace('\\', '/')
                    if (entryOverride != null) {
                        if (entryOverride.contains(n)) names.add(n)
                    } else if (ComicFormat.isImageExt(n.substringAfterLast('.', ""))) {
                        names.add(n)
                    }
                }
                e = zis.nextEntry
            }
        }
        // EPUB 传入的条目已按 spine 顺序排好；普通压缩包按文件名自然排序
        return if (entryOverride != null) entryOverride else names.sortedWith(::naturalCompare)
    }

    /**
     * 按条目名读取。随机访问模式直接定位数据区（O(1)）；
     * 顺序流模式：顺序翻页复用当前流推进，目标已越过则重开流重扫。
     */
    private suspend fun bytesFor(index: Int): ByteArray? = ioMutex.withLock {
        if (index < 0 || index >= entryNames.size) return@withLock null
        val name = entryNames[index]
        val entry = randomByName?.get(name)
        if (entry != null) return@withLock readEntry(entry)
        legacyBytesFor(name, index)
    }

    private suspend fun legacyBytesFor(name: String, index: Int): ByteArray? {
        try {
            var s = stream
            if (s == null) {
                s = openNewStream()
                stream = s
                currentName = null
                consumedCurrent = false
            }
            while (true) {
                val cur = currentName
                if (cur != null && !consumedCurrent && cur == name) {
                    return readCurrent(s)
                }
                val next: ZipEntry? = s.nextEntry
                if (next == null) break
                currentName = next.name.replace('\\', '/')
                consumedCurrent = false
                if (currentName == name) {
                    return readCurrent(s)
                }
            }
            // 流耗尽仍未命中 → 重开
        } catch (_: Exception) {
            // 流损坏 → 重开
        }
        return reopenAndRead(name)
    }

    private fun reopenAndRead(name: String): ByteArray? {
        try { stream?.close() } catch (_: Exception) {}
        stream = null
        currentName = null
        consumedCurrent = false
        val s = openNewStream()
        stream = s
        try {
            var e: ZipEntry? = s.nextEntry
            while (e != null) {
                val n = e.name.replace('\\', '/')
                if (n == name) {
                    currentName = n
                    return readCurrent(s)
                }
                e = s.nextEntry
            }
        } catch (_: Exception) {}
        return null
    }

    private fun readCurrent(s: ZipInputStream): ByteArray? {
        val bos = ByteArrayOutputStream(1 shl 20)
        val buf = ByteArray(64 * 1024)
        var r: Int
        while (true) {
            r = s.read(buf)
            if (r < 0) break
            bos.write(buf, 0, r)
        }
        s.closeEntry()
        consumedCurrent = true
        return if (bos.size() > 0) bos.toByteArray() else null
    }

    override suspend fun sizeAt(index: Int): Pair<Int, Int>? =
        bytesFor(index)?.let { ImgDecode.boundsOf(it) }

    override suspend fun pageAt(index: Int, targetLong: Int): Bitmap? =
        bytesFor(index)?.let { ImgDecode.decodeBytes(it, targetLong) }

    override fun close() {
        try { stream?.close() } catch (_: Exception) {}
        stream = null
        try { channel?.close() } catch (_: Exception) {}
        channel = null
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
    }
}

/** 单张图片文件（从文件管理器直接打开）。
 *  长图（高/宽 > 1.4）自动按屏幕比例切成多段虚拟页：
 *  每段约一屏高、宽度与原图一致，分段解码（BitmapRegionDecoder），
 *  条漫式阅读适屏宽且内存可控；普通图片仍为单页。 */
class SingleImageBook(
    private val context: Context,
    private val uri: String
) : PageProvider {

    private val ioMutex = Mutex()
    private var decoder: BitmapRegionDecoder? = null
    private var srcW = 0
    private var srcH = 0
    private var segH = 0

    /** 高宽比超过该值判定为长图（条漫切片） */
    private val longImage: Boolean

    override val isWebtoon: Boolean get() = longImage

    init {
        val bounds = probeBounds()
        srcW = bounds?.first ?: 0
        srcH = bounds?.second ?: 0
        longImage = srcW > 0 && srcH > 0 && srcH.toFloat() > srcW * 1.4f
        if (longImage) {
            val dm = context.resources.displayMetrics
            val screenRatio = if (dm.widthPixels > 0)
                dm.heightPixels.toFloat() / dm.widthPixels else 1.5f
            segH = (srcW * screenRatio).toInt().coerceAtLeast(1024)
        }
    }

    override val pageCount: Int
        get() = if (longImage) ((srcH + segH - 1) / segH).coerceAtLeast(1) else 1

    private fun probeBounds(): Pair<Int, Int>? = try {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { s ->
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(s, null, o)
            if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
        }
    } catch (_: Exception) { null }

    /** 第 index 段的真实像素高度（末段可能不足整段） */
    private fun segHeightAt(index: Int): Int {
        if (!longImage || index < 0) return srcH
        val start = index * segH
        return (srcH - start).coerceAtMost(segH).coerceAtLeast(0)
    }

    @Synchronized
    private fun decoderOrNull(): BitmapRegionDecoder? {
        if (decoder == null) {
            decoder = try {
                context.contentResolver.openInputStream(Uri.parse(uri))?.use { s ->
                    BitmapRegionDecoder.newInstance(s, false)
                }
            } catch (_: Exception) { null }
        }
        return decoder
    }

    private suspend fun bytes(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
        } catch (_: Exception) { null }
    }

    override suspend fun sizeAt(index: Int): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        if (!longImage) bytes()?.let { ImgDecode.boundsOf(it) }
        else {
            val h = segHeightAt(index)
            if (srcW > 0 && h > 0) srcW to h else null
        }
    }

    override suspend fun pageAt(index: Int, targetLong: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (!longImage) bytes()?.let { ImgDecode.decodeBytes(it, targetLong) }
        else ioMutex.withLock {
            val d = decoderOrNull() ?: return@withLock null
            val h = segHeightAt(index)
            if (h <= 0) return@withLock null
            // 采样策略：解码宽度不低于屏幕宽（保证适屏清晰），
            // 且单段像素数不超过 1600 万（防 OOM）
            val screenW = context.resources.displayMetrics.widthPixels.coerceAtLeast(720)
            var sample = 1
            while (srcW / (sample * 2) >= screenW) sample *= 2
            while ((srcW / sample).toLong() * (h / sample) > 16_000_000L) sample *= 2
            val rect = android.graphics.Rect(0, index * segH, srcW, index * segH + h)
            try {
                d.decodeRegion(rect, BitmapFactory.Options().apply { inSampleSize = sample })
            } catch (_: Exception) { null }
        }
    }

    override fun close() {
        try { decoder?.recycle() } catch (_: Exception) {}
        decoder = null
    }
}

/** 图片文件夹漫画 */
class FolderBook(
    private val context: Context,
    private val uri: String
) : PageProvider {

    private val children: List<androidx.documentfile.provider.DocumentFile> =
        runCatching {
            androidx.documentfile.provider.DocumentFile.fromTreeUri(context, Uri.parse(uri))
                ?.listFiles()
                ?.filter { it.isFile && ComicFormat.fromName(it.name) == ComicFormat.IMAGES }
                ?.sortedWith(compareBy({ it.name ?: "" }, { naturalCompare(it.name ?: "", "") }))
        }.getOrNull() ?: emptyList()

    override val pageCount: Int get() = children.size

    private suspend fun bytesAt(index: Int): ByteArray? = withContext(Dispatchers.IO) {
        try {
            children.getOrNull(index)?.let {
                context.contentResolver.openInputStream(it.uri)?.use { s -> s.readBytes() }
            }
        } catch (_: Exception) { null }
    }

    override suspend fun sizeAt(index: Int): Pair<Int, Int>? =
        bytesAt(index)?.let { ImgDecode.boundsOf(it) }

    override suspend fun pageAt(index: Int, targetLong: Int): Bitmap? =
        bytesAt(index)?.let { ImgDecode.decodeBytes(it, targetLong) }

    override fun close() {}
}
