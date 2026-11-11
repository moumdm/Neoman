package com.local.comicreader.data

import android.net.Uri

/** 阅读方向 */
enum class ReadDirection(val label: String) {
    RTL("从右到左（日漫）"),
    LTR("从左到右"),
    VERTICAL("从上到下（条漫）")
}

/** 单页 / 双页跨页 */
enum class PageLayout(val label: String) {
    SINGLE("单页"),
    DUAL("双页跨页")
}

/** 翻页效果 */
enum class PageEffect(val label: String) {
    SLIDE("平移滑动"),
    SIMULATION("仿真翻页"),
    FADE("平滑淡入"),
    NONE("直接切换")
}

/** 应用主题 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色")
}

/** 阅读器背景色 */
enum class ReadBackground(val label: String) {
    BLACK("纯黑"), GRAY("深灰"), WHITE("纯白"), SEPIA("米黄")
}

/** 支持的漫画格式 */
enum class ComicFormat(val exts: Set<String>, val label: String) {
    ZIP(setOf("cbz", "zip"), "ZIP/CBZ"),
    RAR(setOf("cbr", "rar"), "RAR/CBR"),
    SEVEN_Z(setOf("7z", "cb7"), "7Z"),
    EPUB(setOf("epub"), "EPUB"),
    PDF(setOf("pdf"), "PDF"),
    MOBI(setOf("mobi", "azw", "azw3", "prc"), "MOBI"),
    IMAGES(setOf("jpg", "jpeg", "png", "webp", "bmp", "gif"), "图片");

    companion object {
        fun fromName(name: String?): ComicFormat? {
            if (name == null) return null
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext.isEmpty()) return null
            return entries.firstOrNull { it.exts.contains(ext) }
        }

        /** 文件名不含后缀时按 MIME 兜底（外部打开常见） */
        fun fromMime(mime: String?): ComicFormat? = when (mime) {
            "application/zip", "application/x-zip-compressed" -> ZIP
            "application/x-rar-compressed", "application/vnd.rar" -> RAR
            "application/x-7z-compressed" -> SEVEN_Z
            "application/epub+zip" -> EPUB
            "application/pdf" -> PDF
            "application/x-mobipocket-ebook" -> MOBI
            else -> if (mime?.startsWith("image/") == true) IMAGES else null
        }
        fun isImageExt(ext: String): Boolean =
            IMAGES.exts.contains(ext.lowercase())
    }
}

/** 书架中的一部漫画 */
data class Book(
    val id: String,
    val uri: String,
    val title: String,
    val format: ComicFormat,
    val size: Long,
    val addedAt: Long,
    val lastReadAt: Long = 0L,
    val progressPage: Int = 0,
    val progressTotal: Int = 0,
    /** 每本书可单独记住的方向/布局/翻页效果，null 表示使用全局设置 */
    val direction: ReadDirection? = null,
    val layout: PageLayout? = null,
    val effect: PageEffect? = null,
    /** 手动标记的"已看完"分类 */
    val finished: Boolean = false
) {
    companion object {
        fun idOf(uri: String): String = (uri.hashCode().toLong() and 0xffffffffL).toString(16) +
                (uri.length.toString(16))
        fun titleFromName(name: String): String =
            name.substringBeforeLast('.', name).trim()
    }
}

/** 书架排序方式 */
enum class BookSortKey(val label: String) {
    ADDED("添加时间"),
    TITLE("首字母")
}

/** 书架筛选分类 */
enum class BookFilter(val label: String) {
    ALL("全部"),
    UNREAD("未观看"),
    FINISHED("已看完")
}

/** 书签 */
data class Bookmark(
    val page: Int,
    val note: String,
    val time: Long
)

/** 全局阅读设置 */
data class ReaderSettings(
    val direction: ReadDirection = ReadDirection.RTL,
    val layout: PageLayout = PageLayout.SINGLE,
    val pageEffect: PageEffect = PageEffect.SLIDE,
    val autoSplit: Boolean = false,
    val cropBorders: Boolean = false,
    val rotation: Int = 0,
    val volumeKeyPaging: Boolean = true,
    val keepScreenOn: Boolean = true,
    val autoPlaySec: Int = 0,          // 自动翻页间隔秒数，0=关闭
    val autoScrollSpeed: Int = 0,      // 自动滚动速度（px/s），0=关闭，仅条漫
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val eyeCare: Boolean = false,
    val useCustomBrightness: Boolean = false,
    val brightness: Float = 0.5f,      // 0.02~1.0
    val background: ReadBackground = ReadBackground.BLACK
) {
    /** 影响位图管线的设置指纹，变化时需要清缓存 */
    val pipelineHash: Int
        get() = listOf(autoSplit, cropBorders, rotation).hashCode()
}

/** 阅读页切片：源页号 + 拆页后的半页 */
enum class PagePart { FULL, FIRST, SECOND }

data class PageSpec(val srcIndex: Int, val part: PagePart = PagePart.FULL)

fun String.toUriSafe(): Uri = Uri.parse(this)
