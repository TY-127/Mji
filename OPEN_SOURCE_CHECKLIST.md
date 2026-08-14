# M叽开源发布检查清单

## 发布前必须完成

- 选择并添加根目录 `LICENSE`。不要假设第三方素材会自动跟随代码许可证。
- 轮换所有曾写入 `.env`、聊天记录、构建日志或历史提交的 API Key、Token 和 Session Secret。
- 确认 `git status` 中没有 `.env`、`google-services.json`、签名文件、APK、数据库、聊天导出或用户图片。
- 使用 secret scanner 检查当前文件和完整 Git 历史；仅加入 `.gitignore` 无法清除历史提交中的秘密。
- 单独审核 `app/src/main/assets` 下的 HTML、音频、图片、字体、角色设定和游戏素材是否允许再分发。
- 审核应用名称、图标、角色姓名、第三方服务名称和商标使用权。
- 为联网功能补充隐私说明：发送给 LLM、TTS、STT、图片生成、MCP 和网页服务的数据类型。

## 已在开源版移除或调整

- Firebase 邮箱登录、注册、密码重置、Firestore 授权、设备绑定和远程封禁。
- `google-services.json` 与 Firebase Gradle 依赖。
- 私人 TTS 服务器已获服务所有者同意保留，并允许用户在设置页覆盖地址。
- `.env`、密钥文件、签名文件和构建产物的 Git 忽略规则。

## 建议继续处理

- 将 `android:usesCleartextTraffic="true"` 改为 `false`；如果必须支持局域网 HTTP，使用仅 Debug 生效的网络安全配置。
- 减少 `MANAGE_EXTERNAL_STORAGE`、旧版外部存储和 Usage Stats 等高权限，按功能请求并解释用途。
- MCP OAuth Token、API Key 当前存放于 SharedPreferences；发布前建议迁移到 Android Keystore/EncryptedSharedPreferences。
- 给网络请求统一增加隐私提示、超时、域名展示和数据发送确认。
- 增加依赖许可证清单和第三方素材署名文件。
