package com.moon.aiphone

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*

data class Sticker(
    val id: Long,
    val packId: Long,
    val name: String,
    val url: String
)

class StickerPanelHelper(
    private val context: Context,
    private val onStickerSelected: (Sticker) -> Unit
) {
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()

    fun show() {
        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        // 顶部把手
        val handle = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).also {
                it.gravity = Gravity.CENTER_HORIZONTAL
                it.topMargin = dp(10)
                it.bottomMargin = dp(10)
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#DDDDDD"))
                cornerRadius = dp(4).toFloat()
            }
        }
        root.addView(handle)

        // 搜索栏 + 导入 + 管理
        val searchRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(4), dp(12), dp(8))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val etSearch = EditText(context).apply {
            hint = "🔍 搜寻贴图"
            textSize = 14f
            setHintTextColor(Color.parseColor("#AAAAAA"))
            setTextColor(Color.BLACK)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#F2F2F2"))
                cornerRadius = dp(20).toFloat()
            }
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnImport = TextView(context).apply {
            text = "导入"
            textSize = 14f
            setTextColor(Color.parseColor("#007AFF"))
            setPadding(dp(12), 0, dp(8), 0)
            setOnClickListener {
                dialog.dismiss()
                val intent = Intent(context, StickerManageActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
        val btnManage = TextView(context).apply {
            text = "管理"
            textSize = 14f
            setTextColor(Color.parseColor("#333333"))
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener {
                dialog.dismiss()
                val intent = Intent(context, StickerImportActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
        searchRow.addView(etSearch)
        searchRow.addView(btnImport)
        searchRow.addView(btnManage)
        root.addView(searchRow)

        // 分割线
        val dividerView = android.widget.LinearLayout(context)
        dividerView.setBackgroundColor(Color.parseColor("#EEEEEE"))
        dividerView.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        root.addView(dividerView)

        // 表情包网格
        val recyclerView = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 3)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(360)
            )
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        root.addView(recyclerView)

        // 加载数据
        val allStickers = mutableListOf<Sticker>()
        val adapter = StickerGridAdapter(allStickers) { sticker ->
            dialog.dismiss()
            onStickerSelected(sticker)
        }
        recyclerView.adapter = adapter

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = DatabaseHelper(context).readableDatabase
                val cursor = db.rawQuery(
                    "SELECT id, packId, name, url FROM Stickers ORDER BY createdAt DESC",
                    null
                )
                android.util.Log.d("STICKER", "查到数量: ${cursor.count}")
                while (cursor.moveToNext()) {
                    allStickers.add(Sticker(
                        id = cursor.getLong(0),
                        packId = cursor.getLong(1),
                        name = cursor.getString(2) ?: "",
                        url = cursor.getString(3) ?: ""
                    ))
                }
                cursor.close()
                withContext(Dispatchers.Main) {
                    adapter.filter(etSearch.text.toString().trim())
                }
            } catch (e: Exception) {}
        }

        // 搜索过滤
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val keyword = s.toString().trim()
                adapter.filter(keyword)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        dialog.setContentView(root)
        val window = dialog.window
        window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        window?.setGravity(Gravity.BOTTOM)
        window?.setWindowAnimations(android.R.style.Animation_InputMethod)
        dialog.show()
    }
}

class StickerGridAdapter(
    private var fullList: MutableList<Sticker>,
    private val onClick: (Sticker) -> Unit
) : RecyclerView.Adapter<StickerGridAdapter.VH>() {

    private var displayList = fullList.toMutableList()

    fun filter(keyword: String) {
        displayList = if (keyword.isEmpty()) {
            fullList.toMutableList()
        } else {
            fullList.filter { it.name.contains(keyword, ignoreCase = true) }.toMutableList()
        }
        notifyDataSetChanged()
    }

    private fun dp(ctx: Context, n: Int) = (n * ctx.resources.displayMetrics.density).toInt()

    inner class VH(val root: LinearLayout) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                dp(ctx, 120)
            ).also { it.setMargins(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4)) }
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#F8F8F8"))
                cornerRadius = dp(ctx, 8).toFloat()
            }
        }
        return VH(card)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val sticker = displayList[position]
        val ctx = holder.root.context
        holder.root.removeAllViews()

        val ivSticker = android.widget.ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 80), dp(ctx, 80))
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#EEEEEE"))
                cornerRadius = dp(ctx, 6).toFloat()
            }
        }

        // 异步加载图片
        ivSticker.tag = sticker.url

        if (sticker.url.isNotBlank()) {
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    val bitmap = java.net.URL(sticker.url)
                        .openStream()
                        .use { input ->
                            android.graphics.BitmapFactory.decodeStream(input)
                        }

                    withContext(Dispatchers.Main) {
                        if (ivSticker.tag == sticker.url) {
                            ivSticker.setImageBitmap(bitmap)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("StickerGridAdapter", e.stackTraceToString())
                }
            }
        }

        val tvName = TextView(ctx).apply {
            text = sticker.name
            textSize = 11f
            setTextColor(Color.parseColor("#555555"))
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(ctx, 4), 0, 0)
        }

        holder.root.addView(ivSticker)
        holder.root.addView(tvName)
        holder.root.setOnClickListener { onClick(sticker) }
    }

    override fun getItemCount() = displayList.size
}
