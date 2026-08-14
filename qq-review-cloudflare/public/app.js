"use strict";

const state = {
  concentrationToken: sessionStorage.getItem("concentrationToken"),
  quizToken: sessionStorage.getItem("quizToken"),
  adult: false,
  follow: false,
  voice: false,
  turnstileToken: ""
};
let questionData;

function element(id) { return document.getElementById(id); }
function setResult(id, text, ok) {
  const target = element(id);
  target.textContent = text;
  target.className = `result ${ok ? "ok" : "bad"}`;
}
function setPending(id, text) {
  const target = element(id);
  target.textContent = text;
  target.className = "result pending";
}
function unlock(id) { element(id).classList.add("unlocked"); }
function lock(id) { element(id).classList.remove("unlocked"); }

function renderQuiz(targetId, title, questions, prefix) {
  const target = element(targetId);
  target.innerHTML = `<h3>${title}</h3>` + questions.map((question, index) => {
    if (question.type === "text") {
      return `<div class="question"><strong>${index + 1}. ${question.q}</strong><textarea data-answer="${prefix}-${index}" rows="3" placeholder="请输入完整答案"></textarea></div>`;
    }
    return `<div class="question"><strong>${index + 1}. ${question.q}</strong>${question.options.map((option, optionIndex) =>
      `<label class="option"><input type="radio" name="${prefix}-${index}" value="${optionIndex}"><span>${option}</span></label>`
    ).join("")}</div>`;
  }).join("");
}

function collect(questions, prefix) {
  return questions.map((question, index) => {
    if (question.type === "text") return document.querySelector(`[data-answer="${prefix}-${index}"]`)?.value || "";
    return document.querySelector(`input[name="${prefix}-${index}"]:checked`)?.value ?? null;
  });
}

const TARGET_IMAGE_BYTES = 1500000;

function blobDataUrl(blob) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result);
    reader.onerror = () => reject(new Error("读取截图失败，请重新选择"));
    reader.readAsDataURL(blob);
  });
}

function canvasBlob(canvas, type, quality) {
  return new Promise((resolve) => canvas.toBlob(resolve, type, quality));
}

async function fileDataUrl(file) {
  if (!file) throw new Error("请先选择图片");
  if (file.size <= TARGET_IMAGE_BYTES) return blobDataUrl(file);

  let source;
  try {
    source = await createImageBitmap(file);
  } catch {
    throw new Error("无法识别这张截图，请使用 JPG、PNG 或 WebP 格式");
  }

  try {
    const largestSide = Math.max(source.width, source.height);
    let scale = Math.min(1, 2800 / largestSide);
    const qualities = [0.9, 0.8, 0.7, 0.6, 0.5];
    for (let attempt = 0; attempt < 8; attempt++) {
      const canvas = document.createElement("canvas");
      canvas.width = Math.max(1, Math.round(source.width * scale));
      canvas.height = Math.max(1, Math.round(source.height * scale));
      const context = canvas.getContext("2d", { alpha: false });
      context.fillStyle = "#fff";
      context.fillRect(0, 0, canvas.width, canvas.height);
      context.drawImage(source, 0, 0, canvas.width, canvas.height);
      const quality = qualities[Math.min(attempt, qualities.length - 1)];
      const optimized = await canvasBlob(canvas, "image/webp", quality);
      if (optimized && optimized.size <= TARGET_IMAGE_BYTES) return blobDataUrl(optimized);
      scale *= 0.82;
    }
    throw new Error("截图尺寸过大，自动优化失败，请裁剪后重试");
  } finally {
    source.close();
  }
}

async function post(url, body) {
  const response = await fetch(url, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) });
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || "请求失败");
  return data;
}

async function setupTurnstile(siteKey) {
  if (!siteKey) return;
  element("turnstileWrap").classList.remove("hidden");
  await new Promise((resolve, reject) => {
    const script = document.createElement("script");
    script.src = "https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit";
    script.async = true;
    script.onload = resolve;
    script.onerror = () => reject(new Error("人机验证加载失败"));
    document.head.appendChild(script);
  });
  window.turnstile.render("#turnstile", {
    sitekey: siteKey,
    theme: "dark",
    callback: (token) => { state.turnstileToken = token; },
    "expired-callback": () => { state.turnstileToken = ""; },
    "error-callback": () => { state.turnstileToken = ""; }
  });
}

function rememberToken(name, value) {
  state[name] = value;
  if (value) sessionStorage.setItem(name, value);
  else sessionStorage.removeItem(name);
}

