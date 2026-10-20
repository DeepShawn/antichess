package com.deepshawn.antichess.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.deepshawn.antichess.core.Piece
import kotlin.math.hypot

data class Dest(val to: String, val promotion: Boolean)
data class Arrow(val from: String, val to: String, val color: Int, val width: Float = 0.16f, val label: String? = null)

/** Canvas chess board with animated moves, drag & tap input, arrows and a promotion picker. */
class BoardView(context: Context, private val pieces: PieceSet) : View(context) {
    var onMove: ((from: String, to: String, promotion: Char?) -> Unit)? = null

    var whiteBottom = true
        set(v) { if (field != v) { field = v; invalidate() } }
    var theme = BOARD_THEMES[0]
        set(v) { field = v; invalidate() }
    var accent = Color.rgb(118, 224, 181)
    var showCoords = true
        set(v) { field = v; invalidate() }

    private var position: Map<String, String> = emptyMap()
    private var lastMove: Pair<String, String>? = null
    private var check: String? = null
    private var movable: Boolean? = null // null = none, true = white, false = black (bothColors overrides)
    private var bothColors = false
    private var dests: Map<String, List<Dest>> = emptyMap()
    private var arrows: List<Arrow> = emptyList()
    private var selected: String? = null

    private class Slide(val code: String, val from: String, val to: String)
    private var slides: List<Slide> = emptyList()
    private var fading: List<Pair<String, String>> = emptyList()
    private var progress = 1f
    private var animator: ValueAnimator? = null

    private var dragSquare: String? = null
    private var dragX = 0f; private var dragY = 0f
    private var downX = 0f; private var downY = 0f
    private var dragging = false
    private var promo: Pair<String, String>? = null // from, to
    private var promoWhite = true

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val rect = RectF()
    private val arrowPath = Path()

