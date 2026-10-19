package com.deepshawn.antichess.core

// Opening book shared with the web app (web/data/openings.txt, packaged as an Android asset).
// Positions are keyed by the first four FEN fields so transpositions are recognised.

data class OpeningLine(val eco: String, val name: String, val english: String, val weight: Int, val moves: List<String>)
data class BookMove(val san: String, val uci: String, val weight: Int, val share: Double, val name: String, val eco: String)

class OpeningBook private constructor() {
    val lines = ArrayList<OpeningLine>()
    private class Edge(val san: String, val uci: String, var weight: Int, var line: OpeningLine)
    private val edges = HashMap<String, LinkedHashMap<String, Edge>>()
    private val named = HashMap<String, OpeningLine>()

    fun candidates(fen: String): List<BookMove> {
        val list = edges[key(fen)]?.values?.sortedByDescending { it.weight } ?: return emptyList()
        val total = list.sumOf { it.weight }
        return list.map { BookMove(it.san, it.uci, it.weight, if (total > 0) it.weight.toDouble() / total else 1.0 / list.size, it.line.name, it.line.eco) }
    }

    /** Deepest named opening along a game, given the FEN after each ply (index 0 = start). */
    fun identify(fens: List<String>): OpeningLine? {
        for (i in fens.indices.reversed()) named[key(fens[i])]?.let { return it }
        return null
    }

    companion object {
        fun key(fen: String) = fen.split(" ").take(4).joinToString(" ")

        fun parse(text: String): OpeningBook {
            val book = OpeningBook()
            for (raw in text.lineSequence()) {
                val row = raw.trim()
                if (row.isEmpty() || row.startsWith("#")) continue
                val cols = row.split('|').map { it.trim() }
                if (cols.size < 5) continue
                val line = OpeningLine(cols[0], cols[1], cols[2], cols[3].toIntOrNull() ?: 1, cols[4].split(Regex("\\s+")))
                book.lines.add(line)
                val g = Game()
                for (san in line.moves) {
                    val k = key(g.fen())
                    val rec = g.move(san) ?: break
                    val map = book.edges.getOrPut(k) { LinkedHashMap() }
                    val e = map.getOrPut(rec.san) { Edge(rec.san, rec.uci, 0, line) }
                    e.weight += line.weight
                    if (line.weight > e.line.weight) e.line = line
                }
                val end = key(g.fen())
                val prev = book.named[end]
                if (prev == null || prev.moves.size > line.moves.size) book.named[end] = line
            }
            return book
        }
    }
}
