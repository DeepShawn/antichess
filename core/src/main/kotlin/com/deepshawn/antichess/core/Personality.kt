package com.deepshawn.antichess.core

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.random.Random

// 谱着 personality — mirrors web/src/engine/personality.js.

data class Level(
    val id: String, val name: String, val elo: Int, val depth: Int, val movetime: Int, val multipv: Int,
    val temperature: Double, val maxLoss: Int, val blunder: Double,
)

val LEVELS = listOf(
    Level("novice", "入门", 800, 2, 250, 6, 320.0, 900, 0.14),
    Level("casual", "初级", 1100, 4, 400, 5, 170.0, 450, 0.07),
    Level("club", "中级", 1400, 7, 700, 4, 90.0, 260, 0.035),
    Level("expert", "高级", 1800, 10, 1100, 4, 45.0, 140, 0.012),
    Level("master", "大师", 2200, 14, 1600, 3, 18.0, 60, 0.003),
    Level("max", "Stockfish 19", 2850, 20, 2500, 3, 1.0, 0, 0.0),
)

data class Preference(val id: String, val name: String, val note: String, val weights: DoubleArray)

val PREFERENCES = listOf(
    Preference("main", "一选主线", "稳定，接近引擎首选", doubleArrayOf(0.72, 0.16, 0.08, 0.04)),
    Preference("second", "二选偏好", "最像真人：常走次优但合理的着法", doubleArrayOf(0.15, 0.52, 0.25, 0.08)),
    Preference("third", "三选偏好", "更有探索性，局面更丰富", doubleArrayOf(0.08, 0.28, 0.52, 0.12)),
    Preference("pure", "纯一选", "复盘主线，几乎总走最佳着", doubleArrayOf(0.96, 0.02, 0.01, 0.01)),
    Preference("random", "均匀探索", "候选着法等概率", doubleArrayOf(0.25, 0.25, 0.25, 0.25)),
)

val RANK_NAMES = listOf("一选", "二选", "三选", "四选", "五选", "六选")

/** UCI-style score: exactly one of cp / mate is set. Always from the side to move unless stated. */
data class Score(val cp: Int? = null, val mate: Int? = null) {
    val centipawns: Int get() = when {
        mate != null -> if (mate > 0) 10000 - mate * 10 else -10000 - mate * 10
        else -> cp ?: 0
    }
    operator fun unaryMinus() = Score(cp?.let { -it }, mate?.let { -it })
    fun format(): String = when {
        mate != null -> (if (mate > 0) "#" else "#-") + kotlin.math.abs(mate)
        else -> { val v = (cp ?: 0) / 100.0; (if (v > 0) "+" else if (v < 0) "−" else "") + "%.2f".format(kotlin.math.abs(v)) }
    }
}

data class EngineLine(val score: Score, val pv: List<String>, val depth: Int = 0)

private fun prefWeight(p: Preference, i: Int): Double =
    if (i < p.weights.size) p.weights[i] else p.weights.last() * 0.5.pow(i - p.weights.size + 1)

object Personality {
    fun engineDistribution(lines: List<EngineLine>, level: Level, pref: Preference): DoubleArray {
        if (lines.isEmpty()) return DoubleArray(0)
        val best = lines[0].score.centipawns
        val raw = DoubleArray(lines.size) { i ->
            val loss = max(0, best - lines[i].score.centipawns)
            if (i == 0 || loss <= level.maxLoss) prefWeight(pref, i) * exp(-loss / max(1.0, level.temperature)) else 0.0
        }
        val total = raw.sum().takeIf { it > 0 } ?: 1.0
        return DoubleArray(raw.size) { raw[it] / total }
    }

    fun bookDistribution(book: List<BookMove>, pref: Preference): DoubleArray {
        val raw = DoubleArray(book.size) { 0.5 * book[it].share + 0.5 * prefWeight(pref, it) }
        val total = raw.sum().takeIf { it > 0 } ?: 1.0
        return DoubleArray(raw.size) { raw[it] / total }
    }

    fun sample(probs: DoubleArray, rng: Random): Int {
        val r = rng.nextDouble(); var acc = 0.0
        for (i in probs.indices) { acc += probs[i]; if (r < acc) return i }
        return max(0, probs.indexOfFirst { it > 0 })
    }

    enum class Source { BOOK, ENGINE, BLUNDER }
    data class Choice(val uci: String, val rank: Int, val source: Source, val probs: DoubleArray)

    fun choose(book: List<BookMove>, lines: List<EngineLine>, legal: List<String>, level: Level, pref: Preference, blunders: Boolean, rng: Random = Random.Default): Choice {
        if (book.isNotEmpty() && level.id != "max") {
            val probs = bookDistribution(book, pref)
            val i = sample(probs, rng)
            return Choice(book[i].uci, i, Source.BOOK, probs)
        }
        if (blunders && level.blunder > 0 && legal.size > 1 && rng.nextDouble() < level.blunder) {
            val pool = legal.filter { it != lines.firstOrNull()?.pv?.firstOrNull() }
            return Choice(pool[rng.nextInt(pool.size)], -1, Source.BLUNDER, engineDistribution(lines, level, pref))
        }
        val probs = engineDistribution(lines, level, pref)
        val i = sample(probs, rng)
        return Choice(lines.getOrNull(i)?.pv?.firstOrNull() ?: legal.first(), i, Source.ENGINE, probs)
    }
}
