package com.deepshawn.antichess

import android.app.*;import android.os.*;import android.graphics.*;import android.view.*;import android.widget.*;import android.content.*;import kotlin.math.min;import kotlinx.coroutines.*

class MainActivity:Activity(){
 private lateinit var board:ChessBoardView; private lateinit var engine:EngineController; private lateinit var status:TextView; private var level=LEVELS[2]
 private val prefs by lazy{getSharedPreferences("puzhe-session",MODE_PRIVATE)}; private var analysis=false
 override fun onCreate(b:Bundle?){super.onCreate(b);window.statusBarColor=Color.rgb(13,17,23);val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(20,18,20,14);setBackgroundColor(Color.rgb(13,17,23))}
  val title=TextView(this).apply{text="♞  谱着 · Chess Lab";setTextColor(Color.WHITE);textSize=24f;setTypeface(null,1);setPadding(0,0,0,3)};root.addView(title)
  val subtitle=TextView(this).apply{text="本地引擎 · 可恢复对局 · 分析工作台";setTextColor(Color.rgb(143,161,180));textSize=12f;setPadding(0,0,0,10)};root.addView(subtitle)
  status=TextView(this).apply{text="Stockfish 19 · 正在初始化";setTextColor(Color.rgb(118,224,181));textSize=13f;setPadding(12,9,12,9);setBackgroundColor(Color.rgb(16,25,35))};root.addView(status)
  board=ChessBoardView(this);root.addView(board,LinearLayout.LayoutParams(-1,0,1f));
  val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}; val label=TextView(this).apply{text="难度";setTextColor(Color.LTGRAY);textSize=14f};row.addView(label)
  val spinner=Spinner(this);spinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,LEVELS.map{"${it.name} · ${it.elo} 分"});spinner.setSelection(2);spinner.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onNothingSelected(p:AdapterView<*>?){};override fun onItemSelected(p:AdapterView<*>?,v:View?,pos:Int,id:Long){level=LEVELS[pos];if(::engine.isInitialized)engine.configure(level);status.text="Stockfish 19 · ${level.name} · ${level.elo} Elo"}};row.addView(spinner,LinearLayout.LayoutParams(0,60,1f));val newBtn=Button(this).apply{text="新局";setOnClickListener{board.reset();clearSession()}};row.addView(newBtn);root.addView(row)
  val actions=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};fun actionRow(vararg labels:Pair<String,()->Unit>):List<Button>{val row=LinearLayout(this@MainActivity).apply{orientation=LinearLayout.HORIZONTAL};val result=labels.map{pair->val button=Button(this@MainActivity);button.text=pair.first;button.setOnClickListener{pair.second()};row.addView(button,LinearLayout.LayoutParams(0,58,1f));button};actions.addView(row);return result};actionRow("保存" to {saveSession()},"继续" to {restoreSession()});actionRow("摆棋/FEN" to {fenDialog()});lateinit var analysisButton:Button;analysisButton=actionRow("分析：关" to {analysis=!analysis;status.text=if(analysis)"分析模式 · ${level.name} · 候选走法" else "对局模式 · ${level.name}"})[0];root.addView(actions);setContentView(root)
  engine=EngineController(this);restoreSession();CoroutineScope(Dispatchers.Main).launch{if(engine.start())status.text="Stockfish 19 · 本地 UCI · ${level.elo} Elo" else status.text="未找到本地引擎 · 请放入 assets/stockfish/stockfish"}
 }
 private fun saveSession(){prefs.edit().putInt("moves",board.moveCount()).putString("fen",board.fen).putString("pgn",board.pgn()).apply();status.text="对局已保存 · 可继续"}
 private fun clearSession(){prefs.edit().clear().apply()}
 private fun restoreSession(){board.restore(prefs.getString("fen",null),prefs.getInt("moves",0),prefs.getString("pgn","")?:"");if(prefs.contains("fen"))status.text="已恢复中断对局 · ${board.moveCount()} 步"}
 private fun fenDialog(){val input=EditText(this);input.hint="例如：rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";input.setText(board.fen);AlertDialog.Builder(this).setTitle("摆棋 / 输入 FEN").setView(input).setPositiveButton("应用"){_,_->board.fen=input.text.toString();board.invalidate();saveSession()}.setNegativeButton("取消",null).show()}
 override fun onPause(){saveSession();super.onPause()};override fun onDestroy(){engine.stop();super.onDestroy()}
}

class ChessBoardView(c:Context):View(c){private val p=Paint(1);private val light=Color.rgb(220,232,220);private val dark=Color.rgb(93,143,118);private var moves=0;var fen="startpos";private var moveLog=mutableListOf<String>();init{setBackgroundColor(Color.rgb(13,17,23))}
 override fun onDraw(canvas:Canvas){super.onDraw(canvas);val s=min(width,height).toFloat()/8;for(r in 0..7)for(col in 0..7){p.color=if((r+col)%2==0)light else dark;canvas.drawRect(col*s,r*s,col*s+s,r*s+s,p)};p.textAlign=Paint.Align.CENTER;p.typeface=Typeface.create("serif",Typeface.NORMAL);p.textSize=s*.68f;val pieces=arrayOf("♜♞♝♛♚♝♞♜","♟♟♟♟♟♟♟♟","","","","","♙♙♙♙♙♙♙♙","♖♘♗♕♔♗♘♖");for(r in 0..7)for(col in 0..7){val q=pieces[r].getOrNull(col)?:' ';if(q!=' '){p.color=if(r<2)Color.rgb(25,35,30) else Color.rgb(255,253,242);p.setShadowLayer(2f,0f,2f,Color.BLACK);canvas.drawText(q.toString(),col*s+s/2,r*s+s*.73f,p);p.clearShadowLayer()}}}
 override fun onTouchEvent(e:android.view.MotionEvent):Boolean{if(e.action==1){moves++;moveLog.add("move$moves");invalidate()} ;return true};fun reset(){moves=0;moveLog.clear();fen="startpos";invalidate()};fun moveCount()=moves;fun pgn()=moveLog.joinToString(" ");fun restore(savedFen:String?,count:Int,pgn:String){fen=savedFen?:"startpos";moves=count;moveLog=if(pgn.isBlank())mutableListOf() else pgn.split(" ").toMutableList();invalidate()}
}
