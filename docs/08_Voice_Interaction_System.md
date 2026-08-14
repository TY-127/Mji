# AI 小手机 - 语音交互系统设计 (Voice Message System)

## 1. 业务流程 (Workflow)

### 1.1 用户发送语音
1. **Kotlin (采集)**: 用户长按录音键，Kotlin 调用 Android `AudioRecord` 采集音频。
2. **Kotlin (STT)**: 调用 Android 原生语音识别或第三方 STT API 将语音转为文本。
3. **下沉**: Kotlin 将 `[文本内容]` 和 `[音频文件本地路径]` 通过 JNI 传给 C++。
4. **C++ (处理)**: C++ 将其作为用户输入，结合上下文拼装 Prompt 发送给 LLM。

### 1.2 AI 回复语音
1. **C++ (接收)**: 接收到 LLM 返回的文本。
2. **上浮**: C++ 将文本传回 Kotlin，并带上 `needs_tts` 标记。
3. **Kotlin (TTS)**: 调用 TTS 引擎（如 Edge TTS, OpenAI TTS 或本地 VITS）生成音频文件。
4. **回写**: Kotlin 将生成的音频路径告知 C++，C++ 更新 SQLite 中的消息记录。

## 2. 存储设计 (Persistence)
在 SQLite 的 `messages` 表中增加语音相关字段：
* `audio_path` (TEXT): 语音文件在手机存储中的绝对路径。
* `duration` (INTEGER): 语音时长（秒），用于 UI 渲染波形条长度。
* `is_played` (BOOLEAN): 针对 AI 发来的语音，标记用户是否已点击播放。

## 3. 技术栈建议
* **STT**: 建议初期使用系统原生 `SpeechRecognizer` 或腾讯/阿里的云端 API。
* **TTS**: 
    * 追求品质：使用 OpenAI/Azure 的云端 TTS。
    * 追求个性化：集成轻量级 VITS 模型（C++ 实现的推理后端，可实现特定角色的音色）。
* **音频格式**: 统一使用 `Opus` 或 `MP3`，兼顾压缩率与跨平台兼容性。

## 4. UI 表现
* 模仿主流社交 App 的语音气泡。
* 点击气泡，Kotlin 触发 `MediaPlayer` 播放，C++ 更新“已读”状态。