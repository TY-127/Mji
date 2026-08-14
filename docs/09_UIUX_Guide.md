# AI 小手机 - UI/UX 设计与视觉规范 (UI/UX Guidelines)

## 1. 视觉基调 (Visual Identity)
*(待定 - 将根据后续确认补充)*

## 2. 核心交互逻辑 (Core Interactions)
* **全局手势**: 采用现代智能手机的标准全面屏手势（底部上滑回桌面，边缘侧滑返回）。
* **沉浸模式**: 进入“AI 小手机”后，隐藏真实的宿主 Android 状态栏，渲染一套纯虚拟的顶部状态栏（包含虚拟时间、虚拟电量、虚拟信号等）。

## 3. UI 组件库 (Kotlin Jetpack Compose / XML)
* 建议全面采用 **Jetpack Compose** 进行构建，以便于快速实现复杂的自定义动画和高仿真的系统级转场效果。
* 构建统一的 `VirtualOSTheme`，支持一键切换“深色模式 (Dark Mode)”和“浅色模式 (Light Mode)”，并与系统的虚拟时间系统（`Time Engine`）绑定（例如：虚拟时间晚上 8 点自动切换为深色模式）。