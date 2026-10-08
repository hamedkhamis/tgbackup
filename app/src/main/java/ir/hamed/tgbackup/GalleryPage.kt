package ir.hamed.tgbackup

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.decode.VideoFrameDecoder
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.concurrent.thread

class GalleryPage(private val act: MainActivity, private val root: View) {
    private val store = Store(act)
    private var all: List<MediaItem> = emptyList()
    private var shown: List<MediaItem> = emptyList()
    private var uploaded: Set<String> = emptySet()
    private val selected = LinkedHashSet<String>()
    private var album: String? = null
    private val adapter = Adapter()

    private val grid = root.findViewById<RecyclerView>(R.id.grid)
    private val selBar = root.findViewById<View>(R.id.selBar)
    private val selCount = root.findViewById<TextView>(R.id.selCount)
    private val stateGroup = root.findViewById<ChipGroup>(R.id.stateGroup)
    private val typeGroup = root.findViewById<ChipGroup>(R.id.typeGroup)
    private val btnAlbum = root.findViewById<MaterialButton>(R.id.btnAlbum)

    init {
        grid.layoutManager = GridLayoutManager(act, 4)
        grid.adapter = adapter
        stateGroup.setOnCheckedStateChangeListener { _, _ -> apply() }
        typeGroup.setOnCheckedStateChangeListener { _, _ -> apply() }
        btnAlbum.setOnClickListener { pickAlbum() }
        root.findViewById<View>(R.id.selClose).setOnClickListener { selected.clear(); refreshSel() }
        root.findViewById<View>(R.id.selAll).setOnClickListener {
            if (shown.all { it.key in selected }) shown.forEach { selected.remove(it.key) }
            else shown.forEach { selected.add(it.key) }
            refreshSel()
        }
        root.findViewById<View>(R.id.selBackup).setOnClickListener {
            val keys = selected.filter { it !in uploaded }
            if (keys.isEmpty()) { act.toast("همه‌ی انتخاب‌شده‌ها قبلا بک‌آپ شده‌اند"); return@setOnClickListener }
            BackupWorker.runNow(act, keys); act.toast("بک‌آپ ${keys.size} مورد شروع شد")
            selected.clear(); refreshSel(); act.goHome()
        }
        root.findViewById<View>(R.id.selDelete).setOnClickListener {
            val items = all.filter { it.key in selected }
            val notBacked = items.count { it.key !in uploaded }
            val size = items.sumOf { it.size }
            MaterialAlertDialogBuilder(act).setTitle("حذف ${items.size} مورد از گوشی؟")
                .setMessage(
                    "${fmtSize(size)} فضا آزاد می‌شود." +
                    if (notBacked > 0) "\n\n⚠️ $notBacked مورد هنوز بک‌آپ نشده و برای همیشه پاک می‌شود!" else "\n\n✅ همه در کانال تلگرام بک‌آپ دارند."
                )
                .setPositiveButton("حذف") { _, _ -> act.deleteItems(items) { selected.clear(); reload() } }
                .setNegativeButton("لغو", null).show()
        }
    }

    fun reload() = thread {
        val a = Media.all(act).reversed(); val u = store.uploaded()
        act.runOnUiThread { all = a; uploaded = u; apply() }
    }

    private fun apply() {
        var l = all
        l = when (stateGroup.checkedChipId) {
            R.id.fPending -> l.filter { it.key !in uploaded }
            R.id.fDone -> l.filter { it.key in uploaded }
            else -> l
        }
        l = when (typeGroup.checkedChipId) {
            R.id.tPhoto -> l.filter { !it.video }
            R.id.tVideo -> l.filter { it.video }
            R.id.tBig -> l.sortedByDescending { it.size }
            else -> l
        }
        album?.let { a -> l = l.filter { it.bucket == a } }
        shown = l
        root.findViewById<TextView>(R.id.gCount).text = "${l.size} مورد · ${fmtSize(l.sumOf { it.size })}"
        root.findViewById<View>(R.id.gEmpty).visibility = if (l.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged(); refreshSel()
    }

    private fun pickAlbum() {
        val buckets = all.groupBy { it.bucket }.map { it.key to it.value.size }.sortedByDescending { it.second }
        val names = listOf("همه پوشه‌ها (${all.size})") + buckets.map { "${it.first.ifBlank { "بدون پوشه" }} (${it.second})" }
        MaterialAlertDialogBuilder(act).setTitle("انتخاب پوشه").setItems(names.toTypedArray()) { _, i ->
            album = if (i == 0) null else buckets[i - 1].first
            btnAlbum.text = if (i == 0) "همه پوشه‌ها" else album!!.ifBlank { "بدون پوشه" }
            apply()
        }.show()
    }

    private fun refreshSel() {
        selBar.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        val size = all.filter { it.key in selected }.sumOf { it.size }
        selCount.text = "${selected.size} انتخاب · ${fmtSize(size)}"
        adapter.notifyDataSetChanged()
    }

    fun onBack(): Boolean = if (selected.isNotEmpty()) { selected.clear(); refreshSel(); true } else false

    private fun toggle(key: String) { if (!selected.remove(key)) selected.add(key); refreshSel() }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.img)
        val overlay: View = v.findViewById(R.id.selOverlay)
        val check: View = v.findViewById(R.id.check)
        val cloud: View = v.findViewById(R.id.cloud)
        val dur: TextView = v.findViewById(R.id.dur)
    }

    inner class Adapter : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = shown.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_media, p, false))
        override fun onBindViewHolder(h: VH, pos: Int) {
            val it = shown[pos]
            h.img.load(it.uri) {
                crossfade(true); size(300)
                if (it.video) decoderFactory(VideoFrameDecoder.Factory())
            }
            val sel = it.key in selected
            h.overlay.visibility = if (sel) View.VISIBLE else View.GONE
            h.check.visibility = if (sel) View.VISIBLE else View.GONE
            h.cloud.visibility = if (it.key in uploaded) View.VISIBLE else View.GONE
            when {
                typeGroup.checkedChipId == R.id.tBig -> { h.dur.text = fmtSize(it.size); h.dur.visibility = View.VISIBLE }
                it.video -> {
                    val sec = it.duration / 1000
                    h.dur.text = "▶ %d:%02d".format(sec / 60, sec % 60); h.dur.visibility = View.VISIBLE
                }
                else -> h.dur.visibility = View.GONE
            }
            h.itemView.setOnClickListener { _ ->
                if (selected.isNotEmpty()) toggle(it.key) else act.preview(it)
            }
            h.itemView.setOnLongClickListener { _ -> toggle(it.key); true }
        }
    }
}
