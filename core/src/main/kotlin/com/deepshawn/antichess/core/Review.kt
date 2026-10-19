package com.deepshawn.antichess.core

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

// Move grading with the Lichess win-probability model — mirrors web/src/engine/review.js.

enum class Grade(val mark: String, val label: String) { BEST("", "最佳"), GOOD("", "好棋"), INACCURACY("?!", "欠准"), MISTAKE("?", "失误"), BLUNDER("??", "漏着") }
data class MoveGrade(val grade: Grade, val drop: Double, val accuracy: Double, val whiteMoved: Boolean)

object Review {
    fun winChance(cp: Int): Double = 50 + 50 * (2 / (1 + exp(-0.00368208 * max(-1500, min(1500, cp)))) - 1)

    /** before/after are white-perspective scores. */
    fun grade(before: Score, after: Score, whiteMoved: Boolean, playedBest: Boolean = false): MoveGrade {
        val sign = if (whiteMoved) 1 else -1
        val drop = max(0.0, winChance(sign * before.centipawns) - winChance(sign * after.centipawns))
        val accuracy = max(0.0, min(100.0, 103.1668 * exp(-0.04354 * drop) - 3.1669))
        val g = when {
            playedBest || drop < 2 -> Grade.BEST
            drop >= 30 -> Grade.BLUNDER
            drop >= 20 -> Grade.MISTAKE
            drop >= 10 -> Grade.INACCURACY
            else -> Grade.GOOD
        }
        return MoveGrade(g, drop, accuracy, whiteMoved)
    }

    /** Blend of arithmetic and harmonic mean of per-move accuracy, per side (null when no data). */
    fun accuracy(grades: Collection<MoveGrade>, white: Boolean): Double? {
        val xs = grades.filter { it.whiteMoved == white }.map { it.accuracy }
        if (xs.isEmpty()) return null
        return (xs.average() + xs.size / xs.sumOf { 1 / max(1.0, it) }) / 2
    }
}
