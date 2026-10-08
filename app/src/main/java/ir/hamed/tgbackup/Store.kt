package ir.hamed.tgbackup

import android.content.Context
import java.io.File

/** Settings + record of media already uploaded (key = "i:<id>" or "v:<id>"). */
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    private val uploadedFile = File(ctx.filesDir, "uploaded.txt")
    private val skippedFile = File(ctx.filesDir, "skipped.txt")

    var token: String
        get() = p.getString("token", "")!!
        set(v) { p.edit().putString("token", v.trim()).apply() }
    var chat: String
        get() = p.getString("chat", "")!!
        set(v) { p.edit().putString("chat", v.trim()).apply() }
    var api: String
        get() = p.getString("api", "")!!.ifBlank { "https://api.telegram.org" }.trimEnd('/')
        set(v) { p.edit().putString("api", v.trim()).apply() }
    var wifiOnly: Boolean
        get() = p.getBoolean("wifi", false)
        set(v) { p.edit().putBoolean("wifi", v).apply() }
    var auto: Boolean
        get() = p.getBoolean("auto", false)
        set(v) { p.edit().putBoolean("auto", v).apply() }

    @Synchronized fun uploaded(): MutableSet<String> = read(uploadedFile)
    @Synchronized fun skipped(): MutableSet<String> = read(skippedFile)
    @Synchronized fun markUploaded(key: String) = uploadedFile.appendText("$key\n")
    @Synchronized fun markSkipped(key: String) = skippedFile.appendText("$key\n")

    private fun read(f: File): MutableSet<String> =
        if (f.exists()) f.readLines().filter { it.isNotBlank() }.toMutableSet() else mutableSetOf()
}
