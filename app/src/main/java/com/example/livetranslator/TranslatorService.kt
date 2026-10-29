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
import android.widget.Toast
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
 *  - record the video's audio once; Gemini returns timed, speaker-labelled translation (a "session")
 *  - follow a saved session: when the video is replayed, the audio fingerprint tells where we are
 *    and the matching line is shown with the speaker name
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
    @Volatile private var followWanted = false
    private val sinkLock = Any()
    private var liveSink: AudioSink? = null
    private var recorder: SessionRecorder? = null
    private var followSink: FollowSink? = null
    private var recordFile: File? = null

    // views
    private var wm: WindowManager? = null
    private var subtitleBox: View? = null
    private var subtitleLp: WindowManager.LayoutParams? = null
    private var arabicOnly = false
    private var bubble: View? = null
    private var menu: View? = null
    private lateinit var spView: TextView
    private lateinit var arView: TextView
    private lateinit var enView: TextView

    // offline engine: ML Kit translation
    private var translator: Translator? = null
    private var translatorReady = false
    private var seq = 0
    private var applied = 0
    private var lastTranslated = ""
    private var lastTranslateAt = 0L

    // follow mode
    private var syncMs = 0
    private var followShown = false
    private val speakerColors = intArrayOf(
        0xFF8AB4F8.toInt(), 0xFFF28B82.toInt(), 0xFF81C995.toInt(), 0xFFFDD663.toInt(), 0xFFC58AF9.toInt()
    )

    private val clearRunnable = Runnable {
        if (::arView.isInitialized) {
            arView.text = ""
            enView.text = ""
            spView.visibility = View.GONE
        }
    }

    private val followTick = object : Runnable {
        override fun run() {
            if (!followWanted) return
            val sink = synchronized(sinkLock) { followSink }
            val pos = sink?.position()
            var found: Segment? = null
            var idx = 0
            if (pos != null) {
                val session = pos.first
                val t = pos.second + syncMs / 1000.0
                for (s in session.segments) {
                    if (t >= s.start - 0.15 && t <= s.end + 0.5) found = s
                }
                val f = found
                if (f != null) idx = session.speakers.indexOf(f.speaker).coerceAtLeast(0)
            }
            val seg = found
            if (seg != null) {
                showSegment(seg, idx)
            } else if (followShown) {
                followShown = false
                arView.text = ""
                enView.text = ""
                spView.visibility = View.GONE
            }
            main.postDelayed(this, 250)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Must be called right away, before touching MediaProjection (Android 14+).
        // Also when already running: every startForegroundService() needs a startForeground().
        startForegroundNow()
        if (running) return START_NOT_STICKY

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

        syncMs = Prefs.syncMs(this)
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
        spView = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_RTL
            visibility = View.GONE
        }
        val size = Prefs.subtitleSize(this).toFloat()
        arabicOnly = Prefs.arabicOnly(this)
        arView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = size
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_RTL
            text = "جاهز"
        }
        enView = TextView(this).apply {
            setTextColor(0xFFAAAAAA.toInt())
            textSize = size * 0.65f
            gravity = Gravity.CENTER
            if (arabicOnly) visibility = View.GONE
        }
        box.addView(spView)
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
            y = (Prefs.subtitleOffsetDp(this@TranslatorService) * dp).toInt()
            // Android 12+ blocks touches through untrusted overlays more opaque than 0.8
            alpha = 0.8f
        }
        manager.addView(box, lp)
        subtitleBox = box
        subtitleLp = lp
    }

    /** Moves the subtitle bar up (positive) or down by [deltaDp] and remembers the place. */
    private fun moveSubtitles(deltaDp: Int) {
        val box = subtitleBox ?: return
        val lp = subtitleLp ?: return
        val dp = resources.displayMetrics.density
        val maxDp = (resources.displayMetrics.heightPixels / dp).toInt() - 120
        val v = (Prefs.subtitleOffsetDp(this) + deltaDp).coerceIn(0, maxOf(maxDp, 0))
        Prefs.setSubtitleOffsetDp(this, v)
        lp.y = (v * dp).toInt()
        try {
            wm?.updateViewLayout(box, lp)
        } catch (_: Exception) {
        }
    }

    private fun setStatus(msg: String) {
        main.removeCallbacks(clearRunnable)
        spView.visibility = View.GONE
        arView.text = msg
        enView.text = ""
    }

    private fun postStatus(msg: String) {
        main.post { setStatus(msg) }
    }

    private fun showEnglishOnly(en: String) {
        if (!liveWanted) return
        spView.visibility = View.GONE
        enView.text = en
        // with "Arabic only" the original text still shows until its translation arrives
        if (arabicOnly) arView.text = en
        main.removeCallbacks(clearRunnable)
        main.postDelayed(clearRunnable, 9000)
    }

    private fun showPair(en: String, ar: String) {
        spView.visibility = View.GONE
        enView.text = en
        arView.text = ar
        main.removeCallbacks(clearRunnable)
        main.postDelayed(clearRunnable, 9000)
    }

    /** Streaming engine: the texts grow while the person speaks. */
    private fun showStream(src: String, ar: String) {
        if (!liveWanted) return
        spView.visibility = View.GONE
        enView.text = src
        arView.text = if (ar.isNotBlank()) ar else if (arabicOnly) "…" else ""
        main.removeCallbacks(clearRunnable)
        main.postDelayed(clearRunnable, 7000)
    }

    private fun showSegment(seg: Segment, speakerIdx: Int) {
        main.removeCallbacks(clearRunnable)
        if (seg.speaker.isNotBlank()) {
            spView.text = seg.speaker
            spView.setTextColor(speakerColors[speakerIdx % speakerColors.size])
            spView.visibility = View.VISIBLE
        } else {
            spView.visibility = View.GONE
        }
        arView.text = seg.ar
        enView.text = seg.en
        followShown = true
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
        addItem(if (recordingWanted) "إيقاف التسجيل وحفظ" else "سجّل وترجم (حفظ)") {
            if (recordingWanted) stopRecordingAndTranslate() else startRecording()
        }
        addItem(if (followWanted) "إيقاف المتابعة" else "متابعة ترجمة محفوظة") {
            if (followWanted) stopFollow() else startFollow()
        }
        if (followWanted) {
            addItem("الترجمة أبكر (+0.5 ث)") { adjustSync(500) }
            addItem("الترجمة أبطأ (−0.5 ث)") { adjustSync(-500) }
        }
        addItem("رفع شريط الترجمة ↑") { moveSubtitles(40) }
        addItem("خفض شريط الترجمة ↓") { moveSubtitles(-40) }
        addItem("إيقاف التطبيق") { stopSelf() }

        val menuWidth = (190 * dp).toInt()
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

    private fun adjustSync(deltaMs: Int) {
        syncMs += deltaMs
        Prefs.setSyncMs(this, syncMs)
        val sign = if (syncMs > 0) "+" else ""
        Toast.makeText(this, "ضبط التزامن: $sign${syncMs / 1000.0} ث", Toast.LENGTH_SHORT).show()
    }

    // ---------- Live translation ----------

    private fun startLive() {
        if (liveWanted) return
        if (followWanted) stopFollow()
        val engine = Prefs.liveEngine(this)
        val key = Prefs.apiKey(this)
        if ((engine == Prefs.ENGINE_GEMINI || engine == Prefs.ENGINE_STREAM) && key.isBlank()) {
            setStatus("أدخل مفتاح Gemini في التطبيق أو اختر الوضع الأوفلاين")
            return
        }
        if (engine == Prefs.ENGINE_OFFLINE && !ModelManager.isReady(this)) {
            setStatus("نزّل نموذج الصوت من داخل التطبيق أولاً")
            return
        }
        liveWanted = true
        setStatus("جاري التحضير…")
        if (engine == Prefs.ENGINE_HYBRID) {
            if (!WhisperBridge.available) {
                liveWanted = false
                setStatus("محرك Whisper غير مضمَّن في هذا الإصدار")
                return
            }
            if (!ModelManager.isWhisperReady(this)) {
                liveWanted = false
                setStatus("نزّل نموذج Whisper من داخل التطبيق أولاً")
                return
            }
        }
        val source = Prefs.sourceLang(this)
        if (engine == Prefs.ENGINE_OFFLINE || engine == Prefs.ENGINE_HYBRID) ensureTranslator(source)
        val liveModel = Prefs.liveModel(this)
        val hint = Prefs.glossaryHint(this)
        if (engine == Prefs.ENGINE_OFFLINE && source != "en") {
            Toast.makeText(this, "الوضع الأوفلاين يفهم الإنكليزية فقط", Toast.LENGTH_LONG).show()
        }

        Thread {
            var sink: AudioSink? = null
            try {
                sink = if (engine == Prefs.ENGINE_HYBRID) {
                    WhisperSink(
                        ModelManager.whisperFile(this).absolutePath, source, key, liveModel, hint,
                        Prefs.glossaryTerms(this),
                        onEnglish = { en -> main.post { showEnglishOnly(en) } },
                        onResult = { en, ar -> main.post { showPair(en, ar) } },
                        onFallback = { en -> main.post { onOfflineSpeech(en, true) } },
                        onError = { msg -> postStatus(msg) }
                    )
                } else if (engine == Prefs.ENGINE_STREAM) {
                    LiveTranslateSink(
                        key, Prefs.STREAM_MODEL,
                        onText = { src, ar -> main.post { showStream(src, ar) } },
                        onStatus = { msg -> postStatus(msg) }
                    )
                } else if (engine == Prefs.ENGINE_GEMINI) {
                    GeminiSink(
                        key, liveModel, source, hint,
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

    private fun ensureTranslator(source: String) {
        if (translator != null) return
        // ML Kit needs a fixed source language; "auto" falls back to English
        val mlSource = TranslateLanguage.fromLanguageTag(source) ?: TranslateLanguage.ENGLISH
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(mlSource)
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
        spView.visibility = View.GONE
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

    // ---------- Record once (session), translate with Gemini ----------

    private fun startRecording() {
        if (recordingWanted) return
        if (Prefs.apiKey(this).isBlank()) {
            setStatus("أدخل مفتاح Gemini في التطبيق أولاً")
            return
        }
        val f = File(cacheDir, "rec_${System.currentTimeMillis()}.wav")
        val r = try {
            SessionRecorder(f)
        } catch (e: Exception) {
            setStatus("تعذّر بدء التسجيل")
            return
        }
        synchronized(sinkLock) { recorder = r }
        recordFile = f
        recordingWanted = true
        setStatus("● يسجّل… شغّل الفيديو كاملاً ثم اختر «إيقاف التسجيل وحفظ»")
    }

    private fun stopRecordingAndTranslate() {
        if (!recordingWanted) return
        recordingWanted = false
        var r: SessionRecorder? = null
        synchronized(sinkLock) {
            r = recorder
            recorder = null
        }
        val rec = r
        rec?.close()
        val f = recordFile
        recordFile = null
        val seconds = rec?.durationSec ?: 0.0
        if (rec == null || f == null || seconds < 3.0) {
            f?.delete()
            setStatus("التسجيل قصير جداً")
            main.postDelayed(clearRunnable, 2500)
            return
        }

        val fpArray = rec.fingerprint()
        val onsets = rec.onsets()
        setStatus("جاري التحليل والترجمة…")
        Thread {
            try {
                val raw = FileTranslator.runSegments(this, f, seconds, onsets) { msg -> postStatus(msg) }
                val segs = SegmentTools.snap(raw, onsets)
                SessionStore.save(this, segs, seconds, fpArray)
                val text = SegmentTools.formatText(segs)
                FileTranslator.lastFile(this).writeText(text)
                Notifier.result(this, "حُفظت الترجمة", text.take(120))
                main.post {
                    setStatus("حُفظت الجلسة ✓ — عند إعادة الفيديو اختر «متابعة ترجمة محفوظة»")
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

    // ---------- Follow a saved session ----------

    private fun startFollow() {
        if (followWanted) return
        val sessions = SessionStore.list(this)
        if (sessions.isEmpty()) {
            setStatus("لا توجد جلسات محفوظة. استخدم «سجّل وترجم» أولاً")
            return
        }
        if (liveWanted) stopLive()
        followWanted = true
        setStatus("أحمّل الجلسات المحفوظة…")

        Thread {
            var sink: FollowSink? = null
            try {
                val loaded = ArrayList<FollowSession>()
                for (s in sessions) {
                    try {
                        loaded.add(FollowSession(s.id, SessionStore.loadFp(this, s.id), s.segments))
                    } catch (_: Exception) {
                    }
                }
                if (loaded.isNotEmpty()) sink = FollowSink(loaded)
            } catch (e: Throwable) {
                postStatus("خطأ: ${e.message}")
            }
            main.post {
                val s = sink
                if (s == null) {
                    followWanted = false
                    setStatus("تعذّر تحميل الجلسات")
                } else if (followWanted) {
                    synchronized(sinkLock) { followSink = s }
                    followShown = false
                    setStatus("أستمع… شغّل الفيديو من أي نقطة")
                    main.removeCallbacks(followTick)
                    main.post(followTick)
                } else {
                    Thread { s.close() }.start()
                }
            }
        }.start()
    }

    private fun stopFollow() {
        followWanted = false
        main.removeCallbacks(followTick)
        var s: FollowSink? = null
        synchronized(sinkLock) {
            s = followSink
            followSink = null
        }
        val old = s
        if (old != null) Thread { old.close() }.start()
        followShown = false
        setStatus("توقفت المتابعة")
        main.postDelayed(clearRunnable, 2500)
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

    /** Reads audio only while something needs it (live translation, recording or following). */
    private fun captureLoop(rec: AudioRecord) {
        val buf = ShortArray(2048)
        var started = false
        try {
            while (alive) {
                if (!liveWanted && !recordingWanted && !followWanted) {
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
                    try {
                        followSink?.onAudio(buf, n)
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
        followWanted = false
        main.removeCallbacks(followTick)
        try {
            captureThread?.join(1000)
        } catch (_: InterruptedException) {
        }

        var s: AudioSink? = null
        var r: SessionRecorder? = null
        var fs: FollowSink? = null
        synchronized(sinkLock) {
            s = liveSink
            liveSink = null
            r = recorder
            recorder = null
            fs = followSink
            followSink = null
        }
        val oldSink = s
        if (oldSink != null) Thread { oldSink.close() }.start()
        val oldFollow = fs
        if (oldFollow != null) Thread { oldFollow.close() }.start()
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
