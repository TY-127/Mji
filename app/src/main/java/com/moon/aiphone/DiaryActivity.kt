package com.moon.aiphone

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

class DiaryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        val webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.addJavascriptInterface(DiaryBridge(), "Android")
        setContentView(webView)

        webView.webViewClient = object : WebViewClient() {}
        webView.loadDataWithBaseURL(null, getDiaryHomeHtml(), "text/html", "UTF-8", null)
    }

    inner class DiaryBridge {
        @android.webkit.JavascriptInterface
        fun getContacts(): String {
            return try {
                val arr = JSONArray()
                val helper = DatabaseHelper(this@DiaryActivity)

                helper.readableDatabase.rawQuery(
                    """
            SELECT userId, realName, IFNULL(avatarUri, '')
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
                ).use { cur ->
                    while (cur.moveToNext()) {
                        arr.put(JSONObject().apply {
                            put("id", cur.getString(0) ?: "")
                            put("name", cur.getString(1) ?: "未知")
                            put("avatar", cur.getString(2) ?: "")
                        })
                    }
                }

                helper.close()
                arr.toString()
            } catch (e: Exception) {
                android.util.Log.e("DiaryActivity", e.stackTraceToString())
                "[]"
            }
        }

        @android.webkit.JavascriptInterface
        fun openAiDiary(aiId: String, aiName: String) {
            runOnUiThread {
                val intent = Intent(this@DiaryActivity, AiDiaryActivity::class.java)
                intent.putExtra("AI_ID", aiId)
                intent.putExtra("AI_NAME", aiName)
                startActivity(intent)
            }
        }

        @android.webkit.JavascriptInterface
        fun openUserDiary() {
            runOnUiThread {
                startActivity(Intent(this@DiaryActivity, UserDiaryActivity::class.java))
            }
        }

        @android.webkit.JavascriptInterface
        fun goBack() {
            runOnUiThread { finish() }
        }
    }

    private fun getDiaryHomeHtml(): String = """
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
* { margin:0; padding:0; box-sizing:border-box; }
body {
  background: #f0ebe3;
  font-family: 'Georgia', serif;
  min-height: 100vh;
  padding-bottom: 40px;
}
.header {
  background: #e8e0d0;
  padding: 52px 20px 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #d4c9b8;
}
.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.back-btn {
  font-size: 22px;
  color: #7a6a55;
  cursor: pointer;
  padding: 4px 8px;
}
.header-title {
  font-size: 20px;
  color: #4a3f32;
  font-weight: normal;
  letter-spacing: 2px;
}
.add-btn {
  width: 36px;
  height: 36px;
  background: #7a6a55;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  font-size: 22px;
  cursor: pointer;
  box-shadow: 0 2px 8px rgba(122,106,85,0.3);
}
.subtitle {
  text-align: center;
  color: #9a8a78;
  font-size: 12px;
  letter-spacing: 3px;
  padding: 16px 0 8px;
  text-transform: uppercase;
}
.books-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  padding: 16px;
}
.book-card {
  background: #fff;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 2px 12px rgba(0,0,0,0.08);
  cursor: pointer;
  transition: transform 0.2s;
  position: relative;
}
.book-card:active { transform: scale(0.97); }
.book-spine {
  width: 8px;
  height: 100%;
  position: absolute;
  left: 0;
  top: 0;
  background: #c4a882;
}
.book-cover {
  padding: 20px 16px 16px 20px;
  min-height: 160px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #fdf8f0 0%, #f5ede0 100%);
}
.book-icon {
  font-size: 48px;
  margin-bottom: 10px;
  filter: drop-shadow(0 2px 4px rgba(0,0,0,0.1));
}
.book-name {
  font-size: 14px;
  color: #4a3f32;
  text-align: center;
  letter-spacing: 1px;
}
.book-label {
  font-size: 11px;
  color: #9a8a78;
  text-align: center;
  margin-top: 4px;
  letter-spacing: 1px;
}
.book-footer {
  padding: 10px 16px;
  background: #f5ede0;
  border-top: 1px solid #e8ddd0;
  font-size: 11px;
  color: #9a8a78;
  text-align: center;
}
.empty {
  text-align: center;
  color: #9a8a78;
  padding: 60px 20px;
  font-size: 14px;
  line-height: 2;
}
</style>
</head>
<body>
<div class="header">
  <div class="header-left">
    <div class="back-btn" onclick="Android.goBack()">←</div>
    <div class="header-title">交换日记</div>
  </div>
  <div class="add-btn" onclick="Android.openUserDiary()">+</div>
</div>
<div class="subtitle">— Exchange Diary —</div>
<div class="books-grid" id="booksGrid">
  <div class="empty">加载中...</div>
</div>
<script>
const spineColors = ['#c4a882','#a8b4c0','#b8c4a8','#c0a8b8','#c4b8a8','#a8c0bc'];

function openDiaryFromCard(el) {
  Android.openAiDiary(
    decodeURIComponent(el.getAttribute('data-id') || ''),
    decodeURIComponent(el.getAttribute('data-name') || '')
  );
}

function init() {
  try {
    const contacts = JSON.parse(Android.getContacts());
    const grid = document.getElementById('booksGrid');
    if (!contacts.length) {
      grid.innerHTML = '<div class="empty">还没有角色<br>先去添加联系人吧</div>';
      return;
    }
    grid.innerHTML = contacts.map(function(c, i) {
      var color = spineColors[i % spineColors.length];
      return '<div class="book-card" data-id="' + encodeURIComponent(c.id) + '" data-name="' + encodeURIComponent(c.name) + '" onclick="openDiaryFromCard(this)">'
        + '<div class="book-spine" style="background:' + color + '"></div>'
        + '<div class="book-cover">'
        + '<div class="book-icon">📔</div>'
        + '<div class="book-name">' + c.name + '</div>'
        + '<div class="book-label">的日记</div>'
        + '</div>'
        + '<div class="book-footer">点击翻阅</div>'
        + '</div>';
    }).join('');
  } catch(e) {
    document.getElementById('booksGrid').innerHTML = '<div class="empty">加载失败</div>';
  }
}
init();
</script>
</body>
</html>
    """.trimIndent()
}