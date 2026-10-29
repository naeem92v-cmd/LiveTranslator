package com.example.livetranslator

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Shows the last full translation, or a saved session, with copy / share / summary buttons.
 * A session's lines can be edited (tap a line) and the session renamed.
 */
class ResultActivity : Activity() {

    companion object {
        const val EXTRA_SESSION = "session"
    }

    private var sessionId: String? = null
    private var session: Session? = null
    private lateinit var body: TextView
    private lateinit var titleView: TextView
    private lateinit var linesBox: LinearLayout
    private var summaryText = ""
    private lateinit var summaryBtn: Button
    private var text = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()

        sessionId = intent.getStringExtra(EXTRA_SESSION)
        val sid = sessionId
        session = if (sid != null) SessionStore.get(this, sid) else null
        val ses = session
        text = if (sid != null) {
            if (ses != null) SegmentTools.formatText(ses.segments) else ""
        } else {
            val f = FileTranslator.lastFile(this)
            if (f.exists()) f.readText() else ""
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (36 * dp).toInt(), pad, pad)
        }

        titleView = TextView(this).apply {
            this.text = ses?.title ?: "الترجمة"
            textSize = 22f
            textDirection = View.TEXT_DIRECTION_RTL
        }
        body = TextView(this).apply {
            this.text = if (text.isBlank()) "لا توجد ترجمة بعد." else text
            // a session shows its lines one by one below; this view then holds only a summary
            if (ses != null) visibility = View.GONE
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
                cm.setPrimaryClip(ClipData.newPlainText("translation", fullText()))
                Toast.makeText(this@ResultActivity, "تم النسخ", Toast.LENGTH_SHORT).show()
            }
        }
        val shareBtn = Button(this).apply {
            this.text = "مشاركة"
            setOnClickListener {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, fullText())
                }
                startActivity(Intent.createChooser(send, "مشاركة الترجمة"))
            }
        }
        summaryBtn = Button(this).apply {
            this.text = "اطلب ملخصاً"
            setOnClickListener { summarize() }
        }

        val srtBtn = Button(this).apply {
            this.text = "تصدير ملف ترجمة SRT"
            setOnClickListener { exportSrt() }
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(copyBtn)
            addView(shareBtn)
            addView(summaryBtn)
            if (sessionId != null) addView(srtBtn)
            if (ses != null) {
                addView(Button(this@ResultActivity).apply {
                    this.text = "إعادة تسمية الجلسة"
                    setOnClickListener { rename() }
                })
            }
        }

        linesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        root.addView(titleView)
        root.addView(buttons)
        root.addView(body)
        if (ses != null) {
            root.addView(TextView(this).apply {
                this.text = "اضغط أي سطر لتعديله"
                textSize = 13f
                textDirection = View.TEXT_DIRECTION_RTL
                setPadding(0, pad, 0, 0)
            })
            root.addView(linesBox)
            renderLines()
        }
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun fullText(): String =
        if (session == null) body.text.toString()
        else if (summaryText.isNotBlank()) "الملخص:\n$summaryText\n\n———\n\n$text"
        else text

    private fun renderLines() {
        val s = session ?: return
        val dp = resources.displayMetrics.density
        linesBox.removeAllViews()
        for ((i, seg) in s.segments.withIndex()) {
            val sec = seg.start.toInt()
            val sb = StringBuilder(String.format(Locale.US, "[%02d:%02d] ", sec / 60, sec % 60))
            if (seg.speaker.isNotBlank()) sb.append(seg.speaker).append(": ")
            sb.append(seg.ar)
            if (seg.en.isNotBlank()) sb.append("\n").append(seg.en)
            linesBox.addView(TextView(this).apply {
                this.text = sb.toString()
                textSize = 16f
                setLineSpacing(0f, 1.2f)
                textDirection = View.TEXT_DIRECTION_RTL
                setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
                setOnClickListener { editLine(i) }
            })
        }
    }

    private fun saveSession(s: Session) {
        SessionStore.update(this, s)
        session = s
        text = SegmentTools.formatText(s.segments)
        titleView.text = s.title
        renderLines()
    }

    private fun editLine(index: Int) {
        val s = session ?: return
        val seg = s.segments.getOrNull(index) ?: return
        val dp = resources.displayMetrics.density
        fun field(value: String, hintText: String) = EditText(this).apply {
            setText(value)
            hint = hintText
            textDirection = View.TEXT_DIRECTION_RTL
        }
        val sp = field(seg.speaker, "المتحدث")
        val ar = field(seg.ar, "الترجمة العربية")
        val en = field(seg.en, "النص الأصلي")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * dp).toInt()
            setPadding(p, p / 2, p, 0)
            addView(sp)
            addView(ar)
            addView(en)
        }
        AlertDialog.Builder(this)
            .setTitle("تعديل السطر")
            .setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                val segs = s.segments.toMutableList()
                segs[index] = Segment(
                    seg.start, seg.end, sp.text.toString().trim(),
                    en.text.toString().trim(), ar.text.toString().trim()
                )
                saveSession(Session(s.id, s.title, s.durationSec, segs))
            }
            .setNeutralButton("حذف السطر") { _, _ ->
                val segs = s.segments.toMutableList()
                segs.removeAt(index)
                saveSession(Session(s.id, s.title, s.durationSec, segs))
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun rename() {
        val s = session ?: return
        val input = EditText(this).apply {
            setText(s.title)
            setSingleLine()
            textDirection = View.TEXT_DIRECTION_RTL
        }
        AlertDialog.Builder(this)
            .setTitle("اسم الجلسة")
            .setView(input)
            .setPositiveButton("حفظ") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) saveSession(Session(s.id, t, s.durationSec, s.segments))
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    /** Saves the session as a .srt file in the Downloads folder (no storage permission needed). */
    private fun exportSrt() {
        val id = sessionId ?: return
        val session = SessionStore.get(this, id) ?: return
        val srt = SegmentTools.toSrt(session.segments)
        val name = "translation_$id.srt"
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                // text/plain would make MediaStore rename the file to .srt.txt
                put(MediaStore.MediaColumns.MIME_TYPE, "application/x-subrip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("تعذّر إنشاء الملف")
            contentResolver.openOutputStream(uri)?.use { it.write(srt.toByteArray(Charsets.UTF_8)) }
            Toast.makeText(this, "حُفظ في التنزيلات: $name", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "تعذّر الحفظ: " + (e.message ?: "").take(100), Toast.LENGTH_LONG).show()
        }
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
                        summaryText = summary
                        body.visibility = View.VISIBLE
                        body.text = if (session != null) "الملخص:\n$summary"
                        else "الملخص:\n$summary\n\n———\n\n$source"
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
