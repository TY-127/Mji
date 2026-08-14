package com.moon.aiphone

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import androidx.lifecycle.lifecycleScope
class StickerImportActivity : AppCompatActivity() {

    private val stickerList = mutableListOf<Sticker>()
    private lateinit var rv: RecyclerView
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }

        val topBar = RelativeLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50))
            setBackgroundColor(Color.WHITE)
            elevation = 4f
        }
        val tvTitle = TextView(this).apply {
            text = "管理表情包"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.BLACK)
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).also { it.addRule(RelativeLayout.CENTER_IN_PARENT) }
        }
        val btnBack = TextView(this).apply {
            text = "‹"
            textSize = 32f
            setPadding(dp(16), 0, dp(16), dp(4))
            setTextColor(Color.BLACK)
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            ).also {
                it.addRule(RelativeLayout.ALIGN_PARENT_START)
                it.addRule(RelativeLayout.CENTER_VERTICAL)
            }
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { finish() }
        }
        topBar.addView(tvTitle)
        topBar.addView(btnBack)
        root.addView(topBar)

        rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@StickerImportActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(rv)
        setContentView(root)
        loadStickers()
    }

    private fun loadStickers() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = DatabaseHelper(this@StickerImportActivity).readableDatabase
                val cursor = db.rawQuery("SELECT id, packId, name, url FROM Stickers ORDER BY createdAt DESC", null)
                val list = mutableListOf<Sticker>()
                while (cursor.moveToNext()) {
                    list.add(Sticker(
                        id = cursor.getLong(0),
                        packId = cursor.getLong(1),
                        name = cursor.getString(2) ?: "",
                        url = cursor.getString(3) ?: ""
                    ))
                }
                cursor.close()
                withContext(Dispatchers.Main) {
                    stickerList.clear()
                    stickerList.addAll(list)
                    rv.adapter = StickerManageAdapter(stickerList) { sticker, position ->
                        AlertDialog.Builder(this@StickerImportActivity)
                            .setTitle("删除表情包")
                            .setMessage("确定删除「${sticker.name}」吗？")
                            .setPositiveButton("删除") { _, _ ->
                                try {
                                    DatabaseHelper(this@StickerImportActivity).writableDatabase
                                        .delete("Stickers", "id=?", arrayOf(sticker.id.toString()))
                                    val index = stickerList.indexOfFirst { it.id == sticker.id }
                                    if (index >= 0) {
                                        stickerList.removeAt(index)
                                        rv.adapter?.notifyItemRemoved(index)
                                    }
                                } catch (e: Exception) {}
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("StickerImport", e.stackTraceToString())
            }
        }
    }
}
