package com.moon.aiphone

data class Message(
    var content: String,
    var isFromMe: Boolean,
    var isRead: Boolean,
    var isSystem: Boolean,
    var isVoice: Boolean = false,
    var voiceDuration: Int = 0,
    var localVoicePath: String? = null,
    var translatedText: String? = null,
    var isPlaying: Boolean = false,
    var innerThoughts: String = "",
    var timestamp: Long = System.currentTimeMillis(),
    var dbId: Long = 0,
    var imageDesc: String? = null,
    var isSelected: Boolean = false// ⚡ 极其致命的护身符！有了它多模态图片绝对不飘红！
)