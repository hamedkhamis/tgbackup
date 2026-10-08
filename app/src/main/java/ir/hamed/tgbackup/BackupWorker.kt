package ir.hamed.tgbackup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        const val ONCE = "backup_once"
        const val PERIODIC = "backup_periodic"
        private const val CH = "backup"
        private const val NID = 42
        private const val PART = 49L * 1024 * 1024

        private fun constraints(s: Store) = Constraints.Builder()
            .setRequiredNetworkType(if (s.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()

        /** keys == null -> everything not yet backed up */
        fun runNow(ctx: Context, keys: Collection<String>? = null) {
            val s = Store(ctx)
            if (keys != null) s.setQueue(keys) else s.queueFile.delete()
            val req = OneTimeWorkRequestBuilder<BackupWorker>()
                .setConstraints(constraints(s))
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, req)
        }

        fun schedule(ctx: Context) {
            val s = Store(ctx); val wm = WorkManager.getInstance(ctx)
            if (!s.auto) { wm.cancelUniqueWork(PERIODIC); return }
            val req = PeriodicWorkRequestBuilder<BackupWorker>(s.autoHours.toLong().coerceAtLeast(1), TimeUnit.HOURS)
                .setConstraints(constraints(s)).build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }

    private val store = Store(ctx)
    private val tg = Telegram(store)
    private val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var done = 0
    private var total = 0

    private fun info(text: String): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel(CH, "بک‌آپ", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(applicationContext, CH)
            .setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("بک‌آپ تلگرام  $done/$total")
            .setContentText(text).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .setProgress(total, done, total == 0).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NID, n)
    }

    private class Abort(val retry: Boolean, msg: String) : Exception(msg)

    override suspend fun doWork(): Result {
        if (store.token.isBlank() || store.chat.isBlank()) return Result.failure()
        val queue = store.takeQueue()
        val uploaded = store.uploaded(); val skipped = store.skipped(); val excl = store.excluded
        val todo = Media.all(applicationContext).filter {
            it.key !in uploaded && if (queue != null) it.key in queue
            else it.key !in skipped && it.bucket !in excl
        }
        total = todo.size; done = 0
        if (total == 0) { report("چیزی برای بک‌آپ نیست ✅", false); return Result.success() }
        store.p.edit().putBoolean("running", true).apply()
        val startBytes = store.uploadedBytes
        try { setForeground(info("آماده‌سازی…")) } catch (_: Exception) {}

        return try {
            if (store.sendMode == "media") runAlbums(todo) else todo.forEach { if (!isStopped) sendOne(it) }
            val sent = store.uploadedBytes - startBytes
            report("تمام شد ✅  $done فایل، ${fmtSize(sent)}", false)
            store.addHistory("✅ بک‌آپ $done فایل (${fmtSize(sent)})")
            Result.success()
        } catch (a: Abort) {
            report(a.message ?: "خطا", false)
            store.addHistory("⚠️ ${a.message}")
            if (a.retry) { if (queue != null) store.setQueue(queue); Result.retry() } else Result.failure()
        } finally {
            store.p.edit().putBoolean("running", false).apply()
        }
    }

    private suspend fun send(label: String, call: () -> Telegram.Result): Boolean {
        var attempt = 0
        while (true) {
            if (isStopped) throw Abort(false, "متوقف شد")
            when (val r = call()) {
                is Telegram.Result.Ok -> { delay(3000); return true }
                is Telegram.Result.Retry -> {
                    if (++attempt > 5) throw Abort(true, "اینترنت/VPN قطع است؛ خودکار دوباره تلاش می‌کند")
                    report("صبر ${r.sec} ثانیه… ($label)")
                    delay(r.sec * 1000)
                }
                is Telegram.Result.Fail -> {
                    store.addHistory("❌ $label: ${r.msg}")
                    if (r.msg.contains("chat not found", true) || r.msg.contains("Unauthorized", true) ||
                        r.msg.contains("not enough rights", true)) throw Abort(false, "خطای اتصال: ${r.msg}")
                    return false
                }
            }
        }
    }

    private suspend fun sendOne(item: MediaItem) {
        val max = if (store.officialApi) 50L * 1024 * 1024 else 2000L * 1024 * 1024
        report("ارسال ${done + 1} از $total: ${item.name}")
        val ok = if (item.size <= max) {
            send(item.name) { tg.sendDocument(applicationContext, item, store.caption(item)) }
        } else if (store.splitLarge) {
            val parts = ((item.size + PART - 1) / PART).toInt()
            var all = true
            for (i in 0 until parts) {
                report("ارسال ${done + 1} از $total: ${item.name} (تکه ${i + 1}/$parts)")
                val off = i * PART; val len = minOf(PART, item.size - off)
                val name = "${item.name}.${"%03d".format(i + 1)}"
                val cap = store.caption(item, "  [تکه ${i + 1} از $parts]") +
                    if (i == 0) "\n🔗 برای یکی کردن: copy /b name.001+name.002 ... یا 7-Zip" else ""
                if (!send(name) { tg.sendDocument(applicationContext, item, cap, name, off, len) }) { all = false; break }
            }
            all
        } else {
            store.markSkipped(item.key); store.addHistory("⏭ رد شد (بزرگ): ${item.name}"); false
        }
        if (ok) store.markUploaded(item)
        done++
    }

    private suspend fun runAlbums(items: List<MediaItem>) {
        val batch = ArrayList<MediaItem>(); var batchSize = 0L
        suspend fun flush() {
            if (batch.isEmpty()) return
            if (batch.size == 1) { sendOne(batch[0]); batch.clear(); batchSize = 0; return }
            report("ارسال آلبوم ${done + 1}..${done + batch.size} از $total")
            val list = batch.toList()
            if (send("آلبوم ${list.size} تایی") { tg.sendAlbum(applicationContext, list) }) list.forEach { store.markUploaded(it) }
            done += list.size; batch.clear(); batchSize = 0
        }
        for (it in items) {
            if (isStopped) break
            val albumOk = if (it.video) it.size <= 45L * 1048576 else it.size <= 9L * 1048576
            if (!albumOk) { flush(); sendOne(it); continue }
            if (batch.size == 10 || batchSize + it.size > 45L * 1048576) flush()
            batch += it; batchSize += it.size
        }
        flush()
    }

    private suspend fun report(text: String, running: Boolean = true) {
        try { setProgress(workDataOf("text" to text, "done" to done, "total" to total)) } catch (_: Exception) {}
        if (running) try { setForeground(info(text)) } catch (_: Exception) {}
        store.p.edit().putString("status", text).putInt("done", done).putInt("total", total).apply()
    }
}
