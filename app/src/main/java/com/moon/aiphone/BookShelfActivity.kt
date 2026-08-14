package com.moon.aiphone

import android.content.ContentValues
import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class BookShelfActivity : AppCompatActivity() {

    private lateinit var rvBooks: RecyclerView
    private val books = mutableListOf<BookItem>()

    data class BookItem(val id: Long, val title: String, val filePath: String,
                        val totalChunks: Int, val lastChunk: Int)

    private val pickTxt = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        Thread {
            try {
                val (title, path, chunks) = BookParser.importTxt(this, uri)
                val db = DatabaseHelper(this).writableDatabase
                val cv = ContentValues().apply {
                    put("title", title); put("filePath", path)
                    put("totalChunks", chunks); put("createdAt", System.currentTimeMillis())
                }
                db.insert("BookShelf", null, cv)
                runOnUiThread { loadBooks() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "导入失败：${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 简单布局，代码里直接建
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 80, 0, 0)
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(30, 20, 30, 20)
        }
        val tvTitle = TextView(this).apply {
            text = "📚 书架"
            textSize = 20f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnImport = Button(this).apply {
            text = "导入txt"
            setOnClickListener { pickTxt.launch("text/plain") }
        }
        toolbar.addView(tvTitle); toolbar.addView(btnImport)
        rvBooks = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@BookShelfActivity, 3)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        layout.addView(toolbar); layout.addView(rvBooks)
        setContentView(layout)
        loadBooks()
    }

    private fun loadBooks() {
        books.clear()

        try {
            val db = DatabaseHelper(this).readableDatabase
            db.rawQuery(
                "SELECT id, title, filePath, totalChunks, lastReadChunkIndex FROM BookShelf ORDER BY id DESC",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    books.add(
                        BookItem(
                            cursor.getLong(0),
                            cursor.getString(1) ?: "未知书名",
                            cursor.getString(2) ?: "",
                            cursor.getInt(3),
                            cursor.getInt(4)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "加载书架失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
        rvBooks.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = books.size
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
                object : RecyclerView.ViewHolder(TextView(this@BookShelfActivity).apply {
                    setPadding(20, 60, 20, 60); textSize = 14f; gravity = android.view.Gravity.CENTER
                    setBackgroundResource(android.R.color.white)
                    val margin = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        300).apply { setMargins(16,16,16,16) }
                    layoutParams = margin
                }) {}
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val book = books[position]
                (holder.itemView as TextView).apply {
                    text = "📖\n${book.title}\n共${book.totalChunks}章"
                    setOnClickListener { pickContactAndRead(book) }
                    setOnLongClickListener { deleteBook(book); true }
                }
            }
        }
    }

    private fun pickContactAndRead(book: BookItem) {
        val db = DatabaseHelper(this).readableDatabase
        val cursor = db.rawQuery(
            """
    SELECT realName, userId
    FROM Contacts
    WHERE userId IS NOT NULL
      AND TRIM(userId) <> ''
      AND id IN (
          SELECT MAX(id)
          FROM Contacts
          WHERE userId IS NOT NULL
            AND TRIM(userId) <> ''
          GROUP BY userId
      )
    ORDER BY id DESC
    """.trimIndent(),
            null
        )

        val names = mutableListOf<String>()
        val ids = mutableListOf<String>()

        cursor.use {
            while (it.moveToNext()) {
                names.add(it.getString(0) ?: "未知")
                ids.add(it.getString(1) ?: "")
            }
        }
        if (names.isEmpty()) { Toast.makeText(this, "还没有角色，请先添加联系人", Toast.LENGTH_SHORT).show(); return }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选择陪读角色")
            .setItems(names.toTypedArray()) { _, i ->
                startActivity(Intent(this, ReadingActivity::class.java).apply {
                    putExtra("bookId", book.id)
                    putExtra("bookTitle", book.title)
                    putExtra("filePath", book.filePath)
                    putExtra("totalChunks", book.totalChunks)
                    putExtra("lastChunk", book.lastChunk)
                    putExtra("contactId", ids[i])
                    putExtra("contactName", names[i])
                })
            }.show()
    }

    private fun deleteBook(book: BookItem) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("删除《${book.title}》？")
            .setPositiveButton("删除") { _, _ ->
                val db = DatabaseHelper(this).writableDatabase

                db.delete(
                    "BookComments",
                    "bookId=?",
                    arrayOf(book.id.toString())
                )

                db.delete(
                    "BookShelf",
                    "id=?",
                    arrayOf(book.id.toString())
                )

                try {
                    java.io.File(book.filePath).delete()
                } catch (_: Exception) {}

                loadBooks()
            }.setNegativeButton("取消", null).show()
    }
}