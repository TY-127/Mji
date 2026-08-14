"use strict";

const state = { concentrationToken: null, quizToken: null, adult: false, follow: false, voice: false, fingerprint: null };
let questionData;

function element(id) { return document.getElementById(id); }
function setResult(id, text, ok) {
  const target = element(id);
  target.textContent = text;
  target.className = `result ${ok ? "ok" : "bad"}`;
}
function unlock(id) { element(id).classList.add("unlocked"); }

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

async function fileDataUrl(file) {
  if (!file) throw new Error("请先选择图片");
  if (file.size > 6 * 1024 * 1024) throw new Error("图片不能超过 6MB");
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result);
    reader.onerror = reject;
    reader.readAsDataURL(file);
  });
}

async function post(url, body) {
  const response = await fetch(url, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) });
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || "请求失败");
  return data;
}

async function getFingerprint() {
  const raw = [navigator.userAgent, navigator.language, screen.width, screen.height, screen.colorDepth,
    new Date().getTimezoneOffset(), navigator.hardwareConcurrency || 0, navigator.platform || ""].join("|");
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(raw));
  return Array.from(new Uint8Array(digest)).map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function init() {
  questionData = await fetch("/api/questions").then((res) => res.json());
  renderQuiz("concentration", "COD 浓度测试（10 题，至少 8 分）", questionData.concentration, "c");
  renderQuiz("intro", "小手机入门问卷（至少 80 分）", questionData.intro, "i");
  state.fingerprint = await getFingerprint();
}

element("submitConcentration").addEventListener("click", async () => {
  try {
    const qq = element("qq").value.trim();
    if (!/^\d{5,12}$/.test(qq)) throw new Error("请先填写正确的 QQ 号");
    if (collect(questionData.concentration, "c").some((answer) => answer === null)) throw new Error("请答完全部浓度测试题");
    if (!confirm("浓度测试仅有一次机会，确认提交吗？")) return;
    element("submitConcentration").disabled = true;
    const data = await post("/api/concentration", {
      qq,
      fingerprint: state.fingerprint,
      concentration: collect(questionData.concentration, "c")
    });
    state.concentrationToken = data.token;
    setResult("concentrationResult", `浓度测试 ${data.concentration}/10。${data.passed ? "通过，可继续入门问卷。" : "未通过，无法再次作答。"}`, data.passed);
    if (data.passed) unlock("introBlock");
  } catch (error) { setResult("concentrationResult", error.message, false); }
});

element("submitIntro").addEventListener("click", async () => {
  try {
    const data = await post("/api/intro", {
      concentrationToken: state.concentrationToken,
      intro: collect(questionData.intro, "i")
    });
    state.quizToken = data.token;
    setResult("introResult", `入门问卷 ${data.introScore} 分。${data.passed ? "通过。" : "未达到 80 分，可以修改后再次提交。"}`, data.passed);
    if (data.passed) { unlock("materials"); unlock("voiceCard"); unlock("completeCard"); }
  } catch (error) { setResult("introResult", error.message, false); }
});

document.querySelectorAll("[data-verify]").forEach((button) => button.addEventListener("click", async () => {
  const kind = button.dataset.verify;
  try {
    button.disabled = true;
    const image = await fileDataUrl(element(`${kind}File`).files[0]);
    const result = await post("/api/verify-image", { kind, image });
    state[kind] = result.pass === true;
    setResult(`${kind}Result`, result.reason, state[kind]);
  } catch (error) {
    state[kind] = false;
    setResult(`${kind}Result`, error.message, false);
  } finally { button.disabled = false; }
}));

element("complete").addEventListener("click", async () => {
  try {
    const data = await post("/api/complete", {
      quizToken: state.quizToken,
      adult: state.adult,
      follow: state.follow,
      voice: state.voice
    });
    element("finalResult").innerHTML = `<div class="success"><b>审核通过</b><p>小手机功能禁聊群：${data.groups.required}<br>更新 App 在这里，必须加入。</p><p>小手机闲聊群：${data.groups.optional}<br>闲聊、提问等可加入，非必要。</p><p>审核群可以退出了，辛苦老师。祝老师玩得愉快。</p></div>`;
  } catch (error) {
    element("finalResult").innerHTML = `<p class="bad">${error.message}</p>`;
  }
});

init().catch((error) => setResult("quizResult", `页面初始化失败：${error.message}`, false));
