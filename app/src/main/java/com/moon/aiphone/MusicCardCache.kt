package com.moon.aiphone

object MusicCardCache {
    private val cache = LinkedHashMap<String, String>(100, 0.75f, true)
    fun put(songId: String, base64: String) {
        cache[songId] = base64

        while (cache.size > 100) {
            val firstKey = cache.entries.firstOrNull()?.key ?: break
            cache.remove(firstKey)
        }
    }fun clear() {
        cache.clear()
    }
    fun get(songId: String): String = cache[songId] ?: ""
}