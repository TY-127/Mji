"use strict";

const state = { status: "pending", admin: null, admins: [], selectedId: null };
const labels = { adult: "成年证明", follow: "关注证明", voice: "群语音证明" };
const $ = (id) => document.getElementById(id);

async function request(url, options = {}) {
  const response = await fetch(url, {
    ...options,
    headers: options.body ? { "content-type": "application/json" } : undefined
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.error || "请求失败");
  return data;
}

function notice(text, bad = false) {
  $("notice").textContent = text;
  $("notice").style.color = bad ? "#ff7b7b" : "#a5aaba";
}

function escapeText(value) {
  const node = document.createElement("span");
  node.textContent = String(value ?? "");
  return node.innerHTML;
}

async function loadQueue() {
  notice("正在加载…");
  try {
    const data = await request(`/api/admin/queue?status=${state.status}`);
    $("queue").innerHTML = data.items.length ? data.items.map((item) => `
      <article class="card">
        <span class="status">${escapeText(labels[item.kind] || item.kind)}</span>
        <h2>QQ ${escapeText(item.qq)}</h2>
        <div class="meta">
          浓度测试：${escapeText(item.concentration_score)}/10<br>
          入门问卷：${escapeText(item.intro_score)} 分<br>
          提交时间：${escapeText(item.submitted_at)}
          ${item.reviewed_by ? `<br>审核人：${escapeText(item.reviewed_by)}` : ""}
          ${item.reason ? `<br>原因：${escapeText(item.reason)}` : ""}
        </div>
        ${state.status === "pending" ? `<button data-review="${escapeText(item.id)}" data-kind="${escapeText(item.kind)}" data-qq="${escapeText(item.qq)}">查看并审核</button>` : ""}
      </article>`).join("") : `<p>当前没有${state.status === "pending" ? "待审核" : "相关"}材料。</p>`;
    document.querySelectorAll("[data-review]").forEach((button) => {
      button.addEventListener("click", () => openReview(button.dataset.review, button.dataset.kind, button.dataset.qq));
    });
    notice(`共 ${data.items.length} 条记录`);
  } catch (error) { notice(error.message, true); }
}

function openReview(id, kind, qq) {
  state.selectedId = id;
  $("dialogTitle").textContent = `审核 ${labels[kind]} · QQ ${qq}`;
  $("reason").value = "";
  $("materialImage").src = `/api/admin/material/${encodeURIComponent(id)}/image`;
  $("reviewDialog").showModal();
}

async function decide(decision) {
  const reason = $("reason").value.trim();
  if (decision === "rejected" && !reason) {
    notice("拒绝时请填写原因", true);
    return;
  }
  $("approve").disabled = $("reject").disabled = true;
  try {
    await request("/api/admin/review", {
      method: "POST",
      body: JSON.stringify({ id: state.selectedId, decision, reason })
    });
    $("reviewDialog").close();
    await loadQueue();
  } catch (error) { notice(error.message, true); }
  finally { $("approve").disabled = $("reject").disabled = false; }
}

async function loadAdmins() {
  if (state.admin?.role !== "owner") return;
  const data = await request("/api/admin/admins");
  state.admins = data.admins;
  $("adminList").innerHTML = data.admins.map((admin) => `
    <div class="admin-row ${admin.active ? "" : "off"}">
      <span>${escapeText(admin.email)} · ${admin.role === "owner" ? "主管理员" : "审核员"}</span>
      <button data-toggle="${escapeText(admin.email)}" data-role="${escapeText(admin.role)}" data-active="${admin.active}">
        ${admin.active ? "停用" : "启用"}
      </button>
    </div>`).join("");
  document.querySelectorAll("[data-toggle]").forEach((button) => button.addEventListener("click", async () => {
    try {
      await saveAdmin(button.dataset.toggle, button.dataset.role, button.dataset.active !== "1", "");
      await loadAdmins();
    } catch (error) { notice(error.message, true); }
  }));
}

async function saveAdmin(email, role, active = true, password = "") {
  return request("/api/admin/admins", {
    method: "POST",
    body: JSON.stringify({ email, role, active, password })
  });
}

function adminFormNotice(text, bad = false) {
  $("adminFormNotice").textContent = text;
  $("adminFormNotice").classList.toggle("error", bad);
}

async function init() {
  try {
    const session = await request("/api/admin/session");
    state.admin = session.admin;
    $("identity").textContent = `${session.admin.email} · ${session.admin.role === "owner" ? "主管理员" : "审核员"}`;
    if (session.admin.role === "owner") {
      $("adminPanel").classList.remove("hidden");
      await loadAdmins();
    }
    $("loginPanel").classList.add("hidden");
    $("dashboard").classList.remove("hidden");
    await loadQueue();
  } catch (error) {
    $("loginPanel").classList.remove("hidden");
    $("dashboard").classList.add("hidden");
    $("loginNotice").textContent = error.message;
  }
}

document.querySelectorAll("[data-status]").forEach((button) => button.addEventListener("click", () => {
  document.querySelectorAll("[data-status]").forEach((item) => item.classList.remove("active"));
  button.classList.add("active");
  state.status = button.dataset.status;
  loadQueue();
}));
$("refresh").addEventListener("click", loadQueue);
$("logout").addEventListener("click", async () => {
  await request("/api/admin/logout", { method: "POST", body: "{}" });
  location.reload();
});
$("approve").addEventListener("click", () => decide("approved"));
$("reject").addEventListener("click", () => decide("rejected"));
$("adminForm").addEventListener("submit", async (event) => {
  event.preventDefault();
  const email = $("adminEmail").value.trim().toLowerCase();
  const password = $("adminPassword").value;
  const existing = state.admins.some((admin) => admin.email.toLowerCase() === email);
  if ((!existing && password.length < 12) || (password && password.length < 12)) {
    adminFormNotice("新增审核员时，密码必须至少填写 12 位。", true);
    $("adminPassword").focus();
    return;
  }
  const submit = event.submitter;
  submit.disabled = true;
  adminFormNotice("正在保存…");
  try {
    await saveAdmin(email, $("adminRole").value, true, password);
    $("adminEmail").value = "";
    $("adminPassword").value = "";
    await loadAdmins();
    adminFormNotice(existing ? "管理员资料已更新。" : "审核员添加成功，可以使用新账号登录。");
  } catch (error) {
    adminFormNotice(error.message, true);
  } finally {
    submit.disabled = false;
  }
});
$("loginForm").addEventListener("submit", async (event) => {
  event.preventDefault();
  $("loginNotice").textContent = "正在登录…";
  try {
    await request("/api/admin/login", {
      method: "POST",
      body: JSON.stringify({ email: $("loginEmail").value, password: $("loginPassword").value })
    });
    $("loginPassword").value = "";
    await init();
  } catch (error) { $("loginNotice").textContent = error.message; }
});

init();
