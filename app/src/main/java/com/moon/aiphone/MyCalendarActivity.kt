package com.moon.aiphone

import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Calendar

data class MyEvent(val id: Int, val dateStr: String, val desc: String)

class MyCalendarActivity : AppCompatActivity() {
    private val eventList = mutableListOf<MyEvent>()
    private lateinit var adapter: MyEventAdapter
    private var selectedDateStr = ""

    // ⚡ 控制时间齿轮的核心指针
    private var currentMonth = Calendar.getInstance()
    private lateinit var dbHelper: DatabaseHelper
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_my_calendar)
        supportActionBar?.hide()
        dbHelper = DatabaseHelper(this)
        try {
            val db = DatabaseHelper(this).writableDatabase
            db.execSQL("CREATE TABLE IF NOT EXISTS MyEvents (id INTEGER PRIMARY KEY AUTOINCREMENT, dateStr TEXT, eventDesc TEXT)")
        } catch (e: Exception) {}

        val tvDateTitle = findViewById<TextView>(R.id.tvSelectedDateTitle)
        val btnAdd = findViewById<TextView>(R.id.btnAddMyEvent)
        val rv = findViewById<RecyclerView>(R.id.rvMyEvents)
        val btnRoutine = findViewById<LinearLayout>(R.id.btnSetRoutine)
        val tvRoutine = findViewById<TextView>(R.id.tvMyRoutine)

        // 锁定今天
        val cal = Calendar.getInstance()
        selectedDateStr = String.format("%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        tvDateTitle.text = "📌 $selectedDateStr 行程"

        rv.layoutManager = LinearLayoutManager(this)
        adapter = MyEventAdapter(eventList) { loadEvents(); renderCalendar() }
        rv.adapter = adapter

        // ⚡ 月份切换开关
        findViewById<TextView>(R.id.btnPrevMonth).setOnClickListener {
            currentMonth.add(Calendar.MONTH, -1)
            renderCalendar()
        }
        findViewById<TextView>(R.id.btnNextMonth).setOnClickListener {
            currentMonth.add(Calendar.MONTH, 1)
            renderCalendar()
        }

        btnAdd.setOnClickListener {
            val input = EditText(this)
            input.hint = "例如：大姨妈第一天 / 专业课考试"
            AlertDialog.Builder(this)
                .setTitle("添加特殊事件")
                .setMessage("给 $selectedDateStr 安排点什么事？")
                .setView(input)
                .setPositiveButton("强行烙印") { _, _ ->
                    val text = input.text.toString().trim()
                    if (text.isNotEmpty()) {
                        try {
                            val db = DatabaseHelper(this).writableDatabase
                            val values = ContentValues().apply {
                                put("dateStr", selectedDateStr)
                                put("eventDesc", text)
                            }
                            db.insert("MyEvents", null, values)
                            Toast.makeText(this, "🎯 现实事件已注入防空洞！", Toast.LENGTH_SHORT).show()
                            loadEvents()
                            renderCalendar() // ⚡ 加完事件马上刷新红点雷达！
                        } catch (e: Exception) {}
                    }
                }.setNegativeButton("取消", null).show()
        }

        loadRoutine(tvRoutine)
        btnRoutine.setOnClickListener {
            val input = EditText(this)
            val sharedPref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
            input.setText(sharedPref.getString("myRoutineStr", ""))
            input.hint = "例如：09:00-18:00 苦逼上班"
            AlertDialog.Builder(this)
                .setTitle("设定常驻作息")
                .setMessage("工作日这个点，你雷打不动在干嘛？")
                .setView(input)
                .setPositiveButton("锁死作息") { _, _ ->
                    sharedPref.edit().putString("myRoutineStr", input.text.toString().trim()).apply()
                    loadRoutine(tvRoutine)
                    Toast.makeText(this, "💼 你的打工/上学时间已强制广播给所有AI！", Toast.LENGTH_SHORT).show()
                }.setNegativeButton("取消", null).show()
        }

        loadEvents()
        renderCalendar() // 初始化第一眼月历
    }
    private fun loadRoutine(tv: TextView) {
        val sharedPref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val routine = sharedPref.getString("myRoutineStr", "") ?: ""
        if (routine.isNotEmpty()) {
            tv.text = routine
            tv.setTextColor(Color.parseColor("#4FC3F7"))
        } else {
            tv.text = "尚未设定 (AI不知道你几点上班)"
            tv.setTextColor(Color.parseColor("#999999"))
        }
    }

    private fun loadEvents() {
        eventList.clear()
        try {
            val db = dbHelper.readableDatabase
            val cur = db.rawQuery("SELECT id, eventDesc FROM MyEvents WHERE dateStr=?", arrayOf(selectedDateStr))
            while (cur.moveToNext()) {
                eventList.add(MyEvent(cur.getInt(0), selectedDateStr, cur.getString(1)))
            }
            cur.close()
            adapter.notifyDataSetChanged()
        } catch (e: Exception) {}
    }

    // ⚡ 极其变态的上帝级手搓渲染引擎！！！终极防空指针修复版！！！
    private fun renderCalendar() {
        try {
            val gl = findViewById<GridLayout>(R.id.glMyCalendarDays)
            gl.removeAllViews()

            val year = currentMonth.get(Calendar.YEAR)
            val month = currentMonth.get(Calendar.MONTH)
            findViewById<TextView>(R.id.tvCalendarTitle).text = "${year}年${month + 1}月"

            val eventDays = mutableSetOf<Int>()
            val db = DatabaseHelper(this).readableDatabase
            val monthStr = String.format("%04d-%02d", year, month + 1)
            val cur = db.rawQuery("SELECT dateStr FROM MyEvents WHERE dateStr LIKE ?", arrayOf("$monthStr%"))
            while (cur.moveToNext()) {
                val dStr = cur.getString(0)
                val dayPart = dStr.substringAfterLast("-").toIntOrNull()
                if (dayPart != null) eventDays.add(dayPart)
            }
            cur.close()

            val cal = currentMonth.clone() as Calendar
            cal.set(Calendar.DAY_OF_MONTH, 1)
            val firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1
            val maxDays = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

            for (i in 0 until firstDayOfWeek) {
                val emptyView = View(this)
                val params = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f))
                params.width = 0
                params.height = 120
                gl.addView(emptyView, params)
            }

            val todayCal = Calendar.getInstance()
            val todayYear = todayCal.get(Calendar.YEAR)
            val todayMonth = todayCal.get(Calendar.MONTH)
            val todayDay = todayCal.get(Calendar.DAY_OF_MONTH)

            for (day in 1..maxDays) {
                val dateString = String.format("%04d-%02d-%02d", year, month + 1, day)
                val isSelected = (dateString == selectedDateStr)
                val isToday = (year == todayYear && month == todayMonth && day == todayDay)
                val hasEvent = eventDays.contains(day)

                val cellLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(0, 15, 0, 15)
                    tag = dateString

                    if (isSelected) {
                        val bg = GradientDrawable()
                        bg.shape = GradientDrawable.OVAL
                        bg.setColor(android.graphics.Color.parseColor("#4FC3F7"))
                        background = bg
                    }

                    setOnClickListener {
                        selectedDateStr = dateString
                        // ⚡ 极其致命的修复：强行指明是大楼(Activity)在找牌子，而不是地砖！
                        this@MyCalendarActivity.findViewById<TextView>(R.id.tvSelectedDateTitle).text = "📌 $selectedDateStr 行程"
                        loadEvents()

                        val todayStr = String.format("%04d-%02d-%02d", todayYear, todayMonth + 1, todayDay)
                        for (i in 0 until gl.childCount) {
                            val cell = gl.getChildAt(i) as? LinearLayout ?: continue
                            val dStr = cell.tag as? String ?: continue
                            val tv = cell.getChildAt(0) as? TextView ?: continue

                            if (dStr == selectedDateStr) {
                                val bg2 = GradientDrawable()
                                bg2.shape = GradientDrawable.OVAL
                                bg2.setColor(android.graphics.Color.parseColor("#4FC3F7"))
                                cell.background = bg2
                                tv.setTextColor(android.graphics.Color.WHITE)
                                tv.setTypeface(null, android.graphics.Typeface.BOLD)
                            } else {
                                cell.background = null
                                if (dStr == todayStr) {
                                    tv.setTextColor(android.graphics.Color.parseColor("#4FC3F7"))
                                    tv.setTypeface(null, android.graphics.Typeface.BOLD)
                                } else {
                                    tv.setTextColor(android.graphics.Color.parseColor("#333333"))
                                    tv.setTypeface(null, android.graphics.Typeface.NORMAL)
                                }
                            }
                        }
                    }
                }

                val tvDay = TextView(this).apply {
                    text = day.toString()
                    textSize = 15f
                    gravity = Gravity.CENTER
                    if (isSelected) {
                        setTextColor(android.graphics.Color.WHITE)
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    } else if (isToday) {
                        setTextColor(android.graphics.Color.parseColor("#4FC3F7"))
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    } else {
                        setTextColor(android.graphics.Color.parseColor("#333333"))
                    }
                }

                val dotView = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(12, 12).apply {
                        topMargin = 6
                    }
                    if (hasEvent) {
                        val dotBg = GradientDrawable()
                        dotBg.shape = GradientDrawable.OVAL
                        dotBg.setColor(android.graphics.Color.parseColor("#FF5252"))
                        background = dotBg
                    } else {
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    }
                }

                cellLayout.addView(tvDay)
                cellLayout.addView(dotView)

                val params = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f))
                params.width = 0
                gl.addView(cellLayout, params)
            }
        } catch (e: Exception) {}
    }

    inner class MyEventAdapter(private val list: List<MyEvent>, private val onUpdate: () -> Unit) : RecyclerView.Adapter<MyEventAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvEvent: TextView = v.findViewById(android.R.id.text1)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(parent.context).apply {
                setPadding(30, 40, 30, 40)
                setTextColor(Color.parseColor("#333333"))
                textSize = 16f
                id = android.R.id.text1
            }
            return VH(tv)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            val evt = list[position]
            holder.tvEvent.text = "🔸 ${evt.desc}"
            holder.itemView.setOnLongClickListener {
                AlertDialog.Builder(holder.itemView.context)
                    .setTitle("⚠️ 赛博消除")
                    .setMessage("取消了")
                    .setPositiveButton("物理超度！") { _, _ ->
                        try {
                            val db = DatabaseHelper(holder.itemView.context).writableDatabase
                            db.delete("MyEvents", "id=?", arrayOf(evt.id.toString()))

                            loadEvents()
                            renderCalendar()
                            onUpdate()
                        } catch (e: Exception) {
                            android.util.Log.e(
                                "MyCalendar",
                                e.stackTraceToString()
                            )
                        }
                    }.setNegativeButton("留着", null).show()
                true
            }
        }
        override fun getItemCount() = list.size
    }
}