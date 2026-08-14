const encoder = new TextEncoder();
const decoder = new TextDecoder();

export function scoreQuiz(questions, answers) {
  let correct = 0;
  questions.forEach((question, index) => {
    const value = answers?.[index];
    if (question.type === "text") {
      const normalize = (text) => String(text || "")
        .replace(/\s+/g, "")
        .replace(/[，。！？、,.!?]/g, "")
        .toLowerCase();
      if (normalize(value) === normalize(question.answerText)) correct += 1;
    } else if (Number(value) === question.answer) {
      correct += 1;
    }
  });
  return correct;
}

function toBase64Url(bytes) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function fromBase64Url(value) {
  const padded = value.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(value.length / 4) * 4, "=");
  const binary = atob(padded);
  return Uint8Array.from(binary, (char) => char.charCodeAt(0));
}

async function hmac(secret, value) {
  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );
  return new Uint8Array(await crypto.subtle.sign("HMAC", key, encoder.encode(value)));
}

export async function signToken(secret, payload, ttlSeconds = 3600) {
  if (!secret || secret.length < 32) throw new Error("SESSION_SECRET 必须至少为 32 个字符");
  const body = toBase64Url(encoder.encode(JSON.stringify({
    ...payload,
    exp: Math.floor(Date.now() / 1000) + ttlSeconds
  })));
  const signature = toBase64Url(await hmac(secret, body));
  return `${body}.${signature}`;
}

export async function verifyToken(secret, token) {
  try {
    const [body, signature] = String(token || "").split(".");
    if (!body || !signature) return null;
    const expected = await hmac(secret, body);
    const actual = fromBase64Url(signature);
    if (expected.length !== actual.length) return null;
    let mismatch = 0;
    expected.forEach((byte, index) => { mismatch |= byte ^ actual[index]; });
    if (mismatch !== 0) return null;
    const payload = JSON.parse(decoder.decode(fromBase64Url(body)));
    if (!payload.exp || payload.exp < Math.floor(Date.now() / 1000)) return null;
    return payload;
  } catch {
    return null;
  }
}

export function publicQuestions(questions) {
  return questions.map(({ answer, answerText, ...question }) => question);
}
