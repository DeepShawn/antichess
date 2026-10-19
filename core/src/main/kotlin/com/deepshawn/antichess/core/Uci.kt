package com.deepshawn.antichess.core

/** Parsed `info … pv …` line. Score is from the side to move. */
data class UciInfo(val depth: Int, val multipv: Int, val score: Score, val nodes: Long, val nps: Long, val pv: List<String>)

object Uci {
    fun parseInfo(line: String): UciInfo? {
        if (!line.startsWith("info ") || !line.contains(" pv ")) return null
        val t = line.split(' ')
        fun num(k: String) = t.indexOf(k).takeIf { it > 0 }?.let { t.getOrNull(it + 1)?.toLongOrNull() }
        val s = t.indexOf("score")
        if (s < 0 || s + 2 >= t.size) return null
        if (t.getOrNull(s + 3) == "lowerbound" || t.getOrNull(s + 3) == "upperbound") return null
        val v = t[s + 2].toIntOrNull() ?: return null
        val score = if (t[s + 1] == "mate") Score(mate = v) else Score(cp = v)
        return UciInfo((num("depth") ?: 0).toInt(), (num("multipv") ?: 1).toInt(), score, num("nodes") ?: 0, num("nps") ?: 0, t.subList(t.indexOf("pv") + 1, t.size))
    }
}
