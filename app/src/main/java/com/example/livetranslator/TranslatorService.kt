package com.example.livetranslator

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.File
import kotlin.math.abs

/**
 * Foreground service that owns the screen-audio capture, the subtitle bar and the floating bubble.
 *
 * Bubble menu:
 *  - live translation (Gemini API or offline Vosk + ML Kit)
 *  - record the audio and get a detailed translation from Gemini afterwards
 *  - stop
 */
class TranslatorService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.livetranslator.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_AUTOSTART = "autostart"
        private const val CHANNEL_ID = "translator"
        private const val NOTIF_ID = 1
        private const val SAMPLE_RATE = AudioUtil.SAMPLE_RATE

        @Volatile
        var running = false
    }

    private val main = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null

    // capture
    private var captureThread: Thread? = null
    @Volatile private var alive = false
    @Volatile private var liveWanted = false
    @Volatile private var recordingWanted = false
    private val sinkLock = Any()
    private var liveSink: AudioSink? = null
    private var recorder: WavRecorder? = null
    private var recordFile: File? = null

    // views
    private var wm: WindowManager? = null
    private var subtitleBox: View? = null
    private var bubble: View? = null
    private var menu: View? = null
    private lateinit var arView: TextView
    private lateinit var enView: TextView

    // offline engine: ML Kit translation
    private var translator: Translator? = null
    private var translatorReady = false
    private var seq = 0
    private var applied = 0
    private var lastTranslated = ""
    private var lastTranslateAt = 0L

    private val clearRunnable = Runnable {
        if (::arView.isInitialized) {
            arView.text = ""
            enView.text = ""
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        // Must be called right away, before touching MediaProjection (Android 14+)
        startForegroundNow()

        val code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(code, data)
        if (proj == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        projection = proj
        // Android 14 requires a callback to be registered before capturing
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopSelf() }
            }
        }
        projectionCallback = cb
        proj.registerCallback(cb, main)

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        showSubtitleBar()
        showBubble()
        if (!startCapture(proj)) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true

        if (intent.getBooleanExtra(EXTRA_AUTOSTART, true)) {
            startLive()
        } else {
            setStatus("اضغط الفقاعة لبدء الترجمة")
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNow() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "الترجمة الفورية", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, TranslatorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        @Suppress("DEPRECATION")
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("المترجم يعمل")
            .setContentText("استخدم الفقاعة العائمة، أو اضغط «إيقاف»")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف", stopPi)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    // ---------- Subtitle bar ----------

    private fun showSubtitleBar() {
        val dp = resources.displayMetrics.density
        val manager = wm ?: return

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val h = (16 * dp).toInt()
            val v = (10 * dp).toInt()
            setPadding(h, v, h, v)
            background = GradientDrawable().apply {
                setColor(0xCC000000.toInt())
                cornerRadius = 16 * dp
            }
        }
        arView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_RTL
            text = "جاهز"
        }
        enView = TextView(this).apply {
            setTextColor(0xFFAAAAAA.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
        }
        box.addView(arView)
        box.addView(enView)

        val width = resources.displayMetrics.widthPixels - (32 * dp).toInt()
        val lp = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (110 * dp).toInt()
        }
        manager.addView(box, lp)
        subtitleBox = box
    }

    private fun setStatus(msg: String) {
        main.removeCallbacks(clearRunnable)
        arView.text = msg
        enView.text = ""
    }

    private fun postStatus(msg: String) {
        main.post { setStatus(msg) }
    }

    private fun showPair(en: String, ar: String) {
        enView.text = en
        arView.text = ar
        main.removeCallbacks(clearRunnable)
        main.postDelayed(clearRunnable, 9000)
    }

    // ---------- Floating bubble + menu ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        val dp = resources.displayMetrics.density
        val manager = wm ?: return
        val size = (52 * dp).toInt()

        val tv = TextView(this).apply {
            text = "ترجم"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF378ADD.toInt())
                setStroke((2 * dp).toInt(), Color.WHITE)
            }
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - (4 * dp).toInt()
            y = (resources.displayMetrics.heightPixels * 0.35f).toInt()
        }

        tv.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var moved = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = lp.x
                        startY = lp.y
                        touchX = e.rawX
                        touchY = e.rawY
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - touchX
                        val dy = e.rawY - touchY
                        if (abs(dx) > 10 * dp || abs(dy) > 10 * dp) moved = true
                        if (moved) {
                            lp.x = (startX + dx).toInt()
                            lp.y = (startY + dy).toInt()
                            try {
                                wm?.updateViewLayout(v, lp)
                            } catch (_: Exception) {
                            }
                            if (menu != null) hideMenu()
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) toggleMenu(lp)
                    }
                }
                return true
            }
        })

        manager.addView(tv, lp)
        bubble = tv
    }

    private fun toggleMenu(bubbleLp: WindowManager.LayoutParams) {
        if (menu != null) hideMenu() else showMenu(bubbleLp)
    }

    private fun hideMenu() {
        val m = menu ?: return
        try {
            wm?.removeView(m)
        } catch (_: Exception) {
        }
        menu = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showMenu(bubbleLp: WindowManager.LayoutParams) {
        val dp = resources.displayMetrics.density
        val manager = wm ?: return

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (6 * dp).toInt()
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                setColor(0xF0222222.toInt())
                cornerRadius = 14 * dp
            }
        }

        fun addItem(label: String, action: () -> Unit) {
            val item = TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 14f
                textDirection = View.TEXT_DIRECTION_RTL
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                val h = (12 * dp).toInt()
                val v = (11 * dp).toInt()
                setPadding(h, v, h, v)
                setOnClickListener {
                    hideMenu()
                    action()
                }
            }
            box.addView(item)
        }

        addItem(if (liveWanted) "إيقاف الترجمة الحية" else "ترجمة حية") {
            if (liveWanted) stopLive() else startLive()
        }
        addItem(if (recordingWanted) "إيقاف التسجيل وترجم" else "سجّل وترجم") {
            if (recordingWanted) stopRecordingAndTranslate() else startRecording()
        }
        addItem("إيقاف التطبيق") { stopSelf() }

        val menuWidth = (180 * dp).toInt()
        val bubbleSize = (52 * dp).toInt()
        val screenW = resources.displayMetrics.widthPixels
        val gap = (6 * dp).toInt()
        val lp = WindowManager.LayoutParams(
            menuWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (bubbleLp.x > screenW / 2) bubbleLp.x - menuWidth - gap else bubbleLp.x + bubbleSize + gap
            y = bubbleLp.y
        }
        box.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) hideMenu()
            false
        }
        manager.addView(box, lp)
        menu = box
    }

    // ---------- Live translation ----------

    private fun startLive() {
        if (liveWanted) return
        val engine = Prefs.liveEngine(this)
        val key = Prefs.apiKey(this)
        if (engine == Prefs.ENGINE_GEMINI && key.isBlank()) {
            setStatus("أدخل مفتاح Gemini في التطبيق أو اختر الوضع الأوفلاين")
            return
        }
        if (engine == Prefs.ENGINE_OFFLINE && !ModelManager.isReady(this)) {
            setStatus("نزّل نموذج الصوت من داخل التطبيق أولاً")
            return
        }
        liveWanted = true
        setStatus("جاري التحضير…")
        if (engine == Prefs.ENGINE_OFFLINE) ensureTranslator()
        val liveModel = Prefs.liveModel(this)

        Thread {
            var sink: AudioSink? = null
            try {
                sink = if (engine == Prefs.ENGINE_GEMINI) {
                    GeminiSink(
                        key, liveModel,
                        onResult = { en, ar -> main.post { showPair(en, ar) } },
                        onError = { msg -> postStatus(msg) }
                    )
                } else {
                    VoskSink(ModelManager.modelDir(this).absolutePath) { text, isFinal ->
                        main.post { onOfflineSpeech(text, isFinal) }
                    }
                }
            } catch (e: Throwable) {
                postStatus("خطأ: ${e.message}")
            }
            main.post {
                val s = sink
                if (s == null) {
                    liveWanted = false
                } else if (liveWanted) {
                    synchronized(sinkLock) { liveSink = s }
                    setStatus("أستمع… شغّل الفيديو")
                } else {
                    Thread { s.close() }.start()
                }
            }
        }.start()
    }

    private fun stopLive() {
        liveWanted = false
        var s: AudioSink? = null
        synchronized(sinkLock) {
            s = liveSink
            liveSink = null
        }
        val old = s
        if (old != null) Thread { old.close() }.start()
        setStatus("توقفت الترجمة الحية")
        main.postDelayed(clearRunnable, 2500)
    }

    private fun ensureTranslator() {
        if (translator != null) return
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.ARABIC)
            .build()
        val tr = Translation.getClient(options)
        translator = tr
        tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { translatorReady = true }
            .addOnFailureListener { setStatus("تعذّر تنزيل نموذج الترجمة، تأكد من الإنترنت") }
    }

    private fun translateOffline(text: String) {
        val tr = translator
        if (tr == null || !translatorReady) {
            arView.text = "(جاري تحميل نموذج الترجمة…)"
            return
        }
        val id = ++seq
        tr.translate(text).addOnSuccessListener { result ->
            if (id > applied) {
                applied = id
                arView.text = result
            }
        }
    }

    private fun onOfflineSpeech(text: String, isFinal: Boolean) {
        if (!liveWanted) return
        enView.text = text
        val now = SystemClock.elapsedRealtime()
        if (text != lastTranslated && (isFinal || now - lastTranslateAt > 1200)) {
            lastTranslated = text
            lastTranslateAt = now
            translateOffline(text)
        }
        main.removeCallbacks(clearRunnable)
        main.postDelayed(clearRunnable, 7000)
    }

    // ---------- Record, then translate in detail ----------

    private fun startRecording() {
        if (recordingWanted) return
        if (Prefs.apiKey(this).isBlank()) {
            setStatus("أدخل مفتاح Gemini في التطبيق أولاً")
            return
        }
        val f = File(cacheDir, "rec_${System.currentTimeMillis()}.wav")
        val r = try {
            WavRecorder(f)
        } catch (e: Exception) {
            setStatus("تعذّر بدء التسجيل")
            return
        }
        synchronized(sinkLock) { recorder = r }
        recordFile = f
        recordingWanted = true
        setStatus("● يسجّل… افتح الفقاعة واختر «إيقاف التسجيل وترجم»")
    }

    private fun stopRecordingAndTranslate() {
        if (!recordingWanted) return
        recordingWanted = false
        var r: WavRecorder? = null
        synchronized(sinkLock) {
            r = recorder
            recorder = null
        }
        val rec = r
        rec?.close()
        val f = recordFile
        recordFile = null
        val seconds = rec?.durationSec ?: 0L
        if (f == null || seconds < 2) {
            f?.delete()
            setStatus("التسجيل قصير جداً")
            main.postDelayed(clearRunnable, 2500)
            return
        }

        setStatus("جاري الترجمة المفصلة…")
        val style = Prefs.style(this)
        Thread {
            try {
                val text = FileTranslator.run(this, null, f, style) { msg -> postStatus(msg) }
                Notifier.result(this, "اكتملت الترجمة", text.take(120))
                main.post {
                    setStatus("اكتملت الترجمة ✓")
                    main.postDelayed(clearRunnable, 3000)
                    try {
                        startActivity(
                            Intent(this, ResultActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Exception) {
                    }
                }
            } catch (e: Exception) {
                postStatus("فشلت الترجمة: " + (e.message ?: "").take(100))
            } finally {
                f.delete()
            }
        }.start()
    }

    // ---------- Audio capture ----------

    @SuppressLint("MissingPermission")
    private fun startCapture(proj: MediaProjection): Boolean {
        val config = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuf, 4096) * 4)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (e: Exception) {
            postStatus("تعذّر بدء الالتقاط: ${e.message}")
            return false
        }
        alive = true
        captureThread = Thread { captureLoop(rec) }.also { it.start() }
        return true
    }

    /** Reads audio only while something needs it (live translation or recording). */
    private fun captureLoop(rec: AudioRecord) {
        val buf = ShortArray(2048)
        var started = false
        try {
            while (alive) {
                if (!liveWanted && !recordingWanted) {
                    if (started) {
                        try {
                            rec.stop()
                        } catch (_: Exception) {
                        }
                        started = false
                    }
                    Thread.sleep(80)
                    continue
                }
                if (!started) {
                    rec.startRecording()
                    started = true
                }
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) {
                    postStatus("انقطع التقاط الصوت")
                    break
                }
                if (n == 0) continue
                synchronized(sinkLock) {
                    try {
                        liveSink?.onAudio(buf, n)
                    } catch (_: Exception) {
                    }
                    try {
                        recorder?.onAudio(buf, n)
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            postStatus("خطأ: ${e.message}")
        } finally {
            try {
                if (started) rec.stop()
            } catch (_: Exception) {
            }
            rec.release()
        }
    }

    override fun onDestroy() {
        running = false
        alive = false
        liveWanted = false
        recordingWanted = false
        try {
            captureThread?.join(1000)
        } catch (_: InterruptedException) {
        }

        var s: AudioSink? = null
        var r: WavRecorder? = null
        synchronized(sinkLock) {
            s = liveSink
            liveSink = null
            r = recorder
            recorder = null
        }
        val oldSink = s
        if (oldSink != null) Thread { oldSink.close() }.start()
        r?.close()
        recordFile?.delete()

        projectionCallback?.let { projection?.unregisterCallback(it) }
        projection?.stop()
        translator?.close()

        hideMenu()
        for (v in listOf(bubble, subtitleBox)) {
            if (v != null) {
                try {
                    wm?.removeView(v)
                } catch (_: Exception) {
                }
            }
        }
        bubble = null
        subtitleBox = null
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
