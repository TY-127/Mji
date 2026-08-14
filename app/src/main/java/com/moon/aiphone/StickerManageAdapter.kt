package com.moon.aiphone

import android.graphics.Color
import android.view.Gravity
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*

class StickerManageAdapter(
    private val list: MutableList<Sticker>,
    private val onDelete: (Sticker, Int) -> Unit
) : RecyclerView.Adapter<StickerManageAdapter.VH>() {

    private fun dp(ctx: android.content.Context, n: Int) =
        (n * ctx.resources.displayMetrics.density).toInt()

    inner class VH(val root: LinearLayout) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(ctx, 1) }
        }
        return VH(row)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val sticker = list[position]
        val ctx = holder.root.context
        holder.root.removeAllViews()

        val ivThumb = android.widget.ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 50), dp(ctx, 50)).also {
                it.marginEnd = dp(ctx, 12)
            }
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#EEEEEE"))
                cornerRadius = dp(ctx, 6).toFloat()
            }
        }
        if (sticker.url.isBlank()) {
            return
        }
        ivThumb.tag = sticker.url

        GlobalScope.launch(Dispatchers.IO) {
            try {
                val bitmap = java.net.URL(sticker.url)
                    .openStream()
                    .use { input ->
                        android.graphics.BitmapFactory.decodeStream(input)
                    }

                withContext(Dispatchers.Main) {
                    if (ivThumb.tag == sticker.url) {
                        ivThumb.setImageBitmap(bitmap)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("StickerManageAdapter", e.stackTraceToString())
            }
        }

        val tvInfo = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvName = TextView(ctx).apply {
            text = sticker.name
            textSize = 15f
            setTextColor(Color.parseColor("#111111"))
        }
        val tvUrl = TextView(ctx).apply {
            text = sticker.url
            textSize = 11f
            setTextColor(Color.parseColor("#AAAAAA"))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        tvInfo.addView(tvName)
        tvInfo.addView(tvUrl)

        val btnDelete = TextView(ctx).apply {
            text = "删除"
            textSize = 13f
            setTextColor(Color.parseColor("#FF3B30"))
            setPadding(dp(ctx, 8), 0, 0, 0)
            setOnClickListener {
                val pos = holder.adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onDelete(sticker, pos)
                }
            }
        }

        holder.root.addView(ivThumb)
        holder.root.addView(tvInfo)
        holder.root.addView(btnDelete)
    }

    override fun getItemCount() = list.size
}
