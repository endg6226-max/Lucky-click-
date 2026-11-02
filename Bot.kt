package com.monkeybot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ---------------------------------------------------------------- helpers

suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { c.resume(it) }
    addOnFailureListener { c.resumeWithException(it) }
}

enum class Mode { GAME, CLAIM, CONT, REWARD, SHEET, OVER, UNKNOWN }

// ---------------------------------------------------------------- main screen

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
        }
        val title = TextView(this).apply { text = "Monkey Bot"; textSize = 26f }
        status = TextView(this).apply { textSize = 16f; setPadding(0, 20, 0, 20) }
        val help = TextView(this).apply {
            textSize = 14f
            text = "Upar se 1, 2, 3 order mein dabao. Phir game kholo aur side mein dikhne wala START dabao."
            setPadding(0, 0, 0, 20)
        }
        fun btn(t: String, f: () -> Unit) = Button(this).apply {
            text = t
            setOnClickListener { f() }
        }
        root.addView(title)
        root.addView(status)
        root.addView(help)
        root.addView(btn("1) Overlay permission do") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(btn("2) Accessibility ON karo") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(btn("3) Bot shuru karo") { startBot() })
        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 2)
        }
    }

    override fun onResume() {
        super.onResume()
        val ov = Settings.canDrawOverlays(this)
        val acc = BotAccessibilityService.instance != null
        status.text = "Overlay: " + (if (ov) "OK" else "NAHI") +
            "\nAccessibility: " + (if (acc) "OK" else "NAHI")
    }

    private fun startBot() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Pehle step 1 karo", Toast.LENGTH_LONG).show()
            return
        }
        if (BotAccessibilityService.instance == null) {
            Toast.makeText(this, "Pehle step 2 karo (Monkey Bot ko ON karo)", Toast.LENGTH_LONG).show()
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, BotService::class.java)
            i.putExtra("code", resultCode)
            i.putExtra("data", data)
            startForegroundService(i)
            Toast.makeText(this, "Ab game kholo aur START dabao", Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
        }
    }
}

// ---------------------------------------------------------------- taps and drags

class BotAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: BotAccessibilityService? = null
    }

    private var stroke: GestureDescription.StrokeDescription? = null
    private var endX = 0f
    private var endY = 0f

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private suspend fun send(g: GestureDescription): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Boolean> { c ->
            val started = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (c.isActive) c.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (c.isActive) c.resume(false)
                }
            }, null)
            if (!started && c.isActive) c.resume(false)
        }
    }

    /** Ek simple tap. */
    suspend fun tap(x: Float, y: Float) {
        val p = Path()
        p.moveTo(x, y)
        send(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 60))
            .build())
    }

    /** Ungli screen par rakhe rakhe x tak khiskao (bandar ko chalane ke liye). */
    suspend fun moveHold(x: Float, y: Float, dur: Long) {
        val s = stroke
        val p = Path()
        val newStroke: GestureDescription.StrokeDescription
        val newEnd: Float
        if (s == null) {
            p.moveTo(x, y)
            p.lineTo(x + 2f, y)
            newEnd = x + 2f
            newStroke = GestureDescription.StrokeDescription(p, 0, dur, true)
        } else {
            val toX = if (abs(x - endX) < 1f) x + 2f else x
            p.moveTo(endX, endY)
            p.lineTo(toX, y)
            newEnd = toX
            newStroke = s.continueStroke(p, 0, dur, true)
        }
        val ok = send(GestureDescription.Builder().addStroke(newStroke).build())
        if (ok) {
            stroke = newStroke
            endX = newEnd
            endY = y
        } else {
            stroke = null
        }
    }

    /** Phone ka Back button. */
    suspend fun back() {
        withContext(Dispatchers.Main) { performGlobalAction(GLOBAL_ACTION_BACK) }
    }

    /** Ungli uthao. */
    suspend fun releaseHold() {
        val s = stroke ?: return
        stroke = null
        val p = Path()
        p.moveTo(endX, endY)
        p.lineTo(endX + 1f, endY)
        send(GestureDescription.Builder().addStroke(s.continueStroke(p, 0, 30, false)).build())
    }
}

// ---------------------------------------------------------------- vision

object Vision {
    class Hit(val text: String, val box: Rect)

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val hudRe = Regex("\\d+\\s*/\\s*\\d+")
    private val claimRe = Regex("\\bclaim\\b")

