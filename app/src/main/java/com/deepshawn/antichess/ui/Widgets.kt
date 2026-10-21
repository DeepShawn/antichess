package com.deepshawn.antichess.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import com.deepshawn.antichess.core.Grade
import com.deepshawn.antichess.core.MoveGrade
import com.deepshawn.antichess.core.Review
import com.deepshawn.antichess.core.Score
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Vertical evaluation bar (white share animates). */
class EvalBarView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private var shown = 0.5f
    private var text = "0.0"
    var whiteBottom = true
        set(v) { field = v; invalidate() }
    private var anim: ValueAnimator? = null
    private val rect = RectF()

    fun set(score: Score?) {
        val target = when {
            score == null -> 0.5f
            score.mate != null -> if (score.mate!! > 0) 1f else 0f
            else -> (1 / (1 + exp(-0.004 * score.centipawns))).toFloat()
        }
        text = score?.format()?.replace("+", "") ?: "0.0"
        anim?.cancel()
        anim = ValueAnimator.ofFloat(shown, target).apply {
            duration = 550; interpolator = DecelerateInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }; start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = w / 2
        canvas.save()
        val clip = Path().apply { addRoundRect(RectF(0f, 0f, w, h), r, r, Path.Direction.CW) }
        canvas.clipPath(clip)
        paint.color = Color.rgb(38, 40, 43); canvas.drawRect(0f, 0f, w, h, paint)
        paint.color = Color.rgb(240, 243, 241)
        val wh = h * shown
        if (whiteBottom) canvas.drawRect(0f, h - wh, w, h, paint) else canvas.drawRect(0f, 0f, w, wh, paint)
        paint.color = Color.argb(150, 255, 70, 70); canvas.drawRect(0f, h / 2 - 1, w, h / 2 + 1, paint)
        canvas.restore()
        label.textSize = w * 0.62f
        val whiteAhead = shown >= 0.5f
        val atBottom = whiteAhead == whiteBottom
        label.color = if (whiteAhead) Color.rgb(50, 50, 50) else Color.rgb(220, 220, 220)
        canvas.save()
        val y = if (atBottom) h - w * 0.4f else w * 0.4f
        canvas.rotate(-90f, w / 2, y)
        label.textAlign = if (atBottom) Paint.Align.LEFT else Paint.Align.RIGHT
        canvas.drawText(text, w / 2, y + label.textSize * 0.35f, label)
        canvas.restore()
    }
}

/** Win-probability curve with mistake markers; tap to seek. */
class EvalGraphView(context: Context) : View(context) {
    var onSeek: ((Int) -> Unit)? = null
    var palette = PALETTES[0]
    private var evals: List<Score?> = emptyList()
    private var grades: Map<Int, MoveGrade> = emptyMap()
    private var current = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(evals: List<Score?>, grades: Map<Int, MoveGrade>, current: Int) { this.evals = evals; this.grades = grades; this.current = current; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = context.dpf(12)
        val clip = Path().apply { addRoundRect(RectF(0f, 0f, w, h), r, r, Path.Direction.CW) }
        canvas.save(); canvas.clipPath(clip)
        paint.style = Paint.Style.FILL; paint.color = if (palette.dark) Color.argb(90, 0, 0, 0) else Color.rgb(28, 54, 50)
        canvas.drawRect(0f, 0f, w, h, paint)
        val n = max(1, evals.size - 1)
        fun x(i: Int) = i * w / n
        fun y(s: Score) = h - (Review.winChance(s.centipawns) / 100).toFloat() * h
        if (evals.size >= 3) {
            val pts = ArrayList<Pair<Float, Float>>(); var last = Score(cp = 0)
            evals.forEachIndexed { i, s -> if (s != null) last = s; pts.add(x(i) to y(last)) }
            val line = Path(); line.moveTo(pts[0].first, pts[0].second)
            for (i in 0 until pts.size - 1) {
                val p0 = pts.getOrElse(i - 1) { pts[i] }; val p1 = pts[i]; val p2 = pts[i + 1]; val p3 = pts.getOrElse(i + 2) { p2 }
                line.cubicTo(p1.first + (p2.first - p0.first) / 6, p1.second + (p2.second - p0.second) / 6, p2.first - (p3.first - p1.first) / 6, p2.second - (p3.second - p1.second) / 6, p2.first, p2.second)
            }
            val area = Path(line); area.lineTo(pts.last().first, h); area.lineTo(0f, h); area.close()
            paint.shader = LinearGradient(0f, 0f, 0f, h, Color.argb(245, 240, 245, 242), Color.argb(150, 240, 245, 242), Shader.TileMode.CLAMP)
            canvas.drawPath(area, paint); paint.shader = null
            paint.style = Paint.Style.STROKE; paint.strokeWidth = context.dpf(1.6f); paint.color = palette.accent
            canvas.drawPath(line, paint); paint.style = Paint.Style.FILL
            for ((ply, g) in grades) {
                val s = evals.getOrNull(ply) ?: continue
                val color = when (g.grade) { Grade.INACCURACY -> Color.rgb(232, 197, 71); Grade.MISTAKE -> Color.rgb(240, 138, 60); Grade.BLUNDER -> Color.rgb(229, 72, 77); else -> continue }
                paint.color = Color.WHITE; canvas.drawCircle(x(ply), y(s), context.dpf(4.6f), paint)
                paint.color = color; canvas.drawCircle(x(ply), y(s), context.dpf(3.4f), paint)
            }
            paint.color = palette.gold; paint.strokeWidth = context.dpf(2)
            canvas.drawLine(x(min(current, n)), 0f, x(min(current, n)), h, paint)
        }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = context.dpf(1); paint.color = Color.argb(120, 128, 128, 128)
        paint.pathEffect = DashPathEffect(floatArrayOf(context.dpf(4), context.dpf(4)), 0f)
        canvas.drawLine(0f, h / 2, w, h / 2, paint); paint.pathEffect = null; paint.style = Paint.Style.FILL
        canvas.restore()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (evals.size < 2) return false
        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
            parent?.requestDisallowInterceptTouchEvent(true)
            val ply = ((e.x / width) * (evals.size - 1) + 0.5f).toInt().coerceIn(0, evals.size - 1)
            if (ply != current) onSeek?.invoke(ply)
        }
        return true
    }
}

