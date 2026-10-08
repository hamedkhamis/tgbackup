package ir.hamed.tgbackup

import android.content.Context
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import okio.buffer
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class Telegram(private val store: Store) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.MINUTES)
        .readTimeout(5, TimeUnit.MINUTES).build()

    var lastError: String? = null

    private fun url(m: String) = "${store.api}/bot${store.token}/$m"

    sealed class Result {
        object Ok : Result()
        data class Retry(val sec: Long) : Result()
        data class Fail(val msg: String) : Result()
    }

    private fun get(method: String): JSONObject =
        http.newCall(Request.Builder().url(url(method)).build()).execute().use { JSONObject(it.body?.string() ?: "{}") }

    /** Bot @username, or throws with Telegram's error text. */
    fun getMe(): String {
        val j = get("getMe")
        if (!j.optBoolean("ok")) throw Exception(j.optString("description", "توکن نامعتبر"))
        return j.getJSONObject("result").optString("username")
    }

    /** Channels/groups the bot has seen recently: id -> title. */
    fun findChats(): List<Pair<String, String>> {
        val j = get("getUpdates?allowed_updates=%5B%22channel_post%22%2C%22my_chat_member%22%2C%22message%22%5D")
        if (!j.optBoolean("ok")) throw Exception(j.optString("description"))
        val out = LinkedHashMap<String, String>()
        val arr = j.getJSONArray("result")
        for (i in 0 until arr.length()) {
            val u = arr.getJSONObject(i)
            for (k in listOf("channel_post", "my_chat_member", "message", "edited_channel_post")) {
                val chat = u.optJSONObject(k)?.optJSONObject("chat") ?: continue
                if (chat.optString("type") == "private") continue
                out[chat.optLong("id").toString()] = chat.optString("title", "بدون نام")
            }
        }
        return out.map { it.key to it.value }
    }

    /** Sends a test message; returns null on success or an error string. */
    fun test(): String? = try {
        val body = FormBody.Builder().add("chat_id", store.chat)
            .add("text", "✅ اتصال اپ بک‌آپ برقرار شد").build()
        http.newCall(Request.Builder().url(url("sendMessage")).post(body).build()).execute().use { r ->
            val j = JSONObject(r.body?.string() ?: "{}")
            if (j.optBoolean("ok")) null else j.optString("description", "HTTP ${r.code}")
        }
    } catch (e: Exception) { e.message ?: e.toString() }

    fun sendText(text: String) = try {
        val body = FormBody.Builder().add("chat_id", store.chat).add("text", text)
            .add("disable_notification", store.silent.toString()).build()
        http.newCall(Request.Builder().url(url("sendMessage")).post(body).build()).execute().close()
    } catch (_: Exception) {}

    /** Streams [length] bytes of the item starting at [offset]. */
    private fun body(ctx: Context, item: MediaItem, offset: Long = 0, length: Long = item.size) = object : RequestBody() {
        override fun contentType() = item.mime.toMediaType()
        override fun contentLength() = length
        override fun writeTo(sink: BufferedSink) {
            ctx.contentResolver.openInputStream(item.uri)!!.use { ins ->
                var toSkip = offset
                while (toSkip > 0) { val s = ins.skip(toSkip); if (s <= 0) break; toSkip -= s }
                val src = ins.source().buffer()
                var left = length
                val buf = okio.Buffer()
                while (left > 0) {
                    val n = src.read(buf, minOf(left, 65536L))
                    if (n == -1L) break
                    sink.write(buf, n); left -= n
                }
            }
        }
    }

    private fun exec(method: String, body: RequestBody): Result = try {
        http.newCall(Request.Builder().url(url(method)).post(body).build()).execute().use { r ->
            val j = JSONObject(r.body?.string() ?: "{}")
            when {
                j.optBoolean("ok") -> Result.Ok
                r.code == 429 -> Result.Retry(j.optJSONObject("parameters")?.optLong("retry_after", 5) ?: 5)
                r.code >= 500 -> Result.Retry(10)
                else -> Result.Fail(j.optString("description", "HTTP ${r.code}"))
            }
        }
    } catch (e: Exception) { lastError = e.message ?: e.toString(); Result.Retry(10) }

    /** Original file as document (optionally one byte-range part of it). */
    fun sendDocument(ctx: Context, item: MediaItem, caption: String,
                     fileName: String = item.name, offset: Long = 0, length: Long = item.size): Result {
        val b = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", store.chat)
            .addFormDataPart("caption", caption)
            .addFormDataPart("disable_notification", store.silent.toString())
            .addFormDataPart("document", fileName, body(ctx, item, offset, length)).build()
        return exec("sendDocument", b)
    }

    /** Up to 10 photos/videos as one album (Telegram compresses photos). */
    fun sendAlbum(ctx: Context, items: List<MediaItem>): Result {
        val b = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", store.chat)
            .addFormDataPart("disable_notification", store.silent.toString())
        val arr = JSONArray()
        items.forEachIndexed { i, it ->
            arr.put(JSONObject().put("type", if (it.video) "video" else "photo")
                .put("media", "attach://f$i").put("caption", store.caption(it))
                .apply { if (it.video) put("supports_streaming", true) })
            b.addFormDataPart("f$i", it.name, body(ctx, it))
        }
        b.addFormDataPart("media", arr.toString())
        return exec("sendMediaGroup", b.build())
    }
}

