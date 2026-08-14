package com.moon.aiphone

import android.app.AlertDialog
import android.app.PendingIntent
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import coil.load
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

class PetHouseActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private lateinit var repo: PetHouseRepository
    private var selectedCharacter: PetCharacter? = null
    private var selectedType = ""
    private var currentCandidate: PetCandidate? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "宠物小屋"
        repo = PetHouseRepository(this)
        buildShell()
        scheduleBackgroundCare()
        val autoEvents = repo.autoCare()
        showHome()
        if (autoEvents.isNotEmpty()) Toast.makeText(this, autoEvents.joinToString("；"), Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized && currentCandidate == null) showHome()
    }

    private fun buildShell() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(255, 248, 242))
            isFillViewport = true
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(32))
        }
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun showHome() {
        currentCandidate = null
        clear()
        val pet = repo.activePet()?.let { repo.applyDecay(it) }
        header("宠物小屋")
        if (pet == null) showEmpty() else showPet(pet)
    }

    private fun showEmpty() {
        image(R.drawable.ic_pet_house, 210)
        titleText("这里还没有宠物")
        bodyText("和喜欢的角色一起，去遇见属于你们的小家伙吧。")
        addButton("去宠物店看看") { chooseCharacter(false) }
        addButton("去随意逛逛", secondary = true) { chooseCharacter(true) }
    }

    private fun showPet(pet: Pet) {
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        top.addView(smallButton("＋ 新增") { chooseWay() }, weightParams())
        top.addView(space(10))
        top.addView(smallButton("⇄ 切换") { choosePet() }, weightParams())
        root.addView(top)

        val portrait = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(Color.WHITE, 28f)
            clipToOutline = true
        }
        root.addView(portrait, LinearLayout.LayoutParams(-1, dp(280)).apply { topMargin = dp(18) })
        loadPetImage(portrait, pet)

        titleText(pet.name)
        bodyText("${pet.color}${pet.breed} · 与 ${pet.bondedCharacterName} 共同养育")
        repo.recentEvents(pet.id, 20)
            .firstOrNull { it.actor == pet.bondedCharacterName && it.action in setOf("feed", "clean", "groom", "play", "walk") }
            ?.let { care ->
                val time = java.text.SimpleDateFormat("MM月dd日 HH:mm", java.util.Locale.CHINA)
                    .format(java.util.Date(care.createdAt))
                bodyText("角色照料提醒 · $time\n${pet.bondedCharacterName}：${care.dialogue}", card = true)
            }
        bodyText("性格：${pet.personality}\n喜欢：${pet.likes}\n不喜欢：${pet.dislikes}", card = true)

        status("心情", pet.mood, 0xFFFF8EA1.toInt())
        status("饥饿", pet.hunger, 0xFFFFB45C.toInt())
        status("清洁", pet.cleanliness, 0xFF6FCFC2.toInt())
        val (coins, messages) = repo.wallet(pet)
        bodyText("今日共同钱包：$coins 枚  ·  今日与${pet.bondedCharacterName}对话 $messages 条\n每条对话获得 ${PetHouseRepository.COINS_PER_MESSAGE} 枚，投喂需 ${PetHouseRepository.FEED_COST} 枚。", card = true)

        val row1 = actionRow(
            "陪它玩" to "play", "梳毛" to "groom", "投喂" to "feed"
        ) { label, action -> interact(pet, label, action) }
        val row2 = actionRow(
            "铲屎/清洁" to "clean", "遛弯" to "walk"
        ) { label, action -> interact(pet, label, action) }
        root.addView(row1)
        root.addView(row2)
        showCareLog(pet)
    }

    private fun showCareLog(pet: Pet) {
        val events = repo.recentEvents(pet.id, 12)
        val heading = TextView(this).apply {
            text = "照料日志"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF3A2D2A.toInt())
            setPadding(0, dp(24), 0, dp(10))
        }
        root.addView(heading)
        if (events.isEmpty()) {
            bodyText("还没有照料记录。", card = true)
            return
        }
        val formatter = java.text.SimpleDateFormat("MM月dd日 HH:mm", java.util.Locale.CHINA)
        events.forEach { event ->
            val actionName = when (event.action) {
                "adopt" -> "领养回家"; "play" -> "陪它玩"; "groom" -> "梳毛"
                "feed" -> "投喂"; "clean" -> "铲屎/清洁"; "walk" -> "遛弯"; else -> event.action
            }
            val expense = if (event.cost > 0) " · 花费${event.cost}枚" else ""
            bodyText(
                "${formatter.format(java.util.Date(event.createdAt))}  $actionName$expense\n${event.actor}：${event.dialogue}",
                card = true
            )
        }
    }

    private fun chooseWay() {
        AlertDialog.Builder(this).setTitle("去哪里遇见新伙伴？")
            .setItems(arrayOf("去宠物店看看", "去随意逛逛")) { _, which -> chooseCharacter(which == 1) }
            .setNegativeButton("取消", null).show()
    }

    private fun chooseCharacter(wander: Boolean) {
        val chars = repo.characters()
        if (chars.isEmpty()) {
            AlertDialog.Builder(this).setTitle("还没有可同行的角色")
                .setMessage("请先在联系人中创建至少一个角色，再一起出发。")
                .setPositiveButton("知道了", null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("想和谁一起去？")
            .setItems(chars.map { it.name }.toTypedArray()) { _, which ->
                selectedCharacter = chars[which]
                if (wander) {
                    selectedType = listOf("猫", "狗", "鼠", "异宠").random()
                    generateCandidate(true)
                } else showTypePicker()
            }.setNegativeButton("取消", null).show()
    }

    private fun showTypePicker() {
        clear(); header("宠物店"); bodyText("${selectedCharacter?.name}陪你站在一排温暖的小屋前。")
        titleText("想先看看哪一种？")
        listOf("猫" to R.drawable.pet_chibi_cat, "狗" to R.drawable.pet_chibi_dog,
            "鼠" to R.drawable.pet_chibi_hamster, "异宠" to R.drawable.pet_chibi_exotic).forEach { (type, res) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10)); background = rounded(Color.WHITE, 20f)
                setOnClickListener { selectedType = type; generateCandidate(false) }
            }
            row.addView(ImageView(this).apply { setImageResource(res); scaleType = ImageView.ScaleType.CENTER_CROP }, LinearLayout.LayoutParams(dp(72), dp(72)))
            row.addView(TextView(this).apply { text = type; textSize = 18f; setTextColor(Color.DKGRAY); setPadding(dp(18), 0, 0, 0) }, weightParams())
            root.addView(row, LinearLayout.LayoutParams(-1, dp(92)).apply { bottomMargin = dp(10) })
        }
        addButton("返回小屋", secondary = true) { showHome() }
    }

    private fun generateCandidate(wander: Boolean) {
        clear(); header(if (wander) "随意逛逛" else "宠物店")
        image(resourceForType(selectedType), 190)
        titleText("正在遇见一只小家伙…")
        bodyText("AI 正在生成它固定的外形、性格、喜好，以及它对你们的第一反应。")
        val progress = ProgressBar(this)
        root.addView(progress, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12) })
        Thread {
            val candidate = requestCandidate(selectedType, selectedCharacter!!, wander)
            val generatedPath = try { ImageGenManager.generate(this, candidate.imagePrompt) } catch (_: Exception) { null }
            if (!generatedPath.isNullOrBlank()) candidate.imagePath = generatedPath
            runOnUiThread { currentCandidate = candidate; showCandidate(candidate, wander) }
        }.start()
    }

    private fun showCandidate(candidate: PetCandidate, wander: Boolean) {
        clear(); header(if (wander) "偶遇" else "宠物店 · ${candidate.type}")
        val portrait = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = rounded(Color.WHITE, 28f); clipToOutline = true }
        root.addView(portrait, LinearLayout.LayoutParams(-1, dp(300)))
        if (candidate.imagePath.isNotBlank() && File(candidate.imagePath).exists()) portrait.load(File(candidate.imagePath))
        else portrait.setImageResource(resourceForType(candidate.type))
        titleText("${candidate.color}${candidate.breed}")
        bodyText(candidate.appearance)
        bodyText("性格：${candidate.personality}\n喜欢：${candidate.likes}\n不喜欢：${candidate.dislikes}", card = true)
        bodyText("它的反应：${candidate.reaction}\n\n${selectedCharacter?.name}：${candidate.characterReaction}", card = true)
        addButton("就是它了") { askName(candidate) }
        addButton("看看这个品种的另一只", secondary = true) { generateCandidate(wander) }
        addButton(if (wander) "继续随意逛逛" else "退回宠物类型", secondary = true) {
            if (wander) {
                selectedType = listOf("猫", "狗", "鼠", "异宠").random(); generateCandidate(true)
            } else showTypePicker()
        }
    }

    private fun askName(candidate: PetCandidate) {
        val input = EditText(this).apply { hint = "给它取一个名字"; setSingleLine() }
        AlertDialog.Builder(this).setTitle("欢迎新成员")
            .setMessage("领养后会标记为“${selectedCharacter?.name} × 用户”共同养育。")
            .setView(input).setPositiveButton("确认领养", null).setNegativeButton("再想想", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val name = input.text.toString().trim()
                        if (name.isBlank()) input.error = "请先取名" else {
                            repo.adopt(candidate, name, selectedCharacter!!)
                            dialog.dismiss(); Toast.makeText(this, "$name 已经到家啦", Toast.LENGTH_SHORT).show(); showHome()
                        }
                    }
                }; dialog.show()
            }
    }

    private fun choosePet() {
        val pets = repo.pets()
        if (pets.size <= 1) { Toast.makeText(this, "目前只有这一只宠物", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setTitle("切换宠物")
            .setItems(pets.map { "${it.name} · ${it.bondedCharacterName} × 用户" }.toTypedArray()) { _, which ->
                repo.setActive(pets[which].id); showHome()
            }.show()
    }

    private fun interact(pet: Pet, label: String, action: String) {
        if (action == "feed") {
            val (coins, _) = repo.wallet(pet)
            if (coins < PetHouseRepository.FEED_COST) {
                Toast.makeText(this, "共同钱包不够，今天多和${pet.bondedCharacterName}聊聊天吧", Toast.LENGTH_LONG).show(); return
            }
        }
        val loading = AlertDialog.Builder(this).setTitle("${label}中…").setMessage("正在生成你、${pet.bondedCharacterName}和${pet.name}的互动").setCancelable(false).create()
        loading.show()
        Thread {
            val dialogue = requestInteraction(pet, label)
            val cost = if (action == "feed") PetHouseRepository.FEED_COST else 0
            repo.perform(pet, action, "用户和${pet.bondedCharacterName}", dialogue, cost)
            runOnUiThread {
                loading.dismiss()
                AlertDialog.Builder(this).setTitle("$label · ${pet.name}").setMessage(dialogue).setPositiveButton("好") { _, _ -> showHome() }.show()
            }
        }.start()
    }

    private fun requestCandidate(type: String, character: PetCharacter, wander: Boolean): PetCandidate {
        val place = if (wander) "街角、公园或其他合理地点的偶遇" else "宠物店内的挑选"
        val prompt = """
            你是宠物小屋叙事引擎。场景是$place。用户和角色【${character.name}】一起遇见一只$type。
            角色固定设定如下，绝不能脱离或改写：
            ${character.snapshot}
            请自由生成一个具体品种与花色的独特宠物，并生成它看到用户、角色时各自不同的反应，以及角色符合设定的互动。
            只输出严格 JSON：
            {"breed":"品种","color":"花色","appearance":"具体Q版外形","personality":"稳定且明确的性格","likes":"2-3项喜好","dislikes":"1-2项不喜欢","reaction":"宠物对用户和角色的现场反应","characterReaction":"角色的台词和动作","imagePrompt":"用于生成该宠物Q版单体肖像的英文提示词，无文字"}
        """.trimIndent()
        val raw = callAi(prompt)
        return try {
            val obj = JSONObject(cleanJson(raw))
            PetCandidate(type, obj.optString("breed", fallbackBreed(type)), obj.optString("color", "奶油色"),
                obj.optString("appearance", "圆滚滚、眼睛亮晶晶的小家伙"), obj.optString("personality", "好奇、慢热但很忠诚"),
                obj.optString("likes", "温柔说话、晒太阳"), obj.optString("dislikes", "突然的巨响"),
                obj.optString("reaction", "它谨慎地靠近，先闻了闻你的指尖，又望向同行的角色。"),
                obj.optString("characterReaction", "看来它已经在偷偷选择我们了。"),
                obj.optString("imagePrompt", "adorable chibi $type pet, ${obj.optString("color", "cream")}, kawaii 3D portrait, no text"))
        } catch (_: Exception) { fallbackCandidate(type, character.name) }
    }

    private fun requestInteraction(pet: Pet, action: String): String {
        val prompt = """
            你是宠物小屋互动叙事引擎。现在用户和【${pet.bondedCharacterName}】一起对宠物【${pet.name}】进行“$action”。
            以下全部是永久固定设定，不得改写、遗漏、拆分或让任一方脱离设定：
            宠物：${pet.type}/${pet.breed}/${pet.color}；外形：${pet.appearance}；性格：${pet.personality}；喜欢：${pet.likes}；不喜欢：${pet.dislikes}。
            共同角色完整设定：${pet.bondedCharacterSnapshot}
            当前状态：心情${pet.mood}，饥饿${pet.hunger}，清洁${pet.cleanliness}。
            写一段100-180字的自然互动，必须同时出现宠物的动作/反应、用户参与、角色符合设定的动作或台词。只输出正文。
        """.trimIndent()
        return callAi(prompt).ifBlank { "${pet.name}先小心地观察你的动作，很快又顺着${pet.bondedCharacterName}的声音放松下来。你们一左一右陪着它，它按照自己${pet.personality}的性子回应，最后舒服地靠近了一点。" }
    }

    private fun callAi(prompt: String): String {
        return try {
            val pref = getSharedPreferences("AppConfig", MODE_PRIVATE)
            var url = (pref.getString("apiUrl", "") ?: "").trimEnd('/')
            val key = pref.getString("apiKey", "") ?: ""
            val model = (pref.getString("modelName", "") ?: "").ifBlank { "gpt-4o" }
            if (url.isBlank() || key.isBlank()) return ""
            if (!url.endsWith("/chat/completions")) url += if (url.endsWith("/v1")) "/chat/completions" else "/v1/chat/completions"
            val body = JSONObject().apply {
                put("model", model); put("temperature", 0.9); put("max_tokens", 650)
                put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            }.toString().toRequestBody("application/json".toMediaTypeOrNull())
            Http.client.newCall(Request.Builder().url(url).addHeader("Authorization", "Bearer $key").post(body).build()).execute().use { response ->
                if (!response.isSuccessful) return ""
                JSONObject(response.body?.string().orEmpty()).optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content", "")?.trim().orEmpty()
            }
        } catch (_: Exception) { "" }
    }

    private fun fallbackCandidate(type: String, charName: String): PetCandidate {
        val variants = when (type) {
            "猫" -> listOf("英国短毛猫" to "蓝白", "布偶猫" to "海豹双色", "中华田园猫" to "三花")
            "狗" -> listOf("柴犬" to "奶油色", "柯基" to "黄白", "贵宾犬" to "杏色")
            "鼠" -> listOf("金丝熊" to "金白", "侏儒仓鼠" to "银狐色", "花枝鼠" to "奶牛花")
            else -> listOf("美西螈" to "樱花粉", "蜜袋鼯" to "银灰", "豹纹守宫" to "橘白")
        }
        val (breed, color) = variants.random()
        return PetCandidate(type, breed, color, "身体小小的，眼睛亮晶晶，带着独一无二的细微花纹。", "好奇、慢热、认定家人后很黏人",
            "轻声呼唤、温暖角落、小零食", "巨响、被突然抱起", "它先盯着你看了好一会儿，随后试探着靠近，又回头确认${charName}也在。",
            "${charName}放轻动作，笑着说：别急，我们让它自己决定。", "adorable chibi $breed, $color, kawaii 3D pet portrait, soft pastel background, no text")
    }

    private fun cleanJson(raw: String): String {
        val start = raw.indexOf('{'); val end = raw.lastIndexOf('}')
        return if (start >= 0 && end > start) raw.substring(start, end + 1) else raw
    }

    private fun fallbackBreed(type: String) = when (type) { "猫" -> "中华田园猫"; "狗" -> "柴犬"; "鼠" -> "金丝熊"; else -> "美西螈" }

    private fun loadPetImage(view: ImageView, pet: Pet) {
        if (pet.imagePath.isNotBlank() && File(pet.imagePath).exists()) view.load(File(pet.imagePath)) else view.setImageResource(resourceForType(pet.type))
    }

    private fun resourceForType(type: String) = when (type) {
        "猫" -> R.drawable.pet_chibi_cat; "狗" -> R.drawable.pet_chibi_dog
        "鼠" -> R.drawable.pet_chibi_hamster; else -> R.drawable.pet_chibi_exotic
    }

    private fun scheduleBackgroundCare() {
        val alarm = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, PetCareReceiver::class.java)
        val pending = PendingIntent.getBroadcast(this, 2407, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 60 * 60 * 1000L, 4 * 60 * 60 * 1000L, pending)
    }

    private fun clear() = root.removeAllViews()
    private fun header(text: String) = root.addView(TextView(this).apply { this.text = text; textSize = 28f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF3A2D2A.toInt()); gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(10)) })
    private fun titleText(text: String) = root.addView(TextView(this).apply { this.text = text; textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF3A2D2A.toInt()); gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(8)) })
    private fun bodyText(text: String, card: Boolean = false) = root.addView(TextView(this).apply { this.text = text; textSize = 15f; setTextColor(0xFF695653.toInt()); gravity = if (card) Gravity.START else Gravity.CENTER; setPadding(dp(16), dp(13), dp(16), dp(13)); if (card) background = rounded(Color.WHITE, 18f) }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    private fun image(res: Int, size: Int) = root.addView(ImageView(this).apply { setImageResource(res); scaleType = ImageView.ScaleType.CENTER_CROP; background = rounded(Color.WHITE, 26f); clipToOutline = true }, LinearLayout.LayoutParams(dp(size), dp(size)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10) })
    private fun addButton(text: String, secondary: Boolean = false, click: () -> Unit) = root.addView(smallButton(text, secondary, click), LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) })
    private fun smallButton(text: String, secondary: Boolean = false, click: () -> Unit) = Button(this).apply { this.text = text; textSize = 15f; isAllCaps = false; setTextColor(if (secondary) 0xFF795B54.toInt() else Color.WHITE); background = rounded(if (secondary) 0xFFFFE6DD.toInt() else 0xFFE78D79.toInt(), 18f); setOnClickListener { click() } }
    private fun status(label: String, value: Int, color: Int) {
        val line = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(5), 0, dp(5)) }
        line.addView(TextView(this).apply { text = "$label  $value/100"; textSize = 14f; setTextColor(0xFF554744.toInt()) })
        line.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = value; progressTintList = android.content.res.ColorStateList.valueOf(color) }, LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(4) })
        root.addView(line)
    }
    private fun actionRow(vararg actions: Pair<String, String>, click: (String, String) -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        actions.forEachIndexed { index, pair -> if (index > 0) addView(space(8)); addView(smallButton(pair.first) { click(pair.first, pair.second) }, weightParams()) }
    }
    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius.toInt()).toFloat() }
    private fun weightParams() = LinearLayout.LayoutParams(0, dp(52), 1f)
    private fun space(width: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(width), 1) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
