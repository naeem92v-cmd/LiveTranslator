package com.example.livetranslator

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_OVERLAY = 2
        private const val REQ_PROJECTION = 3
        private const val REQ_PICK = 4
    }

    private lateinit var status: TextView
    private lateinit var startBtn: Button
    private lateinit var pickBtn: Button
    private lateinit var keyInput: EditText
    private lateinit var liveModelInput: EditText
    private lateinit var fileModelInput: EditText
    private lateinit var engineGroup: RadioGroup
    private lateinit var radioGemini: RadioButton
    private lateinit var radioOffline: RadioButton
    private lateinit var sessionsBox: LinearLayout
    private lateinit var styleGroup: RadioGroup
    private val styleButtons = ArrayList<RadioButton>()

    @Volatile private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        loadSettings()
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    // ---------- UI ----------

    private fun label(text: String, size: Float = 14f, topDp: Int = 0): TextView {
        val dp = resources.displayMetrics.density
        return TextView(this).apply {
            this.text = text
            textSize = size
            textDirection = View.TEXT_DIRECTION_RTL
            setPadding(0, (topDp * dp).toInt(), 0, 0)
        }
    }

    private fun buildUi() {
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (40 * dp).toInt(), pad, pad)
        }

        root.addView(label("مترجم الفيديو الفوري", 24f).apply { gravity = Gravity.CENTER })
        root.addView(
            label("يترجم صوت أي فيديو إنكليزي إلى العربية: مباشرة فوق الشاشة، أو ترجمة مفصلة لفيديو كامل.", 14f, 8)
                .apply { gravity = Gravity.CENTER }
        )

        status = label("جاهز", 14f, 16).apply { gravity = Gravity.CENTER }
        root.addView(status)

        startBtn = Button(this).apply {
            text = "تشغيل الفقاعة العائمة"
            setOnClickListener { startFlow() }
        }
        pickBtn = Button(this).apply {
            text = "ترجمة ملف فيديو أو صوت"
            setOnClickListener { pickFile() }
        }
        val stopBtn = Button(this).apply {
            text = "إيقاف الفقاعة"
            setOnClickListener {
                stopService(Intent(this@MainActivity, TranslatorService::class.java))
                setStatus("تم الإيقاف")
            }
        }
        val lastBtn = Button(this).apply {
            text = "آخر ترجمة"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, ResultActivity::class.java))
            }
        }
        root.addView(startBtn)
        root.addView(pickBtn)
        root.addView(lastBtn)
        root.addView(stopBtn)

        // ----- settings -----
        root.addView(label("الإعدادات", 18f, 24))

        root.addView(label("مفتاح Gemini API (من Google AI Studio)", 13f, 8))
        keyInput = EditText(this).apply {
            hint = "الصق المفتاح هنا"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        root.addView(keyInput)

        root.addView(label("محرك الترجمة الحية", 13f, 12))
        engineGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        radioGemini = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Gemini (أدق، يحتاج إنترنت)"
        }
        radioOffline = RadioButton(this).apply {
            id = View.generateViewId()
            text = "أوفلاين: Vosk + ML Kit (بدون إرسال الصوت)"
        }
        engineGroup.addView(radioGemini)
        engineGroup.addView(radioOffline)
        root.addView(engineGroup)

        root.addView(label("نوع ترجمة الملفات", 13f, 12))
        styleGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val names = listOf("مفصلة كاملة", "مع الأوقات", "ملخص قصير")
        for (n in names) {
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = n
            }
            styleButtons.add(rb)
            styleGroup.addView(rb)
        }
        root.addView(styleGroup)

        root.addView(label("متقدم: أسماء النماذج (اتركها فارغة للافتراضي)", 13f, 12))
        liveModelInput = EditText(this).apply {
            hint = "نموذج الترجمة الحية: ${Prefs.DEFAULT_LIVE_MODEL}"
            setSingleLine()
        }
        fileModelInput = EditText(this).apply {
            hint = "نموذج ترجمة الملفات: ${Prefs.DEFAULT_FILE_MODEL}"
            setSingleLine()
        }
        root.addView(liveModelInput)
        root.addView(fileModelInput)

        val saveBtn = Button(this).apply {
            text = "حفظ الإعدادات"
            setOnClickListener {
                saveSettings()
                Toast.makeText(this@MainActivity, "تم الحفظ", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(saveBtn)

        root.addView(label("الجلسات المحفوظة", 18f, 24))
        sessionsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(sessionsBox)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refreshSessions()
    }

    private fun refreshSessions() {
        sessionsBox.removeAllViews()
        val list = SessionStore.list(this)
        if (list.isEmpty()) {
            sessionsBox.addView(label("لا توجد جلسات بعد. من الفقاعة اختر «سجّل وترجم (حفظ)».", 13f, 4))
            return
        }
        for (s in list) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val t = TextView(this).apply {
                text = s.title
                textSize = 14f
                textDirection = View.TEXT_DIRECTION_RTL
                setOnClickListener {
                    startActivity(
                        Intent(this@MainActivity, ResultActivity::class.java)
                            .putExtra(ResultActivity.EXTRA_SESSION, s.id)
                    )
                }
            }
            t.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val d = Button(this).apply {
                text = "حذف"
                setOnClickListener {
                    SessionStore.delete(this@MainActivity, s.id)
                    refreshSessions()
                }
            }
            row.addView(t)
            row.addView(d)
            sessionsBox.addView(row)
        }
    }

    private fun loadSettings() {
        keyInput.setText(Prefs.apiKey(this))
        if (Prefs.liveEngine(this) == Prefs.ENGINE_OFFLINE) radioOffline.isChecked = true
        else radioGemini.isChecked = true
        styleButtons[Prefs.style(this).ordinal].isChecked = true
        val lm = Prefs.liveModel(this)
        val fm = Prefs.fileModel(this)
        if (lm != Prefs.DEFAULT_LIVE_MODEL) liveModelInput.setText(lm)
        if (fm != Prefs.DEFAULT_FILE_MODEL) fileModelInput.setText(fm)
    }

    private fun currentStyle(): Style {
        val idx = styleButtons.indexOfFirst { it.isChecked }
        return Style.values().getOrElse(if (idx < 0) 0 else idx) { Style.DETAILED }
    }

    private fun saveSettings() {
        val engine = if (radioOffline.isChecked) Prefs.ENGINE_OFFLINE else Prefs.ENGINE_GEMINI
        Prefs.save(
            this,
            keyInput.text.toString(),
            engine,
            liveModelInput.text.toString(),
            fileModelInput.text.toString(),
            currentStyle()
        )
    }

    private fun setStatus(msg: String) {
        if (!isDestroyed && !isFinishing) status.text = msg
    }

    private fun postStatus(msg: String) {
        runOnUiThread { setStatus(msg) }
    }

    // ---------- Bubble start flow ----------

    private fun startFlow() {
        saveSettings()

        // 1) Microphone permission (needed by AudioPlaybackCapture) + notifications
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
            requestPermissions(perms.toTypedArray(), REQ_PERMS)
            return
        }

        // 2) Draw over other apps
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "فعّل «العرض فوق التطبيقات» لهذا التطبيق ثم ارجع", Toast.LENGTH_LONG).show()
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                REQ_OVERLAY
            )
            return
        }

        // 3) Engine requirements
        if (radioGemini.isChecked && Prefs.apiKey(this).isBlank()) {
            setStatus("أدخل مفتاح Gemini API في الإعدادات أو اختر الوضع الأوفلاين")
            return
        }
        if (radioOffline.isChecked && !ModelManager.isReady(this)) {
            startBtn.isEnabled = false
            Thread {
                try {
                    ModelManager.download(this) { msg -> postStatus(msg) }
                    runOnUiThread {
                        startBtn.isEnabled = true
                        startFlow()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        startBtn.isEnabled = true
                        setStatus("فشل تنزيل النموذج (تأكد من الإنترنت): ${e.message}")
                    }
                }
            }.start()
            return
        }

        // 4) Screen-capture consent (this also grants system-audio capture)
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_PERMS) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startFlow()
            } else {
                setStatus("إذن المايك مطلوب لالتقاط صوت الفيديو")
            }
        }
    }

    // ---------- File translation ----------

    private fun pickFile() {
        saveSettings()
        if (Prefs.apiKey(this).isBlank()) {
            setStatus("أدخل مفتاح Gemini API في الإعدادات أولاً")
            return
        }
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*"))
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_PICK)
    }

    private fun handleShare(i: Intent?) {
        if (i == null || i.action != Intent.ACTION_SEND) return
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            i.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        // Only handle a share once
        i.action = null
        if (uri == null) {
            setStatus("لم يصل ملف من المشاركة")
            return
        }
        translateUri(uri)
    }

    private fun translateUri(uri: Uri) {
        saveSettings()
        if (Prefs.apiKey(this).isBlank()) {
            setStatus("أدخل مفتاح Gemini API في الإعدادات ثم أعد المشاركة")
            return
        }
        if (busy) {
            setStatus("توجد ترجمة قيد العمل، انتظر انتهاءها")
            return
        }
        busy = true
        pickBtn.isEnabled = false
        val style = currentStyle()
        setStatus("جاري البدء…")

        val app = applicationContext
        Thread {
            try {
                val text = FileTranslator.run(app, uri, null, style) { msg -> postStatus(msg) }
                Notifier.result(app, "اكتملت الترجمة", text.take(120))
                runOnUiThread {
                    setStatus("اكتملت الترجمة ✓")
                    if (!isDestroyed && !isFinishing) {
                        startActivity(Intent(this, ResultActivity::class.java))
                    }
                }
            } catch (e: Exception) {
                postStatus("فشلت الترجمة: " + (e.message ?: e.javaClass.simpleName).take(160))
            } finally {
                busy = false
                runOnUiThread { if (!isDestroyed) pickBtn.isEnabled = true }
            }
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_OVERLAY -> {
                if (Settings.canDrawOverlays(this)) startFlow()
                else setStatus("إذن العرض فوق التطبيقات مطلوب")
            }
            REQ_PROJECTION -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val svc = Intent(this, TranslatorService::class.java).apply {
                        putExtra(TranslatorService.EXTRA_CODE, resultCode)
                        putExtra(TranslatorService.EXTRA_DATA, data)
                        putExtra(TranslatorService.EXTRA_AUTOSTART, false)
                    }
                    startForegroundService(svc)
                    setStatus("الفقاعة تعمل. افتح الفيديو واضغط الفقاعة الزرقاء.")
                    moveTaskToBack(true)
                } else {
                    setStatus("تم رفض إذن الالتقاط")
                }
            }
            REQ_PICK -> {
                if (resultCode == Activity.RESULT_OK) {
                    val uri = data?.data
                    if (uri != null) translateUri(uri)
                }
            }
        }
    }
}
