package ir.hamed.tgbackup

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.*
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var store: Store
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private val prefs: SharedPreferences by lazy { getSharedPreferences("cfg", Context.MODE_PRIVATE) }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        toast(if (it.resultCode == Activity.RESULT_OK) "حذف شد" else "لغو شد"); refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = Store(this)
        val token = findViewById<EditText>(R.id.token); val chat = findViewById<EditText>(R.id.chat)
        val api = findViewById<EditText>(R.id.api)
        val wifi = findViewById<CheckBox>(R.id.wifiOnly); val auto = findViewById<CheckBox>(R.id.auto)
        status = findViewById(R.id.status); logView = findViewById(R.id.log)

        token.setText(store.token); chat.setText(store.chat)
        api.setText(prefs.getString("api", "")); wifi.isChecked = store.wifiOnly; auto.isChecked = store.auto

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            store.token = token.text.toString(); store.chat = chat.text.toString(); store.api = api.text.toString()
            store.wifiOnly = wifi.isChecked; store.auto = auto.isChecked
            BackupWorker.schedule(this)
            status.text = "در حال تست اتصال…"
            thread {
                val err = Telegram(store).test()
                runOnUiThread { status.text = if (err == null) "اتصال موفق ✅ پیام تست به کانال رفت" else "خطا: $err" }
            }
        }
        findViewById<Button>(R.id.btnBackup).setOnClickListener {
            if (!hasPerms()) { askPerms(); return@setOnClickListener }
            if (store.token.isBlank() || store.chat.isBlank()) { toast("اول توکن و کانال رو ذخیره کن"); return@setOnClickListener }
            BackupWorker.runNow(this); toast("بک‌آپ شروع شد (در پس‌زمینه ادامه دارد)")
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            WorkManager.getInstance(this).cancelUniqueWork(BackupWorker.ONCE); toast("متوقف شد")
        }
        findViewById<Button>(R.id.btnDelete).setOnClickListener { confirmDelete() }

        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(BackupWorker.ONCE).observe(this) { list ->
            val wi = list.firstOrNull() ?: return@observe
            if (wi.state == WorkInfo.State.RUNNING) wi.progress.getString("text")?.let { status.text = it }
            else if (wi.state == WorkInfo.State.ENQUEUED) status.text = "در صف (منتظر اینترنت/وای‌فای)…"
        }
        askPerms()
    }

    override fun onResume() { super.onResume(); prefs.registerOnSharedPreferenceChangeListener(prefListener); refresh() }
    override fun onPause() { super.onPause(); prefs.unregisterOnSharedPreferenceChangeListener(prefListener) }

    private fun refresh() {
        logView.text = prefs.getString("log", "")
        if (!hasPerms()) { status.text = "اجازه دسترسی به عکس‌ها و ویدئوها لازم است"; return }
        thread {
            val all = Media.all(this); val up = store.uploaded()
            val n = all.count { it.key in up }
            val mb = all.filter { it.key in up }.sumOf { it.size } / 1048576
            runOnUiThread {
                findViewById<Button>(R.id.btnDelete).text = "حذف $n فایل بک‌آپ‌شده از گوشی ($mb MB)"
                if (status.text.isNullOrBlank() || status.text.startsWith("اجازه"))
                    status.text = prefs.getString("status", null) ?: "کل: ${all.size} | بک‌آپ‌شده: $n"
            }
        }
    }

    private fun mediaPerms() = if (Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.POST_NOTIFICATIONS)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun hasPerms() = mediaPerms().filter { it != Manifest.permission.POST_NOTIFICATIONS }
        .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun askPerms() { if (!hasPerms()) permLauncher.launch(mediaPerms()) }

    private fun confirmDelete() {
        if (!hasPerms()) { askPerms(); return }
        thread {
            val up = store.uploaded()
            val items = Media.all(this).filter { it.key in up }
            runOnUiThread {
                if (items.isEmpty()) { toast("فایلی برای حذف نیست"); return@runOnUiThread }
                AlertDialog.Builder(this).setTitle("حذف از گوشی")
                    .setMessage("${items.size} فایل که در کانال تلگرام آپلود شده‌اند از گوشی حذف شوند؟")
                    .setPositiveButton("حذف") { _, _ -> delete(items) }
                    .setNegativeButton("لغو", null).show()
            }
        }
    }

    private fun delete(items: List<MediaItem>) {
        if (Build.VERSION.SDK_INT >= 30) {
            // system shows one confirmation for the whole batch (chunked to keep the intent small)
            val uris = items.map { it.uri }.take(2000)
            val pi = MediaStore.createDeleteRequest(contentResolver, uris)
            deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } else thread {
            var n = 0
            items.forEach { try { n += contentResolver.delete(it.uri, null, null) } catch (_: Exception) {} }
            runOnUiThread { toast("$n فایل حذف شد"); refresh() }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