/** Wrapping row layout for move chips. */
class FlowLayout(context: Context, private val hGap: Int, private val vGap: Int) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + vGap; rowH = 0 }
            x += c.measuredWidth + hGap; rowH = max(rowH, c.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), y + rowH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + vGap; rowH = 0 }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + hGap; rowH = max(rowH, c.measuredHeight)
        }
    }
}

/** Small stroke icons drawn with canvas primitives (no drawable resources). */
class IconView(context: Context, kind: String, var tint: Int) : View(context) {
    var kind = kind
        set(v) { if (field != v) { field = v; invalidate() } }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    override fun onDraw(canvas: Canvas) {
        val s = min(width, height) / 24f
        canvas.save(); canvas.translate((width - 24 * s) / 2, (height - 24 * s) / 2); canvas.scale(s, s)
        p.color = if (isEnabled) tint else withAlpha(tint, 0.35f); p.strokeWidth = 1.9f
        path.reset()
        when (kind) {
            "plus" -> { canvas.drawLine(12f, 5f, 12f, 19f, p); canvas.drawLine(5f, 12f, 19f, 12f, p) }
            "share" -> { path.moveTo(4f, 12f); path.lineTo(4f, 20f); path.lineTo(20f, 20f); path.lineTo(20f, 12f); canvas.drawPath(path, p); canvas.drawLine(12f, 3f, 12f, 15f, p); path.reset(); path.moveTo(8f, 7f); path.lineTo(12f, 3f); path.lineTo(16f, 7f); canvas.drawPath(path, p) }
            "settings" -> {
                canvas.drawCircle(12f, 12f, 3.2f, p); canvas.drawCircle(12f, 12f, 7.2f, p)
                for (i in 0 until 8) { val a = i * PI / 4; canvas.drawLine(12 + 7.2f * cos(a).toFloat(), 12 + 7.2f * sin(a).toFloat(), 12 + 9.6f * cos(a).toFloat(), 12 + 9.6f * sin(a).toFloat(), p) }
            }
            "undo" -> { path.moveTo(9f, 14f); path.lineTo(4f, 9f); path.lineTo(9f, 4f); canvas.drawPath(path, p); path.reset(); path.moveTo(4f, 9f); path.lineTo(14.5f, 9f); path.cubicTo(22f, 9f, 22f, 20f, 14.5f, 20f); path.lineTo(11f, 20f); canvas.drawPath(path, p) }
            "bulb" -> { path.moveTo(9f, 17f); path.cubicTo(9f, 14f, 5f, 13f, 5f, 9f); path.cubicTo(5f, 1f, 19f, 1f, 19f, 9f); path.cubicTo(19f, 13f, 15f, 14f, 15f, 17f); path.close(); canvas.drawPath(path, p); canvas.drawLine(10f, 21f, 14f, 21f, p) }
            "flip" -> { canvas.drawLine(7f, 3f, 7f, 21f, p); canvas.drawLine(17f, 3f, 17f, 21f, p); path.moveTo(3f, 7f); path.lineTo(7f, 3f); path.lineTo(11f, 7f); path.moveTo(13f, 17f); path.lineTo(17f, 21f); path.lineTo(21f, 17f); canvas.drawPath(path, p) }
            "first" -> { path.moveTo(18f, 17f); path.lineTo(13f, 12f); path.lineTo(18f, 7f); path.moveTo(11f, 17f); path.lineTo(6f, 12f); path.lineTo(11f, 7f); canvas.drawPath(path, p) }
            "prev" -> { path.moveTo(15f, 18f); path.lineTo(9f, 12f); path.lineTo(15f, 6f); canvas.drawPath(path, p) }
            "next" -> { path.moveTo(9f, 18f); path.lineTo(15f, 12f); path.lineTo(9f, 6f); canvas.drawPath(path, p) }
            "last" -> { path.moveTo(6f, 17f); path.lineTo(11f, 12f); path.lineTo(6f, 7f); path.moveTo(13f, 17f); path.lineTo(18f, 12f); path.lineTo(13f, 7f); canvas.drawPath(path, p) }
            "chart" -> { path.moveTo(3f, 3f); path.lineTo(3f, 21f); path.lineTo(21f, 21f); path.moveTo(7f, 15f); path.lineTo(11f, 11f); path.lineTo(14f, 14f); path.lineTo(19f, 8f); canvas.drawPath(path, p) }
            "close" -> { canvas.drawLine(6f, 6f, 18f, 18f, p); canvas.drawLine(18f, 6f, 6f, 18f, p) }
            "pause" -> { canvas.drawLine(9f, 6f, 9f, 18f, p); canvas.drawLine(15f, 6f, 15f, 18f, p) }
            "play" -> { path.moveTo(8f, 5f); path.lineTo(19f, 12f); path.lineTo(8f, 19f); path.close(); canvas.drawPath(path, p) }
        }
        canvas.restore()
    }
    override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); invalidate() }
}

