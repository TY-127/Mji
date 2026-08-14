package com.moon.aiphone

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.Cursor

fun Cursor.getSafeString(columnName: String): String {
    val idx = this.getColumnIndex(columnName)
    return if (idx != -1) this.getString(idx) ?: "" else ""
}

fun Cursor.getSafeInt(columnName: String): Int {
    val idx = this.getColumnIndex(columnName)
    return if (idx != -1) this.getInt(idx) else 0
}

fun Cursor.getSafeLong(columnName: String): Long {
    val idx = this.getColumnIndex(columnName)
    return if (idx != -1) this.getLong(idx) else 0L
}

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, "AiPhone.db", null, 24){

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE MemoryBank (id INTEGER PRIMARY KEY AUTOINCREMENT, aiId TEXT, memoryText TEXT, category TEXT DEFAULT 'misc', insertTime INTEGER)")
        db.execSQL("CREATE TABLE Contacts (id INTEGER PRIMARY KEY AUTOINCREMENT, userId TEXT UNIQUE, realName TEXT, birthday TEXT, identityInfo TEXT, avatarUri TEXT, appearance TEXT DEFAULT '', patience INTEGER DEFAULT 60, relationship TEXT DEFAULT '普通朋友', presetOnline TEXT DEFAULT '', presetOffline TEXT DEFAULT '', lockPassword TEXT DEFAULT '', userNicknameByAi TEXT DEFAULT '', isPinned INTEGER DEFAULT 0)")
        db.execSQL("CREATE TABLE Moments (id INTEGER PRIMARY KEY AUTOINCREMENT, aiId TEXT, content TEXT, translatedText TEXT DEFAULT '', imageDesc TEXT, timestamp INTEGER)")
        db.execSQL("CREATE TABLE MyProfile (id INTEGER PRIMARY KEY AUTOINCREMENT, myName TEXT, myId TEXT, gender TEXT, birthday TEXT, mbti TEXT, identity TEXT, myAvatarUri TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS Likes (id INTEGER PRIMARY KEY AUTOINCREMENT, momentId INTEGER, userId TEXT, userName TEXT)")
        db.execSQL("""
    CREATE TABLE IF NOT EXISTS Comments (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        momentId INTEGER,
        userId TEXT,
        userName TEXT,
        content TEXT,
        translatedText TEXT DEFAULT '',
        timestamp INTEGER,
        isReplied INTEGER DEFAULT 0,
        replyToId INTEGER DEFAULT 0,
        replyToName TEXT DEFAULT ''
    )
""".trimIndent())
        db.execSQL("CREATE TABLE ChatHistory (id INTEGER PRIMARY KEY AUTOINCREMENT, aiId TEXT, groupId TEXT DEFAULT '', content TEXT, isFromMe INTEGER, msgTime TEXT, isVoice INTEGER DEFAULT 0, voiceDuration INTEGER DEFAULT 0, localVoicePath TEXT, translatedText TEXT, innerThoughts TEXT DEFAULT '', imageDesc TEXT DEFAULT '', timestamp INTEGER DEFAULT 0, senderId TEXT DEFAULT '', senderName TEXT DEFAULT '', isRead INTEGER DEFAULT 0)")
        db.execSQL("CREATE TABLE AdminWorldBook (id INTEGER PRIMARY KEY AUTOINCREMENT, keyword TEXT, content TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS UserWorldBook (id INTEGER PRIMARY KEY AUTOINCREMENT, aiId TEXT, keyword TEXT, content TEXT, priority TEXT DEFAULT 'keyword', targetAiId TEXT DEFAULT 'global')")
        db.execSQL("""
    CREATE TABLE GroupChats (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        groupId TEXT UNIQUE,
        groupName TEXT,
        createdAt INTEGER,
        avatarUri TEXT DEFAULT '',
        isDisbanded INTEGER DEFAULT 0,
        isPinned INTEGER DEFAULT 0,
        isObserveOnly INTEGER DEFAULT 0
    )
""".trimIndent())
        db.execSQL("""
    CREATE TABLE GroupMembers (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        groupId TEXT,
        memberId TEXT,
        memberName TEXT,
        isAi INTEGER DEFAULT 1,
        nickname TEXT DEFAULT '',
        title TEXT DEFAULT '',
        isOwner INTEGER DEFAULT 0,
        UNIQUE(groupId, memberId)
    )
""".trimIndent())
        db.execSQL("CREATE TABLE StickerPacks (id INTEGER PRIMARY KEY AUTOINCREMENT, packName TEXT, createdAt INTEGER)")
        db.execSQL("CREATE TABLE Stickers (id INTEGER PRIMARY KEY AUTOINCREMENT, packId INTEGER DEFAULT 1, name TEXT, url TEXT, createdAt INTEGER)")
        db.execSQL("INSERT INTO StickerPacks (packName, createdAt) VALUES ('默认', ${System.currentTimeMillis()})")
        db.execSQL("CREATE TABLE ForumPosts (id INTEGER PRIMARY KEY AUTOINCREMENT, boardId TEXT, title TEXT, content TEXT, authorId TEXT, authorName TEXT, isAlias INTEGER DEFAULT 0, likeCount INTEGER DEFAULT 0, timestamp INTEGER)")
        db.execSQL("CREATE TABLE ForumComments (id INTEGER PRIMARY KEY AUTOINCREMENT, postId INTEGER, authorId TEXT, authorName TEXT, content TEXT, likeCount INTEGER DEFAULT 0, timestamp INTEGER)")
        db.execSQL("CREATE TABLE ForumAlias (id INTEGER PRIMARY KEY AUTOINCREMENT, aliasName TEXT, avatarColor TEXT, createdAt INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS AiDiary (id INTEGER PRIMARY KEY AUTOINCREMENT, aiId TEXT, dateStr TEXT, weather TEXT, location TEXT, content TEXT, summaryForNext TEXT, timestamp INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS UserDiary (id INTEGER PRIMARY KEY AUTOINCREMENT, dateStr TEXT, weather TEXT, content TEXT, visibleAiIds TEXT, aiAnnotations TEXT, timestamp INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS UserTravelPlans (id INTEGER PRIMARY KEY AUTOINCREMENT, travelType TEXT, fromPlace TEXT, toPlace TEXT, tripNo TEXT, departTime TEXT, note TEXT, status TEXT DEFAULT 'active', createdAt INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS UserPackages (id INTEGER PRIMARY KEY AUTOINCREMENT, trackingNo TEXT, carrier TEXT, itemName TEXT, status TEXT DEFAULT 'active', note TEXT, createdAt INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS DreamHousePosts (id INTEGER PRIMARY KEY AUTOINCREMENT, authorId TEXT, authorAlias TEXT, boardId TEXT, content TEXT, imagePath TEXT, grade INTEGER DEFAULT 0, likeCount INTEGER DEFAULT 0, timestamp INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS DreamHouseComments (id INTEGER PRIMARY KEY AUTOINCREMENT, postId INTEGER, authorId TEXT, authorAlias TEXT, content TEXT, timestamp INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS DreamHouseAlias (id INTEGER PRIMARY KEY AUTOINCREMENT, aiId TEXT UNIQUE, alias TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS UserAppearance (id INTEGER PRIMARY KEY AUTOINCREMENT, description TEXT, updatedAt INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS BookShelf (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, filePath TEXT, totalChars INTEGER DEFAULT 0, totalChunks INTEGER DEFAULT 0, lastReadChunkIndex INTEGER DEFAULT 0, lastReadContactId TEXT DEFAULT '', createdAt INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS BookComments (id INTEGER PRIMARY KEY AUTOINCREMENT, bookId INTEGER, contactId TEXT, chunkIndex INTEGER, anchorText TEXT, comment TEXT, createdAt INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS NpcEvents (id INTEGER PRIMARY KEY AUTOINCREMENT, aiId TEXT, npcName TEXT, eventDesc TEXT, dateStr TEXT, insertTime INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS PendingAiMessages (id INTEGER PRIMARY KEY AUTOINCREMENT, aiId TEXT, simulatedTime INTEGER, triggeredAt INTEGER, isDone INTEGER DEFAULT 0)")
        db.execSQL("CREATE TABLE IF NOT EXISTS CharacterRelationships (id INTEGER PRIMARY KEY AUTOINCREMENT, aiId1 TEXT, aiId2 TEXT, relationship TEXT, note TEXT DEFAULT '')")
        createPetHouseTables(db)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chat_aiId ON ChatHistory(aiId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chat_groupId ON ChatHistory(groupId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chat_timestamp ON ChatHistory(timestamp)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_memory_aiId ON MemoryBank(aiId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_group_groupId ON GroupChats(groupId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_member_groupId ON GroupMembers(groupId)")
        db.execSQL("""
    CREATE TABLE IF NOT EXISTS UserProfile (
        fieldKey TEXT PRIMARY KEY,
        fieldValue TEXT
    )
""".trimIndent())
    }

    private fun splitVisibleAndTranslation(raw: String): Pair<String, String> {
        var s = raw.replace("\r", "").trim()
        if (s.isEmpty()) return "" to ""
        s = s.replace(
            Regex("[\\[【「『（(]\\s*(评论翻译|私聊翻译|翻译|译文|译|translation)\\s*[\\]】」』）)]", RegexOption.IGNORE_CASE),
            "【翻译】"
        )

        val transMatch = Regex("【翻译】[：:]?\\s*([\\s\\S]*?)(?=【(?:内心|台词|评论|私聊|角色ID|发送者)】|$)").find(s)
        val trans = transMatch?.groupValues?.get(1)
            ?.replace(Regex("【[^】]*】"), "")
            ?.trim()
            .orEmpty()

        val dialogMatch = Regex("【台词】([\\s\\S]*?)(?=【(?:翻译|内心)】|$)").find(s)
        var visible = when {
            dialogMatch != null -> dialogMatch.groupValues[1]
            transMatch != null -> s.substring(0, transMatch.range.first)
            else -> s
        }

        visible = visible
            .replace(Regex("【内心】[\\s\\S]*?(?=【台词】|【评论】|【私聊】|$)"), "")
            .replace(Regex("【(?:翻译|评论翻译|私聊翻译|译)】[\\s\\S]*$"), "")
            .replace(Regex("【(?:台词|评论|私聊|发送者|角色ID)】"), "")
            .replace(Regex("【[^】]*】"), "")
            .trim()

        return visible to trans
    }

    private fun repairLegacyTextArtifacts(db: SQLiteDatabase) {
        fun repairTable(table: String) {
            try {
                db.rawQuery("SELECT id, content, IFNULL(translatedText,'') FROM $table WHERE content LIKE '%【%' OR IFNULL(translatedText,'') LIKE '%【%'", null).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val oldContent = c.getString(1) ?: ""
                        val oldTrans = c.getString(2) ?: ""
                        val parsed = splitVisibleAndTranslation(oldContent)
                        val cleanedTrans = oldTrans
                            .replace(Regex("【内心】[\\s\\S]*?(?=【台词】|【翻译】|$)"), "")
                            .replace(Regex("【[^】]*】"), "")
                            .trim()
                        val newContent = parsed.first.ifEmpty { oldContent }
                        val newTrans = when {
                            parsed.second.isNotEmpty() -> parsed.second
                            cleanedTrans != oldTrans -> cleanedTrans
                            else -> oldTrans
                        }
                        if (newContent != oldContent || newTrans != oldTrans) {
                            val values = android.content.ContentValues().apply {
                                put("content", newContent)
                                put("translatedText", newTrans)
                            }
                            db.update(table, values, "id=?", arrayOf(id.toString()))
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        repairTable("ChatHistory")
        repairTable("Comments")
        repairTable("Moments")
    }

    fun repairLegacyTextArtifacts() {
        try { repairLegacyTextArtifacts(writableDatabase) } catch (_: Exception) {}
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        try { repairLegacyTextArtifacts(db) } catch (_: Exception) {}
        try { db.execSQL("CREATE TABLE IF NOT EXISTS NpcEvents (id INTEGER PRIMARY KEY AUTOINCREMENT, aiId TEXT, npcName TEXT, eventDesc TEXT, dateStr TEXT, insertTime INTEGER)") } catch (_: Exception) {}
        try { db.execSQL("CREATE TABLE IF NOT EXISTS PendingAiMessages (id INTEGER PRIMARY KEY AUTOINCREMENT, aiId TEXT, simulatedTime INTEGER, triggeredAt INTEGER, isDone INTEGER DEFAULT 0)") } catch (_: Exception) {}
        try { db.execSQL("CREATE TABLE IF NOT EXISTS CharacterRelationships (id INTEGER PRIMARY KEY AUTOINCREMENT, aiId1 TEXT, aiId2 TEXT, relationship TEXT, note TEXT DEFAULT '')") } catch (_: Exception) {}
        try { createPetHouseTables(db) } catch (_: Exception) {}
        fun hasColumn(table: String, column: String): Boolean {
            db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                val idx = c.getColumnIndex("name")
                while (c.moveToNext()) {
                    if (idx >= 0 && c.getString(idx) == column) return true
                }
            }
            return false
        }

        fun addColumnIfMissing(table: String, column: String, def: String) {
            if (!hasColumn(table, column)) {
                db.execSQL("ALTER TABLE $table ADD COLUMN $column $def")
            }

        }

        if (oldVersion < 2) {
            try { addColumnIfMissing("ChatHistory", "isVoice", "INTEGER DEFAULT 0") } catch (_: Exception) {}
            try { addColumnIfMissing("ChatHistory", "voiceDuration", "INTEGER DEFAULT 0") } catch (_: Exception) {}
            try { addColumnIfMissing("ChatHistory", "localVoicePath", "TEXT") } catch (_: Exception) {}
        }
        if (oldVersion < 3) {
            try { addColumnIfMissing("ChatHistory", "translatedText", "TEXT") } catch (_: Exception) {}
            try { addColumnIfMissing("Moments", "translatedText", "TEXT") } catch (_: Exception) {}
            try { db.execSQL("CREATE TABLE IF NOT EXISTS Likes (id INTEGER PRIMARY KEY AUTOINCREMENT, momentId INTEGER, userId TEXT, userName TEXT)") } catch (_: Exception) {}
            try { db.execSQL("CREATE TABLE IF NOT EXISTS Comments (id INTEGER PRIMARY KEY AUTOINCREMENT, momentId INTEGER, userId TEXT, userName TEXT, content TEXT, timestamp INTEGER)") } catch (_: Exception) {}
        }
        if (oldVersion < 4) {
            try { addColumnIfMissing("Comments", "translatedText", "TEXT") } catch (_: Exception) {}
            try { addColumnIfMissing("Comments", "isReplied", "INTEGER DEFAULT 0") } catch (_: Exception) {}
            try { db.execSQL("UPDATE Comments SET isReplied = 1") } catch (_: Exception) {}
        }
        try { addColumnIfMissing("Contacts", "lockPassword", "TEXT DEFAULT ''") } catch (_: Exception) {}
        try { addColumnIfMissing("Contacts", "isPinned", "INTEGER DEFAULT 0") } catch (_: Exception) {}
        try { addColumnIfMissing("GroupChats", "isPinned", "INTEGER DEFAULT 0") } catch (_: Exception) {}
        if (oldVersion < 5) {
            try { db.execSQL("CREATE TABLE IF NOT EXISTS AdminWorldBook (id INTEGER PRIMARY KEY AUTOINCREMENT, keyword TEXT, content TEXT)") } catch (_: Exception) {}
            try { db.execSQL("CREATE TABLE IF NOT EXISTS UserWorldBook (id INTEGER PRIMARY KEY AUTOINCREMENT, aiId TEXT, keyword TEXT, content TEXT)") } catch (_: Exception) {}
        }
        if (oldVersion < 6) {
            try { db.execSQL("CREATE TABLE IF NOT EXISTS MemoryBank (id INTEGER PRIMARY KEY AUTOINCREMENT, aiId TEXT, memoryText TEXT, category TEXT DEFAULT 'misc', insertTime INTEGER)") } catch (_: Exception) {}
            try { addColumnIfMissing("Contacts", "patience", "INTEGER DEFAULT 60") } catch (_: Exception) {}
        }
        if (oldVersion < 7) {
            try { addColumnIfMissing("Comments", "replyToId", "INTEGER DEFAULT 0") } catch (_: Exception) {}
            try { addColumnIfMissing("Comments", "replyToName", "TEXT DEFAULT ''") } catch (_: Exception) {}
            try { addColumnIfMissing("ChatHistory", "timestamp", "INTEGER DEFAULT 0") } catch (_: Exception) {}
        }
        if (oldVersion < 8) {
            try { addColumnIfMissing("ChatHistory", "groupId", "TEXT DEFAULT ''") } catch (_: Exception) {}
            try { db.execSQL("CREATE TABLE IF NOT EXISTS GroupChats (id INTEGER PRIMARY KEY AUTOINCREMENT, groupId TEXT, groupName TEXT, createdAt INTEGER)") } catch (_: Exception) {}
            try { db.execSQL("CREATE TABLE IF NOT EXISTS GroupMembers (id INTEGER PRIMARY KEY AUTOINCREMENT, groupId TEXT, memberId TEXT, memberName TEXT, isAi INTEGER DEFAULT 1)") } catch (_: Exception) {}
        }
        try { addColumnIfMissing("ChatHistory", "senderId", "TEXT DEFAULT ''") } catch (_: Exception) {}
        try { addColumnIfMissing("ChatHistory", "senderName", "TEXT DEFAULT ''") } catch (_: Exception) {}
        try { addColumnIfMissing("ChatHistory", "innerThoughts", "TEXT DEFAULT ''") } catch (_: Exception) {}
        try { addColumnIfMissing("GroupChats", "avatarUri", "TEXT DEFAULT ''") } catch (_: Exception) {}
        if (oldVersion < 9) {
            try { db.execSQL("CREATE TABLE IF NOT EXISTS ForumPosts (id INTEGER PRIMARY KEY AUTOINCREMENT, boardId TEXT, title TEXT, content TEXT, authorId TEXT, authorName TEXT, isAlias INTEGER DEFAULT 0, likeCount INTEGER DEFAULT 0, timestamp INTEGER)") } catch (_: Exception) {}
            try { db.execSQL("CREATE TABLE IF NOT EXISTS ForumComments (id INTEGER PRIMARY KEY AUTOINCREMENT, postId INTEGER, authorId TEXT, authorName TEXT, content TEXT, likeCount INTEGER DEFAULT 0, timestamp INTEGER)") } catch (_: Exception) {}
            try { db.execSQL("CREATE TABLE IF NOT EXISTS ForumAlias (id INTEGER PRIMARY KEY AUTOINCREMENT, aliasName TEXT, avatarColor TEXT, createdAt INTEGER)") } catch (_: Exception) {}
        }
        if (oldVersion < 10) {
            try { db.execSQL("CREATE TABLE IF NOT EXISTS StickerPacks (id INTEGER PRIMARY KEY AUTOINCREMENT, packName TEXT, createdAt INTEGER)") } catch (_: Exception) {}
            try { db.execSQL("CREATE TABLE IF NOT EXISTS Stickers (id INTEGER PRIMARY KEY AUTOINCREMENT, packId INTEGER DEFAULT 1, name TEXT, url TEXT, createdAt INTEGER)") } catch (_: Exception) {}
            try { db.execSQL("INSERT INTO StickerPacks (packName, createdAt) VALUES ('默认', ${System.currentTimeMillis()})") } catch (_: Exception) {}
        }
        if (oldVersion < 11) {
            try { db.execSQL("CREATE TABLE IF NOT EXISTS DreamHousePosts (id INTEGER PRIMARY KEY AUTOINCREMENT, authorId TEXT, authorAlias TEXT, boardId TEXT, content TEXT, imagePath TEXT, grade INTEGER DEFAULT 0, likeCount INTEGER DEFAULT 0, timestamp INTEGER)") } catch (_: Exception) {}
            try { db.execSQL("CREATE TABLE IF NOT EXISTS DreamHouseComments (id INTEGER PRIMARY KEY AUTOINCREMENT, postId INTEGER, authorId TEXT, authorAlias TEXT, content TEXT, timestamp INTEGER)") } catch (_: Exception) {}
            try { db.execSQL("CREATE TABLE IF NOT EXISTS DreamHouseAlias (id INTEGER PRIMARY KEY AUTOINCREMENT, aiId TEXT UNIQUE, alias TEXT)") } catch (_: Exception) {}
            try { db.execSQL("CREATE TABLE IF NOT EXISTS UserAppearance (id INTEGER PRIMARY KEY AUTOINCREMENT, description TEXT, updatedAt INTEGER)") } catch (_: Exception) {}
            try { db.execSQL("CREATE TABLE IF NOT EXISTS AiDiary (id INTEGER PRIMARY KEY AUTOINCREMENT, aiId TEXT, dateStr TEXT, weather TEXT, location TEXT, content TEXT, summaryForNext TEXT, timestamp INTEGER)") } catch (_: Exception) {}
            try { db.execSQL("CREATE TABLE IF NOT EXISTS UserDiary (id INTEGER PRIMARY KEY AUTOINCREMENT, dateStr TEXT, weather TEXT, content TEXT, visibleAiIds TEXT, aiAnnotations TEXT, timestamp INTEGER)") } catch (_: Exception) {}
            try { db.execSQL("CREATE TABLE IF NOT EXISTS UserTravelPlans (id INTEGER PRIMARY KEY AUTOINCREMENT, travelType TEXT, fromPlace TEXT, toPlace TEXT, tripNo TEXT, departTime TEXT, note TEXT, status TEXT DEFAULT 'active', createdAt INTEGER)") } catch (_: Exception) {}
            try { db.execSQL("CREATE TABLE IF NOT EXISTS UserPackages (id INTEGER PRIMARY KEY AUTOINCREMENT, trackingNo TEXT, carrier TEXT, itemName TEXT, status TEXT DEFAULT 'active', note TEXT, createdAt INTEGER)") } catch (_: Exception) {}
        }
        if (oldVersion < 12) {
            try { db.execSQL("CREATE TABLE IF NOT EXISTS BookShelf (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, filePath TEXT, totalChars INTEGER DEFAULT 0, totalChunks INTEGER DEFAULT 0, lastReadChunkIndex INTEGER DEFAULT 0, lastReadContactId TEXT DEFAULT '', createdAt INTEGER)") } catch (_: Exception) {}
            try { db.execSQL("CREATE TABLE IF NOT EXISTS BookComments (id INTEGER PRIMARY KEY AUTOINCREMENT, bookId INTEGER, contactId TEXT, chunkIndex INTEGER, anchorText TEXT, comment TEXT, createdAt INTEGER)") } catch (_: Exception) {}
        }
        if (oldVersion < 14) {
            try { addColumnIfMissing("MemoryBank", "embedding", "TEXT DEFAULT ''") } catch (_: Exception) {}
        }
        if (oldVersion < 15) {
            try { addColumnIfMissing("GroupChats", "isObserveOnly", "INTEGER DEFAULT 0") } catch (_: Exception) {}
            try { addColumnIfMissing("GroupMembers", "nickname", "TEXT DEFAULT ''") } catch (_: Exception) {}
            try { addColumnIfMissing("GroupMembers", "title", "TEXT DEFAULT ''") } catch (_: Exception) {}
        }
        if (oldVersion < 16) {
            try { addColumnIfMissing("GroupMembers", "isOwner", "INTEGER DEFAULT 0") } catch (_: Exception) {}
        }
        if (oldVersion < 17) {
            try { addColumnIfMissing("GroupChats", "avatarUri", "TEXT DEFAULT ''") } catch (_: Exception) {}
            try { addColumnIfMissing("GroupChats", "isDisbanded", "INTEGER DEFAULT 0") } catch (_: Exception) {}
            try { addColumnIfMissing("ChatHistory", "isRead", "INTEGER DEFAULT 0") } catch (_: Exception) {}
            try { addColumnIfMissing("UserWorldBook", "priority", "TEXT DEFAULT 'keyword'") } catch (_: Exception) {}
            try { addColumnIfMissing("UserWorldBook", "targetAiId", "TEXT DEFAULT 'global'") } catch (_: Exception) {}
            try { addColumnIfMissing("Contacts", "appearance", "TEXT DEFAULT ''") } catch (_: Exception) {}
            try { addColumnIfMissing("Contacts", "relationship", "TEXT DEFAULT '普通朋友'") } catch (_: Exception) {}
        }
        if (oldVersion < 22) {
            try {
                db.execSQL("""
            CREATE TABLE IF NOT EXISTS UserProfile (
                fieldKey TEXT PRIMARY KEY,
                fieldValue TEXT
            )
        """.trimIndent())
            } catch (_: Exception) {}

            try { addColumnIfMissing("Contacts", "presetOnline", "TEXT DEFAULT ''") } catch (_: Exception) {}
            try { addColumnIfMissing("Contacts", "presetOffline", "TEXT DEFAULT ''") } catch (_: Exception) {}
            try { addColumnIfMissing("Contacts", "userNicknameByAi", "TEXT DEFAULT ''") } catch (_: Exception) {}
            try { addColumnIfMissing("GroupChats", "isPinned", "INTEGER DEFAULT 0") } catch (_: Exception) {}
            try { addColumnIfMissing("GroupChats", "isObserveOnly", "INTEGER DEFAULT 0") } catch (_: Exception) {}
            try { addColumnIfMissing("GroupMembers", "nickname", "TEXT DEFAULT ''") } catch (_: Exception) {}
            try { addColumnIfMissing("GroupMembers", "title", "TEXT DEFAULT ''") } catch (_: Exception) {}
            try { addColumnIfMissing("GroupMembers", "isOwner", "INTEGER DEFAULT 0") } catch (_: Exception) {}
        }
    }

    private fun createPetHouseTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS Pets (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                type TEXT NOT NULL,
                breed TEXT NOT NULL,
                color TEXT NOT NULL,
                appearance TEXT NOT NULL,
                personality TEXT NOT NULL,
                likes TEXT NOT NULL,
                dislikes TEXT NOT NULL,
                bondedCharacterId TEXT NOT NULL,
                bondedCharacterName TEXT NOT NULL,
                bondedCharacterSnapshot TEXT NOT NULL,
                imageKey TEXT NOT NULL,
                imagePath TEXT DEFAULT '',
                mood INTEGER DEFAULT 80,
                hunger INTEGER DEFAULT 80,
                cleanliness INTEGER DEFAULT 80,
                lastUpdated INTEGER NOT NULL,
                lastRoleCareAt INTEGER DEFAULT 0,
                createdAt INTEGER NOT NULL,
                isActive INTEGER DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS PetEvents (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                petId INTEGER NOT NULL,
                action TEXT NOT NULL,
                actor TEXT NOT NULL,
                dialogue TEXT NOT NULL,
                cost INTEGER DEFAULT 0,
                createdAt INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pet_events_pet_time ON PetEvents(petId, createdAt)")
    }

    fun getRecentChats(): List<RecentChat> {
        val list = mutableListOf<RecentChat>()
        try {
            val db = this.readableDatabase
            val cursor = db.rawQuery("""
    SELECT c.userId, c.realName, c.avatarUri, h.content, h.timestamp,
           IFNULL(c.isPinned, 0),
           (SELECT COUNT(*) FROM ChatHistory 
            WHERE aiId = c.userId 
            AND isFromMe = 0 
            AND IFNULL(isRead, 0) = 0 
            AND IFNULL(groupId,'') = '')
    FROM Contacts c
    LEFT JOIN ChatHistory h ON h.id = (
        SELECT id FROM ChatHistory
        WHERE aiId = c.userId
        AND IFNULL(groupId,'') = ''
        ORDER BY timestamp DESC, id DESC
        LIMIT 1
    )
    WHERE c.userId IS NOT NULL
    AND TRIM(c.userId) <> ''
    ORDER BY IFNULL(c.isPinned,0) DESC, COALESCE(h.timestamp, 0) DESC
""".trimIndent(), null)
            while (cursor.moveToNext()) {
                val userId = cursor.getString(0) ?: ""
                val realName = cursor.getString(1) ?: "联系人"
                val avatarUri = cursor.getString(2) ?: ""
                val lastMsg = cursor.getString(3) ?: "点击开始聊天"
                val ts = cursor.getLong(4)
                val isPinned = cursor.getInt(5) == 1
                val unread = cursor.getInt(6)
                val msgTime = if (ts > 0) java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts)) else ""
                list.add(RecentChat(userId, realName, avatarUri, lastMsg, msgTime, isGroup = false, isPinned = isPinned, unreadCount = unread))
            }
            cursor.close()
        } catch (_: Exception) {}
        return list
    }

    fun getRecentGroups(): List<RecentChat> {
        val list = mutableListOf<RecentChat>()
        try {
            val db = this.readableDatabase
            val cursor = db.rawQuery("""
            SELECT
                g.groupId,
                CASE
                    WHEN g.groupName IS NULL OR g.groupName='' OR g.groupName LIKE 'group_%' THEN '群聊'
                    ELSE g.groupName
                END AS showName,
                COALESCE(h.content, '群聊创建成功') AS lastMsg,
                COALESCE(h.timestamp, g.createdAt, 0) AS lastTs,
                IFNULL(g.avatarUri, '') AS avatarUri,
                IFNULL(g.isPinned, 0) AS isPinned,
                (SELECT COUNT(*) FROM ChatHistory 
                 WHERE groupId = g.groupId AND isFromMe = 0 
                 AND IFNULL(isRead, 0) = 0) AS unread
            FROM GroupChats g
            LEFT JOIN ChatHistory h
              ON h.id = (
                SELECT id
FROM ChatHistory
WHERE groupId = g.groupId
ORDER BY timestamp DESC, id DESC
LIMIT 1
              )
            WHERE IFNULL(g.isDisbanded, 0) = 0
              AND IFNULL(g.groupId, '') <> ''
            ORDER BY lastTs DESC
        """.trimIndent(), null)
            while (cursor.moveToNext()) {
                val groupId = cursor.getString(0) ?: ""
                val groupName = cursor.getString(1) ?: "群聊"
                val lastMsg = cursor.getString(2) ?: "群聊创建成功"
                val ts = cursor.getLong(3)
                val avatarUri = cursor.getString(4) ?: ""
                val isPinned = cursor.getInt(5) == 1
                val unread = cursor.getInt(6)
                val msgTime = if (ts > 0) java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts)) else ""
                if (groupId.isNotEmpty()) {
                    list.add(RecentChat(groupId, groupName, avatarUri, lastMsg, msgTime, isGroup = true, isPinned = isPinned, unreadCount = unread))
                }
            }
            cursor.close()
        } catch (_: Exception) {}
        return list
    }

}

data class RecentChat(
    val aiId: String,
    val aiName: String,
    val avatarUri: String,
    val lastMsg: String,
    val msgTime: String,
    val isGroup: Boolean = false,
    val isPinned: Boolean = false,
    val unreadCount: Int = 0
)