async function restoreStatus() {
  if (!state.quizToken) return;
  try {
    const status = await post("/api/status", { quizToken: state.quizToken });
    state.adult = status.adult;
    state.follow = status.follow;
    state.voice = status.voice;
    unlock("introBlock");
    unlock("materials");
    unlock("voiceCard");
    unlock("completeCard");
    for (const kind of ["adult", "follow", "voice"]) {
      const material = status.materials?.[kind];
      if (status[kind]) setResult(`${kind}Result`, "管理员已审核通过", true);
      else if (material?.status === "pending") setPending(`${kind}Result`, "已提交，等待管理员审核");
      else if (material?.status === "rejected") setResult(`${kind}Result`, `未通过：${material.reason || "请重新提交材料"}`, false);
    }
  } catch {
    rememberToken("quizToken", null);
  }
}

async function init() {
  const [questionsResponse, configResponse] = await Promise.all([
    fetch("/api/questions"),
    fetch("/api/config")
  ]);
  if (!questionsResponse.ok || !configResponse.ok) throw new Error("无法加载审核配置");
  questionData = await questionsResponse.json();
  const config = await configResponse.json();
  renderQuiz("concentration", "COD 浓度测试（10 题，至少 8 分）", questionData.concentration, "c");
  renderQuiz("intro", "小手机入门问卷（至少 80 分）", questionData.intro, "i");
  await setupTurnstile(config.turnstileSiteKey);
  await restoreStatus();
}

element("submitConcentration").addEventListener("click", async () => {
  try {
    const qq = element("qq").value.trim();
    if (!/^\d{5,12}$/.test(qq)) throw new Error("请先填写正确的 QQ 号");
    if (collect(questionData.concentration, "c").some((answer) => answer === null)) throw new Error("请答完全部浓度测试题");
    if (!confirm("浓度测试仅有一次机会，确认提交吗？")) return;
    const button = element("submitConcentration");
    button.disabled = true;
    const data = await post("/api/concentration", {
      qq,
      turnstileToken: state.turnstileToken,
      concentration: collect(questionData.concentration, "c")
    });
    rememberToken("concentrationToken", data.token);
    setResult("concentrationResult", `浓度测试 ${data.concentration}/10。${data.passed ? "通过，可继续入门问卷。" : "未通过，无法再次作答。"}`, data.passed);
    if (data.passed) unlock("introBlock");
  } catch (error) {
    setResult("concentrationResult", error.message, false);
    if (!/仅有一次机会|已经提交/.test(error.message)) element("submitConcentration").disabled = false;
  }
});

element("submitIntro").addEventListener("click", async () => {
  try {
    const data = await post("/api/intro", {
      concentrationToken: state.concentrationToken,
      intro: collect(questionData.intro, "i")
    });
    rememberToken("quizToken", data.token);
    setResult("introResult", `入门问卷 ${data.introScore} 分。${data.passed ? "通过。" : "未达到 80 分，可以修改后再次提交。"}`, data.passed);
    if (data.passed) { unlock("materials"); unlock("voiceCard"); unlock("completeCard"); }
  } catch (error) { setResult("introResult", error.message, false); }
});

document.querySelectorAll("[data-verify]").forEach((button) => button.addEventListener("click", async () => {
  const kind = button.dataset.verify;
  try {
    if (!element("privacyConsent").checked) throw new Error("请先阅读并同意截图处理说明");
    button.disabled = true;
    setPending(`${kind}Result`, "正在处理并上传截图…");
    const image = await fileDataUrl(element(`${kind}File`).files[0]);
    const result = await post("/api/verify-image", { kind, image, quizToken: state.quizToken });
    state[kind] = result.pass === true;
    if (result.pending) setPending(`${kind}Result`, result.reason);
    else setResult(`${kind}Result`, result.reason, state[kind]);
  } catch (error) {
    state[kind] = false;
    setResult(`${kind}Result`, error.message, false);
  } finally { button.disabled = false; }
}));

element("complete").addEventListener("click", async () => {
  try {
    const data = await post("/api/complete", {
      quizToken: state.quizToken
    });
    element("finalResult").innerHTML = `<div class="success"><b>审核通过</b><p>小手机功能禁聊群：${data.groups.required}<br>更新 App 在这里，必须加入。</p><p>小手机闲聊群：${data.groups.optional}<br>闲聊、提问等可加入，非必要。</p><p>审核群可以退出了，辛苦老师。祝老师玩得愉快。</p></div>`;
  } catch (error) {
    const result = element("finalResult");
    result.textContent = error.message;
    result.className = "bad";
  }
});

element("refreshStatus").addEventListener("click", async () => {
  try { await restoreStatus(); }
  catch (error) { element("finalResult").textContent = error.message; }
});

init().catch((error) => setResult("concentrationResult", `页面初始化失败：${error.message}`, false));
