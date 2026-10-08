package ir.hamed.tgbackup

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlin.concurrent.thread

class SetupActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        val store = Store(this)
        val token = findViewById<TextInputEditText>(R.id.token)
        val chat = findViewById<TextInputEditText>(R.id.chat)
        val api = findViewById<TextInputEditText>(R.id.api)
        val msg = findViewById<TextView>(R.id.msg)
        val connect = findViewById<MaterialButton>(R.id.btnConnect)
        val editing = intent.getBooleanExtra("edit", false)

        chat.setText(store.chat); api.setText(store.apiRaw)
        if (store.apiRaw.isNotBlank()) findViewById<View>(R.id.apiBox).visibility = View.VISIBLE
        if (editing) {
            findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tokenBox).hint = "توکن (خالی = همان قبلی)"
            findViewById<View>(R.id.btnCancel).apply { visibility = View.VISIBLE; setOnClickListener { finish() } }
        }
        findViewById<View>(R.id.advToggle).setOnClickListener {
            val b = findViewById<View>(R.id.apiBox); b.visibility = if (b.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        fun show(text: String, ok: Boolean) = runOnUiThread {
            msg.text = text
            msg.setTextColor(ContextCompat.getColor(this, if (ok) R.color.ok else R.color.danger))
        }
        fun tempStore(): Store {
            if (token.text.toString().isNotBlank()) store.token = token.text.toString()
            store.apiRaw = api.text.toString()
            return store
        }

        findViewById<View>(R.id.btnFind).setOnClickListener {
            if (token.text.isNullOrBlank() && store.token.isBlank()) { show("اول توکن رو وارد کن", false); return@setOnClickListener }
            show("در حال جستجو…", true)
            val prevChat = store.chat
            thread {
                try {
                    val chats = Telegram(tempStore()).findChats()
                    runOnUiThread {
                        if (chats.isEmpty()) show("کانالی پیدا نشد. یک پیام جدید در کانال بفرست و دوباره بزن", false)
                        else MaterialAlertDialogBuilder(this).setTitle("کانال رو انتخاب کن")
                            .setItems(chats.map { "${it.second}\n${it.first}" }.toTypedArray()) { _, i ->
                                chat.setText(chats[i].first); show("✓ ${chats[i].second}", true)
                            }.show()
                    }
                } catch (e: Exception) { show("خطا: ${e.message}", false) }
                if (store.chat != prevChat) store.chat = prevChat
            }
        }

        connect.setOnClickListener {
            if (chat.text.isNullOrBlank()) { show("آیدی کانال رو وارد کن", false); return@setOnClickListener }
            connect.isEnabled = false; show("در حال اتصال…", true)
            thread {
                try {
                    val s = tempStore(); s.chat = chat.text.toString()
                    val bot = Telegram(s).getMe()
                    val err = Telegram(s).test()
                    if (err != null) throw Exception(
                        if (err.contains("chat not found", true)) "کانال پیدا نشد؛ آیدی رو چک کن یا ربات رو ادمین کن" else err)
                    s.p.edit().putString("botName", bot).apply()
                    s.addHistory("🔗 متصل شد به @$bot")
                    runOnUiThread {
                        if (!editing) startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    }
                } catch (e: Exception) {
                    show("خطا: ${e.message}\n(VPN روشن است؟)", false)
                    runOnUiThread { connect.isEnabled = true }
                }
            }
        }
    }
}