    suspend fun ocr(bmp: Bitmap): List<Hit> {
        val res = recognizer.process(InputImage.fromBitmap(bmp, 0)).awaitResult()
        val out = ArrayList<Hit>()
        for (b in res.textBlocks) {
            for (l in b.lines) {
                val r = l.boundingBox ?: continue
                out.add(Hit(l.text, r))
            }
        }
        return out
    }

    /** Screen kaunsi hai: game, claim, continue, game over, ad (reward granted), ya Play Store pop-up. */
    fun classify(hits: List<Hit>, w: Int, h: Int): Pair<Mode, Rect?> {
        val low = hits.map { it.text.lowercase() }

        // 1) Google Play wala pop-up: upar-beech mein "Google Play" + Install
        val sheet = hits.firstOrNull {
            it.text.lowercase().contains("google play") &&
                it.box.centerY() < h * 0.6f && it.box.left < w * 0.4f
        }
        if (sheet != null && low.any { it.contains("install") }) return Pair(Mode.SHEET, sheet.box)

        // 2) Ad: Reward granted
        val reward = hits.firstOrNull { it.text.lowercase().contains("granted") }
        if (reward != null) return Pair(Mode.REWARD, reward.box)

        // 3) Game over: Try Again
        val again = hits.firstOrNull { it.text.lowercase().contains("try again") }
        if (again != null && low.any { it.contains("game over") || it.contains("quit") }) {
            return Pair(Mode.OVER, again.box)
        }

        // 4) Claim popup
        val claim = hits.firstOrNull { claimRe.containsMatchIn(it.text.lowercase()) }
        val cancel = low.any { it.contains("cancel") }
        if (claim != null && cancel) return Pair(Mode.CLAIM, claim.box)

        // 5) Claimed -> Continue
        val cont = hits.firstOrNull { it.text.lowercase().trim() == "continue" }
        val extra = low.any { it.contains("claimed") || it.contains("no thanks") }
        if (cont != null && extra) return Pair(Mode.CONT, cont.box)

        // 6) Game (upar 40/150 jaisa counter)
        if (hits.any { it.box.centerY() < h * 0.18f && hudRe.containsMatchIn(it.text) }) {
            return Pair(Mode.GAME, null)
        }
        return Pair(Mode.UNKNOWN, null)
    }
}

/** Bomb = kaala gol dhabba (seedha pehchaante hain). Fruit = baaki girti hui cheezein (do frames ka farq). */
class MotionDetector(private val w: Int, private val h: Int) {
    class Blob(val cx: Float, val cy: Float)
    class Result(val bombs: List<Blob>, val fruits: List<Blob>)

    private val step = 6
    private val gw = w / step
    private val gh = h / step
    private var prev: IntArray? = null
    private val px = IntArray(w * h)

    fun reset() {
        prev = null
    }

