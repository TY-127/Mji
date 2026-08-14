# AI 小手机 - 项目初始化与 JNI 通信验证

## 1. 环境信息
* **Android Studio**: Ladybug (或你使用的版本)
* **NDK**: 默认版本
* **CMake**: 3.22.1+
* **C++ 标准**: C++ 17
* **UI 框架**: XML/ViewBinding (初始默认) -> 准备迁移至 Jetpack Compose

## 2. JNI 通信原理记录
本项目采用 JNI (Java Native Interface) 作为 Kotlin 和 C++ 的翻译官：
1. **Kotlin 定义接口**: 使用 `external` 关键字声明方法。
2. **C++ 实现接口**: 函数名必须严格遵循 `Java_包名_类名_方法名` 的规范。
3. **加载库**: 在 Kotlin 的 `companion object` 中使用 `System.loadLibrary("aiphone")` 加载生成的 .so 库。

## 3. 初始状态验证
* [x] 项目成功创建
* [x] CMake 编译链正常工作
* [x] 真机/模拟器成功运行并显示来自 C++ 的字符串