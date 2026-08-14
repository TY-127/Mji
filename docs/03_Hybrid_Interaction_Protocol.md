# AI 小手机 - 混合交互与指令协议设计 (Hybrid Interaction Protocol)

## 1. 交互模型架构
系统采用“双向监听-异步执行”机制：
* **Kotlin -> C++ (系统事件上报)**: 用户在 UI 上的操作被封装为 `SystemEvent` 写入 SQLite，并通知 C++ 核心。
* **C++ -> Kotlin (指令与文本回传)**: C++ 处理 LLM 返回的结构化数据，解析出 `Text`（对话内容）和 `Action`（系统指令）。

## 2. 数据结构定义

### 2.1 系统日志 (System Log - 方案 A)
当用户在“小手机”中执行非对话操作时，系统向 C++ 核心推送的隐性上下文：
```json
{
  "event_type": "USER_ACTION",
  "app": "FoodDelivery",
  "action": "ORDER_PLACED",
  "detail": "奶茶",
  "timestamp": 1714545600
}