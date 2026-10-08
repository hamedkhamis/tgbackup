package ir.hamed.tgbackup

import android.content.Context
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class Telegram(private val store: Store) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.MINUTES)
        .readTimeout(5, TimeUnit.MINUTES).build()

    private fun url(m: String) = "${store.api}/bot${store.token}/$m"

    /** Sends a test message; returns null on success or an error string. */
    fun test(): String? = try {
        val body = FormBody.Builder().add("chat_id", store.chat)
            .add("text", "✅ اتصال اپ بک‌آپ برقرار شد").build()
        http.newCall(Request.Builder().url(url("sendMessage")).post(body).build()).execute().use { r ->
            val j = JSONObject(r.body?.string() ?: "{}")
            if (j.optBoolean("ok")) null else j.optString("description", "HTTP ${r.code}")
        }
    } catch (e: Exception) { e.message ?: e.toString() }

    sealed class Result { object Ok : Result(); data class Retry(val sec: Long) : Result(); data class Fail(val msg: String) : Result() }

    fun sendFile(ctx: Context, item: MediaItem): Result = try {
        val type = (if (item.video) "video/*" else "image/*").toMediaType()
        val fileBody = object : RequestBody() {
            override fun contentType() = type
            override fun contentLength() = item.size
            override fun writeTo(sink: BufferedSink) {
                ctx.contentResolver.openInputStream(item.uri)!!.source().use { sink.writeAll(it) }
            }
        }
        val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            .format(java.util.Date(item.date * 1000))
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", store.chat)
            .addFormDataPart("caption", "${item.name}\n$date")
            .addFormDataPart("disable_notification", "true")
            .addFormDataPart("document", item.name, fileBody).build()
        http.newCall(Request.Builder().url(url("sendDocument")).post(body).build()).execute().use { r ->
            val j = JSONObject(r.body?.string() ?: "{}")
            when {
                j.optBoolean("ok") -> Result.Ok
                r.code == 429 -> Result.Retry(j.optJSONObject("parameters")?.optLong("retry_after", 5) ?: 5)
                else -> Result.Fail(j.optString("description", "HTTP ${r.code}"))
            }
        }
    } catch (e: Exception) { Result.Retry(10).also { lastError = e.message } }

    var lastError: String? = null
}
