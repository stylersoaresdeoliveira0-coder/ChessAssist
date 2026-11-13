package com.chessassist

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class HintView(ctx: Context) : View(ctx) {
    var geo: Geometry? = null
    var flipped = false
    var from = -1
    var to = -1
    private val pFrom = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.argb(255, 0, 230, 118)
    }
    private val pTo = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.argb(255, 255, 23, 68)
    }

    override fun onDraw(canvas: Canvas) {
        val g = geo ?: return
        if (from < 0 || to < 0) return
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        drawSq(canvas, g, from, pFrom)
        drawSq(canvas, g, to, pTo)
        canvas.restore()
    }

    private fun drawSq(canvas: Canvas, g: Geometry, idx: Int, paint: Paint) {
        val file = idx % 8
        val rank = idx / 8
        val c = if (!flipped) file else 7 - file
        val r = if (!flipped) 7 - rank else rank
        val s = g.sq
        val w = max(5f, s * 0.06f)
        paint.strokeWidth = w
        val l = g.x0 + c * s
        val t = g.y0 + r * s
        canvas.drawRect(l + w / 2, t + w / 2, l + s - w / 2, t + s - w / 2, paint)
    }
}

class CalibView(ctx: Context, private val onDone: (Float, Float, Float, Float) -> Unit) : View(ctx) {
    private val pts = ArrayList<Float>()
    private val paint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        isAntiAlias = true
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN && pts.size < 4) {
            pts.add(e.rawX)
            pts.add(e.rawY)
            invalidate()
            if (pts.size == 4) onDone(pts[0], pts[1], pts[2], pts[3])
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        canvas.drawColor(Color.argb(110, 0, 0, 0))
        val msg = when (pts.size / 2) {
            0 -> "Toque no canto SUPERIOR ESQUERDO do tabuleiro"
            1 -> "Agora toque no canto INFERIOR DIREITO do tabuleiro"
            else -> "Ok"
        }
        canvas.drawText(msg, 30f, 300f, paint)
        var i = 0
        while (i < pts.size) {
            canvas.drawCircle(pts[i] - loc[0], pts[i + 1] - loc[1], 12f, paint)
            i += 2
        }
    }
}

