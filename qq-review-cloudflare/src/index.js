import { concentrationQuestions, introQuestions } from "./questions.js";
import { publicQuestions, scoreQuiz, signToken, verifyToken } from "./core.js";

const MAX_IMAGE_BYTES = 1800000;
const PASSWORD_ITERATIONS = 100000;
const MATERIAL_COLUMNS = {
  adult: "adult_passed",
  follow: "follow_passed",
  voice: "voice_passed"
};

function securityHeaders(headers = {}) {
  return {
    "content-security-policy": "default-src 'self'; script-src 'self' https://challenges.cloudflare.com; frame-src https://challenges.cloudflare.com; connect-src 'self' https://challenges.cloudflare.com; img-src 'self' data: blob:; style-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'",
    "cross-origin-opener-policy": "same-origin",
    "referrer-policy": "no-referrer",
    "x-content-type-options": "nosniff",
    "x-frame-options": "DENY",
    "permissions-policy": "camera=(), microphone=(), geolocation=()",
    ...headers
  };
}

function json(payload, status = 200, extraHeaders = {}) {
  return Response.json(payload, {
    status,
    headers: securityHeaders({ "cache-control": "no-store", ...extraHeaders })
  });
}

function fail(message, status = 400) {
  const error = new Error(message);
  error.status = status;
  throw error;
}

function errorResponse(error) {
  const status = Number(error?.status) || 500;
  if (status >= 500) console.error(error);
  return json({ error: status >= 500 ? "服务器暂时无法处理请求，请稍后重试" : error.message }, status);
}

function ensureSameOrigin(request) {
  const origin = request.headers.get("origin");
  if (origin && origin !== new URL(request.url).origin) fail("请求来源无效", 403);
}

async function readBody(request) {
  if (!(request.headers.get("content-type") || "").includes("application/json")) fail("请求格式错误", 415);
  try { return await request.json(); }
  catch { fail("请求格式错误"); }
}

async function validateTurnstile(request, env, token) {
  if (!env.TURNSTILE_SECRET_KEY) return;
  if (!token || token.length > 2048) fail("请先完成人机验证");
  const result = await fetch("https://challenges.cloudflare.com/turnstile/v0/siteverify", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      secret: env.TURNSTILE_SECRET_KEY,
      response: token,
      remoteip: request.headers.get("CF-Connecting-IP") || undefined,
      idempotency_key: crypto.randomUUID()
    })
  }).then((response) => response.json());
  if (!result.success) fail("人机验证失败，请刷新后重试");
}

async function requireToken(env, token, expectedStage) {
  const payload = await verifyToken(env.SESSION_SECRET, token);
  if (!payload || payload.stage !== expectedStage || !payload.id || !payload.qq) {
    fail("审核凭证无效或已过期，请重新开始", 401);
  }
  return payload;
}

async function getApplication(env, id, qq) {
  return env.DB.prepare(
    `SELECT id, qq, concentration_score, concentration_passed, intro_score, quiz_passed,
            adult_passed, follow_passed, voice_passed, completed_at
       FROM applications WHERE id = ? AND qq = ?`
  ).bind(id, qq).first();
}

async function handleConcentration(request, env) {
  const body = await readBody(request);
  const qq = String(body.qq || "").trim();
  if (!/^\d{5,12}$/.test(qq)) fail("请输入正确的 QQ 号");
  if (!Array.isArray(body.concentration) || body.concentration.length !== concentrationQuestions.length) {
    fail("请答完全部浓度测试题");
  }
  await validateTurnstile(request, env, body.turnstileToken);
  if (!(await env.SUBMIT_RATE_LIMITER.limit({ key: `qq:${qq}` })).success) {
    fail("提交过于频繁，请一分钟后再试", 429);
  }
  const existing = await env.DB.prepare(
    "SELECT id, concentration_score, concentration_passed FROM applications WHERE qq = ?"
  ).bind(qq).first();
  if (existing) {
    if (!existing.concentration_passed) {
      return json({ locked: true, passed: false, concentration: existing.concentration_score, error: "浓度测试仅有一次机会，该 QQ 申请未通过。" }, 409);
    }
    return json({
      locked: true, passed: true, concentration: existing.concentration_score, resumed: true,
      token: await signToken(env.SESSION_SECRET, { id: existing.id, qq, stage: "concentration" }, 7200)
    });
  }
  const score = scoreQuiz(concentrationQuestions, body.concentration);
  const passed = score >= 8;
  const id = crypto.randomUUID();
  try {
    await env.DB.prepare(
      `INSERT INTO applications (id, qq, concentration_score, concentration_passed, created_at, updated_at)
       VALUES (?, ?, ?, ?, datetime('now'), datetime('now'))`
    ).bind(id, qq, score, passed ? 1 : 0).run();
  } catch (error) {
    if (String(error).toLowerCase().includes("unique")) fail("该 QQ 已经提交过浓度测试", 409);
    throw error;
  }
  return json({
    passed, locked: true, concentration: score,
    token: passed ? await signToken(env.SESSION_SECRET, { id, qq, stage: "concentration" }, 7200) : null
  });
}

