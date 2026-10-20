package com.deepshawn.antichess.core

import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {
    // Reference counts: https://www.chessprogramming.org/Perft_Results (same suite as tests/core.test.mjs)
    private val perftSuite = listOf(
        START_FEN to longArrayOf(20, 400, 8902, 197281),
        "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1" to longArrayOf(48, 2039, 97862),
        "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1" to longArrayOf(14, 191, 2812, 43238),
        "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1" to longArrayOf(6, 264, 9467),
        "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8" to longArrayOf(44, 1486, 62379),
        "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10" to longArrayOf(46, 2079, 89890),
    )

    @Test fun perft() {
        for ((fen, counts) in perftSuite) {
            val pos = Position(fen)
            counts.forEachIndexed { i, n -> assertEquals(n, perft(pos, i + 1), "$fen depth ${i + 1}") }
            assertEquals(Position(fen).fen(), pos.fen())
        }
    }

    @Test fun sanFenAndHash() {
        val g = Game()
        for (s in listOf("e4", "c5", "Nf3", "d6", "d4", "cxd4", "Nxd4", "Nf6", "Nc3", "a6")) assertNotNull(g.move(s), s)
        assertEquals("rnbqkb1r/1p2pppp/p2p1n2/8/3NP3/2N5/PPP2PPP/R1BQKB1R w KQkq - 0 6", g.fen())
        assertEquals(Position(g.fen()).hash, g.pos.hash)
        assertTrue("Rab1" in Game("7k/8/8/8/8/8/8/R5RK w - - 0 1").legalSan())
        assertEquals("a8=Q", Game("8/P6k/8/8/8/8/8/K7 w - - 0 1").move("a8=Q")?.san)
        val m = Game(); listOf("f3", "e5", "g4").forEach { m.move(it) }
        assertEquals("Qh4#", m.move("d8h4")?.san)
        assertEquals(EndReason.CHECKMATE, m.status().reason)
        assertEquals(false, m.status().winnerWhite)
    }

    @Test fun draws() {
        assertEquals(EndReason.INSUFFICIENT, Game("8/8/8/4k3/8/8/8/4KB2 w - - 0 1").status().reason)
        assertEquals(EndReason.STALEMATE, Game("k7/8/1Q6/8/8/8/8/7K b - - 0 1").status().reason)
        val g = Game(); listOf("Nf3", "Nf6", "Ng1", "Ng8", "Nf3", "Nf6", "Ng1", "Ng8").forEach { g.move(it) }
        assertEquals(EndReason.THREEFOLD, g.status().reason)
    }

    @Test fun pgnRoundTrip() {
        val g = Game(); listOf("e4", "e5", "Nf3", "Nc6", "Bb5", "a6", "Ba4", "Nf6", "O-O").forEach { g.move(it) }
        val back = Game.fromPgn(g.pgn(mapOf("White" to "A")))
        assertEquals(g.fen(), back.fen()); assertEquals("A", back.headers["White"])
        val b = Game("r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 3 3"); b.move("Nf6")
        assertTrue(b.pgn().contains("3... Nf6"))
        assertEquals(b.fen(), Game.fromPgn(b.pgn()).fen())
        assertFailsWith<FenException> { Position("8/8/8/8/8/8/8/8 w - - 0 1") }
    }

    @Test fun openingBookSharedWithWeb() {
        val book = OpeningBook.parse(File(System.getProperty("openings")).readText())
        assertTrue(book.lines.size > 60)
        for (line in book.lines) { val g = Game(); for (s in line.moves) assertNotNull(g.move(s), "${line.name}: $s") }
        val first = book.candidates(START_FEN)
        assertTrue(first.size >= 4); assertEquals(1.0, first.sumOf { it.share }, 1e-9)
        val g = Game(); val fens = mutableListOf(g.fen())
        for (s in "e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3 a6".split(' ')) { g.move(s); fens.add(g.fen()) }
        assertEquals("西西里·纳道尔夫", book.identify(fens)?.name)
    }

    private fun best(fen: String, ms: Long = 1500): Pair<String, Int> {
        val p = Position(fen); val r = Searcher().search(p, movetimeMs = ms)
        return p.uci(r.bestmove) to r.lines[0].score
    }

    @Test fun engineFindsTactics() {
        assertEquals("f3f7", best("r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5Q2/PPPP1PPP/RNB1K1NR w KQkq - 4 4").first)
        assertEquals("d1d8", best("6k1/5ppp/8/8/8/8/5PPP/3R2K1 w - - 0 1").first)
        val (mv, score) = best("r2qkb1r/pp2nppp/3p4/2pNN1B1/2BnP3/3P4/PPP2PPP/R2bK2R w KQkq - 1 1", 4000)
        assertEquals("d5f6", mv); assertTrue(score > MATE - 10)
        assertEquals(Score(mate = 2), Searcher.toScore(MATE - 3))
    }

    @Test fun evaluationIsSymmetric() {
        assertEquals(
            evaluate(Position("r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4")),
            evaluate(Position("rnbqk2r/pppp1ppp/5n2/2b1p3/4P3/2N2N2/PPPP1PPP/R1BQKB1R b KQkq - 4 4")),
        )
    }

    @Test fun multipvAndUci() {
        val r = Searcher().search(Position(), depth = 4, multipv = 3)
        assertEquals(3, r.lines.map { it.move }.toSet().size)
        val i = Uci.parseInfo("info depth 18 seldepth 25 multipv 2 score cp -34 nodes 1000 nps 50000 time 20 pv e7e5 g1f3")!!
        assertEquals(listOf(18, 2, -34), listOf(i.depth, i.multipv, i.score.cp))
        assertEquals(-3, Uci.parseInfo("info depth 5 multipv 1 score mate -3 nodes 1 pv a1a2")?.score?.mate)
        assertNull(Uci.parseInfo("info string NNUE evaluation enabled"))
    }

    @Test fun personalityMatchesWeb() {
        val lines = listOf(EngineLine(Score(40), listOf("e2e4")), EngineLine(Score(10), listOf("d2d4")), EngineLine(Score(-60), listOf("b1c3")), EngineLine(Score(-400), listOf("g2g4")))
        val second = PREFERENCES.first { it.id == "second" }
        assertEquals(1.0, Personality.engineDistribution(lines, LEVELS[5], second)[0], 1e-12)
        val club = Personality.engineDistribution(lines, LEVELS[2], second)
        assertTrue(club[1] > club[0]); assertEquals(0.0, club[3]); assertEquals(1.0, club.sum(), 1e-9)
        val c = Personality.choose(emptyList(), lines, listOf("e2e4", "d2d4", "a2a3"), LEVELS[0], second, blunders = true, rng = object : Random() { override fun nextBits(bitCount: Int) = 0 })
        assertEquals(Personality.Source.BLUNDER, c.source)
    }

    @Test fun review() {
        assertEquals(50.0, Review.winChance(0), 1e-9)
        assertEquals(Grade.BEST, Review.grade(Score(30), Score(20), true).grade)
        assertEquals(Grade.BLUNDER, Review.grade(Score(30), Score(-500), true).grade)
        assertEquals(Grade.BEST, Review.grade(Score(30), Score(-500), false).grade)
    }

    private val fried = "r1bqkb1r/ppp2ppp/2n5/3np1N1/2B5/8/PPPP1PPP/RNBQK2R w KQkq - 0 6"

    @Test fun sacrificeDetectionMatchesWeb() {
        assertEquals(2, Sacrifice.material(fried, listOf("g5f7", "e8f7", "d1f3", "f7e6")))
        assertEquals(0, Sacrifice.material(fried, listOf("c4d5", "d8d5")))
        assertEquals(1, Sacrifice.material(fried, listOf("d2d4", "e5d4")))
        assertEquals(0, Sacrifice.material(fried, listOf("g5f7")))
        assertEquals(0, Sacrifice.material(fried, listOf("g5f7", "e8f7", "c4d5", "c8e6")))
        assertEquals(0, Sacrifice.material(fried, listOf("h2h3", "d5f4", "d2d3", "f4g2")))
        val lines = listOf(
            EngineLine(Score(40), listOf("g5f7", "e8f7", "d1f3", "f7e6")), EngineLine(Score(35), listOf("d2d4", "e5d4", "e1g1", "c8e6")),
            EngineLine(Score(30), listOf("b1c3", "d5c3", "d2c3", "f8e7")), EngineLine(Score(-400), listOf("c4d5", "d8d5", "g5f7", "d5f7")),
        )
        assertEquals(listOf(Sac.BRILLIANT, Sac.GOOD, null, null), Sacrifice.classify(fried, lines, LEVELS[2]))
        val style = PREFERENCES.first { it.id == "sacrifice" }
        val plain = Personality.engineDistribution(lines, LEVELS[2], style)
        val c = Personality.choose(emptyList(), lines, emptyList(), LEVELS[2], style, blunders = false, fen = fried)
        assertTrue(c.probs[0] > 0.7 && c.probs[0] > plain[0] * 1.4)
        assertEquals(0.0, c.probs[3]); assertEquals(1.0, c.probs.sum(), 1e-9)
        val smothered = Position("r6k/6pp/7N/8/8/1Q6/6PP/6K1 w - - 0 1")
        assertEquals(9, Sacrifice.material(smothered.fen(), listOf("b3g8", "a8g8", "h6f7")))
    }
}
