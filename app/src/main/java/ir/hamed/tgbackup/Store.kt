package ir.hamed.tgbackup

import android.content.Context
import java.io.File

/** Settings, upload records and stats. Media key = "i:<id>" or "v:<id>". */
class Store(ctx: Context) {
    val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    private val uploadedFile = File(ctx.filesDir, "uploaded.txt")
    private val skippedFile = File(ctx.filesDir, "skipped.txt")
    val queueFile = File(ctx.filesDir, "queue.txt")
    private val historyFile = File(ctx.filesDir, "history.txt")

    private fun s(k: String, d: String = "") = p.getString(k, d)!!
    private fun b(k: String, d: Boolean) = p.getBoolean(k, d)
    private fun put(k: String, v: String) = p.edit().putString(k, v).apply()
    private fun put(k: String, v: Boolean) = p.edit().putBoolean(k, v).apply()

    var token: String
        get() = s("token")
        set(v) { put("token", v.trim()) }
    var chat: String
        get() = s("chat")
        set(v) { put("chat", v.trim()) }
    var apiRaw: String
        get() = s("api")
        set(v) { put("api", v.trim()) }
    val api: String get() = apiRaw.ifBlank { "https://api.telegram.org" }.trimEnd('/')
    val officialApi: Boolean get() = api.contains("api.telegram.org")

    var wifiOnly: Boolean
        get() = b("wifi", false)
        set(v) { put("wifi", v) }
    var auto: Boolean
        get() = b("auto", false)
        set(v) { put("auto", v) }
    var autoHours: Int
        get() = p.getInt("autoHours", 6)
        set(v) { p.edit().putInt("autoHours", v).apply() }

    /** "doc" = original file, "media" = Telegram album (compressed, viewable in channel) */
    var sendMode: String
        get() = s("sendMode", "doc")
        set(v) { put("sendMode", v) }
    var capName: Boolean
        get() = b("capName", true)
        set(v) { put("capName", v) }
    var capDate: Boolean
        get() = b("capDate", true)
        set(v) { put("capDate", v) }
    var capFolder: Boolean
        get() = b("capFolder", true)
        set(v) { put("capFolder", v) }
    var capHashtag: Boolean
        get() = b("capHashtag", true)
        set(v) { put("capHashtag", v) }
    var splitLarge: Boolean
        get() = b("split", true)
        set(v) { put("split", v) }
    var silent: Boolean
        get() = b("silent", true)
        set(v) { put("silent", v) }
    /** folders excluded from "backup all" / auto backup */
    var excluded: Set<String>
        get() = p.getStringSet("excluded", emptySet())!!.toSet()
        set(v) { p.edit().putStringSet("excluded", v).apply() }

    var freedBytes: Long
        get() = p.getLong("freed", 0)
        set(v) { p.edit().putLong("freed", v).apply() }
    var uploadedBytes: Long
        get() = p.getLong("upBytes", 0)
        set(v) { p.edit().putLong("upBytes", v).apply() }

    @Synchronized fun uploaded(): MutableSet<String> = read(uploadedFile)
    @Synchronized fun skipped(): MutableSet<String> = read(skippedFile)
    @Synchronized fun markUploaded(item: MediaItem) {
        uploadedFile.appendText("${item.key}\n"); uploadedBytes += item.size
    }
    @Synchronized fun markSkipped(key: String) = skippedFile.appendText("$key\n")
    @Synchronized fun clearSkipped() { skippedFile.delete() }

    @Synchronized fun setQueue(keys: Collection<String>) = queueFile.writeText(keys.joinToString("\n"))
    @Synchronized fun takeQueue(): Set<String>? =
        if (queueFile.exists()) read(queueFile).also { queueFile.delete() } else null

    @Synchronized fun addHistory(line: String) {
        val t = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.US).format(java.util.Date())
        val old = if (historyFile.exists()) historyFile.readLines().takeLast(199) else emptyList()
        historyFile.writeText((old + "$t  $line").joinToString("\n"))
    }
    fun history(): List<String> = if (historyFile.exists()) historyFile.readLines().reversed() else emptyList()

    private fun read(f: File): MutableSet<String> =
        if (f.exists()) f.readLines().filter { it.isNotBlank() }.toMutableSet() else mutableSetOf()

    fun caption(item: MediaItem, part: String = ""): String {
        val lines = ArrayList<String>()
        if (capName) lines += item.name + part
        if (capDate) lines += java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.US)
            .format(java.util.Date(item.date * 1000))
        if (capFolder && item.bucket.isNotBlank()) lines += "📁 ${item.bucket}"
        if (capHashtag) {
            val ym = java.text.SimpleDateFormat("yyyy_MM", java.util.Locale.US).format(java.util.Date(item.date * 1000))
            val tag = item.bucket.replace(Regex("[^\\p{L}\\p{N}_]"), "_").trim('_')
            lines += "#d$ym" + (if (tag.isNotEmpty()) " #$tag" else "") + (if (item.video) " #video" else " #photo")
        }
        return lines.joinToString("\n").take(1000)
    }
}

fun fmtSize(b: Long): String = when {
    b >= 1L shl 30 -> String.format(java.util.Locale.US, "%.2f GB", b / 1073741824.0)
    b >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0)
    else -> "${b / 1024} KB"
}
