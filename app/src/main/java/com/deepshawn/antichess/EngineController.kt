package com.deepshawn.antichess

import android.content.Context
import kotlinx.coroutines.*
import java.io.File

data class Difficulty(val name:String, val elo:Int, val depth:Int, val skill:Int, val blunder:Double)
val LEVELS = listOf(
    Difficulty("入门", 800, 4, 2, .18), Difficulty("初级", 1100, 7, 5, .10),
    Difficulty("中级", 1400, 11, 10, .055), Difficulty("高级", 1800, 16, 16, .02),
    Difficulty("大师", 2200, 22, 20, .006), Difficulty("Stockfish 19", 2850, 28, 20, .001)
)

class EngineController(private val context: Context) {
    private var process: Process? = null; private var input: java.io.BufferedWriter? = null
    suspend fun start(): Boolean = withContext(Dispatchers.IO) { try {
        val asset = context.assets.open("stockfish/stockfish"); val file = File(context.filesDir, "stockfish")
        asset.use { it.copyTo(file.outputStream()) }; file.setExecutable(true)
        process = ProcessBuilder(file.absolutePath).redirectErrorStream(true).start()
        input = process!!.outputStream.bufferedWriter(); send("uci"); true
    } catch (_: Exception) { false } }
    fun send(command:String) { input?.apply { write(command); newLine(); flush() } }
    fun configure(level:Difficulty) { send("setoption name Skill Level value ${level.skill}"); send("setoption name UCI_LimitStrength value true"); send("setoption name UCI_Elo value ${level.elo}") }
    fun go(fen:String, level:Difficulty, callback:(String?)->Unit) { CoroutineScope(Dispatchers.IO).launch { send("position fen $fen"); send("go depth ${level.depth}"); val line=process?.inputStream?.bufferedReader()?.lineSequence()?.firstOrNull { it.startsWith("bestmove") }; withContext(Dispatchers.Main){callback(line?.split(" ")?.getOrNull(1))} } }
    fun stop(){send("stop");process?.destroy();process=null}
}
