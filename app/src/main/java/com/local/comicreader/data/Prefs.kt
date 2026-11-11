package com.local.comicreader.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 基于 SharedPreferences + JSON 的轻量持久化：
 * 书库根目录、书籍元数据、阅读进度、书签、全局设置。
 * 全部本地存储，无任何联网行为。
 */
object Prefs {

    private lateinit var sp: SharedPreferences
    private lateinit var appContext: Context
    private val bookMap = LinkedHashMap<String, Book>()

    fun init(context: Context) {
        if (::sp.isInitialized) return
        appContext = context.applicationContext
        sp = appContext
            .getSharedPreferences("newman_prefs", Context.MODE_PRIVATE)
        loadBooks()
    }

    // ---------------- 书库根目录 ----------------

    fun roots(): List<String> = sp.getStringSet("roots", emptySet())?.toList() ?: emptyList()

    fun addRoot(uri: String) {
        val set = roots().toMutableSet()
        set.add(uri)
        sp.edit().putStringSet("roots", set).apply()
    }

    fun removeRoot(uri: String) {
        val set = roots().toMutableSet()
        set.remove(uri)
        sp.edit().putStringSet("roots", set).apply()
    }

    // ---------------- 书籍 ----------------

    val books: List<Book> get() = bookMap.values.toList()

