package com.moon.aiphone

import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

class UserProfileActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            isVerticalScrollBarEnabled = true
            webViewClient = WebViewClient()
            addJavascriptInterface(ProfileBridge(), "ProfileBridge")
        }
        setContentView(webView)
        webView.loadDataWithBaseURL(
            null,
            getProfileHtml(),
            "text/html",
            "UTF-8",
            null
        )
    }

    private fun loadProfileData(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            DatabaseHelper(this).readableDatabase
                .rawQuery("SELECT fieldKey, fieldValue FROM UserProfile", null)
                .use { c ->
                    while (c.moveToNext())
                        map[c.getString(0)] = c.getString(1) ?: ""
                }
        } catch (_: Exception) {}
        return map
    }

    inner class ProfileBridge {
        @JavascriptInterface
        fun saveField(key: String, value: String) {
            try {
                val db = DatabaseHelper(this@UserProfileActivity).writableDatabase
                val cv = ContentValues().apply {
                    put("fieldKey", key)
                    put("fieldValue", value)
                }
                val rows = db.update(
                    "UserProfile",
                    cv,
                    "fieldKey=?",
                    arrayOf(key)
                )

                if (rows <= 0) {
                    db.insert("UserProfile", null, cv)
                }
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun loadAll(): String {
            return try {
                val map = loadProfileData()
                val obj = JSONObject()
                map.forEach { (k, v) ->
                    obj.put(k, v)
                }
                obj.toString()
            } catch (e: Exception) {
                android.util.Log.e("UserProfile", e.stackTraceToString())
                "{}"
            }
        }

        @JavascriptInterface
        fun finish() {
            runOnUiThread { this@UserProfileActivity.finish() }
        }

        @JavascriptInterface
        fun showToast(msg: String) {
            runOnUiThread {
                Toast.makeText(this@UserProfileActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getProfileHtml(): String = """
<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
<title>我的档案</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  html, body {
    min-height: 100%;
    overflow-y: auto;
    -webkit-overflow-scrolling: touch;
  }

  body {
    background: #2c1a0e;
    background-image:
      radial-gradient(ellipse at 20% 20%, #3d2510 0%, transparent 50%),
      radial-gradient(ellipse at 80% 80%, #1a0f05 0%, transparent 50%);
    min-height: 100vh;
    font-family: 'Georgia', serif;
    padding: 16px;
    padding-bottom: 40px;
    touch-action: pan-y;
  }

  .parchment {
    background: linear-gradient(135deg, #f4e4c1 0%, #e8d5a3 30%, #f0deb8 60%, #e5cfa0 100%);
    border-radius: 4px;
    padding: 24px 20px;
    box-shadow:
      inset 0 0 40px rgba(139,90,43,0.3),
      0 4px 20px rgba(0,0,0,0.6),
      0 0 0 1px rgba(139,90,43,0.4);
    position: relative;
    overflow: visible;
  }

  .parchment::before {
    content: '';
    position: absolute;
    top: 0; left: 0; right: 0; bottom: 0;
    background-image:
      repeating-linear-gradient(0deg, transparent, transparent 28px, rgba(139,90,43,0.08) 28px, rgba(139,90,43,0.08) 29px);
    pointer-events: none;
  }

  .parchment::after {
    content: '';
    position: absolute;
    top: 8px; left: 8px; right: 8px; bottom: 8px;
    border: 1px solid rgba(139,90,43,0.25);
    border-radius: 2px;
    pointer-events: none;
  }

  .header {
    text-align: center;
    margin-bottom: 24px;
    padding-bottom: 16px;
    border-bottom: 2px solid rgba(139,90,43,0.4);
    position: relative;
  }

  .header-deco {
    color: #8b5a2b;
    font-size: 18px;
    letter-spacing: 8px;
    margin-bottom: 4px;
  }

  .header h1 {
    font-size: 22px;
    color: #3d1f00;
    letter-spacing: 4px;
    text-shadow: 1px 1px 2px rgba(255,255,255,0.5);
  }

  .header-sub {
    font-size: 11px;
    color: #8b6540;
    letter-spacing: 2px;
    margin-top: 4px;
    font-style: italic;
  }

  .section {
    margin-bottom: 20px;
    position: relative;
  }

  .section-title {
    font-size: 13px;
    color: #5c2e00;
    letter-spacing: 3px;
    text-transform: uppercase;
    margin-bottom: 10px;
    padding-left: 12px;
    border-left: 3px solid #8b5a2b;
    display: flex;
    align-items: center;
    gap: 6px;
  }

  .field-row {
    display: flex;
    align-items: center;
    margin-bottom: 8px;
    gap: 8px;
  }

  .field-label {
    font-size: 12px;
    color: #6b3a1f;
    width: 72px;
    flex-shrink: 0;
    font-style: italic;
  }

  input[type="text"], textarea {
    flex: 1;
    background: rgba(255,255,255,0.25);
    border: none;
    border-bottom: 1px solid rgba(139,90,43,0.4);
    padding: 4px 6px;
    font-family: 'Georgia', serif;
    font-size: 13px;
    color: #2c1000;
    outline: none;
    border-radius: 0;
  }

  input[type="text"]:focus, textarea:focus {
    background: rgba(255,255,255,0.45);
    border-bottom-color: #8b5a2b;
  }

  textarea {
    resize: none;
    width: 100%;
    line-height: 1.6;
  }

  .tags-container {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin-top: 4px;
  }

  .tag {
    background: rgba(139,90,43,0.15);
    border: 1px solid rgba(139,90,43,0.35);
    color: #5c2e00;
    padding: 3px 10px;
    border-radius: 20px;
    font-size: 12px;
    cursor: pointer;
  }

  .tag.selected {
    background: #8b5a2b;
    color: #f4e4c1;
    border-color: #5c2e00;
  }

  .event-list { width: 100%; }

  .event-item {
    display: flex;
    align-items: center;
    gap: 6px;
    margin-bottom: 6px;
  }

  .event-bullet {
    color: #8b5a2b;
    font-size: 16px;
    flex-shrink: 0;
  }

  .divider {
    text-align: center;
    color: rgba(139,90,43,0.5);
    letter-spacing: 6px;
    margin: 16px 0;
    font-size: 14px;
  }

  .btn-save {
    width: 100%;
    padding: 14px;
    background: linear-gradient(135deg, #5c2e00, #8b5a2b);
    color: #f4e4c1;
    border: none;
    border-radius: 4px;
    font-family: 'Georgia', serif;
    font-size: 15px;
    letter-spacing: 3px;
    cursor: pointer;
    margin-top: 8px;
    box-shadow: 0 2px 8px rgba(0,0,0,0.4);
  }

  .btn-back {
    width: 100%;
    padding: 10px;
    background: transparent;
    color: #8b5a2b;
    border: 1px solid rgba(139,90,43,0.4);
    border-radius: 4px;
    font-family: 'Georgia', serif;
    font-size: 13px;
    letter-spacing: 2px;
    cursor: pointer;
    margin-top: 8px;
  }

  .seal {
    text-align: center;
    margin-top: 16px;
    color: rgba(139,90,43,0.3);
    font-size: 28px;
    letter-spacing: 4px;
  }
</style>
</head>
<body>
<div class="parchment">

  <div class="header">
    <div class="header-deco">⊱ ❧ ⊰</div>
    <h1>人物档案</h1>
    <div class="header-sub">PERSONAL DOSSIER</div>
  </div>

  <!-- 基础信息 -->
  <div class="section">
    <div class="section-title">📋 基础信息</div>
    <div class="field-row">
      <span class="field-label">姓名</span>
      <input type="text" id="f_name" placeholder="你的名字或昵称">
    </div>
   <div class="field-row">
  <span class="field-label">年龄</span>
  <input type="text" id="f_age" placeholder="岁数" style="max-width:120px">
  <span class="field-label" style="width:48px;text-align:right">生日</span>
  <input type="text" id="f_birthday" placeholder="如 03/15">
</div>
<div class="field-row">
  <span class="field-label">性别</span>
  <input type="text" id="f_gender" placeholder="性别认同" style="max-width:120px">
  <span class="field-label" style="width:48px;text-align:right">MBTI</span>
  <input type="text" id="f_mbti" placeholder="如 INFP">
</div>

  <div class="divider">✦ · · · ✦</div>

  <!-- 外形特征 -->
  <div class="section">
    <div class="section-title">🪞 外形特征</div>
    <div class="field-row">
      <span class="field-label">身高/体重</span>
      <input type="text" id="f_height" placeholder="如 163cm / 48kg">
    </div>
    <div class="field-row">
      <span class="field-label">发色发型</span>
      <input type="text" id="f_hair" placeholder="如 黑色长直发">
    </div>
    <div class="field-row">
      <span class="field-label">眼睛</span>
      <input type="text" id="f_eyes" placeholder="如 杏眼，深棕色">
    </div>
    <div class="field-row">
      <span class="field-label">肤色</span>
      <input type="text" id="f_skin" placeholder="如 白皙偏冷">
    </div>
    <div class="field-row">
      <span class="field-label">标志特征</span>
      <input type="text" id="f_mark" placeholder="如 左手腕有一道淡疤">
    </div>
  </div>

  <div class="divider">✦ · · · ✦</div>

  <!-- 性格特征 -->
  <div class="section">
    <div class="section-title">🌿 性格特征</div>
    <div class="field-label" style="margin-bottom:6px">性格标签（点击选择）</div>
    <div class="tags-container" id="personalityTags">
      <span class="tag" onclick="toggleTag(this)">温柔</span>
      <span class="tag" onclick="toggleTag(this)">冷淡</span>
      <span class="tag" onclick="toggleTag(this)">活泼</span>
      <span class="tag" onclick="toggleTag(this)">内敛</span>
      <span class="tag" onclick="toggleTag(this)">敏感</span>
      <span class="tag" onclick="toggleTag(this)">理性</span>
      <span class="tag" onclick="toggleTag(this)">感性</span>
      <span class="tag" onclick="toggleTag(this)">独立</span>
      <span class="tag" onclick="toggleTag(this)">依赖</span>
      <span class="tag" onclick="toggleTag(this)">神经质</span>
      <span class="tag" onclick="toggleTag(this)">腹黑</span>
      <span class="tag" onclick="toggleTag(this)">天然呆</span>
    </div>
    <div class="field-row" style="margin-top:10px">
      <span class="field-label">喜欢</span>
      <input type="text" id="f_likes" placeholder="如 下雨天、猫、独处">
    </div>
    <div class="field-row">
      <span class="field-label">讨厌</span>
      <input type="text" id="f_dislikes" placeholder="如 嘈杂、被催促">
    </div>
    <div class="field-row">
      <span class="field-label">口头禅</span>
      <input type="text" id="f_catchphrase" placeholder="如 随便吧、没事的">
    </div>
  </div>

  <div class="divider">✦ · · · ✦</div>

  <!-- 喜好 -->
  <div class="section">
    <div class="section-title">💛 喜好</div>
    <div class="field-row">
      <span class="field-label">喜欢的色</span>
      <input type="text" id="f_color" placeholder="如 烟蓝色、奶白色">
    </div>
    <div class="field-row">
      <span class="field-label">音乐</span>
      <input type="text" id="f_music" placeholder="如 独立民谣、City Pop">
    </div>
    <div class="field-row">
      <span class="field-label">食物</span>
      <input type="text" id="f_food" placeholder="如 拿铁、抹茶、冷面">
    </div>
    <div class="field-row">
      <span class="field-label">喜欢的季节</span>
      <input type="text" id="f_season" placeholder="如 深秋、梅雨季">
    </div>
  </div>

  <div class="divider">✦ · · · ✦</div>

  <!-- 人生关键事件 -->
  <div class="section">
    <div class="section-title">📖 人生关键事件</div>
    <div class="event-list" id="eventList">
      <div class="event-item">
        <span class="event-bullet">◆</span>
        <input type="text" class="event-input" placeholder="一件塑造了你的事…">
      </div>
      <div class="event-item">
        <span class="event-bullet">◆</span>
        <input type="text" class="event-input" placeholder="一件你无法忘记的事…">
      </div>
      <div class="event-item">
        <span class="event-bullet">◆</span>
        <input type="text" class="event-input" placeholder="一件改变了你的事…">
      </div>
    </div>
  </div>

  <div class="divider">✦ · · · ✦</div>

  <!-- 隐藏面 -->
  <div class="section">
    <div class="section-title">🔒 隐藏面</div>
    <textarea id="f_secret" rows="3"
      placeholder="不为人知的秘密、内心深处的脆弱或执念…角色能感知但不会直接说出"></textarea>
  </div>

  <button class="btn-save" onclick="saveAll()">封存档案 ✦</button>
  <button class="btn-back" onclick="ProfileBridge.finish()">← 返回</button>

  <div class="seal">⊱ ✦ ⊰</div>

</div>

<script>
// 加载已有数据
window.onload = function() {
  try {
    var raw = ProfileBridge.loadAll();
    var data = JSON.parse(raw);

    var fields = ['name','age','birthday','gender','mbti','height','hair',
                  'eyes','skin','mark','likes','dislikes','catchphrase',
                  'color','music','food','season','secret'];
    fields.forEach(function(k) {
      var el = document.getElementById('f_' + k);
      if (el && data[k]) el.value = data[k];
    });

    // 恢复性格标签
    if (data['personality_tags']) {
      var selected = data['personality_tags'].split(',');
      document.querySelectorAll('#personalityTags .tag').forEach(function(tag) {
        if (selected.indexOf(tag.textContent) !== -1) tag.classList.add('selected');
      });
    }

    // 恢复事件
    if (data['events']) {
      var events = data['events'].split('||');
      var inputs = document.querySelectorAll('.event-input');
      events.forEach(function(ev, i) {
        if (inputs[i]) inputs[i].value = ev;
      });
    }
  } catch(e) {}
};

function toggleTag(el) {
  el.classList.toggle('selected');
}

function saveAll() {
  var fields = ['name','age','birthday','gender','mbti','height','hair',
                'eyes','skin','mark','likes','dislikes','catchphrase',
                'color','music','food','season','secret'];
  fields.forEach(function(k) {
    var el = document.getElementById('f_' + k);
    if (el) ProfileBridge.saveField(k, el.value);
  });

  // 保存性格标签
  var selected = [];
  document.querySelectorAll('#personalityTags .tag.selected').forEach(function(t) {
    selected.push(t.textContent);
  });
  ProfileBridge.saveField('personality_tags', selected.join(','));

  // 保存事件
  var events = [];
  document.querySelectorAll('.event-input').forEach(function(inp) {
    if (inp.value.trim()) events.push(inp.value.trim());
  });
  ProfileBridge.saveField('events', events.join('||'));

  ProfileBridge.showToast('档案已封存 ✦');
}
</script>
</body>
</html>
    """.trimIndent()
}
