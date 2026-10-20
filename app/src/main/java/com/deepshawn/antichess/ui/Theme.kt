package com.deepshawn.antichess.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import org.json.JSONObject

/** App palettes, mirroring the web themes (深夜 / 晨雾 / 暮色). */
data class Palette(
    val id: String, val label: String, val dark: Boolean,
    val bg: Int, val bg2: Int, val panel: Int, val panel2: Int, val line: Int,
    val text: Int, val muted: Int, val faint: Int,
    val accent: Int, val accentDeep: Int, val onAccent: Int, val accent2: Int, val gold: Int, val bad: Int,
)

val PALETTES = listOf(
    Palette("midnight", "深夜", true, 0xff0b1016.toInt(), 0xff10202a.toInt(), 0xff141f2a.toInt(), 0xff1b2a37.toInt(), 0x22a0c8e6,
        0xffeef4f8.toInt(), 0xff8b9cb0.toInt(), 0xff5d6e80.toInt(), 0xff76e0b5.toInt(), 0xff2f9d77.toInt(), 0xff062219.toInt(), 0xff81b8ff.toInt(), 0xffe9bd6e.toInt(), 0xfff27f7f.toInt()),
    Palette("mist", "晨雾", false, 0xffeaf1ee.toInt(), 0xffdcebe4.toInt(), 0xffffffff.toInt(), 0xfff0f6f3.toInt(), 0x2214322d,
        0xff15252c.toInt(), 0xff5d7079.toInt(), 0xff93a3aa.toInt(), 0xff23a077.toInt(), 0xff137656.toInt(), 0xffffffff.toInt(), 0xff3a7bd5.toInt(), 0xffc58a1c.toInt(), 0xffd9534f.toInt()),
    Palette("dusk", "暮色", true, 0xff120f17.toInt(), 0xff221626.toInt(), 0xff221a29.toInt(), 0xff32253a.toInt(), 0x22ffc8b4,
        0xfff8efe9.toInt(), 0xffb19c9f.toInt(), 0xff7c6a70.toInt(), 0xffffb86b.toInt(), 0xffe0794a.toInt(), 0xff2a1405.toInt(), 0xffff8fa3.toInt(), 0xffffd37d.toInt(), 0xfff27f7f.toInt()),
)

data class BoardTheme(val id: String, val label: String, val light: Int, val dark: Int, val coordLight: Int, val coordDark: Int)

val BOARD_THEMES = listOf(
    BoardTheme("jade", "翡翠", 0xffdfe9dc.toInt(), 0xff6f9f86.toInt(), 0xff4f7a63.toInt(), 0xffe9f3e6.toInt()),
    BoardTheme("walnut", "胡桃", 0xffefdcb8.toInt(), 0xffb07d52.toInt(), 0xff9a6a42.toInt(), 0xfff5e6c9.toInt()),
    BoardTheme("slate", "青石", 0xffdfe5e9.toInt(), 0xff7f97a5.toInt(), 0xff637b89.toInt(), 0xffeef3f6.toInt()),
    BoardTheme("rose", "樱粉", 0xfff7e7e3.toInt(), 0xffc98d92.toInt(), 0xffa8676d.toInt(), 0xfffbefec.toInt()),
)

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Number): Float = v.toFloat() * resources.displayMetrics.density

fun rounded(color: Int, radius: Float, stroke: Int? = null, strokeWidth: Int = 1) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius; stroke?.let { setStroke(strokeWidth, it) }
}

fun gradient(from: Int, to: Int, radius: Float) = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(from, to)).apply { cornerRadius = radius }

fun withAlpha(color: Int, alpha: Float) = Color.argb((alpha * 255).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

/**
 * The shared Cburnett piece set (web/data/pieces.json, packaged as an asset).
 * Path data uses only absolute M/L/C/Z, so a tiny parser suffices — no androidx needed.
 */
class PieceSet(json: String) {
    private class Part(val path: Path, val fill: Int?, val stroke: Int?, val width: Float, val cap: Paint.Cap, val join: Paint.Join)
    private val pieces = HashMap<String, List<Part>>()
    val viewBox: Float
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        val root = JSONObject(json)
        viewBox = root.getDouble("viewBox").toFloat()
        val set = root.getJSONObject("pieces")
        for (code in set.keys()) {
            val arr = set.getJSONArray(code)
            pieces[code] = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val m = o.getJSONArray("m")
                val path = parsePath(o.getString("d"))
                path.transform(Matrix().apply { setValues(floatArrayOf(m.getDouble(0).toFloat(), m.getDouble(2).toFloat(), m.getDouble(4).toFloat(), m.getDouble(1).toFloat(), m.getDouble(3).toFloat(), m.getDouble(5).toFloat(), 0f, 0f, 1f)) })
                Part(
                    path,
                    if (o.isNull("fill")) null else Color.parseColor(o.getString("fill")),
                    if (o.isNull("stroke")) null else Color.parseColor(o.getString("stroke")),
                    o.optDouble("width", 1.0).toFloat(),
                    if (o.optString("cap") == "round") Paint.Cap.ROUND else if (o.optString("cap") == "square") Paint.Cap.SQUARE else Paint.Cap.BUTT,
                    if (o.optString("join") == "round") Paint.Join.ROUND else Paint.Join.MITER,
                )
            }
        }
    }

    /** Draws piece `code` ("wq", "bn", …) into the square at (x, y) with side `size`. */
    fun draw(canvas: Canvas, code: String, x: Float, y: Float, size: Float, alpha: Int = 255) {
        val parts = pieces[code] ?: return
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(size / viewBox, size / viewBox)
        for (p in parts) {
            p.fill?.let { paint.style = Paint.Style.FILL; paint.color = it; paint.alpha = alpha; canvas.drawPath(p.path, paint) }
            p.stroke?.let {
                paint.style = Paint.Style.STROKE; paint.color = it; paint.alpha = alpha
                paint.strokeWidth = p.width; paint.strokeCap = p.cap; paint.strokeJoin = p.join
                canvas.drawPath(p.path, paint)
            }
        }
        canvas.restore()
    }

    companion object {
        fun parsePath(d: String): Path {
            val path = Path()
            val tokens = Regex("[MLCZ]|-?\\d*\\.?\\d+(?:e-?\\d+)?").findAll(d).map { it.value }.toList()
            var i = 0; var cmd = 'M'
            fun num() = tokens[i++].toFloat()
            while (i < tokens.size) {
                val t = tokens[i]
                if (t.length == 1 && t[0] in "MLCZ") { cmd = t[0]; i++; if (cmd == 'Z') { path.close(); continue } }
                when (cmd) {
                    'M' -> { path.moveTo(num(), num()); cmd = 'L' }
                    'L' -> path.lineTo(num(), num())
                    'C' -> path.cubicTo(num(), num(), num(), num(), num(), num())
                    else -> i++
                }
            }
            return path
        }
    }
}