async function handleIntro(request, env) {
  const body = await readBody(request);
  const token = await requireToken(env, body.concentrationToken, "concentration");
  if (!Array.isArray(body.intro) || body.intro.length !== introQuestions.length) fail("请答完全部入门问卷");
  const application = await getApplication(env, token.id, token.qq);
  if (!application?.concentration_passed) fail("请先通过浓度测试", 403);
  const introScore = Math.round((scoreQuiz(introQuestions, body.intro) / introQuestions.length) * 100);
  const passed = introScore >= 80;
  await env.DB.prepare(
    `UPDATE applications SET intro_score = ?, quiz_passed = CASE WHEN ? = 1 THEN 1 ELSE quiz_passed END,
     updated_at = datetime('now') WHERE id = ? AND qq = ?`
  ).bind(introScore, passed ? 1 : 0, token.id, token.qq).run();
  return json({
    passed, introScore,
    token: passed ? await signToken(env.SESSION_SECRET, { id: token.id, qq: token.qq, stage: "quizzes" }, 7200) : null
  });
}

function imageMime(bytes) {
  if (bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return "image/jpeg";
  if (bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47 &&
      bytes[4] === 0x0d && bytes[5] === 0x0a && bytes[6] === 0x1a && bytes[7] === 0x0a) return "image/png";
  if (bytes[0] === 0x52 && bytes[1] === 0x49 && bytes[2] === 0x46 && bytes[3] === 0x46 &&
      bytes[8] === 0x57 && bytes[9] === 0x45 && bytes[10] === 0x42 && bytes[11] === 0x50) return "image/webp";
  return "";
}

function parseDataUrl(value) {
  const match = String(value || "").match(/^data:(image\/(?:jpeg|png|webp));base64,([A-Za-z0-9+/=]+)$/);
  if (!match) fail("请上传 JPG、PNG 或 WebP 图片");
  const binary = atob(match[2]);
  if (binary.length > MAX_IMAGE_BYTES) fail("截图处理后仍然过大，请换一张截图重试", 413);
  const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0));
  const mime = imageMime(bytes);
  if (!mime) fail("图片内容无法识别，请重新截图后上传");
  return {
    mime,
    bytes,
    extension: { "image/jpeg": "jpg", "image/png": "png", "image/webp": "webp" }[mime]
  };
}

async function handleUploadMaterial(request, env) {
  const body = await readBody(request);
  const kind = String(body.kind || "");
  const column = MATERIAL_COLUMNS[kind];
  if (!column) fail("未知材料类型");
  const token = await requireToken(env, body.quizToken, "quizzes");
  const application = await getApplication(env, token.id, token.qq);
  if (!application?.quiz_passed) fail("请先通过两套测试", 403);
  if (!(await env.IMAGE_RATE_LIMITER.limit({ key: `${token.id}:${kind}` })).success) {
    fail("该材料提交过于频繁，请一分钟后再试", 429);
  }
  const existing = await env.DB.prepare(
    "SELECT id, status FROM materials WHERE application_id = ? AND kind = ?"
  ).bind(token.id, kind).first();
  if (existing?.status === "approved") return json({ pass: true, reason: "该材料已经审核通过" });
  if (existing?.status === "pending") fail("该材料正在等待审核，请勿重复提交", 409);

  const image = parseDataUrl(body.image);
  const materialId = existing?.id || crypto.randomUUID();
  await env.DB.prepare(
      `INSERT INTO materials (id, application_id, kind, image_data, mime_type, status, submitted_at)
       VALUES (?, ?, ?, ?, ?, 'pending', datetime('now'))
       ON CONFLICT(application_id, kind) DO UPDATE SET
         image_data = excluded.image_data, mime_type = excluded.mime_type, status = 'pending',
         reason = NULL, submitted_at = datetime('now'), reviewed_at = NULL, reviewed_by = NULL`
    ).bind(materialId, token.id, kind, image.bytes, image.mime).run();
  await env.DB.prepare(
      `UPDATE applications SET ${column} = 0, completed_at = NULL, updated_at = datetime('now') WHERE id = ?`
    ).bind(token.id).run();
  return json({ pending: true, reason: "材料已提交，等待管理员审核" });
}

