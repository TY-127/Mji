"use strict";

const http = require("node:http");
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const { concentrationQuestions, introQuestions } = require("./questions");

loadEnv(path.join(__dirname, ".env"));

const port = Number(process.env.PORT || 3000);
const publicDir = path.join(__dirname, "public");
const dataDir = path.join(__dirname, "data");
const attemptsFile = path.join(dataDir, "concentration-attempts.json");
const secret = process.env.SESSION_SECRET || crypto.randomBytes(32).toString("hex");
const maxBodyBytes = 12 * 1024 * 1024;
fs.mkdirSync(dataDir, { recursive: true });

function loadEnv(file) {
  if (!fs.existsSync(file)) return;
  for (const line of fs.readFileSync(file, "utf8").split(/\r?\n/)) {
    const match = line.match(/^([A-Z0-9_]+)=(.*)$/);
    if (match && !process.env[match[1]]) process.env[match[1]] = match[2].trim();
  }
}

function json(res, status, payload) {
  res.writeHead(status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" });
  res.end(JSON.stringify(payload));
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on("data", (chunk) => {
      size += chunk.length;
      if (size > maxBodyBytes) {
        reject(new Error("上传内容过大"));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => {
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}"));
      } catch {
        reject(new Error("请求格式错误"));
      }
    });
    req.on("error", reject);
  });
}

function scoreQuiz(questions, answers) {
  let correct = 0;
  questions.forEach((question, index) => {
    const value = answers?.[index];
    if (question.type === "text") {
      const normalize = (text) => String(text || "").replace(/\s+/g, "").replace(/[，。！？、,.!?]/g, "").toLowerCase();
      if (normalize(value) === normalize(question.answerText)) correct += 1;
    } else if (Number(value) === question.answer) {
      correct += 1;
    }
  });
  return correct;
}

function readAttempts() {
  try { return JSON.parse(fs.readFileSync(attemptsFile, "utf8")); }
  catch { return {}; }
}

function writeAttempts(attempts) {
  const temp = `${attemptsFile}.tmp`;
  fs.writeFileSync(temp, JSON.stringify(attempts, null, 2), "utf8");
  fs.renameSync(temp, attemptsFile);
}

function attemptKey(qq, fingerprint) {
  return crypto.createHash("sha256").update(`${qq}|${fingerprint}|${secret}`).digest("hex");
}

function signResult(payload) {
  const body = Buffer.from(JSON.stringify(payload)).toString("base64url");
  const signature = crypto.createHmac("sha256", secret).update(body).digest("base64url");
  return `${body}.${signature}`;
}

function verifyResult(token) {
  const [body, signature] = String(token || "").split(".");
  if (!body || !signature) return null;
  const expected = crypto.createHmac("sha256", secret).update(body).digest("base64url");
  if (signature.length !== expected.length || !crypto.timingSafeEqual(Buffer.from(signature), Buffer.from(expected))) return null;
  const payload = JSON.parse(Buffer.from(body, "base64url").toString("utf8"));
  if (Date.now() - payload.createdAt > 60 * 60 * 1000) return null;
  return payload;
}

async function inspectImage(dataUrl, kind) {
  const apiKey = process.env.ANTHROPIC_API_KEY;
  if (!apiKey) throw new Error("服务器尚未配置 Claude API Key");
  const match = String(dataUrl || "").match(/^data:(image\/(?:jpeg|png|webp));base64,(.+)$/);
  if (!match) throw new Error("请上传 JPG、PNG 或 WebP 图片");

  const prompts = {
    follow: `判断截图是否为小红书或抖音账号”观南”的主页，并且显示已关注状态。
小红书：关注按钮显示”已关注”即可通过。
抖音：关注按钮显示”已关注”或”互相关注”均可通过；抖音主页关注按钮文字可能有多种样式，只要能判断为已关注状态即通过。
账号名称必须为”观南”。只返回 JSON：{“pass”:true或false,”reason”:”一句中文原因”}。`,
    adult: `判断截图能否可靠证明账号持有人已满18岁。以下任一种可通过：
1. 腾讯健康系统明确显示”成年”或”实名认证为成年人”；
2. 微信个人信息页面清晰显示出生日期，按当前日期计算已满18岁。
只返回 JSON：{“pass”:true或false,”reason”:”一句中文原因”}。不得根据头像、姓名、性别或外貌猜年龄。`,
    voice: `判断截图是否显示用户在QQ群聊中发送了语音消息。满足以下条件可通过：截图界面为QQ群聊，右侧（己方发送）有一条语音消息气泡。只返回 JSON：{“pass”:true或false,”reason”:”一句中文原因”}。私聊截图不通过，非语音消息不通过。`
  };

  const response = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "x-api-key": apiKey,
      "anthropic-version": "2023-06-01"
    },
    body: JSON.stringify({
      model: process.env.ANTHROPIC_MODEL || "claude-haiku-4-5-20251001",
      max_tokens: 180,
      temperature: 0,
      messages: [{
        role: "user",
        content: [
          { type: "image", source: { type: "base64", media_type: match[1], data: match[2] } },
          { type: "text", text: prompts[kind] }
        ]
      }]
    })
  });
  if (!response.ok) throw new Error(`图片识别服务错误：${response.status}`);
  const result = await response.json();
  const text = result.content?.find((item) => item.type === "text")?.text || "";
  const object = text.match(/\{[\s\S]*\}/)?.[0];
  if (!object) throw new Error("图片识别结果无法解析");
  return JSON.parse(object);
}