    /** Jude hue True cells ke dhabbe: [minX, maxX, minY, maxY, cells] */
    private fun components(mask: BooleanArray, y0: Int, y1: Int): List<IntArray> {
        val seen = BooleanArray(gw * gh)
        val stack = IntArray(gw * gh)
        val out = ArrayList<IntArray>()
        for (s in y0 * gw until y1 * gw) {
            if (!mask[s] || seen[s]) continue
            var sp = 0
            stack[sp++] = s
            seen[s] = true
            var minX = gw
            var maxX = -1
            var minY = gh
            var maxY = -1
            var cells = 0
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % gw
                val y = i / gw
                cells++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < 0 || nx >= gw || ny < y0 || ny >= y1) continue
                        val j = ny * gw + nx
                        if (mask[j] && !seen[j]) {
                            seen[j] = true
                            stack[sp++] = j
                        }
                    }
                }
            }
            out.add(intArrayOf(minX, maxX, minY, maxY, cells))
        }
        return out
    }

    fun analyze(bmp: Bitmap): Result {
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val gray = IntArray(gw * gh)
        val darkMap = BooleanArray(gw * gh)
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                val c = px[(gy * step + step / 2) * w + gx * step + step / 2]
                val r = (c shr 16) and 255
                val g = (c shr 8) and 255
                val b = c and 255
                gray[gy * gw + gx] = (r * 30 + g * 59 + b * 11) / 100
                darkMap[gy * gw + gx] = max(r, max(g, b)) < 60
            }
        }
        val y0 = (gh * 0.12f).toInt()
        val y1 = (gh * 0.72f).toInt()

        // 1) Bomb: kaala, gol-sa, bade size ka dhabba
        val bombs = ArrayList<Blob>()
        val boxes = ArrayList<IntArray>()
        val yb1 = (gh * 0.88f).toInt()
        for (c in components(darkMap, y0, yb1)) {
            val bw = c[1] - c[0] + 1
            val bh = c[3] - c[2] + 1
            if (c[4] >= 20 && bw in 5..18 && bh in 4..18 && c[4].toFloat() / (bw * bh) >= 0.5f) {
                bombs.add(Blob(((c[0] + c[1] + 1) / 2f) * step, (c[3] + 1f) * step))
                // bomb ke aas-paas (aag/flame samet) ko fruit na maano
                boxes.add(intArrayOf(c[0] - 4, c[1] + 4, c[2] - 9, c[3] + 4))
            }
        }

        val p = prev
        prev = gray
        if (p == null) return Result(bombs, emptyList())

        // 2) Fruit: jo cheez hil rahi hai aur bomb nahi hai
        val mv = BooleanArray(gw * gh)
        var total = 0
        for (gy in y0 until y1) {
            for (gx in 0 until gw) {
                val i = gy * gw + gx
                if (abs(gray[i] - p[i]) <= 30) continue
                var inBomb = false
                for (b in boxes) {
                    if (gx >= b[0] && gx <= b[1] && gy >= b[2] && gy <= b[3]) {
                        inBomb = true
                        break
                    }
                }
                if (!inBomb) {
                    mv[i] = true
                    total++
                }
            }
        }
        if (total > gw * (y1 - y0) * 0.35f) return Result(bombs, emptyList())

        val dil = BooleanArray(gw * gh)
        for (gy in y0 until y1) {
            for (gx in 0 until gw) {
                if (!mv[gy * gw + gx]) continue
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val yy = gy + dy
                        val xx = gx + dx
                        if (yy in y0 until y1 && xx in 0 until gw) dil[yy * gw + xx] = true
                    }
                }
            }
        }
        val fruits = ArrayList<Blob>()
        for (c in components(dil, y0, y1)) {
            if (c[4] < 12) continue
            fruits.add(Blob(((c[0] + c[1] + 1) / 2f) * step, (c[3] + 1f) * step))
        }
        return Result(bombs, fruits)
    }
}

// ---------------------------------------------------------------- the bot

