# AI 小手机 - 时间流引擎与主动事件触发系统 (Time & Event System)

## 1. 虚拟时间引擎 (Virtual Time Engine)
系统采用“时间基准点 + 缩放倍率”的算法来管理小手机的虚拟时间。

* **真实同步模式 (Real-time Sync)**: 时间倍率 `TimeScale = 1.0`。小手机时间与现实物理时间完全绑定。
* **沙盒加速模式 (Sandbox Mode)**: 时间倍率 `TimeScale = N` (例如 N=6 时，现实 4 小时 = 虚拟 24 小时)。
* **C++ 计算逻辑**: 
  `VirtualTime = BaseRealTime + (CurrentRealTime - BaseRealTime) * TimeScale`
  系统所有的外卖送达、天气变化、日历提醒，均依赖 `VirtualTime` 进行结算。

## 2. 状态衰减与羁绊系统 (Status Decay)
为了驱动 AI 主动寻找用户，C++ 逻辑层在 SQLite 中维护角色的“隐藏状态数值”（如：孤独度、饥饿度、表达欲）。
* 随 `VirtualTime` 的流逝，这些数值会发生变化。
* 例如：用户超过 12 小时（虚拟时间）未互动，AI 的“孤独度”上升，增加其主动发消息的概率。

## 3. 主动事件调度器 (Proactive Scheduler)
这是 C++ 层面的“定时任务”系统，负责在没有用户输入的情况下，主动调用 LLM。

### 3.1 触发器类型 (Triggers)
* **计划任务 (Scheduled)**: 设定的闹钟、日历备忘录（如：“明天早上 8 点叫我起床”）。
* **阈值触发 (Threshold)**: 上述的孤独度达到阈值，或好感度升级。
* **随机灵感 (Random Epiphany)**: 引擎以极低的概率随机唤醒 AI，结合当前虚拟天气和时间，让 AI 发一条朋友圈或找用户闲聊。

### 3.2 Android 端实现 (Kotlin)
* 使用 Android `WorkManager` 或 `AlarmManager` 设定后台周期性任务。
* 当触发时，后台唤醒 C++ 引擎。
* C++ 引擎拼装特定的 Prompt (例如：`[系统指令：现在是深夜，下着雨，你有一点想念用户，请决定是否发一条朋友圈或发短信，如果决定发，请输出对应的 action 指令]`)。
* LLM 返回结果后，C++ 解析出 `<action>`，并触发系统的本地通知 (Local Notification) 提醒用户。