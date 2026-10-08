package ir.hamed.tgbackup

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var store: Store
    private lateinit var gallery: GalleryPage
    private lateinit var nav: BottomNavigationView
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
        if (k == "status" || k == "running" || k == "done") runOnUiThread { refreshRun() }
    }
    private var pendingDelete: List<MediaItem> = emptyList()
    private var afterDelete: (() -> Unit)? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { reloadAll() }
    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == Activity.RESULT_OK) onDeleted(pendingDelete) else toast("لغو شد")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        if (store.p.getString("botName", "").isNullOrBlank() || store.token.isBlank()) {
            startActivity(Intent(this, SetupActivity::class.java)); finish(); return
        }
        setContentView(R.layout.activity_main)
        nav = findViewById(R.id.nav)
        val pages = mapOf(R.id.nav_home to R.id.pageHome, R.id.nav_gallery to R.id.pageGallery, R.id.nav_settings to R.id.pageSettings)
        nav.setOnItemSelectedListener { mi ->
            pages.forEach { (n, p) -> findViewById<View>(p).visibility = if (n == mi.itemId) View.VISIBLE else View.GONE }
            if (mi.itemId == R.id.nav_home) refreshHome()
            true
        }
        gallery = GalleryPage(this, findViewById(R.id.pageGallery))
        setupHome(); setupSettings()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (nav.selectedItemId == R.id.nav_gallery && gallery.onBack()) return
                if (nav.selectedItemId != R.id.nav_home) { nav.selectedItemId = R.id.nav_home; return }
                finish()
            }
        })
        if (!hasPerms()) permLauncher.launch(perms())
    }

    override fun onResume() {
        super.onResume()
        if (!::nav.isInitialized) return
        store.p.registerOnSharedPreferenceChangeListener(prefListener)
        reloadAll(); bindSettings()
    }
    override fun onPause() { super.onPause(); store.p.unregisterOnSharedPreferenceChangeListener(prefListener) }

    fun goHome() { nav.selectedItemId = R.id.nav_home }
    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun reloadAll() { if (hasPerms()) { refreshHome(); gallery.reload() } }

    fun preview(item: MediaItem) = try {
        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, item.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (_: Exception) { toast("برنامه‌ای برای نمایش پیدا نشد") }

    // ---------------- Home ----------------
    private fun setupHome() {
        val swipe = findViewById<SwipeRefreshLayout>(R.id.pageHome)
        swipe.setColorSchemeResources(R.color.brand)
        swipe.setOnRefreshListener { refreshHome(); swipe.isRefreshing = false }
        findViewById<View>(R.id.btnBackupAll).setOnClickListener {
            if (!hasPerms()) { permLauncher.launch(perms()); return@setOnClickListener }
            BackupWorker.runNow(this); toast("بک‌آپ شروع شد")
        }
        findViewById<View>(R.id.btnStop).setOnClickListener {
            androidx.work.WorkManager.getInstance(this).cancelUniqueWork(BackupWorker.ONCE); toast("متوقف شد")
        }
        findViewById<View>(R.id.btnFree).setOnClickListener {
            thread {
                val up = store.uploaded(); val items = Media.all(this).filter { it.key in up }
                runOnUiThread {
                    if (items.isEmpty()) { toast("فایل بک‌آپ‌شده‌ای روی گوشی نیست"); return@runOnUiThread }
                    MaterialAlertDialogBuilder(this).setTitle("آزادسازی ${fmtSize(items.sumOf { it.size })}")
                        .setMessage("${items.size} فایل که نسخه‌شان در کانال تلگرام هست از گوشی پاک شوند؟")
                        .setPositiveButton("پاک کن") { _, _ -> deleteItems(items) { refreshHome() } }
                        .setNegativeButton("لغو", null).show()
                }
            }
        }
        findViewById<TextView>(R.id.hello).text = "سلام 👋  @${store.p.getString("botName", "")}"
    }

    private fun refreshHome() {
        refreshRun()
        findViewById<TextView>(R.id.history).text = store.history().take(15).joinToString("\n").ifBlank { "هنوز چیزی نیست" }
        if (!hasPerms()) return
        thread {
            val all = Media.all(this); val up = store.uploaded()
            val backed = all.filter { it.key in up }
            val left = all.size - backed.size
            val leftBytes = all.filter { it.key !in up }.sumOf { it.size }
            runOnUiThread {
                val pct = if (all.isEmpty()) 0 else backed.size * 1000 / all.size
                findViewById<CircularProgressIndicator>(R.id.ring).apply { max = 1000; setProgressCompat(pct, true) }
                findViewById<TextView>(R.id.pct).text = "${pct / 10}٪"
                findViewById<TextView>(R.id.heroTitle).text = if (left == 0) "همه چیز امن است ✨" else "$left مورد در انتظار"
                findViewById<TextView>(R.id.heroSub).text = "${all.size} عکس و ویدئو روی گوشی\n${fmtSize(leftBytes)} هنوز بک‌آپ نشده"
                findViewById<TextView>(R.id.stBacked).text = "${backed.size}"
                findViewById<TextView>(R.id.stLeft).text = "$left"
                findViewById<TextView>(R.id.stFree).text = fmtSize(backed.sumOf { it.size })
                findViewById<TextView>(R.id.stFreed).text = fmtSize(store.freedBytes)
            }
        }
    }

    private fun refreshRun() {
        val running = store.p.getBoolean("running", false)
        findViewById<View>(R.id.runCard).visibility = if (running) View.VISIBLE else View.GONE
        val done = store.p.getInt("done", 0); val total = store.p.getInt("total", 0)
        findViewById<LinearProgressIndicator>(R.id.runBar).apply { max = maxOf(total, 1); setProgressCompat(done, true) }
        findViewById<TextView>(R.id.runCount).text = "$done / $total"
        findViewById<TextView>(R.id.runText).text = store.p.getString("status", "")
        if (!running && done > 0 && done == total) refreshHomeStatsSoon()
    }
    private var lastStats = 0L
    private fun refreshHomeStatsSoon() {
        if (System.currentTimeMillis() - lastStats > 3000) { lastStats = System.currentTimeMillis(); refreshHome(); gallery.reload() }
    }

    // ---------------- Delete ----------------
    fun deleteItems(items: List<MediaItem>, after: () -> Unit) {
        afterDelete = after
        if (Build.VERSION.SDK_INT >= 30) {
            pendingDelete = items.take(2000)
            val pi = MediaStore.createDeleteRequest(contentResolver, pendingDelete.map { it.uri })
            deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } else thread {
            val ok = items.filter { try { contentResolver.delete(it.uri, null, null) > 0 } catch (_: Exception) { false } }
            runOnUiThread { onDeleted(ok) }
        }
    }

    private fun onDeleted(items: List<MediaItem>) {
        val size = items.sumOf { it.size }
        store.freedBytes += size
        store.addHistory("🧹 ${items.size} فایل حذف شد، ${fmtSize(size)} آزاد شد")
        toast("${fmtSize(size)} آزاد شد 🎉")
        afterDelete?.invoke(); refreshHome(); gallery.reload()
    }

    // ---------------- Settings ----------------
    private fun sw(id: Int, get: () -> Boolean, set: (Boolean) -> Unit) {
        val s = findViewById<MaterialSwitch>(id)
        s.setOnCheckedChangeListener(null); s.isChecked = get()
        s.setOnCheckedChangeListener { _, v -> set(v) }
    }

    private fun setupSettings() {
        findViewById<View>(R.id.btnReconnect).setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java).putExtra("edit", true))
        }
        val group = findViewById<MaterialButtonToggleGroup>(R.id.modeGroup)
        group.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            store.sendMode = if (id == R.id.modeMedia) "media" else "doc"; modeHint()
        }
        val slider = findViewById<Slider>(R.id.autoSlider)
        slider.addOnChangeListener { _, v, fromUser ->
            findViewById<TextView>(R.id.autoLabel).text = "هر ${v.toInt()} ساعت یک بار"
            if (fromUser) { store.autoHours = v.toInt(); BackupWorker.schedule(this) }
        }
        findViewById<View>(R.id.rowExclude).setOnClickListener { pickExcluded() }
        findViewById<View>(R.id.rowSkipped).setOnClickListener {
            store.clearSkipped(); toast("فایل‌های ردشده دوباره در صف قرار می‌گیرند"); bindSettings()
        }
        findViewById<View>(R.id.rowAbout).setOnClickListener {
            MaterialAlertDialogBuilder(this).setTitle("بک‌آپ تلگرام")
                .setMessage("نسخه 2.0\nساخته‌شده برای حامد\n\nفایل‌ها مستقیم از گوشی به کانال خصوصی تو فرستاده می‌شوند و هیچ سرور واسطی ندارد.")
                .setPositiveButton("باشه", null).show()
        }
    }

    private fun modeHint() {
        findViewById<TextView>(R.id.modeHint).text = if (store.sendMode == "media")
            "آلبوم‌های ۱۰تایی، قابل دیدن مستقیم در کانال. تلگرام عکس‌ها را فشرده می‌کند."
        else "کیفیت ۱۰۰٪ اصلی، هر فایل جدا (پیشنهادی برای بک‌آپ)"
    }

    private fun bindSettings() {
        findViewById<TextView>(R.id.connTitle).text = "@${store.p.getString("botName", "")}"
        findViewById<TextView>(R.id.connSub).text = "کانال ${store.chat.takeLast(6).padStart(9, '•')} · متصل ✓"
        findViewById<MaterialButtonToggleGroup>(R.id.modeGroup).check(if (store.sendMode == "media") R.id.modeMedia else R.id.modeDoc)
        modeHint()
        sw(R.id.swSplit, { store.splitLarge }) { store.splitLarge = it }
        sw(R.id.swSilent, { store.silent }) { store.silent = it }
        sw(R.id.capName, { store.capName }) { store.capName = it }
        sw(R.id.capDate, { store.capDate }) { store.capDate = it }
        sw(R.id.capFolder, { store.capFolder }) { store.capFolder = it }
        sw(R.id.capTag, { store.capHashtag }) { store.capHashtag = it }
        sw(R.id.swAuto, { store.auto }) { store.auto = it; BackupWorker.schedule(this) }
        sw(R.id.swWifi, { store.wifiOnly }) { store.wifiOnly = it; BackupWorker.schedule(this) }
        findViewById<Slider>(R.id.autoSlider).value = store.autoHours.coerceIn(1, 24).toFloat()
        findViewById<TextView>(R.id.autoLabel).text = "هر ${store.autoHours} ساعت یک بار"
        val ex = store.excluded
        findViewById<TextView>(R.id.rowExcludeSub).text = if (ex.isEmpty()) "هیچ" else ex.joinToString("، ")
        findViewById<TextView>(R.id.rowSkippedSub).text = "${store.skipped().size} فایل ردشده"
    }

    private fun pickExcluded() = thread {
        val buckets = Media.all(this).map { it.bucket }.filter { it.isNotBlank() }.distinct().sorted()
        runOnUiThread {
            val ex = store.excluded.toMutableSet()
            val checked = buckets.map { it in ex }.toBooleanArray()
            MaterialAlertDialogBuilder(this).setTitle("این پوشه‌ها بک‌آپ خودکار نشوند")
                .setMultiChoiceItems(buckets.toTypedArray(), checked) { _, i, c -> if (c) ex.add(buckets[i]) else ex.remove(buckets[i]) }
                .setPositiveButton("ذخیره") { _, _ -> store.excluded = ex; bindSettings() }
                .setNegativeButton("لغو", null).show()
        }
    }

    // ---------------- Permissions ----------------
    private fun perms() = if (Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.POST_NOTIFICATIONS)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun hasPerms() = perms().filter { it != Manifest.permission.POST_NOTIFICATIONS }
        .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
}