async function materialStatus(env, applicationId) {
  const result = await env.DB.prepare(
    "SELECT kind, status, reason, submitted_at, reviewed_at FROM materials WHERE application_id = ?"
  ).bind(applicationId).all();
  return Object.fromEntries((result.results || []).map((row) => [row.kind, {
    status: row.status, reason: row.reason || "", submittedAt: row.submitted_at, reviewedAt: row.reviewed_at
  }]));
}

async function handleStatus(request, env) {
  const body = await readBody(request);
  const token = await requireToken(env, body.quizToken, "quizzes");
  const application = await getApplication(env, token.id, token.qq);
  if (!application) fail("未找到申请记录", 404);
  return json({
    adult: application.adult_passed === 1,
    follow: application.follow_passed === 1,
    voice: application.voice_passed === 1,
    completed: Boolean(application.completed_at),
    materials: await materialStatus(env, token.id)
  });
}

async function handleComplete(request, env) {
  const body = await readBody(request);
  const token = await requireToken(env, body.quizToken, "quizzes");
  const application = await getApplication(env, token.id, token.qq);
  if (!application?.quiz_passed || !application.adult_passed || !application.follow_passed || !application.voice_passed) {
    fail("材料尚未全部审核通过，请等待管理员处理", 400);
  }
  if (!application.completed_at) {
    await env.DB.prepare(
      "UPDATE applications SET completed_at = datetime('now'), updated_at = datetime('now') WHERE id = ? AND qq = ?"
    ).bind(token.id, token.qq).run();
  }
  return json({ passed: true, groups: {
    required: env.REQUIRED_GROUP || "595862009",
    optional: env.OPTIONAL_GROUP || "1103798415"
  } });
}

async function sha256Hex(value) {
  const bytes = typeof value === "string" ? new TextEncoder().encode(value) : value;
  return [...new Uint8Array(await crypto.subtle.digest("SHA-256", bytes))]
    .map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

function cookieValue(request, name) {
  const match = (request.headers.get("cookie") || "").match(new RegExp(`(?:^|;\\s*)${name}=([^;]+)`));
  return match ? decodeURIComponent(match[1]) : "";
}

async function passwordHash(password, salt, iterations = PASSWORD_ITERATIONS) {
  const material = await crypto.subtle.importKey("raw", new TextEncoder().encode(password), "PBKDF2", false, ["deriveBits"]);
  return new Uint8Array(await crypto.subtle.deriveBits(
    { name: "PBKDF2", hash: "SHA-256", salt, iterations }, material, 256
  ));
}

function equalBytes(left, right) {
  const a = new Uint8Array(left || []), b = new Uint8Array(right || []);
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let index = 0; index < a.length; index++) diff |= a[index] ^ b[index];
  return diff === 0;
}

async function adminLogin(request, env) {
  ensureSameOrigin(request);
  const body = await readBody(request);
  const email = String(body.email || "").trim().toLowerCase();
  const password = String(body.password || "");
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email) || password.length < 12) fail("邮箱或密码错误", 401);
  if (!(await env.SUBMIT_RATE_LIMITER.limit({ key: `admin-login:${email}` })).success) fail("登录尝试过多，请一分钟后重试", 429);

  const ownerEmail = String(env.OWNER_EMAIL || "").trim().toLowerCase();
  let valid = false;
  if (email === ownerEmail) {
    valid = equalBytes(
      new TextEncoder().encode(await sha256Hex(password)),
      new TextEncoder().encode(await sha256Hex(env.OWNER_INITIAL_PASSWORD || ""))
    );
    if (valid) await env.DB.prepare(
      `INSERT INTO admins (email, role, active, created_by, created_at, updated_at)
       VALUES (?, 'owner', 1, ?, datetime('now'), datetime('now'))
       ON CONFLICT(email) DO UPDATE SET role = 'owner', active = 1, updated_at = datetime('now')`
    ).bind(email, email).run();
  } else {
    const row = await env.DB.prepare(
      "SELECT password_salt, password_hash, password_iterations, active FROM admins WHERE email = ?"
    ).bind(email).first();
    if (row?.active && row.password_salt && row.password_hash) {
      valid = equalBytes(await passwordHash(password, new Uint8Array(row.password_salt), row.password_iterations), row.password_hash);
    }
  }
  if (!valid) fail("邮箱或密码错误", 401);

  const token = `${crypto.randomUUID()}.${crypto.randomUUID()}`;
  const tokenHash = await sha256Hex(token);
  await env.DB.prepare(
    `INSERT INTO admin_sessions (token_hash, admin_email, expires_at, created_at, last_seen_at)
     VALUES (?, ?, datetime('now', '+7 days'), datetime('now'), datetime('now'))`
  ).bind(tokenHash, email).run();
  return json({ ok: true }, 200, {
    "set-cookie": `admin_session=${encodeURIComponent(token)}; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=604800`
  });
}

