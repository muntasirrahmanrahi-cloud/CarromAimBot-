package com.example.carromaimbot

import android.app.*
import android.content.*
import android.graphics.*
import android.hardware.display.DisplayManager
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.*
import kotlin.math.*

private data class Disc(val x: Float, val y: Float, val r: Float, val type: Int)
private data class Pocket(val x: Float, val y: Float, val r: Float)
private data class Board(val left: Float, val top: Float, val right: Float, val bottom: Float, val pockets: List<Pocket>)
private data class Shot(
    val sx: Float, val sy: Float,
    val tx: Float, val ty: Float,
    val px: Float, val py: Float,
    val score: Float,
    val bankX: Float? = null, val bankY: Float? = null,
    val bank: Boolean = false
)

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var overlay: AimOverlay? = null
    private var lastFrame = 0L

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("aim", "Carrom Aim", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = Notification.Builder(this, "aim")
            .setContentTitle("Carrom Aim Bot")
            .setContentText("Auto-calibration + live aim guide active")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, 32)
        else startForeground(7, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (projection == null && intent != null) {
            val code = intent.getIntExtra("resultCode", 0)
            val data = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra("data", Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra("data")
            if (data != null) {
                val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projection = pm.getMediaProjection(code, data)
                startCapture()
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture() {
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        worker = HandlerThread("carrom-cv").also { it.start() }
        handler = Handler(worker!!.looper)
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        projection!!.createVirtualDisplay(
            "CarromAim", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, handler
        )
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlay = AimOverlay(this)
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        wm.addView(overlay, WindowManager.LayoutParams(-1, -1, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT))
        reader!!.setOnImageAvailableListener({ r ->
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrame < 120) { r.acquireLatestImage()?.close(); return@setOnImageAvailableListener }
            lastFrame = now
            r.acquireLatestImage()?.use { analyze(it) }
        }, handler)
    }

    private fun analyze(image: Image) {
        val plane = image.planes[0]
        val w = image.width; val h = image.height
        val rowStride = plane.rowStride; val pixelStride = plane.pixelStride
        val buf = plane.buffer
        val step = 4
        val sw = max(1, w / step); val sh = max(1, h / step)
        val pixels = IntArray(sw * sh)
        val row = ByteArray(rowStride)
        for (y in 0 until sh) {
            val sy = min(h - 1, y * step)
            buf.position(sy * rowStride)
            val count = min(rowStride, buf.remaining())
            buf.get(row, 0, count)
            for (x in 0 until sw) {
                val sx = x * step * pixelStride
                if (sx + 3 < rowStride) {
                    val rr = row[sx].toInt() and 255
                    val gg = row[sx + 1].toInt() and 255
                    val bb = row[sx + 2].toInt() and 255
                    pixels[y * sw + x] = Color.rgb(rr, gg, bb)
                }
            }
        }

        val board = autoCalibrateBoard(pixels, sw, sh, step, w, h) ?: run {
            overlay?.post { overlay?.setState(null, emptyList(), null) }
            return
        }
        val discs = detectDiscs(pixels, sw, sh, board, step)
        val striker = discs.filter { it.type == 3 && it.y > board.top + (board.bottom - board.top) * .72f }
            .maxByOrNull { it.r }
        val targets = discs.filter { it.type == 1 || it.type == 2 }
        val shot = if (striker != null) findBestShot(striker, targets, board) else null
        overlay?.post { overlay?.setState(board, discs, shot) }
    }

    private fun autoCalibrateBoard(px: IntArray, w: Int, h: Int, step: Int, fullW: Int, fullH: Int): Board? {
        // Detect the four dark corner pockets. Their centers define the board coordinate system.
        val dark = findDarkComponents(px, w, h)
        val expected = listOf(
            PointF(w * .055f, h * .32f), PointF(w * .945f, h * .32f),
            PointF(w * .055f, h * .70f), PointF(w * .945f, h * .70f)
        )
        val chosen = ArrayList<Pocket>()
        val used = HashSet<Int>()
        for (e in expected) {
            var best = -1; var bestD = Float.MAX_VALUE
            dark.forEachIndexed { i, d ->
                if (i in used) return@forEachIndexed
                val dx = d.x - e.x; val dy = d.y - e.y
                val d2 = dx * dx + dy * dy
                val sizeOK = d.r in (max(3f, w * .008f)..(w * .06f))
                if (sizeOK && d2 < bestD) { bestD = d2; best = i }
            }
            if (best >= 0 && sqrt(bestD) < min(w, h) * .16f) {
                chosen.add(dark[best]); used.add(best)
            }
        }
        if (chosen.size < 4) return fallbackBoard(fullW, fullH)

        val left = chosen.minOf { it.x } * step
        val right = chosen.maxOf { it.x } * step
        val top = chosen.minOf { it.y } * step
        val bottom = chosen.maxOf { it.y } * step
        val pr = chosen.map { it.r * step }.average().toFloat()
        // Rail limits are inferred from pocket centers; pocket centers are inset from the outer frame.
        val boardLeft = (left - pr * 1.15f).coerceAtLeast(0f)
        val boardRight = (right + pr * 1.15f).coerceAtMost(fullW.toFloat())
        val boardTop = (top - pr * 1.15f).coerceAtLeast(0f)
        val boardBottom = (bottom + pr * 1.15f).coerceAtMost(fullH.toFloat())
        val pockets = chosen.map { Pocket(it.x * step, it.y * step, it.r * step) }
        return Board(boardLeft, boardTop, boardRight, boardBottom, pockets)
    }

    private data class Blob(val x: Float, val y: Float, val r: Float)

    private fun findDarkComponents(px: IntArray, w: Int, h: Int): List<Blob> {
        val visited = BooleanArray(w * h)
        val out = ArrayList<Blob>()
        val qx = IntArray(min(w * h, 120000)); val qy = IntArray(qx.size)
        fun dark(c: Int): Boolean {
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            return r < 62 && g < 62 && b < 62
        }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (visited[i] || !dark(px[i])) { visited[i] = true; continue }
            var head = 0; var tail = 0
            qx[tail] = x; qy[tail] = y; tail++; visited[i] = true
            var sx = 0; var sy = 0; var n = 0
            while (head < tail) {
                val xx = qx[head]; val yy = qy[head]; head++
                sx += xx; sy += yy; n++
                val neighbors = intArrayOf(-1, 0, 1, 0, -1)
                for (k in 0..3) {
                    val nx = xx + neighbors[k]; val ny = yy + neighbors[k + 1]
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val ni = ny * w + nx
                    if (!visited[ni] && dark(px[ni])) {
                        visited[ni] = true
                        if (tail < qx.size) { qx[tail] = nx; qy[tail] = ny; tail++ }
                    }
                }
            }
            if (n in 35..1200) {
                out.add(Blob(sx.toFloat() / n, sy.toFloat() / n, sqrt(n / PI.toFloat())))
            }
        }
        return out
    }

    private fun fallbackBoard(w: Int, h: Int): Board {
        val l = w * .02f; val r = w * .98f; val t = h * .29f; val b = h * .75f
        val dx = (r - l) * .045f; val dy = (b - t) * .045f
        val pr = min(r - l, b - t) * .035f
        return Board(l, t, r, b, listOf(
            Pocket(l + dx, t + dy, pr), Pocket(r - dx, t + dy, pr),
            Pocket(l + dx, b - dy, pr), Pocket(r - dx, b - dy, pr)
        ))
    }

    private fun detectDiscs(px: IntArray, w: Int, h: Int, board: Board, step: Int): List<Disc> {
        val x0 = (board.left / step).toInt().coerceIn(0, w - 1)
        val x1 = (board.right / step).toInt().coerceIn(0, w - 1)
        val y0 = (board.top / step).toInt().coerceIn(0, h - 1)
        val y1 = (board.bottom / step).toInt().coerceIn(0, h - 1)
        val visited = BooleanArray(w * h)
        val out = ArrayList<Disc>()
        val qx = IntArray(min(w * h, 100000)); val qy = IntArray(qx.size)
        fun cls(c: Int): Int {
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c); val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            return when {
                mx < 72 -> 1
                r > 125 && r > g * 1.30f && r > b * 1.20f -> 2
                r > 195 && g > 195 && b > 195 && mx - mn < 48 -> 3
                else -> 0
            }
        }
        for (y in y0..y1) for (x in x0..x1) {
            val i = y * w + x
            if (visited[i]) continue
            val c = cls(px[i])
            if (c == 0) { visited[i] = true; continue }
            var head = 0; var tail = 0
            qx[tail] = x; qy[tail] = y; tail++; visited[i] = true
            var sx = 0; var sy = 0; var n = 0
            while (head < tail) {
                val xx = qx[head]; val yy = qy[head]; head++; sx += xx; sy += yy; n++
                val dirs = intArrayOf(-1,0,1,0,-1)
                for (k in 0..3) {
                    val nx = xx + dirs[k]; val ny = yy + dirs[k+1]
                    if (nx !in x0..x1 || ny !in y0..y1) continue
                    val ni = ny*w+nx
                    if (!visited[ni] && cls(px[ni]) == c) {
                        visited[ni] = true
                        if (tail < qx.size) { qx[tail] = nx; qy[tail] = ny; tail++ }
                    }
                }
            }
            if (n in 15..900) {
                val rr = (sqrt(n / PI.toFloat()) * step).coerceIn(7f, 32f)
                val cx = sx.toFloat()/n*step; val cy = sy.toFloat()/n*step
                val nearPocket = board.pockets.any { hypot(cx-it.x, cy-it.y) < it.r * 2.2f }
                if (!nearPocket) out.add(Disc(cx, cy, rr, c))
            }
        }
        return out
    }

    private fun findBestShot(s: Disc, targets: List<Disc>, board: Board): Shot? {
        var best: Shot? = null
        val pockets = board.pockets
        for (t in targets) for (p in pockets) {
            val direct = makeDirect(s, t, p, board)
            if (direct != null && isBetter(direct, best)) best = direct
            for (side in 0..3) {
                val bank = makeBank(s, t, p, board, side)
                if (bank != null && isBetter(bank, best)) best = bank
            }
        }
        return best
    }

    private fun makeDirect(s: Disc, t: Disc, p: Pocket, b: Board): Shot? {
        val dx = p.x - t.x; val dy = p.y - t.y; val len = hypot(dx, dy)
        if (len < 2f) return null
        val ux = dx / len; val uy = dy / len
        val hitX = t.x - ux * (t.r + s.r * .9f); val hitY = t.y - uy * (t.r + s.r * .9f)
        if (!clearPath(s.x,s.y,hitX,hitY,b)) return null
        if (!clearPath(t.x,t.y,p.x,p.y,b)) return null
        val d = hypot(hitX-s.x, hitY-s.y) + len
        return Shot(s.x,s.y,hitX,hitY,p.x,p.y,1f/(1f+d/900f))
    }

    private fun makeBank(s: Disc, t: Disc, p: Pocket, b: Board, side: Int): Shot? {
        val rail = when(side) { 0 -> b.left; 1 -> b.right; 2 -> b.top; else -> b.bottom }
        val margin = max(8f, t.r + 2f)
        val virtual = when(side) {
            0 -> PointF(2f*rail-p.x, p.y)
            1 -> PointF(2f*rail-p.x, p.y)
            2 -> PointF(p.x, 2f*rail-p.y)
            else -> PointF(p.x, 2f*rail-p.y)
        }
        val dx = virtual.x - t.x; val dy = virtual.y - t.y
        val denom = if (side < 2) dx else dy
        if (abs(denom) < 1f) return null
        val q = if (side < 2) (rail - t.x) / denom else (rail - t.y) / denom
        if (q <= 0f || q >= 1f) return null
        val bx = t.x + dx*q; val by = t.y + dy*q
        if (bx < b.left+margin || bx > b.right-margin || by < b.top+margin || by > b.bottom-margin) return null
        val ux = dx/hypot(dx,dy); val uy = dy/hypot(dx,dy)
        val hitX = t.x - ux*(t.r+s.r*.9f); val hitY = t.y - uy*(t.r+s.r*.9f)
        if (!clearPath(s.x,s.y,hitX,hitY,b)) return null
        val d = hypot(hitX-s.x,hitY-s.y) + hypot(t.x-bx,t.y-by) + hypot(bx-p.x,by-p.y)
        return Shot(s.x,s.y,hitX,hitY,p.x,p.y,.72f/(1f+d/900f),bx,by,true)
    }

    private fun clearPath(x1:Float,y1:Float,x2:Float,y2:Float,b:Board):Boolean {
        val pad = 3f
        return x2 > b.left-pad && x2 < b.right+pad && y2 > b.top-pad && y2 < b.bottom+pad
    }

    private fun isBetter(a: Shot, old: Shot?): Boolean = old == null || a.score > old.score

    override fun onDestroy() {
        overlay?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        reader?.close(); projection?.stop(); worker?.quitSafely(); super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null
}

class AimOverlay(c: Context) : View(c) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; strokeCap=Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.FILL }
    private var board: Board? = null
    private var discs: List<Disc> = emptyList()
    private var shot: Shot? = null
    fun setState(b: Board?, d: List<Disc>, s: Shot?) { board=b; discs=d; shot=s; invalidate() }
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val b = board ?: return
        val s = shot
        line.color = Color.argb(110,255,255,255); line.strokeWidth=3f
        c.drawRect(b.left,b.top,b.right,b.bottom,line)
        b.pockets.forEach { fill.color=Color.argb(120,0,0,0); c.drawCircle(it.x,it.y,max(12f,it.r*.65f),fill) }
        if (s == null) return
        line.color=Color.WHITE; line.strokeWidth=5f
        c.drawLine(s.sx,s.sy,s.tx,s.ty,line)
        if (s.bank && s.bankX != null && s.bankY != null) c.drawLine(s.tx,s.ty,s.bankX,s.bankY,line)
        c.drawLine(s.bankX ?: s.tx,s.bankY ?: s.ty,s.px,s.py,line)
        fill.color=Color.rgb(255,120,0); c.drawCircle(s.tx,s.ty,12f,fill)
        line.color=Color.rgb(255,120,0); line.strokeWidth=4f; c.drawCircle(s.tx,s.ty,19f,line)
        fill.color=Color.WHITE; c.drawCircle(s.px,s.py,9f,fill)
        fill.color=Color.rgb(255,120,0); c.drawCircle(s.bankX ?: -100f,s.bankY ?: -100f,7f,fill)
    }
}
