# AI 小手机 - 角色卡导入与数据映射系统 (Character Card Parser)

## 1. 兼容性目标
系统需完整支持主流的角色卡格式：
* **SillyTavern V1 / V2 JSON 格式** (纯文本配置)
* **包含隐写数据的 PNG 图片** (通过解析 PNG 的 `tEXt` 或 `iTXt` 数据块读取内嵌的 JSON 设定)

## 2. C++ 层解析组件 (Parser Components)
* **JSON 解析**: 引入轻量级且高性能的 C++ JSON 库（如 `nlohmann/json`）。
* **PNG 解析**: 实现一个轻量的图片元数据读取器。用户在 Kotlin UI 层选择图片后，传入图片路径或字节流到 C++ JNI，C++ 提取出 JSON Payload。

## 3. 核心字段映射规则 (Data Mapping)
当外部卡片导入“小手机”时，数据将自动重组并写入 SQLite 预设库：

| SillyTavern 字段 | 小手机系统映射 (Virtual Phone Mapping) | 作用说明 |
| :--- | :--- | :--- |
| `name` | 联系人备注名 / App 注册名 | 决定 UI 上显示的昵称。 |
| `description` + `personality` | 核心人格 System Prompt | 转化为大模型的 System Message，决定 AI 的基本性格和设定。 |
| `scenario` | 当前系统状态 / 开场情景 | 写入 SQLite 的临时情景记忆（如：“你们正在网恋”）。 |
| `first_mes` | 第一条未读消息 | 作为用户打开“聊天 App”时，AI 已经发来的首条消息。 |
| `mes_example` | 聊天风格少样本 (Few-Shot) | 拼装在 Prompt 中，用于规范 AI 的说话口癖和格式。 |
| *(Image)* | 头像与朋友圈背景 | 解析出的 PNG 本身会被裁剪并保存为虚拟手机通讯录的头像。 |

## 4. 导入流程 (Workflow)
1. 用户在 Android 系统相册选择一张角色卡 PNG。
2. Kotlin 将文件流传递给 C++ `CardParser` 模块。
3. C++ 提取并验证 V2 JSON 规范，清洗数据。
4. 弹窗让用户二次确认信息（允许用户在此步将“中世纪骑士”修改为“现代霸总”以适配小手机语境）。
5. 确认后，将结构化数据写入 SQLite 的 `User_Bot_Presets` 表。