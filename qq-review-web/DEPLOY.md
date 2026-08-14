# 部署到腾讯云轻量服务器

## 1. 上传目录

在腾讯云轻量服务器控制台打开「文件管理」，进入 `/root`，上传整个 `qq-review-web` 目录。

## 2. 配置

复制 `.env.example` 为 `.env`，填写：

- `ANTHROPIC_API_KEY`：Anthropic 官方 API Key。
- `SESSION_SECRET`：任意至少 32 位随机字符串。
- `ANTHROPIC_MODEL`：默认保持 `claude-haiku-4-5-20251001`。

不要把 `.env` 发给别人或截图。

## 3. 启动

在腾讯云「执行命令」依次运行：

```bash
cd /root/qq-review-web
npm run check
nohup npm start > review-web.log 2>&1 &
```

## 4. 开放端口

在轻量服务器「防火墙」添加 TCP 端口 `3000`。

测试地址：

```text
http://服务器公网IP:3000
```

正式开放前应配置域名和 HTTPS；浏览器麦克风在公网环境通常要求 HTTPS。

## 5. 停止服务

```bash
pkill -f "node server.js"
```
