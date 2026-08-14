# AI 小手机 - 项目目录结构与构建系统 (Build System & Structure)

## 1. 构建系统 (Build System)
* **前端与 Android 壳**: `Gradle` (使用 Kotlin DSL `build.gradle.kts`)
* **C++ 核心层**: `CMake` (通过 `CMakeLists.txt` 组织编译)
* **桥接技术**: JNI (Java Native Interface)

## 2. 核心物理目录结构 (Directory Tree)
当在 Android Studio 中创建包含 "Native C++" 的项目时，标准结构如下，我们将严格按照这个规范填入我们的业务逻辑：

```text
AI_Virtual_Phone/
├── docs/                        # 我们的所有设计文档存放在这里
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml
│   │   │   ├── java/com/aiphone/  # (或 kotlin/) Kotlin UI 与系统接口层
│   │   │   │   ├── ui/            # Jetpack Compose 界面代码
│   │   │   │   ├── network/       # OkHttp/Retrofit 网络请求代码
│   │   │   │   ├── jni/           # JNI 接口类 (Native 方法声明)
│   │   │   │   └── service/       # 后台任务与时间引擎保活
│   │   │   │
│   │   │   ├── cpp/               # C++ 核心逻辑层
│   │   │   │   ├── CMakeLists.txt # C++ 构建脚本
│   │   │   │   ├── bridge/        # JNI C++ 实现 (接收 Kotlin 调用)
│   │   │   │   ├── core/          # 核心大脑 (Prompt组装, 时间引擎)
│   │   │   │   ├── db/            # SQLite 数据库交互封装
│   │   │   │   └── third_party/   # 第三方 C++ 库 (JSON, SQLite)