    private fun loadBooks() {
        val raw = sp.getString("books", null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val b = Book(
                    id = o.getString("id"),
                    uri = o.getString("uri"),
                    title = o.getString("title"),
                    format = ComicFormat.valueOf(o.getString("format")),
                    size = o.optLong("size"),
                    addedAt = o.optLong("addedAt"),
                    lastReadAt = o.optLong("lastReadAt"),
                    progressPage = o.optInt("page"),
                    progressTotal = o.optInt("total"),
                    direction = o.optString("dir").takeIf { it.isNotEmpty() }?.let { runCatching { ReadDirection.valueOf(it) }.getOrNull() },
                    layout = o.optString("layout").takeIf { it.isNotEmpty() }?.let { runCatching { PageLayout.valueOf(it) }.getOrNull() },
                    effect = o.optString("effect").takeIf { it.isNotEmpty() }?.let { runCatching { PageEffect.valueOf(it) }.getOrNull() },
                    finished = o.optBoolean("finished", false)
                )
                bookMap[b.id] = b
            }
        } catch (_: Exception) { }
    }

    private fun persistBooks() {
        val arr = JSONArray()
        bookMap.values.sortedBy { it.lastReadAt }.forEach { b ->
            arr.put(JSONObject().apply {
                put("id", b.id); put("uri", b.uri); put("title", b.title)
                put("format", b.format.name); put("size", b.size); put("addedAt", b.addedAt)
                put("lastReadAt", b.lastReadAt); put("page", b.progressPage); put("total", b.progressTotal)
                put("dir", b.direction?.name ?: ""); put("layout", b.layout?.name ?: "")
                put("effect", b.effect?.name ?: "")
                put("finished", b.finished)
            })
        }
        sp.edit().putString("books", arr.toString()).apply()
    }

    fun upsert(book: Book) {
        bookMap[book.id] = book
        persistBooks()
    }

    fun updateBook(id: String, transform: (Book) -> Book) {
        val b = bookMap[id] ?: return
        bookMap[id] = transform(b)
        persistBooks()
    }

    // ---------------- 移除 / 隐藏 ----------------
    // 书架"移除"必须同时记入隐藏名单：否则文件夹里文件还在，
    // 下次扫描会重新出现在书架（表现为"移除后重启又复活"）。

    private fun hiddenIds(): Set<String> = sp.getStringSet("hidden_books", emptySet()) ?: emptySet()

    fun isHidden(id: String): Boolean = id in hiddenIds()

    fun removeBook(id: String) = removeBooks(listOf(id))

    /** 批量移除：全部写入隐藏名单（防扫描复活）；外部导入的落地副本一并物理删除 */
    fun removeBooks(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val set = hiddenIds().toMutableSet()
        ids.forEach { id ->
            val book = bookMap[id]
            set.add(id)
            // 外部导入（已复制到私有目录）的书：物理文件一并清理
            if (book != null && book.uri.startsWith("file://")) {
                runCatching {
                    val f = java.io.File(android.net.Uri.parse(book.uri).path ?: "")
                    if (f.absolutePath.contains("/import/")) f.delete()
                }
            }
            bookMap.remove(id)
        }
        sp.edit().putStringSet("hidden_books", set).apply()
        val edit = sp.edit()
        ids.forEach { edit.remove(bmKey(it)) }
        edit.apply()
        persistBooks()
    }

    /**
     * 自动清理（源文件已删除 / 用户已删源文件）：移除记录与书签，
     * 不写入隐藏名单 —— 文件将来恢复后可重新自动上架。
     */
    fun pruneBooks(ids: Collection<String>) {
        var changed = false
        val edit = sp.edit()
        ids.forEach { id ->
            if (bookMap.remove(id) != null) {
                edit.remove(bmKey(id))
                changed = true
            }
        }
        edit.apply()
        if (changed) persistBooks()
    }

    fun find(id: String): Book? = bookMap[id]

    // ---------------- 阅读进度 ----------------

    fun saveProgress(id: String, page: Int, total: Int) {
        updateBook(id) { it.copy(progressPage = page, progressTotal = total, lastReadAt = System.currentTimeMillis()) }
    }

    fun setBookDirection(id: String, dir: ReadDirection?) =
        updateBook(id) { it.copy(direction = dir) }

    fun setBookLayout(id: String, layout: PageLayout?) =
        updateBook(id) { it.copy(layout = layout) }

    fun setBookEffect(id: String, effect: PageEffect?) =
        updateBook(id) { it.copy(effect = effect) }

    fun setBookFinished(id: String, finished: Boolean) =
        updateBook(id) { it.copy(finished = finished) }

    // ---------------- 书架排序 / 筛选 ----------------

    var bookSortKey: BookSortKey
        get() = enumPref("bookSortKey", BookSortKey.ADDED)
        set(v) = sp.edit().putString("bookSortKey", v.name).apply()

    var bookSortAsc: Boolean
        get() = sp.getBoolean("bookSortAsc", true)
        set(v) = sp.edit().putBoolean("bookSortAsc", v).apply()

    var bookFilter: BookFilter
        get() = enumPref("bookFilter", BookFilter.ALL)
        set(v) = sp.edit().putString("bookFilter", v.name).apply()

    // ---------------- 书签 ----------------

    private fun bmKey(id: String) = "bm_$id"

    fun bookmarks(id: String): List<Bookmark> {
        val raw = sp.getString(bmKey(id), null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Bookmark(o.getInt("p"), o.optString("n", ""), o.optLong("t"))
            }
        } catch (_: Exception) { emptyList() }
    }

    fun addBookmark(id: String, bookmark: Bookmark) {
        val list = bookmarks(id).toMutableList()
        list.removeAll { it.page == bookmark.page }
        list.add(bookmark)
        writeBookmarks(id, list)
    }

    fun removeBookmark(id: String, page: Int) {
        writeBookmarks(id, bookmarks(id).filter { it.page != page })
    }

    private fun writeBookmarks(id: String, list: List<Bookmark>) {
        val arr = JSONArray()
        list.sortedBy { it.page }.forEach { arr.put(JSONObject().apply {
            put("p", it.page); put("n", it.note); put("t", it.time) }) }
        sp.edit().putString(bmKey(id), arr.toString()).apply()
    }

    // ---------------- 全局设置 ----------------

    var settings: ReaderSettings
        get() = ReaderSettings(
            direction = enumPref("dir", ReaderSettings().direction),
            layout = enumPref("layout", ReaderSettings().layout),
            pageEffect = enumPref("effect", ReaderSettings().pageEffect),
            autoSplit = sp.getBoolean("autoSplit", false),
            cropBorders = sp.getBoolean("cropBorders", false),
            rotation = sp.getInt("rotation", 0),
            volumeKeyPaging = sp.getBoolean("volumeKeys", true),
            keepScreenOn = sp.getBoolean("keepOn", true),
            autoPlaySec = sp.getInt("autoPlay", 0),
            autoScrollSpeed = sp.getInt("autoScroll", 0),
            themeMode = enumPref("theme", ReaderSettings().themeMode),
            eyeCare = sp.getBoolean("eyeCare", false),
            useCustomBrightness = sp.getBoolean("useBrightness", false),
            brightness = sp.getFloat("brightnessVal", 0.5f),
            background = enumPref("bg", ReaderSettings().background)
        )
        set(value) = sp.edit().apply {
            putString("dir", value.direction.name)
            putString("layout", value.layout.name)
            putString("effect", value.pageEffect.name)
            putBoolean("autoSplit", value.autoSplit)
            putBoolean("cropBorders", value.cropBorders)
            putInt("rotation", value.rotation)
            putBoolean("volumeKeys", value.volumeKeyPaging)
            putBoolean("keepOn", value.keepScreenOn)
            putInt("autoPlay", value.autoPlaySec)
            putInt("autoScroll", value.autoScrollSpeed)
            putString("theme", value.themeMode.name)
            putBoolean("eyeCare", value.eyeCare)
            putBoolean("useBrightness", value.useCustomBrightness)
            putFloat("brightnessVal", value.brightness)
            putString("bg", value.background.name)
        }.apply()

    private inline fun <reified T : Enum<T>> enumPref(key: String, def: T): T =
        sp.getString(key, null)?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: def
}
