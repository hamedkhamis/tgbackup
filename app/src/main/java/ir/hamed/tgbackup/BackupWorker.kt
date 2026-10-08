package ir.hamed.tgbackup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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

        private fun constraints(s: Store) = Constraints.Builder()
            .setRequiredNetworkType(if (s.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()

        fun runNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<BackupWorker>()
                .setConstraints(constraints(Store(ctx)))
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, req)
        }

        fun schedule(ctx: Context) {
            val s = Store(ctx); val wm = WorkManager.getInstance(ctx)
            if (!s.auto) { wm.cancelUniqueWork(PERIODIC); return }
            val req = PeriodicWorkRequestBuilder<BackupWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints(s)).build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }

    private val store = Store(ctx)
    private val tg = Telegram(store)
    private val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun info(text: String, done: Int, total: Int): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel(CH, "بک‌آپ", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, CH)
            .setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("بک‌آپ تلگرام")
            .setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(total, done, total == 0).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NID, n)
    }

    override suspend fun doWork(): Result {
        if (store.token.isBlank() || store.chat.isBlank()) return Result.failure()
        val maxBytes = if (store.api.contains("api.telegram.org")) 50L * 1024 * 1024 else 2000L * 1024 * 1024
        val uploaded = store.uploaded(); val skipped = store.skipped()
        val todo = Media.all(applicationContext).filter { it.key !in uploaded && it.key !in skipped }
        val total = todo.size
        try { setForeground(info("آماده‌سازی…", 0, total)) } catch (_: Exception) {}

        var done = 0; var fails = 0
        for (item in todo) {
            if (isStopped) break
            if (item.size > maxBytes) {
                store.markSkipped(item.key); done++
                log("رد شد (بزرگ‌تر از ${maxBytes / 1048576}MB): ${item.name}")
                continue
            }
            report("در حال ارسال ${done + 1} از $total: ${item.name}", done, total)
            var attempt = 0
            while (true) {
                when (val r = tg.sendFile(applicationContext, item)) {
                    is Telegram.Result.Ok -> { store.markUploaded(item.key); fails = 0; break }
                    is Telegram.Result.Retry -> {
                        attempt++
                        if (attempt > 4) {
                            log("خطای شبکه: ${tg.lastError ?: "نامشخص"}")
                            report("قطع شد؛ بعدا خودکار ادامه میدهد", done, total)
                            return Result.retry()
                        }
                        delay(r.sec * 1000)
                    }
                    is Telegram.Result.Fail -> {
                        log("خطا در ${item.name}: ${r.msg}")
                        if (++fails >= 5) { report("خطای مکرر: ${r.msg}", done, total); return Result.failure() }
                        break
                    }
                }
            }
            done++
            delay(3000) // Telegram allows ~20 posts/min per channel
        }
        report("تمام شد ✅ ($done فایل)", done, total)
        return Result.success()
    }

    private suspend fun report(text: String, done: Int, total: Int) {
        setProgress(workDataOf("text" to text, "done" to done, "total" to total))
        try { setForeground(info(text, done, total)) } catch (_: Exception) {}
        applicationContext.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putString("status", text).apply()
    }

    private fun log(line: String) {
        val p = applicationContext.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val old = p.getString("log", "")!!.lines().takeLast(30).joinToString("\n")
        p.edit().putString("log", "$old\n$line".trim()).apply()
    }
}