async function api(req, res, pathname) {
  if (req.method === "GET" && pathname === "/api/questions") {
    return json(res, 200, {
      concentration: concentrationQuestions.map(({ answer, answerText, ...question }) => question),
      intro: introQuestions.map(({ answer, answerText, ...question }) => question)
    });
  }

  if (req.method === "POST" && pathname === "/api/concentration") {
    const body = await readJson(req);
    const qq = String(body.qq || "").trim();
    const fingerprint = String(body.fingerprint || "").trim();
    if (!/^\d{5,12}$/.test(qq) || !/^[a-f0-9]{64}$/i.test(fingerprint)) return json(res, 400, { error: "申请信息无效" });
    const attempts = readAttempts();
    const key = attemptKey(qq, fingerprint);
    if (attempts[key]) {
      if (attempts[key].passed) {
        return json(res, 200, {
          locked: true,
          passed: true,
          concentration: attempts[key].score,
          resumed: true,
          token: signResult({ qq, concentration: attempts[key].score, fingerprint, kind: "concentration", createdAt: Date.now() })
        });
      }
      return json(res, 409, {
        locked: true,
        passed: false,
        score: attempts[key].score,
        error: "浓度测试仅有一次机会，该申请未通过。"
      });
    }
    const concentration = scoreQuiz(concentrationQuestions, body.concentration);
    const passed = concentration >= 8;
    attempts[key] = { qq, score: concentration, passed, submittedAt: new Date().toISOString() };
    writeAttempts(attempts);
    return json(res, 200, {
      passed,
      locked: true,
      concentration,
      token: passed ? signResult({ qq, concentration, fingerprint, kind: "concentration", createdAt: Date.now() }) : null
    });
  }

  if (req.method === "POST" && pathname === "/api/intro") {
    const body = await readJson(req);
    const concentrationResult = verifyResult(body.concentrationToken);
    if (!concentrationResult || concentrationResult.kind !== "concentration") return json(res, 400, { error: "请先通过浓度测试" });
    const introCorrect = scoreQuiz(introQuestions, body.intro);
    const introScore = Math.round((introCorrect / introQuestions.length) * 100);
    const passed = introScore >= 80;
    return json(res, 200, {
      passed,
      introScore,
      token: passed ? signResult({
        qq: concentrationResult.qq,
        concentration: concentrationResult.concentration,
        introScore,
        fingerprint: concentrationResult.fingerprint,
        kind: "quizzes",
        createdAt: Date.now()
      }) : null
    });
  }

  if (req.method === "POST" && pathname === "/api/verify-image") {
    const body = await readJson(req);
    if (!["follow", "adult", "voice"].includes(body.kind)) return json(res, 400, { error: "未知材料类型" });
    const result = await inspectImage(body.image, body.kind);
    return json(res, 200, result);
  }

  if (req.method === "POST" && pathname === "/api/complete") {
    const body = await readJson(req);
    const quiz = verifyResult(body.quizToken);
    if (!quiz || quiz.kind !== "quizzes" || body.follow !== true || body.adult !== true || body.voice !== true) {
      return json(res, 400, { passed: false, error: "审核材料未全部通过" });
    }
    return json(res, 200, {
      passed: true,
      groups: {
        required: "595862009",
        optional: "1103798415"
      }
    });
  }

  return json(res, 404, { error: "Not found" });
}

const server = http.createServer(async (req, res) => {
  const pathname = new URL(req.url, `http://${req.headers.host || "localhost"}`).pathname;
  try {
    if (pathname.startsWith("/api/")) return await api(req, res, pathname);
    const requested = pathname === "/" ? "index.html" : pathname.slice(1);
    const file = path.resolve(publicDir, requested);
    if (!file.startsWith(publicDir) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
      res.writeHead(404);
      return res.end("Not found");
    }
    const ext = path.extname(file);
    const types = { ".html": "text/html; charset=utf-8", ".css": "text/css; charset=utf-8", ".js": "text/javascript; charset=utf-8" };
    res.writeHead(200, { "content-type": types[ext] || "application/octet-stream" });
    fs.createReadStream(file).pipe(res);
  } catch (error) {
    json(res, 500, { error: error.message || "服务器错误" });
  }
});

server.listen(port, "0.0.0.0", () => {
  console.log(`QQ review web listening on http://0.0.0.0:${port}`);
});