/** Synthesised move sounds via AudioTrack (no audio assets). */
class Sounds {
    var enabled = true
    private val rate = 22050
    private val clips = HashMap<String, ShortArray>()
    private val rng = Random(7) // must be initialised before init {} renders the clips

    init {
        clips["move"] = render(0.09) { t, i -> tone(t, 520.0, 0.09, 0.32) + noise(i, t, 0.04, 0.18) }
        clips["capture"] = render(0.15) { t, i -> tone(t, 300.0, 0.14, 0.45, square = true) * 0.7 + noise(i, t, 0.08, 0.4) }
        clips["check"] = render(0.26) { t, _ -> tone(t, 880.0, 0.12, 0.22) + tone(t - 0.08, 1320.0, 0.16, 0.2) }
        clips["end"] = render(0.95) { t, _ -> tone(t, 523.0, 0.9, 0.16) + tone(t - 0.07, 659.0, 0.9, 0.14) + tone(t - 0.14, 784.0, 0.9, 0.12) }
    }

    private fun noise(@Suppress("UNUSED_PARAMETER") i: Int, t: Double, dur: Double, gain: Double) = if (t in 0.0..dur) (rng.nextDouble() * 2 - 1) * gain * (1 - t / dur).let { it * it * it } else 0.0
    private fun tone(t: Double, f: Double, dur: Double, gain: Double, square: Boolean = false): Double {
        if (t < 0 || t > dur) return 0.0
        val freq = f * (1 - 0.45 * t / dur)
        val env = min(1.0, t / 0.006) * exp(-5.5 * t / dur)
        val s = sin(2 * PI * freq * t)
        return gain * env * (if (square) (if (s >= 0) 0.6 else -0.6) else s)
    }

    private fun render(seconds: Double, f: (Double, Int) -> Double): ShortArray {
        val n = (rate * seconds).toInt()
        return ShortArray(n) { i -> (f(i.toDouble() / rate, i).coerceIn(-1.0, 1.0) * 26000).toInt().toShort() }
    }

    fun play(kind: String) {
        if (!enabled) return
        val data = clips[kind] ?: return
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(data.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
            track.write(data, 0, data.size)
            track.setNotificationMarkerPosition(data.size - 1)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) { t.release() }
                override fun onPeriodicNotification(t: AudioTrack) {}
            })
            track.play()
        }
    }
}