class BotService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: View? = null
    private var wm: WindowManager? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var paused = true
    private var realW = 0
    private var realH = 0
    private var capW = 0
    private var capH = 0
    private var k = 2f
    private var lastFrame: Bitmap? = null
    private val ghosts = ArrayList<DoubleArray>() // [x, y, last seen ms] neeche gaye bomb ki yaad

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (projection != null) return START_NOT_STICKY
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(code, data)
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))
        setupCapture(proj)
        showOverlay()
        scope.launch { runLoop() }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("bot", "Monkey Bot", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "bot")
            .setContentTitle("Monkey Bot chal raha hai")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    private fun setupCapture(proj: MediaProjection) {
        val dm = DisplayMetrics()
        val w = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = w
        @Suppress("DEPRECATION")
        w.defaultDisplay.getRealMetrics(dm)
        realW = dm.widthPixels
        realH = dm.heightPixels
        capW = realW / 2
        capH = realH / 2
        k = realW.toFloat() / capW
        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        reader = r
        display = proj.createVirtualDisplay(
            "cap", capW, capH, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
        )
    }

    private fun grab(): Bitmap? {
        val img = reader?.acquireLatestImage() ?: return lastFrame
        try {
            val p = img.planes[0]
            val rowPixels = p.rowStride / p.pixelStride
            val full = Bitmap.createBitmap(rowPixels, capH, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(p.buffer)
            val bmp = if (rowPixels != capW) Bitmap.createBitmap(full, 0, 0, capW, capH) else full
            lastFrame = bmp
            return bmp
        } finally {
            img.close()
        }
    }

    private fun showOverlay() {
        val d = resources.displayMetrics.density
        fun pill(color: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = 40 * d
        }
        val green = Color.parseColor("#E62E7D32")
        val orange = Color.parseColor("#E6E65100")
        val red = Color.parseColor("#E6B71C1C")
        val padH = (14 * d).toInt()
        val padV = (8 * d).toInt()

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val toggle = TextView(this).apply {
            text = "\u25B6 START"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(padH, padV, padH, padV)
            background = pill(green)
        }
        val quit = TextView(this).apply {
            text = "\u2715"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(padH, padV, padH, padV)
            background = pill(red)
        }
        toggle.setOnClickListener {
            paused = !paused
            toggle.text = if (paused) "\u25B6 START" else "\u25A0 STOP"
            toggle.background = pill(if (paused) green else orange)
            if (paused) {
                scope.launch { BotAccessibilityService.instance?.releaseHold() }
            }
        }
        quit.setOnClickListener { stopSelf() }
        row.addView(toggle)
        row.addView(quit, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = (8 * d).toInt() })

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0
        lp.y = (realH * 0.35f).toInt()
        wm?.addView(row, lp)
        overlay = row
    }

    private suspend fun runLoop() {
        val det = MotionDetector(capW, capH)
        var mode = Mode.GAME
        var box: Rect? = null
        var hits: List<Vision.Hit> = emptyList()
        var lastOcr = 0L
        var lastGameSeen = SystemClock.elapsedRealtime()
        var unknownSince = 0L
        var adStart = 0L
        var lastTap = 0L
        var tries = 0
        var curX = capW / 2f
        var wasPaused = true
        var lastPlayed: Bitmap? = null

        while (currentCoroutineContext().isActive) {
            if (paused) {
                grab()
                wasPaused = true
                delay(200)
                continue
            }
            val acc = BotAccessibilityService.instance
            if (acc == null) {
                delay(300)
                continue
            }
            val bmp = grab()
            if (bmp == null) {
                delay(50)
                continue
            }
            val now = SystemClock.elapsedRealtime()
            if (wasPaused) {
                wasPaused = false
                mode = Mode.GAME
                lastGameSeen = now
                lastOcr = now
                unknownSince = 0L
                det.reset()
                ghosts.clear()
                curX = capW / 2f
            }

            var fresh = false
            if (now - lastOcr > (if (mode == Mode.GAME) 2000L else 900L)) {
                lastOcr = now
                hits = try {
                    Vision.ocr(bmp)
                } catch (e: Exception) {
                    emptyList()
                }
                val r = Vision.classify(hits, capW, capH)
                mode = r.first
                box = r.second
                fresh = true
            }
            if (mode != Mode.UNKNOWN) unknownSince = 0L
            val b = box

            when (mode) {
                Mode.CLAIM -> if (fresh && b != null) {
                    acc.releaseHold()
                    acc.tap(b.centerX() * k, b.centerY() * k)
                    adStart = now
                    lastTap = now
                    tries = 0
                    mode = Mode.UNKNOWN
                    unknownSince = now
                }

                Mode.CONT -> if (fresh && b != null && now - lastTap > 1200L) {
                    acc.releaseHold()
                    acc.tap(b.centerX() * k, b.centerY() * k)
                    lastTap = now
                }

                Mode.OVER -> if (fresh && b != null && now - lastTap > 1500L) {
                    acc.releaseHold()
                    acc.tap(b.centerX() * k, b.centerY() * k)
                    adStart = now
                    lastTap = now
                    tries = 0
                    det.reset()
                    ghosts.clear()
                    mode = Mode.UNKNOWN
                    unknownSince = now
                }

                Mode.SHEET -> if (fresh && b != null && now - lastTap > 1200L) {
                    // Play Store pop-up: uska X upar right mein; na hate to Back
                    acc.releaseHold()
                    if (tries % 2 == 0) {
                        acc.tap(capW * 0.93f * k, b.centerY() * k)
                    } else {
                        acc.back()
                    }
                    tries++
                    adStart = now
                    lastTap = now
                }

                Mode.REWARD -> if (fresh && b != null && now - lastTap > 1200L) {
                    acc.releaseHold()
                    if (tries % 4 == 3) {
                        acc.back()
                    } else {
                        // X "Reward granted" ke left ya right mein hota hai
                        val onLeft = b.centerX() < capW / 2f
                        val leftX = max(b.left - capW * 0.045f, capW * 0.04f)
                        val rightX = min(b.right + capW * 0.045f, capW * 0.955f)
                        val first = if (onLeft) leftX else rightX
                        val second = if (onLeft) rightX else leftX
                        val rx = if (tries % 2 == 0) first else second
                        acc.tap(rx * k, b.centerY() * k)
                    }
                    tries++
                    adStart = now
                    lastTap = now
                }

                Mode.GAME -> {
                    lastGameSeen = now
                    tries = 0
                    if (bmp !== lastPlayed) {
                        lastPlayed = bmp
                        curX = playStep(acc, bmp, det, curX)
                    }
                }

                Mode.UNKNOWN -> {
                    if (unknownSince == 0L) unknownSince = now
                    val inAd = now - adStart < 150_000L
                    if (inAd) {
                        val age = now - unknownSince
                        if (fresh && age > 20_000L && now - lastTap > 3_000L) {
                            // Ad mein X kahin bhi ho sakta hai: pehle screen par "X" dhoondho
                            val xHit = hits.firstOrNull {
                                val t = it.text.trim()
                                val isX = t == "X" || t == "x" || t == "\u00D7" || t == "\u2715" || t == "\u2716"
                                isX && it.box.width() < capW * 0.12f
                            }
                            if (xHit != null) {
                                acc.tap(xHit.box.centerX() * k, xHit.box.centerY() * k)
                            } else if (age > 30_000L) {
                                when (tries % 3) {
                                    0 -> acc.back()
                                    1 -> acc.tap(realW * 0.94f, realH * 0.055f)
                                    else -> acc.tap(realW * 0.06f, realH * 0.055f)
                                }
                            }
                            tries++
                            lastTap = now
                        }
                    } else if (now - lastGameSeen < 6_000L && bmp !== lastPlayed) {
                        lastPlayed = bmp
                        curX = playStep(acc, bmp, det, curX)
                    }
                }
            }
            delay(if (mode == Mode.GAME) 20L else 100L)
        }
    }

    private suspend fun playStep(
        acc: BotAccessibilityService, bmp: Bitmap, det: MotionDetector, curX: Float
    ): Float {
        val res = det.analyze(bmp)
        val now = SystemClock.elapsedRealtime().toDouble()
        for (b in res.bombs) {
            ghosts.removeAll { abs(it[0] - b.cx) < capW * 0.06f }
            ghosts.add(doubleArrayOf(b.cx.toDouble(), b.cy.toDouble(), now))
        }
        // bomb jo neeche (bandar ke paas) tak gaya aur ab nazar nahi aata, use thodi der yaad rakho
        ghosts.removeAll { now - it[2] > 700.0 || it[1] < capH * 0.40 }
        val bombs = ArrayList<MotionDetector.Blob>(res.bombs)
        for (g in ghosts) {
            if (now - g[2] > 60.0) {
                bombs.add(MotionDetector.Blob(g[0].toFloat(), max(g[1].toFloat(), capH * 0.70f)))
            }
        }
        val fruits = res.fruits.filter { it.cy > capH * 0.20f }.sortedByDescending { it.cy }
        // bomb ki poori seedh (upar-neeche) + dono taraf safe doori: bandar yahan nahi aayega
        val safe = capW * 0.26f

        fun blocked(x: Float) = bombs.any { abs(it.cx - x) < safe }

        var target = curX
        val pick = fruits.firstOrNull { !blocked(it.cx) }
        if (pick != null) {
            target = pick.cx
        } else if (blocked(curX)) {
            // bomb ki seedh mein ho to sabse surakshit jagah par hato
            var best = curX
            var bestScore = -1f
            var bestDist = Float.MAX_VALUE
            var i = 1
            while (i <= 19) {
                val x = capW * i / 20f
                var score = Float.MAX_VALUE
                for (b in bombs) score = min(score, abs(b.cx - x))
                val d = abs(x - curX)
                val okSafe = score >= safe
                val betterSafe = okSafe && (bestScore < safe || d < bestDist)
                val betterScore = !okSafe && bestScore < safe && score > bestScore
                if (betterSafe || betterScore) {
                    best = x
                    bestScore = score
                    bestDist = d
                }
                i++
            }
            target = best
        }
        target = max(capW * 0.05f, min(capW * 0.95f, target))

        val dist = abs(target - curX)
        val dur = (50 + dist / capW * 150).toLong()
        acc.moveHold(target * k, realH * 0.80f, dur)
        return target
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { overlay?.let { wm?.removeView(it) } }
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        CoroutineScope(Dispatchers.Default).launch { BotAccessibilityService.instance?.releaseHold() }
        super.onDestroy()
    }
}
