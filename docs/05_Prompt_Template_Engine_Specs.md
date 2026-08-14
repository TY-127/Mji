# AI 小手机 - 提示词模板引擎设计 (Prompt Template Engine)

## 1. 设计目标
C++ 核心层不硬编码任何特定模型的指令格式。通过解析存储在 SQLite 中的“模板字符串”，在运行时动态拼装出符合特定 LLM 要求的最终 Prompt。

## 2. 核心占位符 (Placeholders)
模板引擎通过替换以下宏来构建内容：
* `{{system_prompt}}`: 系统的核心指令（如角色设定、行为边界）。
* `{{world_book}}`: 经关键词匹配后筛选出的世界书条目集合。
* `{{memory}}`: 长期记忆/作者附言。
* `{{chat_history}}`: 格式化后的历史对话上下文。
* `{{user_input}}`: 当前用户发送的内容。
* `{{system_events}}`: 方案 A 提到的系统日志（如：[用户刚刚点了一份外卖]）。

## 3. 模板示例 (Template Examples)

### 3.1 ChatML 格式 (适用于 Qwen, Llama 3 等)
```text
<|im_start|>system
{{system_prompt}}
{{world_book}}
{{memory}}<|im_end|>
{{chat_history}}
<|im_start|>user
{{system_events}}
{{user_input}}<|im_end|>
<|im_start|>assistant