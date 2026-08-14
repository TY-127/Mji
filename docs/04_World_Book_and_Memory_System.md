# AI 小手机 - 世界书与记忆系统设计 (World Book & Memory System)

## 1. 核心机制概述
采用传统的“关键词触发机制” (Keyword-driven Trigger)。C++ 核心在每次发送请求前，会扫描用户的当前输入及最近的 N 条聊天记录，寻找与“世界书词条 (Lorebook Entries)”绑定的触发词 (Keys)。

## 2. 数据表结构设计 (SQLite Schema - 节选)

### 2.1 世界书表 (Table: world_book)
* `id` (INTEGER): 主键
* `keys` (TEXT): 触发词组（以逗号分隔，支持简单的逻辑组合如 AND/NOT）
* `content` (TEXT): 触发后插入给 AI 的设定内容
* `insertion_order` (INTEGER): 插入顺序（决定该词条在最终 Prompt 中的位置深度）
* `is_constant` (BOOLEAN): 是否为常驻词条（无视触发词，永远生效）

## 3. C++ 核心处理管线 (Processing Pipeline)

1. **文本规范化**: C++ 接收到 Kotlin 传来的近期上下文后，将其转换为统一的小写形式，并去除多余标点符号，以提高匹配率。
2. **扫描与匹配**: 
   * 遍历 SQLite 中的 `keys` 字段。
   * 支持多级触发，例如扫描最近的 3 条消息（深度可配）。
3. **优先级与剪枝 (Token Management)**: 
   * 当触发的词条过多时，根据 `insertion_order` 进行排序。
   * C++ 层内置一个轻量级的 Tokenizer (例如基于正则表达式的近似 Token 计算器)，当上下文总长度逼近 LLM 上限时，优先剔除优先级低的历史记录或非关键词条。

## 4. 记忆系统 (Memory Management)
* **短期记忆**: 即最近的聊天记录 (Context Window)，作为 Prompt 的底部主体。
* **长期记忆 (Author's Note / Memory)**: 用户手动设定的全局记忆（如“我们现在正在吃晚饭”），由 C++ 直接强插在 System Prompt 的特定位置，确保 AI 不会跑题。