async function requireAdmin(request, env, ownerOnly = false) {
  const token = cookieValue(request, "admin_session");
  if (!token) fail("请先登录管理后台", 401);
  const admin = await env.DB.prepare(
    `SELECT a.email, a.role, a.active FROM admin_sessions s
       JOIN admins a ON a.email = s.admin_email
      WHERE s.token_hash = ? AND s.expires_at > datetime('now')`
  ).bind(await sha256Hex(token)).first();
  if (!admin?.active) fail("登录已失效，请重新登录", 401);
  if (ownerOnly && admin.role !== "owner") fail("仅主管理员可以执行此操作", 403);
  return admin;
}

async function adminQueue(env, url) {
  const status = ["pending", "approved", "rejected"].includes(url.searchParams.get("status"))
    ? url.searchParams.get("status") : "pending";
  const result = await env.DB.prepare(
    `SELECT m.id, m.kind, m.status, m.reason, m.submitted_at, m.reviewed_at, m.reviewed_by,
            a.qq, a.concentration_score, a.intro_score
       FROM materials m JOIN applications a ON a.id = m.application_id
      WHERE m.status = ? ORDER BY m.submitted_at ASC LIMIT 100`
  ).bind(status).all();
  return json({ items: result.results || [], status });
}

async function adminImage(env, id) {
  const row = await env.DB.prepare("SELECT image_data, mime_type FROM materials WHERE id = ?").bind(id).first();
  if (!row) fail("材料不存在", 404);
  if (!row.image_data) fail("图片已在审核后删除", 410);
  const bytes = row.image_data instanceof Uint8Array ? row.image_data : new Uint8Array(row.image_data);
  const mime = imageMime(bytes);
  if (!mime) fail("图片内容损坏，请让申请人重新上传", 422);
  return new Response(bytes, { headers: securityHeaders({
    "content-type": mime, "cache-control": "private, no-store", "content-disposition": "inline"
  }) });
}

async function adminReview(env, admin, body) {
  const id = String(body.id || "");
  const decision = String(body.decision || "");
  const reason = String(body.reason || "").trim().slice(0, 300);
  if (!["approved", "rejected"].includes(decision)) fail("审核决定无效");
  if (decision === "rejected" && !reason) fail("拒绝时请填写原因");
  const row = await env.DB.prepare(
    "SELECT id, application_id, kind, status FROM materials WHERE id = ?"
  ).bind(id).first();
  if (!row) fail("材料不存在", 404);
  if (row.status !== "pending") fail("该材料已经由其他管理员处理", 409);
  const column = MATERIAL_COLUMNS[row.kind];
  const updated = await env.DB.prepare(
    `UPDATE materials SET status = ?, reason = ?, image_data = NULL, reviewed_at = datetime('now'), reviewed_by = ?
      WHERE id = ? AND status = 'pending'`
  ).bind(decision, reason || null, admin.email, id).run();
  if (!updated.meta?.changes) fail("该材料已经由其他管理员处理", 409);
  await env.DB.batch([
    env.DB.prepare(`UPDATE applications SET ${column} = ?, completed_at = NULL, updated_at = datetime('now') WHERE id = ?`)
      .bind(decision === "approved" ? 1 : 0, row.application_id),
    env.DB.prepare(
      `INSERT INTO review_events (id, material_id, application_id, kind, decision, reason, reviewer_email, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, datetime('now'))`
    ).bind(crypto.randomUUID(), id, row.application_id, row.kind, decision, reason || null, admin.email)
  ]);
  return json({ ok: true });
}

async function adminList(env) {
  const result = await env.DB.prepare(
    "SELECT email, role, active, created_by, created_at, updated_at FROM admins ORDER BY role, email"
  ).all();
  return json({ admins: result.results || [] });
}

