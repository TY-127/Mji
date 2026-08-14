# M叽

M叽是一款 Android AI 角色互动应用，包含单聊、群聊、语音通话、长期记忆、角色卡、TTS/STT、图片生成和远程 MCP 工具等功能。

> Required Notice: Copyright 2026 w1990709164-bot. Official M叽 repository: https://github.com/w1990709164-bot/Mji

## 唯一官方来源

- 源代码与发布页：<https://github.com/w1990709164-bot/Mji>
- 从其他网站、网盘、群聊或付费店铺获得的版本均不是官方发布。
- 请通过 GitHub Release 中公布的文件摘要核对 APK 完整性。

## 许可证与防倒卖说明

本项目以 **PolyForm Noncommercial License 1.0.0** 提供源码，允许个人学习、研究、修改和其他非商业用途。

未经版权所有者另行书面许可，不允许商业使用、收费分发、倒卖、付费打包、商业托管或以本项目为基础提供收费服务。完整条款见 [LICENSE](LICENSE)，再分发时必须保留 [NOTICE](NOTICE) 和所有 `Required Notice:` 行。

这是一份“源码可见、非商业”许可，不是 OSI 定义下允许所有商业用途的开源许可证。

## 构建

面向普通用户的完整步骤见：[下载、编译与安装简易教程](docs/下载编译安装教程.md)。

想学习如何从零制作同类应用，请阅读：[从零制作一个像 M叽一样的 AI 小手机](docs/从零制作AI小手机教程.md)。

1. 安装 Android Studio、Android SDK 和 JDK 11+。
2. 在项目根目录配置本机 `local.properties`，不要提交该文件。
3. 运行：

```powershell
.\gradlew.bat :app:assembleDebug
```

所有 LLM、TTS、STT、图片生成和 MCP 凭据均应由使用者在应用设置中自行配置。不要把 `.env`、API Key、签名文件或 `google-services.json` 提交到仓库。

## 隐私提醒

部分功能会把用户输入、音频、图片或工具参数发送到使用者配置的第三方服务。使用和分发前请阅读代码、核对服务地址，并根据当地法律提供适当的隐私说明与用户同意。
