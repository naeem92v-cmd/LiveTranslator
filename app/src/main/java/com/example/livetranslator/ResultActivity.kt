package com.example.livetranslator

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Shows the last full translation, with copy / share / summary buttons. */
class ResultActivity : Activity() {

    private lateinit var body: TextView
    private lateinit var summaryBtn: Button
    private var text = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()

        val f = FileTranslator.lastFile(this)
        text = if (f.exists()) f.readText() else ""

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (36 * dp).toInt(), pad, pad)
        }

        val title = TextView(this).apply {
            this.text = "الترجمة"
            textSize = 22f
            textDirection = View.TEXT_DIRECTION_RTL
        }
        body = TextView(this).apply {
            this.text = if (text.isBlank()) "لا توجد ترجمة بعد." else text
            textSize = 17f
            setLineSpacing(0f, 1.3f)
            textDirection = View.TEXT_DIRECTION_RTL
            setTextIsSelectable(true)
            setPadding(0, pad, 0, pad)
        }

        val copyBtn = Button(this).apply {
            this.text = "نسخ الكل"
            setOnClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("translation", body.text))
                Toast.makeText(this@ResultActivity, "تم النسخ", Toast.LENGTH_SHORT).show()
            }
        }
        val shareBtn = Button(this).apply {
            this.text = "مشاركة"
            setOnClickListener {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, body.text.toString())
                }
                startActivity(Intent.createChooser(send, "مشاركة الترجمة"))
            }
        }
        summaryBtn = Button(this).apply {
            this.text = "اطلب ملخصاً"
            setOnClickListener { summarize() }
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(copyBtn)
            addView(shareBtn)
            addView(summaryBtn)
        }

        root.addView(title)
        root.addView(buttons)
        root.addView(body)
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun summarize() {
        if (text.isBlank()) return
        val key = Prefs.apiKey(this)
        if (key.isBlank()) {
            Toast.makeText(this, "أدخل مفتاح Gemini API في الإعدادات", Toast.LENGTH_LONG).show()
            return
        }
        summaryBtn.isEnabled = false
        summaryBtn.text = "جاري التلخيص…"
        val model = Prefs.fileModel(this)
        val source = text
        Thread {
            try {
                val summary = GeminiClient.generate(
                    key, model,
                    listOf(
                        Part.Text(
                            "لخّص النص التالي بالعربية: فقرة قصيرة ثم خمس نقاط للأفكار الأساسية.\n\n" + source
                        )
                    )
                )
                runOnUiThread {
                    if (!isDestroyed) {
                        body.text = "الملخص:\n$summary\n\n———\n\n$source"
                        summaryBtn.text = "اطلب ملخصاً"
                        summaryBtn.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isDestroyed) {
                        summaryBtn.text = "اطلب ملخصاً"
                        summaryBtn.isEnabled = true
                        Toast.makeText(
                            this, "فشل التلخيص: " + (e.message ?: "").take(120), Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }.start()
    }
}