class CaptureService : Service() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private lateinit var wm: WindowManager

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var panel: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var hintView: HintView? = null
    private var calibView: CalibView? = null
    private var statusView: TextView? = null

    // Worker-thread state
    private val frame = FrameBuf()
    private var geo: Geometry? = null
    private var tpl: Templates? = null
    private var flipped = false
    private var mySide = 1
    private var pos: Position? = null
    private var tracking = false
    private var lastObs: IntArray? = null
    private var lastObsSeq = -1L
    private var candidate: IntArray? = null
    private var candCount = 0
    private var unknownTicks = 0
    private var searchGen = 0
    private var searchEngine: Engine? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        workerThread = HandlerThread("chessassist-worker")
        workerThread.start()
        worker = Handler(workerThread.looper)
    }

    @Suppress("DEPRECATION")
    private fun readData(intent: Intent?): Intent? {
        if (intent == null) return null
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra("data", Intent::class.java)
        } else {
            intent.getParcelableExtra("data")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (projection != null) return START_NOT_STICKY
        val code = intent?.getIntExtra("code", 0) ?: 0
        val data = readData(intent)
        if (data == null || code == 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("cap", "ChessAssist", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "cap")
            .setContentTitle("ChessAssist ativo")
            .setContentText("Lendo a tela")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val proj = mpm.getMediaProjection(code, data)
            projection = proj
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    ui.post { stopSelf() }
                }
            }, ui)
            val b = wm.maximumWindowMetrics.bounds
            val w = b.width()
            val h = b.height()
            val rd = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            reader = rd
            display = proj.createVirtualDisplay(
                "chessassist", w, h, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rd.surface, null, null
            )
            buildOverlays()
            worker.postDelayed({ tick() }, 500)
        } catch (t: Throwable) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        worker.removeCallbacksAndMessages(null)
        workerThread.quitSafely()
        searchEngine?.abort()
        panel?.let { runCatching { wm.removeView(it) } }
        hintView?.let { runCatching { wm.removeView(it) } }
        calibView?.let { runCatching { wm.removeView(it) } }
        display?.release()
        reader?.close()
        projection?.stop()
        super.onDestroy()
    }

    // ---------- UI (main thread) ----------

    private fun overlayParams(w: Int, h: Int, touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val p = WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT)
        p.gravity = Gravity.TOP or Gravity.START
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        return p
    }

    private fun status(s: String) {
        ui.post { statusView?.text = s }
    }

    private fun buildOverlays() {
        val dp = resources.displayMetrics.density
        val hv = HintView(this)
        hintView = hv
        wm.addView(hv, overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, false))

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.argb(210, 20, 20, 20))
        root.setPadding((6 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt())

        val sv = TextView(this)
        sv.setTextColor(Color.WHITE)
        sv.textSize = 12f
        sv.maxWidth = (320 * dp).toInt()
        sv.text = "Passo 1: toque em Cantos (arraste este texto para mover o painel)"
        statusView = sv
        root.addView(sv)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        fun mk(label: String, act: () -> Unit): Button {
            val b = Button(this)
            b.text = label
            b.textSize = 11f
            b.isAllCaps = false
            b.minWidth = 0
            b.minimumWidth = 0
            b.minHeight = 0
            b.minimumHeight = 0
            b.setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
            b.setOnClickListener { act() }
            return b
        }
        row.addView(mk("Cantos") { startCalibration() })
        row.addView(mk("Brancas") { worker.post { doLearn(false) } })
        row.addView(mk("Pretas") { worker.post { doLearn(true) } })
        row.addView(mk("Reset") { worker.post { doReset() } })
        row.addView(mk("Sair") { stopSelf() })
        root.addView(row)

        val lp = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true)
        lp.x = 0
        lp.y = (40 * dp).toInt()
        panelParams = lp
        panel = root
        wm.addView(root, lp)

        sv.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f
            var sy = 0f
            var px = 0
            var py = 0
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                val p = panelParams ?: return false
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = e.rawX
                        sy = e.rawY
                        px = p.x
                        py = p.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = px + (e.rawX - sx).toInt()
                        p.y = py + (e.rawY - sy).toInt()
                        panel?.let { wm.updateViewLayout(it, p) }
                    }
                }
                return true
            }
        })
    }

    private fun startCalibration() {
        if (calibView != null) return
        val cv = CalibView(this) { x1, y1, x2, y2 ->
            ui.post {
                calibView?.let { runCatching { wm.removeView(it) } }
                calibView = null
            }
            worker.post { setGeometry(x1, y1, x2, y2) }
        }
        calibView = cv
        wm.addView(cv, overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, true))
    }

    private fun clearHint() {
        ui.post {
            hintView?.from = -1
            hintView?.to = -1
            hintView?.invalidate()
        }
    }

    // ---------- Worker thread ----------

    private fun tick() {
        try {
            grabFrame()
            if (tracking) processFrame()
        } catch (t: Throwable) {
            status("Erro: ${t.message}")
        }
        worker.postDelayed({ tick() }, 250)
    }

    private fun grabFrame() {
        val img = reader?.acquireLatestImage() ?: return
        try {
            val p = img.planes[0]
            val buf = p.buffer
            val stride = p.rowStride
            val need = stride * img.height
            if (frame.data.size != need) frame.data = ByteArray(need)
            buf.rewind()
            buf.get(frame.data, 0, min(buf.remaining(), need))
            frame.w = img.width
            frame.h = img.height
            frame.stride = stride
            frame.seq++
            frame.valid = true
        } finally {
            img.close()
        }
    }

    private fun setGeometry(x1: Float, y1: Float, x2: Float, y2: Float) {
        val w = abs(x2 - x1)
        val h = abs(y2 - y1)
        if (w < 200f || h < 200f) {
            status("Cantos muito próximos. Toque em Cantos e refaça.")
            return
        }
        val side = (w + h) / 2f
        val g = Geometry(min(x1, x2), min(y1, y2), side)
        geo = g
        tracking = false
        ui.post {
            hintView?.geo = g
            hintView?.invalidate()
        }
        val warn = if (abs(w - h) > side * 0.06f) " Aviso: largura e altura diferem, o tabuleiro é quadrado; refaça se errou." else ""
        status("Cantos ok.$warn Deixe a partida na posição inicial e toque Brancas ou Pretas (o seu lado).")
    }

    private fun doLearn(black: Boolean) {
        val g = geo
        if (g == null) {
            status("Marque os cantos primeiro (botão Cantos).")
            return
        }
        grabFrame()
        if (!frame.valid) {
            status("Ainda sem imagem da tela. Tente de novo.")
            return
        }
        if (Vision.isBlack(frame)) {
            status("Imagem preta: o Chess.com está bloqueando a captura de tela.")
            return
        }
        val res = Vision.learn(frame, g, black)
        val t = res.first
        if (t == null) {
            status(res.second ?: "Falha ao aprender as peças.")
            return
        }
        tpl = t
        flipped = black
        mySide = if (black) -1 else 1
        lastObs = null
        lastObsSeq = -1L
        candidate = null
        candCount = 0
        pos = Rules.initial()
        tracking = true
        ui.post {
            hintView?.flipped = black
            hintView?.invalidate()
        }
        onPositionChanged()
    }

    private fun doReset() {
        if (tpl == null) {
            status("Aprenda as peças primeiro (Brancas ou Pretas).")
            return
        }
        candidate = null
        candCount = 0
        pos = Rules.initial()
        tracking = true
        onPositionChanged()
    }

    private fun processFrame() {
        val g = geo ?: return
        val t = tpl ?: return
        val p = pos ?: return
        if (!frame.valid) return
        if (Vision.isBlack(frame)) {
            status("Imagem preta: captura bloqueada (ou o app foi minimizado).")
            return
        }
        var obs = lastObs
        if (frame.seq != lastObsSeq) {
            obs = Vision.observe(frame, g, flipped, t)
            lastObs = obs
            lastObsSeq = frame.seq
        }
        if (obs == null) {
            candidate = null
            candCount = 0
            unknownTicks++
            if (unknownTicks == 12) status("Não reconheço as peças (animação, tema diferente ou calibração errada).")
            return
        }
        unknownTicks = 0
        val c = candidate
        if (c != null && c.contentEquals(obs)) {
            candCount++
        } else {
            candidate = obs
            candCount = 1
        }
        if (candCount < 2) return
        if (obs.contentEquals(p.b)) return
        val seq = Rules.match(p, obs)
        if (seq == null) {
            if (candCount == 2 || candCount == 20) {
                status("Não consegui casar o lance com as regras. Em partida nova, toque Reset.")
            }
            return
        }
        var q = p
        for (m in seq) q = Rules.make(q, m)
        pos = q
        onPositionChanged()
    }

    private fun onPositionChanged() {
        val p = pos ?: return
        searchGen++
        searchEngine?.abort()
        clearHint()
        val ml = Rules.legal(p)
        if (ml.n == 0) {
            status(if (Rules.inCheck(p)) "Xeque-mate." else "Empate por afogamento.")
            return
        }
        if (p.side == mySide) startSearch() else status("Vez do adversário...")
    }

    private fun startSearch() {
        val p = pos ?: return
        searchEngine?.abort()
        val eng = Engine()
        searchEngine = eng
        val gen = ++searchGen
        status("Pensando...")
        Thread {
            val m = eng.best(p, 1500)
            worker.post { if (gen == searchGen && pos === p) showHint(m) }
        }.start()
    }

    private fun showHint(m: Int?) {
        if (m == null) {
            status("Sem lances legais.")
            return
        }
        val from = moveFrom(m)
        val to = moveTo(m)
        ui.post {
            hintView?.from = from
            hintView?.to = to
            hintView?.invalidate()
        }
        status("Jogue: ${moveText(m)}")
    }
}
