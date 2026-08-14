# AI 小手机 - API 配置与本地安全策略 (API & BYOK Architecture)

## 1. 核心定位
本应用为纯本地运行的客户端 (Client-side Engine)，所有逻辑计算在端侧 (C++ NDK) 完成，不依赖开发者提供的中心化业务服务器。大模型能力由用户自行配置的第三方 API 驱动。

## 2. 虚拟设置系统 (Virtual Settings App)
在小手机的桌面上，提供一个“系统设置” App，包含以下 API 配置模块：

### 2.1 供应商支持 (API Providers)
* **OpenAI 兼容模式 (Standard)**: 支持填入 Base URL 和 API Key。这是最重要的选项，因为市面上绝大多数开源模型 API (如 DeepSeek, Qwen, 各种中转 API) 都完全兼容 OpenAI 的请求格式。
* **Claude / Anthropic**: 支持专有请求格式。
* **自定义反向代理 (Reverse Proxy)**: 允许用户填入非标准的代理地址。

### 2.2 模型与参数调节 (Model Tuning)
* **Context Size (上下文长度)**: 用户可根据自己的模型能力，手动设定最大 Token 限制（如 4K, 8K, 32K）。C++ 的 `Tokenizer` 会读取此设置进行动态截断。
* **Temperature / Top_P**: 暴露这些生成参数，允许用户调节 AI 的“随机性”和“创造力”。

## 3. 本地存储安全 (Security)
由于 API Key 具有极高的经济价值，绝对不能明文存储在普通的 SQLite 表中。
* **加密方案**: Kotlin 层使用 Android 官方的 `EncryptedSharedPreferences` 或 `Android Keystore System` 对用户的 API Key 进行高强度 AES 加密。
* **解密流转**: 只有在 C++ 引擎准备发起网络请求的瞬间，Kotlin 才解密 Key 并将其置入 HTTP Header 中，请求结束后立即在内存中销毁引用。

## 4. 错误处理与 UI 反馈 (Error Handling)
当用户的 API 余额不足或网络超时时，系统不应直接崩溃，而是通过“小手机”的系统级通知（如：“您的虚拟网络连接似乎断开了”）或在聊天界面显示红色的感叹号，并引导用户前往“虚拟设置”检查 API 状态。