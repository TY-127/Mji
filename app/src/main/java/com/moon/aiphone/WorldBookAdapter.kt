package com.moon.aiphone

import android.content.ContentValues
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView

data class WorldBookItem(val id: Int, val keyword: String, val content: String)

class WorldBookAdapter(
    private val itemList: List<WorldBookItem>,
    private val isAdminMode: Boolean = false,
    private val onDataChanged: () -> Unit = {}
) : RecyclerView.Adapter<WorldBookAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvKeyword: TextView = view.findViewById(R.id.tvKeyword)
        val tvContent: TextView = view.findViewById(R.id.tvContent)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_world_book, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = itemList[position]
        holder.tvKeyword.text = "关键词：${item.keyword}"
        holder.tvContent.text = "设定内容：${item.content}"

        // ===== 新增：长按弹出修改/删除选项 =====
        holder.itemView.setOnLongClickListener {
            val ctx = holder.itemView.context
            AlertDialog.Builder(ctx)
                .setTitle("对这条设定做什么？")
                .setItems(arrayOf("✏️ 修改", "🗑️ 删除")) { _, action ->
                    when (action) {
                        0 -> {
                            // 修改：预填原内容
                            val layout = LinearLayout(ctx).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(50, 40, 50, 10)
                            }
                            val etKeyword = EditText(ctx).apply {
                                hint = "触发关键词（可留空）"
                                // 去掉显示用的前缀标签，只保留原始关键词
                                val rawKw = item.keyword
                                    .replace(Regex("^【.+?】.+?｜关键词:"), "")
                                    .replace("无", "")
                                    .trim()
                                setText(rawKw)
                            }
                            val etContent = EditText(ctx).apply {
                                hint = "设定内容..."
                                minLines = 3
                                setText(item.content)
                            }
                            layout.addView(etKeyword)
                            layout.addView(etContent)
                            AlertDialog.Builder(ctx)
                                .setTitle("修改世界书设定")
                                .setView(layout)
                                .setPositiveButton("保存") { _, _ ->
                                    val newKw = etKeyword.text.toString().trim()
                                    val newCt = etContent.text.toString().trim()
                                    if (newCt.isEmpty()) {
                                        Toast.makeText(ctx, "内容不能为空", Toast.LENGTH_SHORT).show()
                                        return@setPositiveButton
                                    }
                                    try {
                                        val db = DatabaseHelper(ctx).writableDatabase
                                        val table = if (isAdminMode) "AdminWorldBook" else "UserWorldBook"
                                        val values = ContentValues().apply {
                                            put("keyword", newKw)
                                            put("content", newCt)
                                        }
                                        db.update(table, values, "id=?", arrayOf(item.id.toString()))
                                        Toast.makeText(ctx, "修改成功", Toast.LENGTH_SHORT).show()
                                        onDataChanged()
                                    } catch (e: Exception) {
                                        Toast.makeText(ctx, "修改失败：${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                                .setNegativeButton("取消", null)
                                .show()
                        }
                        1 -> {
                            // 删除：二次确认
                            AlertDialog.Builder(ctx)
                                .setTitle("确认删除")
                                .setMessage("删掉这条设定？不可恢复。")
                                .setPositiveButton("删除") { _, _ ->
                                    try {
                                        val db = DatabaseHelper(ctx).writableDatabase
                                        val table = if (isAdminMode) "AdminWorldBook" else "UserWorldBook"
                                        db.delete(table, "id=?", arrayOf(item.id.toString()))
                                        Toast.makeText(ctx, "已删除", Toast.LENGTH_SHORT).show()
                                        onDataChanged()
                                    } catch (e: Exception) {
                                        Toast.makeText(ctx, "删除失败：${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                                .setNegativeButton("取消", null)
                                .show()
                        }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }
        // ===== 新增结束 =====
    }

    override fun getItemCount() = itemList.size
}
