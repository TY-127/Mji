# AI 小手机 - 高保真 UI 架构与实现规范 (High-Fidelity UI Architecture)

## 1. 核心渲染策略 (Rendering Strategy)
* **全屏沉浸 (Immersive Mode)**: App 启动后强制隐藏宿主（真实手机）的状态栏 (Status Bar) 和导航栏 (Navigation Bar)。
* **UI 框架**: 采用 Kotlin Jetpack Compose。其声明式 UI 特性非常适合构建复杂的、具有高帧率动画的系统级界面（如控制中心的下拉模糊效果、App 打开时的缩放动画）。

## 2. 虚拟系统组件 (Virtual System Components)

### 2.1 虚拟状态栏 (Virtual Status Bar)
* 实时接管并渲染虚拟时间（由 C++ 时间引擎驱动）。
* 模拟网络信号、Wi-Fi 状态（可以根据虚拟环境的天气或好感度甚至剧情发生变化，比如“处于虚拟的地下室，信号变弱”）。
* 模拟电量（与虚拟时间的流逝绑定）。

### 2.2 虚拟桌面与锁屏 (Launcher & Lock Screen)
* **锁屏**: 高仿 iOS/Android 锁屏，支持显示来自虚拟 App 的未读通知堆叠。
* **桌面**: 提供网格化的 App 图标排列。初期固定内置我们确定的几个 App（聊天、朋友圈、外卖、备忘录等）。

### 2.3 核心虚拟应用 (Core Apps UI)
* **Chat (仿微信/iMessage)**: 包含对话气泡、时间戳、语音条渲染、输入法面板高度适配。
* **Moments (仿朋友圈/Ins)**: 包含瀑布流/列表滚动、点赞动效、大图预览功能。

## 3. 动画与手势 (Animations & Gestures)
* 实现系统级非线性动画（Spring Animation），确保打开/关闭虚拟 App 时的阻尼感和回弹感与真实系统一致。
* 拦截边缘滑动事件，确保在虚拟 OS 内的滑动返回不会误触发真实手机的返回动作。