package com.moon.aiphone

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min

data class Pet(
    val id: Long,
    val name: String,
    val type: String,
    val breed: String,
    val color: String,
    val appearance: String,
    val personality: String,
    val likes: String,
    val dislikes: String,
    val bondedCharacterId: String,
    val bondedCharacterName: String,
    val bondedCharacterSnapshot: String,
    val imageKey: String,
    val imagePath: String,
    var mood: Int,
    var hunger: Int,
    var cleanliness: Int,
    var lastUpdated: Long,
    var lastRoleCareAt: Long,
    val isActive: Boolean
)

data class PetCandidate(
    val type: String,
    val breed: String,
    val color: String,
    val appearance: String,
    val personality: String,
    val likes: String,
    val dislikes: String,
    val reaction: String,
    val characterReaction: String,
    val imagePrompt: String,
    var imagePath: String = ""
)

data class PetCharacter(val id: String, val name: String, val snapshot: String)

data class PetEvent(
    val action: String,
    val actor: String,
    val dialogue: String,
    val cost: Int,
    val createdAt: Long
)

class PetHouseRepository(context: Context) {
    private val appContext = context.applicationContext
    private val db: SQLiteDatabase = DatabaseHelper(appContext).writableDatabase

    fun characters(): List<PetCharacter> {
        val result = mutableListOf<PetCharacter>()
        db.rawQuery(
            "SELECT userId, realName, IFNULL(identityInfo,''), IFNULL(appearance,''), " +
                "IFNULL(relationship,''), IFNULL(presetOnline,''), IFNULL(presetOffline,'') FROM Contacts ORDER BY realName",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1).ifBlank { c.getString(0) }
                val snapshot = "姓名：$name\n身份与性格：${c.getString(2)}\n外貌：${c.getString(3)}\n与用户关系：${c.getString(4)}\n在线设定：${c.getString(5)}\n离线设定：${c.getString(6)}"
                result += PetCharacter(c.getString(0), name, snapshot)
            }
        }
        return result
    }

    fun pets(): List<Pet> {
        val result = mutableListOf<Pet>()
        db.rawQuery("SELECT * FROM Pets ORDER BY isActive DESC, createdAt DESC", null).use { c ->
            while (c.moveToNext()) result += c.toPet()
        }
        return result
    }

    fun activePet(): Pet? {
        db.rawQuery("SELECT * FROM Pets ORDER BY isActive DESC, createdAt DESC LIMIT 1", null).use { c ->
            return if (c.moveToFirst()) c.toPet() else null
        }
    }

    fun setActive(id: Long) {
        db.beginTransaction()
        try {
            db.execSQL("UPDATE Pets SET isActive=0")
            db.execSQL("UPDATE Pets SET isActive=1 WHERE id=?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun adopt(candidate: PetCandidate, name: String, character: PetCharacter): Long {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("name", name); put("type", candidate.type); put("breed", candidate.breed)
            put("color", candidate.color); put("appearance", candidate.appearance)
            put("personality", candidate.personality); put("likes", candidate.likes)
            put("dislikes", candidate.dislikes); put("bondedCharacterId", character.id)
            put("bondedCharacterName", character.name); put("bondedCharacterSnapshot", character.snapshot)
            put("imageKey", imageKey(candidate.type)); put("imagePath", candidate.imagePath)
            put("mood", 85); put("hunger", 85); put("cleanliness", 85)
            put("lastUpdated", now); put("lastRoleCareAt", now); put("createdAt", now); put("isActive", 1)
        }
        db.execSQL("UPDATE Pets SET isActive=0")
        val id = db.insertOrThrow("Pets", null, values)
        val adoptionMemory = "【共同宠物】我和用户一起领养了${candidate.color}${candidate.breed}，取名为${name}。${name}的性格是${candidate.personality}，喜欢${candidate.likes}，不喜欢${candidate.dislikes}。这是我们共同养育的家人。"
        addEvent(id, "adopt", "用户和${character.name}", "欢迎${name}来到这个家。", 0)
        MemoryManager.recordPetMemory(appContext, character.id, adoptionMemory, isBond = true)
        return id
    }

    fun recentEvents(petId: Long, limit: Int = 12): List<PetEvent> {
        val result = mutableListOf<PetEvent>()
        db.rawQuery(
            "SELECT action, actor, dialogue, cost, createdAt FROM PetEvents WHERE petId=? ORDER BY createdAt DESC LIMIT ?",
            arrayOf(petId.toString(), limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                result += PetEvent(c.getString(0), c.getString(1), c.getString(2), c.getInt(3), c.getLong(4))
            }
        }
        return result
    }

    fun applyDecay(pet: Pet): Pet {
        val now = System.currentTimeMillis()
        val hours = ((now - pet.lastUpdated).coerceAtLeast(0L) / 3_600_000L).toInt()
        if (hours <= 0) return pet
        pet.hunger = max(0, pet.hunger - hours * 2)
        pet.cleanliness = max(0, pet.cleanliness - hours)
        pet.mood = max(0, pet.mood - max(0, hours - 2))
        pet.lastUpdated = now
        saveVitals(pet)
        return pet
    }

    fun perform(pet: Pet, action: String, actor: String, dialogue: String, cost: Int = 0) {
        when (action) {
            "play" -> { pet.mood = min(100, pet.mood + 16); pet.hunger = max(0, pet.hunger - 4) }
            "groom" -> { pet.mood = min(100, pet.mood + 8); pet.cleanliness = min(100, pet.cleanliness + 13) }
            "feed" -> pet.hunger = min(100, pet.hunger + 28)
            "clean" -> { pet.cleanliness = min(100, pet.cleanliness + 35); pet.mood = min(100, pet.mood + 3) }
            "walk" -> { pet.mood = min(100, pet.mood + 18); pet.hunger = max(0, pet.hunger - 7); pet.cleanliness = max(0, pet.cleanliness - 3) }
        }
        pet.lastUpdated = System.currentTimeMillis()
        saveVitals(pet)
        addEvent(pet.id, action, actor, dialogue, cost)
        val actionName = when (action) {
            "play" -> "陪它玩"; "groom" -> "给它梳毛"; "feed" -> "给它投喂"
            "clean" -> "清理它的小窝"; "walk" -> "带它遛弯"; else -> action
        }
        val date = java.text.SimpleDateFormat("MM月dd日 HH:mm", java.util.Locale.CHINA).format(java.util.Date())
        MemoryManager.recordPetMemory(
            appContext,
            pet.bondedCharacterId,
            "【宠物记忆·$date】$actor 为共同宠物${pet.name}$actionName。$dialogue 当前${pet.name}心情${pet.mood}、饥饿${pet.hunger}、清洁${pet.cleanliness}。"
        )
    }

    fun wallet(pet: Pet): Pair<Int, Int> {
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val messages = db.rawQuery(
            "SELECT COUNT(*) FROM ChatHistory WHERE aiId=? AND timestamp>=?",
            arrayOf(pet.bondedCharacterId, start.toString())
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        val spent = db.rawQuery(
            "SELECT IFNULL(SUM(cost),0) FROM PetEvents WHERE petId=? AND createdAt>=?",
            arrayOf(pet.id.toString(), start.toString())
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        return max(0, messages * COINS_PER_MESSAGE - spent) to messages
    }

    fun autoCare(): List<String> {
        val events = mutableListOf<String>()
        pets().forEach { pet ->
            applyDecay(pet)
            val now = System.currentTimeMillis()
            if (now - pet.lastRoleCareAt < AUTO_CARE_INTERVAL) return@forEach
            val (coins, _) = wallet(pet)
            when {
                pet.hunger <= 35 && coins >= FEED_COST -> {
                    perform(pet, "feed", pet.bondedCharacterName, "${pet.bondedCharacterName}趁你不在，给${pet.name}添好了食物。", FEED_COST)
                    events += "${pet.bondedCharacterName}给${pet.name}喂了食"
                }
                pet.cleanliness <= 35 -> {
                    perform(pet, "clean", pet.bondedCharacterName, "${pet.bondedCharacterName}趁你不在，悄悄把${pet.name}的小窝收拾干净了。")
                    events += "${pet.bondedCharacterName}清理了${pet.name}的小窝"
                }
            }
            db.execSQL("UPDATE Pets SET lastRoleCareAt=? WHERE id=?", arrayOf(now, pet.id))
        }
        return events
    }

    private fun saveVitals(pet: Pet) {
        db.execSQL(
            "UPDATE Pets SET mood=?, hunger=?, cleanliness=?, lastUpdated=? WHERE id=?",
            arrayOf<Any>(pet.mood, pet.hunger, pet.cleanliness, pet.lastUpdated, pet.id)
        )
    }

    private fun addEvent(petId: Long, action: String, actor: String, dialogue: String, cost: Int) {
        val v = ContentValues().apply {
            put("petId", petId); put("action", action); put("actor", actor)
            put("dialogue", dialogue); put("cost", cost); put("createdAt", System.currentTimeMillis())
        }
        db.insert("PetEvents", null, v)
    }

    private fun android.database.Cursor.toPet() = Pet(
        getLong(getColumnIndexOrThrow("id")), getString(getColumnIndexOrThrow("name")),
        getString(getColumnIndexOrThrow("type")), getString(getColumnIndexOrThrow("breed")),
        getString(getColumnIndexOrThrow("color")), getString(getColumnIndexOrThrow("appearance")),
        getString(getColumnIndexOrThrow("personality")), getString(getColumnIndexOrThrow("likes")),
        getString(getColumnIndexOrThrow("dislikes")), getString(getColumnIndexOrThrow("bondedCharacterId")),
        getString(getColumnIndexOrThrow("bondedCharacterName")), getString(getColumnIndexOrThrow("bondedCharacterSnapshot")),
        getString(getColumnIndexOrThrow("imageKey")), getString(getColumnIndexOrThrow("imagePath")),
        getInt(getColumnIndexOrThrow("mood")), getInt(getColumnIndexOrThrow("hunger")),
        getInt(getColumnIndexOrThrow("cleanliness")), getLong(getColumnIndexOrThrow("lastUpdated")),
        getLong(getColumnIndexOrThrow("lastRoleCareAt")), getInt(getColumnIndexOrThrow("isActive")) == 1
    )

    companion object {
        const val FEED_COST = 8
        const val COINS_PER_MESSAGE = 2
        private const val AUTO_CARE_INTERVAL = 4 * 60 * 60 * 1000L
        fun imageKey(type: String) = when (type) {
            "猫" -> "cat"; "狗" -> "dog"; "鼠" -> "hamster"; else -> "exotic"
        }
    }
}