    init { isHapticFeedbackEnabled = true }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w)
    }

    private val cell get() = width / 8f

    private fun coords(sq: String): Pair<Int, Int> {
        val file = sq[0] - 'a'; val rank = sq[1] - '1'
        return if (whiteBottom) file to 7 - rank else 7 - file to rank
    }

    private fun squareAt(x: Float, y: Float): String? {
        if (x < 0 || y < 0 || x >= width || y >= height) return null
        val col = (x / cell).toInt(); val row = (y / cell).toInt()
        return if (whiteBottom) "${'a' + col}${8 - row}" else "${'a' + (7 - col)}${row + 1}"
    }

    /** Syncs to a position (rank-8-first grid). Moved pieces slide from their nearest previous square. */
    fun setPosition(board: List<List<Piece?>>, last: Pair<String, String>?, checkSquare: String?, animate: Boolean) {
        val next = HashMap<String, String>()
        board.forEachIndexed { r, row -> row.forEachIndexed { c, p -> if (p != null) next["${'a' + c}${8 - r}"] = p.code } }
        lastMove = last; check = checkSquare
        if (next == position) { invalidate(); return }
        selected = null
        if (animate && position.isNotEmpty()) {
            val old = HashMap(position)
            val newSlides = ArrayList<Slide>()
            for ((sq, code) in next) if (old[sq] == code) old.remove(sq)
            for ((sq, code) in next) {
                if (position[sq] == code) continue
                val from = old.entries.filter { it.value == code }.minByOrNull { dist(it.key, sq) }?.key
                if (from != null) { old.remove(from); newSlides.add(Slide(code, from, sq)) }
            }
            slides = newSlides; fading = old.map { it.key to it.value }
            animator?.cancel()
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 230; interpolator = DecelerateInterpolator(1.6f)
                addUpdateListener { progress = it.animatedValue as Float; invalidate() }
                start()
            }
        } else { slides = emptyList(); fading = emptyList(); progress = 1f }
        position = next
        invalidate()
    }

    private fun dist(a: String, b: String) = hypot((a[0] - b[0]).toFloat(), (a[1] - b[1]).toFloat())

    fun setInteractive(white: Boolean?, both: Boolean, moves: Map<String, List<Dest>>) {
        movable = white; bothColors = both; dests = moves
        if (selected != null && dests[selected!!] == null) selected = null
        invalidate()
    }

    fun setArrows(list: List<Arrow>) { arrows = list; invalidate() }

    private fun canPick(sq: String): Boolean {
        val code = position[sq] ?: return false
        if (bothColors) return true
        val m = movable ?: return false
        return (code[0] == 'w') == m
    }

    override fun onDraw(canvas: Canvas) {
        val c = cell
        // Squares
        for (row in 0..7) for (col in 0..7) {
            val light = (row + col) % 2 == 0
            paint.color = if (light) theme.light else theme.dark
            canvas.drawRect(col * c, row * c, col * c + c, row * c + c, paint)
        }
        lastMove?.let { (a, b) -> for (sq in listOf(a, b)) fillSquare(canvas, sq, Color.argb(105, 255, 210, 80)) }
        selected?.let { fillSquare(canvas, it, withAlpha(accent, 0.55f)) }
        check?.let { sq ->
            val (x, y) = coords(sq)
            paint.shader = RadialGradient(x * c + c / 2, y * c + c / 2, c * 0.62f, intArrayOf(Color.argb(240, 255, 40, 40), Color.argb(130, 230, 30, 30), Color.TRANSPARENT), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(x * c, y * c, x * c + c, y * c + c, paint); paint.shader = null
        }
        if (showCoords) {
            text.textSize = c * 0.17f
            for (i in 0..7) {
                val fileSq = if (whiteBottom) "${'a' + i}1" else "${'a' + (7 - i)}8"
                val lightF = (i + 7) % 2 == 0
                text.color = if (lightF) theme.coordLight else theme.coordDark; text.textAlign = Paint.Align.RIGHT
                canvas.drawText(fileSq[0].toString(), i * c + c - c * 0.06f, 8 * c - c * 0.07f, text)
                val rankSq = if (whiteBottom) 8 - i else i + 1
                text.color = if (i % 2 == 0) theme.coordLight else theme.coordDark; text.textAlign = Paint.Align.LEFT
                canvas.drawText(rankSq.toString(), c * 0.06f, i * c + c * 0.2f, text)
            }
        }
        // Legal destinations
        selected?.let { s ->
            paint.color = Color.argb(70, 20, 40, 30)
            for (d in dests[s].orEmpty()) {
                val (x, y) = coords(d.to)
                if (position.containsKey(d.to)) {
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = c * 0.08f
                    canvas.drawCircle(x * c + c / 2, y * c + c / 2, c * 0.43f, paint); paint.style = Paint.Style.FILL
                } else canvas.drawCircle(x * c + c / 2, y * c + c / 2, c * 0.15f, paint)
            }
        }
        // Pieces
        val sliding = slides.map { it.to }.toSet()
        if (progress < 1f) for ((sq, code) in fading) { val (x, y) = coords(sq); pieces.draw(canvas, code, x * c, y * c, c, ((1 - progress) * 255).toInt()) }
        for ((sq, code) in position) {
            if (sq == dragSquare && dragging) continue
            if (sq in sliding && progress < 1f) continue
            val (x, y) = coords(sq)
            pieces.draw(canvas, code, x * c, y * c, c)
        }
        if (progress < 1f) for (s in slides) {
            val (x0, y0) = coords(s.from); val (x1, y1) = coords(s.to)
            pieces.draw(canvas, s.code, (x0 + (x1 - x0) * progress) * c, (y0 + (y1 - y0) * progress) * c, c)
        }
        for (a in arrows) drawArrow(canvas, a)
        if (dragging) dragSquare?.let { sq -> position[sq]?.let { code -> val s = c * 1.2f; pieces.draw(canvas, code, dragX - s / 2, dragY - s * 0.75f, s) } }
        promo?.let { (_, to) -> drawPromo(canvas, to) }
    }

    private fun fillSquare(canvas: Canvas, sq: String, color: Int) {
        val (x, y) = coords(sq); paint.color = color
        canvas.drawRect(x * cell, y * cell, x * cell + cell, y * cell + cell, paint)
    }

    private fun drawArrow(canvas: Canvas, a: Arrow) {
        val c = cell
        val (fx, fy) = coords(a.from); val (tx, ty) = coords(a.to)
        val x1 = (fx + .5f) * c; val y1 = (fy + .5f) * c; val x2 = (tx + .5f) * c; val y2 = (ty + .5f) * c
        val len = hypot(x2 - x1, y2 - y1); val ux = (x2 - x1) / len; val uy = (y2 - y1) / len; val px = -uy; val py = ux
        val w = a.width * c / 2; val hw = a.width * c * 1.9f; val head = c * 0.42f
        val sx = x1 + ux * c * .22f; val sy = y1 + uy * c * .22f; val bx = x2 - ux * head; val by = y2 - uy * head
        val tipx = x2 - ux * c * .08f; val tipy = y2 - uy * c * .08f
        arrowPath.reset()
        arrowPath.moveTo(sx + px * w, sy + py * w); arrowPath.lineTo(bx + px * w, by + py * w); arrowPath.lineTo(bx + px * hw, by + py * hw)
        arrowPath.lineTo(tipx, tipy); arrowPath.lineTo(bx - px * hw, by - py * hw); arrowPath.lineTo(bx - px * w, by - py * w); arrowPath.lineTo(sx - px * w, sy - py * w); arrowPath.close()
        paint.color = a.color; canvas.drawPath(arrowPath, paint)
        a.label?.let {
            paint.color = Color.WHITE; canvas.drawCircle(tx * c + c * .84f, ty * c + c * .16f, c * .15f, paint)
            text.textSize = c * .2f; text.color = Color.rgb(20, 30, 40); text.textAlign = Paint.Align.CENTER
            canvas.drawText(it, tx * c + c * .84f, ty * c + c * .23f, text)
        }
    }

    private fun promoSquares(to: String): List<Pair<Char, Pair<Int, Int>>> {
        val (x, y) = coords(to); val down = y == 0
        return listOf('q', 'n', 'r', 'b').mapIndexed { i, t -> t to (x to if (down) i else 7 - i) }
    }

    private fun drawPromo(canvas: Canvas, to: String) {
        paint.color = Color.argb(120, 5, 10, 14); canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        val c = cell
        for ((t, xy) in promoSquares(to)) {
            val (x, y) = xy
            paint.color = Color.WHITE; canvas.drawCircle(x * c + c / 2, y * c + c / 2, c * 0.47f, paint)
            pieces.draw(canvas, (if (promoWhite) "w" else "b") + t, x * c, y * c, c)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val c = cell
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x; downY = e.y
                promo?.let { (from, to) ->
                    val hit = promoSquares(to).firstOrNull { (_, xy) -> xy.first == (e.x / c).toInt() && xy.second == (e.y / c).toInt() }
                    promo = null; selected = null; invalidate()
                    if (hit != null) onMove?.invoke(from, to, hit.first)
                    return true
                }
                val sq = squareAt(e.x, e.y) ?: return true
                val sel = selected
                if (sel != null && sel != sq && dests[sel].orEmpty().any { it.to == sq }) { attempt(sel, sq); return true }
                if (!canPick(sq)) { if (sel != null) { selected = null; invalidate() }; return true }
                dragSquare = sq; dragging = false
                if (selected != sq) { selected = sq; performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragSquare != null && (dragging || hypot(e.x - downX, e.y - downY) > c * 0.15f)) {
                    dragging = true; dragX = e.x; dragY = e.y; invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val from = dragSquare
                if (from != null && dragging) {
                    val target = squareAt(e.x, e.y)
                    dragging = false; dragSquare = null
                    if (target != null && target != from && dests[from].orEmpty().any { it.to == target }) attempt(from, target)
                    invalidate()
                } else dragSquare = null
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; dragSquare = null; invalidate() }
        }
        return true
    }

    private fun attempt(from: String, to: String) {
        val d = dests[from].orEmpty().firstOrNull { it.to == to } ?: return
        if (d.promotion) {
            promo = from to to; promoWhite = position[from]?.startsWith("w") ?: true; invalidate(); return
        }
        selected = null
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        onMove?.invoke(from, to, null)
    }

    override fun onDetachedFromWindow() { animator?.cancel(); super.onDetachedFromWindow() }
}
