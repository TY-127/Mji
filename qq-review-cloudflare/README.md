# QQ 入群审核 · Cloudflare 多人管理版

系统只使用 Cloudflare 原生能力，不需要任何 AI 或第三方 API：

- Workers + Static Assets：申请页面、管理后台和 API
- D1：保存申请、管理员、审核结果和操作日志
- R2：临时保存待审核截图，审核完成后自动删除原图
- Cloudflare Access：管理员邮箱登录和身份验证
- Rate Limiting：限制重复提交
- Turnstile（可选）：阻止机器人批量提交

管理后台地址为 `/admin/`。支持多个独立管理员账号，并区分主管理员和普通审核员。

## 部署前准备

需要一个 Cloudflare 账户和一个已经接入 Cloudflare 的域名。建议先撤销旧压缩包中的 Anthropic API Key，不要上传旧 `.env` 和 QQ 数据文件。

## 第一次部署

### 1. 安装并登录

```bash
npm install
npx wrangler login
```

### 2. 创建 D1 和 R2

```bash
npx wrangler d1 create qq-review
npx wrangler r2 bucket create qq-review-materials
npx wrangler r2 bucket create qq-review-materials-preview
```

把 D1 命令返回的 `database_id` 填入 `wrangler.jsonc`，替换全零占位值。

### 3. 配置管理员和域名

在 `wrangler.jsonc` 中修改：

- `OWNER_EMAIL`：第一个主管理员邮箱。
- `CF_ACCESS_TEAM_DOMAIN`：Zero Trust 团队域名，例如 `https://example.cloudflareaccess.com`。
- `CF_ACCESS_AUD`：下面创建的 Access Application AUD。
- 群号变量和 Turnstile Site Key（如需）。

同时在配置中增加你的自定义域名，例如：

```json
"routes": [
  { "pattern": "review.example.com", "custom_domain": true }
]
```

### 4. 配置 Cloudflare Access

在 Zero Trust 控制台创建 Self-hosted Application：

- Domain：你的审核网站域名。
- Path：`/admin/*`。
- Policy：只允许指定管理员邮箱，或使用 One-time PIN 邮箱验证码。

再创建一个相同策略、Path 为 `/api/admin/*` 的应用，或者在同一应用中添加第二个路径。将应用 AUD 填入 `CF_ACCESS_AUD`；如果两个应用的 AUD 不同，用英文逗号连接两个值。

Worker 会自行验证 Access JWT 的签名、有效期和 AUD，因此伪造邮箱请求无法进入后台。

### 5. 初始化数据库和密钥

```bash
npm run db:remote
npx wrangler secret put SESSION_SECRET
```

`SESSION_SECRET` 使用至少 32 位的随机字符串。

如启用 Turnstile，再执行：

```bash
npx wrangler secret put TURNSTILE_SECRET_KEY
```

### 6. 部署

```bash
npm run deploy
```

首次访问 `/admin/` 时，`OWNER_EMAIL` 对应账号会自动注册为主管理员。主管理员可以在后台添加、停用其他审核员。

## 审核流程

1. 申请人通过两套问卷并上传三项截图。
2. 截图进入 R2，D1 状态变为“待审核”。
3. 管理员从后台查看截图并通过或拒绝。
4. 审核结果、管理员邮箱、时间和原因写入 D1。
5. 无论通过还是拒绝，截图原件立即从 R2 删除。
6. 三项全部通过后，申请人刷新状态即可看到群号。

多人同时操作时，第一位提交决定的管理员生效；后续重复操作会收到“已由其他管理员处理”的提示。

## 权限

- 普通审核员：查看队列、查看截图、通过或拒绝。
- 主管理员：拥有普通审核员权限，还可以添加、停用管理员和设置角色。
- `OWNER_EMAIL` 永远会恢复为有效主管理员，防止所有管理员被误停用。

## 本地开发

```bash
copy .dev.vars.example .dev.vars
npm run db:local
npm run dev
```

Cloudflare Access JWT 在本地环境不容易模拟，申请人流程可以本地测试；管理后台建议部署到预览域名并配置单独的 Access Application 测试。

## 隐私和安全

- 每个 QQ 号只有一次浓度测试记录。
- 最终完成接口只读取 D1，不接受客户端自行声称审核通过。
- 图片限制为 4MB，不写入 D1 或日志。
- 图片仅在待审核期间保存在私有 R2，审核完成立即删除。
- 管理员身份通过 Cloudflare Access JWT 验证，不信任客户端传入的邮箱头。
- 页面启用了 CSP、防嵌套和禁止 MIME 猜测等安全响应头。

成年证明属于敏感材料。正式开放前请确认页面隐私告知符合实际使用地区要求，并限制 Access 只允许可信管理员邮箱。
