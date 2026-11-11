package com.local.comicreader.parser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.net.Uri
import com.local.comicreader.data.ComicFormat
import com.local.comicreader.data.naturalCompare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * ZIP / CBZ 直读：不落地解压，流式按需读取。
 * 针对大体积压缩包优化：维护单个顺序流，顺序翻页只需从头扫一遍，
 * 预取相邻页几乎零开销；随机跳页才重新打开流。
 *
 * @param entryOverride EPUB 按阅读顺序传入条目名列表；null 时按文件名自然排序
 */
open class ZipBook(
    private val context: Context,
    private val uri: String,
    private val entryOverride: List<String>?
) : PageProvider {

    protected val entryNames: List<String> = scanEntries()
    override val pageCount: Int get() = entryNames.size

    private val ioMutex = Mutex()
    private var stream: ZipInputStream? = null
    private var currentName: String? = null    // 当前流定位到的条目名
    private var consumedCurrent = false       // 当前条目是否已被读取

    private fun openNewStream(): ZipInputStream {
        val input: InputStream = context.contentResolver.openInputStream(Uri.parse(uri))
            ?: throw IllegalStateException("无法打开文件流")
        return ZipInputStream(BufferedInputStream(input, 256 * 1024))
    }

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
     * 按条目名匹配读取（zip 内物理顺序可能与自然排序不同，不能按下标推进）。
     * 顺序翻页：复用当前流继续推进，几乎零开销；
     * 目标在物理顺序中已越过：重开流重扫。
     */
    private suspend fun bytesFor(index: Int): ByteArray? = ioMutex.withLock {
        if (index < 0 || index >= entryNames.size) return@withLock null
        val target = entryNames[index]
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
                if (cur != null && !consumedCurrent && cur == target) {
                    return@withLock readCurrent(s)
                }
                val next: ZipEntry? = s.nextEntry
                if (next == null) break
                currentName = next.name.replace('\\', '/')
                consumedCurrent = false
                if (currentName == target) {
                    return@withLock readCurrent(s)
                }
            }
            // 流耗尽仍未命中 → 重开
        } catch (_: Exception) {
            // 流损坏 → 重开
        }
        reopenAndRead(index)
    }

    private fun reopenAndRead(index: Int): ByteArray? {
        val target = entryNames.getOrNull(index) ?: return null
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
                if (n == target) {
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
