package com.deepshawn.antichess

import android.app.*;import android.os.*;import android.graphics.*;import android.view.*;import android.widget.*;import android.content.*;import kotlin.math.min;import kotlinx.coroutines.*

class MainActivity:Activity(){
 private lateinit var board:ChessBoardView; private lateinit var engine:EngineController; private lateinit var status:TextView; private var level=LEVELS[2]
 override fun onCreate(b:Bundle?){super.onCreate(b);window.statusBarColor=Color.rgb(13,17,23);val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(20,18,20,14);setBackgroundColor(Color.rgb(13,17,23))}
  val title=TextView(this).apply{text="♞  谱着 · Chess Lab";setTextColor(Color.WHITE);textSize=23f;setPadding(0,0,0,15)};root.addView(title)
  status=TextView(this).apply{text="Stockfish 19 · 正在初始化";setTextColor(Color.rgb(118,224,181));textSize=13f};root.addView(status)
  board=ChessBoardView(this);root.addView(board,LinearLayout.LayoutParams(-1,0,1f));
  val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}; val label=TextView(this).apply{text="难度";setTextColor(Color.LTGRAY);textSize=14f};row.addView(label)
  val spinner=Spinner(this);spinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,LEVELS.map{"${it.name} · ${it.elo} 分"});spinner.setSelection(2);spinner.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onNothingSelected(p:AdapterView<*>?){};override fun onItemSelected(p:AdapterView<*>?,v:View?,pos:Int,id:Long){level=LEVELS[pos];if(::engine.isInitialized)engine.configure(level);status.text="Stockfish 19 · ${level.name} · ${level.elo} Elo"}};row.addView(spinner,LinearLayout.LayoutParams(0,60,1f));val newBtn=Button(this).apply{text="新局";setOnClickListener{board.reset()}};row.addView(newBtn);root.addView(row);setContentView(root)
  engine=EngineController(this);CoroutineScope(Dispatchers.Main).launch{if(engine.start())status.text="Stockfish 19 · 本地 UCI · ${level.elo} Elo" else status.text="未找到本地引擎 · 请放入 assets/stockfish/stockfish"}
 }
 override fun onDestroy(){engine.stop();super.onDestroy()}
}

class ChessBoardView(c:Context):View(c){private val p=Paint(1);private val light=Color.rgb(220,232,220);private val dark=Color.rgb(93,143,118);private var moves=0;init{setBackgroundColor(Color.rgb(13,17,23))}
 override fun onDraw(canvas:Canvas){super.onDraw(canvas);val s=min(width,height).toFloat()/8;for(r in 0..7)for(col in 0..7){p.color=if((r+col)%2==0)light else dark;canvas.drawRect(col*s,r*s,col*s+s,r*s+s,p)};p.textAlign=Paint.Align.CENTER;p.typeface=Typeface.create("serif",Typeface.NORMAL);p.textSize=s*.68f;val pieces=arrayOf("♜♞♝♛♚♝♞♜","♟♟♟♟♟♟♟♟","","","","","♙♙♙♙♙♙♙♙","♖♘♗♕♔♗♘♖");for(r in 0..7)for(col in 0..7){val q=pieces[r].getOrNull(col)?:' ';if(q!=' '){p.color=if(r<2)Color.rgb(25,35,30) else Color.rgb(255,253,242);p.setShadowLayer(2f,0f,2f,Color.BLACK);canvas.drawText(q.toString(),col*s+s/2,r*s+s*.73f,p);p.clearShadowLayer()}}}
 override fun onTouchEvent(e:android.view.MotionEvent):Boolean{if(e.action==1){moves++;invalidate()} ;return true};fun reset(){moves=0;invalidate()}
}