async function adminSave(env, current, body) {
  const email = String(body.email || "").trim().toLowerCase();
  const password = String(body.password || "");
  const role = body.role === "owner" ? "owner" : "reviewer";
  const active = body.active === false ? 0 : 1;
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) fail("管理员邮箱格式错误");
  if (email === current.email && !active) fail("不能停用当前登录账号");
  const existing = await env.DB.prepare("SELECT email FROM admins WHERE email = ?").bind(email).first();
  if (!existing && password.length < 12) fail("新管理员密码至少需要 12 位");
  let salt = null, hash = null, iterations = null;
  if (password) {
    if (password.length < 12) fail("管理员密码至少需要 12 位");
    salt = crypto.getRandomValues(new Uint8Array(16));
    iterations = PASSWORD_ITERATIONS;
    hash = await passwordHash(password, salt, iterations);
  }
  await env.DB.prepare(
    `INSERT INTO admins (email, role, active, created_by, created_at, updated_at, password_salt, password_hash, password_iterations)
     VALUES (?, ?, ?, ?, datetime('now'), datetime('now'), ?, ?, ?)
     ON CONFLICT(email) DO UPDATE SET role = excluded.role, active = excluded.active,
       password_salt = COALESCE(excluded.password_salt, admins.password_salt),
       password_hash = COALESCE(excluded.password_hash, admins.password_hash),
       password_iterations = COALESCE(excluded.password_iterations, admins.password_iterations),
       updated_at = datetime('now')`
  ).bind(email, role, active, current.email, salt, hash, iterations).run();
  return json({ ok: true });
}

async function routeAdmin(request, env, url) {
  if (request.method === "POST" && url.pathname === "/api/admin/login") return adminLogin(request, env);
  if (request.method === "POST" && url.pathname === "/api/admin/logout") {
    ensureSameOrigin(request);
    const token = cookieValue(request, "admin_session");
    if (token) await env.DB.prepare("DELETE FROM admin_sessions WHERE token_hash = ?").bind(await sha256Hex(token)).run();
    return json({ ok: true }, 200, { "set-cookie": "admin_session=; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=0" });
  }
  const ownerOnly = url.pathname === "/api/admin/admins" && request.method === "POST";
  const admin = await requireAdmin(request, env, ownerOnly);
  if (request.method === "GET" && url.pathname === "/api/admin/session") return json({ admin });
  if (request.method === "GET" && url.pathname === "/api/admin/queue") return adminQueue(env, url);
  if (request.method === "GET" && url.pathname.startsWith("/api/admin/material/") && url.pathname.endsWith("/image")) {
    return adminImage(env, url.pathname.split("/")[4]);
  }
  if (request.method === "GET" && url.pathname === "/api/admin/admins") {
    if (admin.role !== "owner") fail("仅主管理员可以查看账号", 403);
    return adminList(env);
  }
  if (request.method !== "POST") return json({ error: "Method not allowed" }, 405);
  ensureSameOrigin(request);
  const body = await readBody(request);
  if (url.pathname === "/api/admin/review") return adminReview(env, admin, body);
  if (url.pathname === "/api/admin/admins") return adminSave(env, admin, body);
  return json({ error: "Not found" }, 404);
}

async function routeApi(request, env, url) {
  const pathname = url.pathname;
  if (pathname.startsWith("/api/admin/")) return routeAdmin(request, env, url);
  if (request.method === "GET" && pathname === "/api/questions") {
    return json({ concentration: publicQuestions(concentrationQuestions), intro: publicQuestions(introQuestions) });
  }
  if (request.method === "GET" && pathname === "/api/config") {
    return json({ turnstileSiteKey: env.TURNSTILE_SITE_KEY || "" });
  }
  if (request.method !== "POST") return json({ error: "Method not allowed" }, 405, { allow: "POST" });
  ensureSameOrigin(request);
  if (pathname === "/api/concentration") return handleConcentration(request, env);
  if (pathname === "/api/intro") return handleIntro(request, env);
  if (pathname === "/api/verify-image") return handleUploadMaterial(request, env);
  if (pathname === "/api/status") return handleStatus(request, env);
  if (pathname === "/api/complete") return handleComplete(request, env);
  return json({ error: "Not found" }, 404);
}

export default {
  async fetch(request, env) {
    try {
      const url = new URL(request.url);
      if (url.pathname.startsWith("/api/")) return await routeApi(request, env, url);
      const response = await env.ASSETS.fetch(request);
      const headers = new Headers(response.headers);
      Object.entries(securityHeaders()).forEach(([key, value]) => headers.set(key, value));
      if (url.pathname.startsWith("/admin/")) headers.set("cache-control", "no-store");
      return new Response(response.body, { status: response.status, statusText: response.statusText, headers });
    } catch (error) {
      return errorResponse(error);
    }
  }
};
