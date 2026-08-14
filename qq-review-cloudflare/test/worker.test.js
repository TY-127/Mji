import test from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.js";
import { concentrationQuestions, introQuestions } from "../src/questions.js";

class MemoryD1 {
  constructor() {
    this.byId = new Map();
    this.byQq = new Map();
    this.materials = new Map();
  }

  prepare(sql) {
    const db = this;
    return {
      bind(...values) {
        return {
          async first() {
            if (sql.includes("FROM applications WHERE qq = ?")) {
              return db.byQq.get(values[0]) || null;
            }
            if (sql.includes("FROM applications WHERE id = ? AND qq = ?")) {
              const row = db.byId.get(values[0]);
              return row?.qq === values[1] ? row : null;
            }
            if (sql.includes("FROM materials WHERE application_id = ? AND kind = ?")) {
              return db.materials.get(`${values[0]}:${values[1]}`) || null;
            }
            throw new Error(`Unhandled first query: ${sql}`);
          },
          async all() {
            if (sql.includes("FROM materials WHERE application_id = ?")) {
              return {
                results: [...db.materials.values()].filter((row) => row.application_id === values[0])
              };
            }
            throw new Error(`Unhandled all query: ${sql}`);
          },
          async run() {
            if (sql.includes("INSERT INTO applications")) {
              const [id, qq, score, passed] = values;
              const row = {
                id, qq,
                concentration_score: score,
                concentration_passed: passed,
                intro_score: null,
                quiz_passed: 0,
                adult_passed: 0,
                follow_passed: 0,
                voice_passed: 0,
                completed_at: null
              };
              db.byId.set(id, row);
              db.byQq.set(qq, row);
              return { success: true };
            }
            if (sql.includes("SET intro_score")) {
              const [score, passed, id, qq] = values;
              const row = db.byId.get(id);
              if (row?.qq === qq) {
                row.intro_score = score;
                if (passed) row.quiz_passed = 1;
              }
              return { success: true };
            }
            if (sql.includes("INSERT INTO materials")) {
              const [id, applicationId, kind, imageData, mime] = values;
              db.materials.set(`${applicationId}:${kind}`, {
                id, application_id: applicationId, kind, image_data: imageData,
                mime_type: mime, status: "pending", reason: null, submitted_at: "now", reviewed_at: null
              });
              return { success: true };
            }
            const material = ["adult_passed", "follow_passed", "voice_passed"].find((column) =>
              sql.includes(`SET ${column} = 0`)
            );
            if (material) {
              const [id] = values;
              const row = db.byId.get(id);
              if (row) row[material] = 0;
              return { success: true };
            }
            if (sql.includes("SET completed_at")) {
              const [id, qq] = values;
              const row = db.byId.get(id);
              if (row?.qq === qq) row.completed_at = new Date().toISOString();
              return { success: true };
            }
            throw new Error(`Unhandled run query: ${sql}`);
          }
        };
      }
    };
  }
}

function createEnv() {
  return {
    SESSION_SECRET: "worker-integration-secret-with-at-least-32-characters",
    REQUIRED_GROUP: "11111",
    OPTIONAL_GROUP: "22222",
    TURNSTILE_SITE_KEY: "",
    DB: new MemoryD1(),
    MATERIALS: {
      objects: new Map(),
      async put(key, value) { this.objects.set(key, value); },
      async delete(key) { this.objects.delete(key); }
    },
    SUBMIT_RATE_LIMITER: { async limit() { return { success: true }; } },
    IMAGE_RATE_LIMITER: { async limit() { return { success: true }; } },
    ASSETS: { async fetch() { return new Response("asset"); } }
  };
}

async function api(env, path, body) {
  const response = await worker.fetch(new Request(`https://review.example${path}`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "origin": "https://review.example"
    },
    body: JSON.stringify(body)
  }), env);
  return { status: response.status, data: await response.json() };
}

test("完整流程必须由人工审核记录三项材料，客户端布尔值不能绕过", async () => {
  const env = createEnv();
  const answers = (questions) => questions.map((question) =>
    question.type === "text" ? question.answerText : question.answer
  );

  const concentration = await api(env, "/api/concentration", {
    qq: "987654321",
    concentration: answers(concentrationQuestions)
  });
  assert.equal(concentration.status, 200);
  assert.equal(concentration.data.passed, true);

  const intro = await api(env, "/api/intro", {
    concentrationToken: concentration.data.token,
    intro: answers(introQuestions)
  });
  assert.equal(intro.status, 200);
  assert.equal(intro.data.passed, true);

  const bypass = await api(env, "/api/complete", {
    quizToken: intro.data.token,
    adult: true,
    follow: true,
    voice: true
  });
  assert.equal(bypass.status, 400);
  assert.equal(bypass.data.passed, undefined);

  const image = "data:image/jpeg;base64,iVBORw0KGgo=";
  for (const kind of ["adult", "follow", "voice"]) {
    const verification = await api(env, "/api/verify-image", {
      quizToken: intro.data.token,
      kind,
      image
    });
    assert.equal(verification.status, 200);
    assert.equal(verification.data.pending, true);
    assert.equal(env.DB.materials.get(`${env.DB.byQq.get("987654321").id}:${kind}`).mime_type, "image/png");
  }

  const stillPending = await api(env, "/api/complete", { quizToken: intro.data.token });
  assert.equal(stillPending.status, 400);

  const application = env.DB.byQq.get("987654321");
  application.adult_passed = application.follow_passed = application.voice_passed = 1;
  const complete = await api(env, "/api/complete", { quizToken: intro.data.token });
  assert.equal(complete.status, 200);
  assert.equal(complete.data.passed, true);
  assert.deepEqual(complete.data.groups, { required: "11111", optional: "22222" });
});

test("管理员接口不信任可伪造的邮箱请求头", async () => {
  const env = createEnv();
  const response = await worker.fetch(new Request("https://review.example/api/admin/session", {
    headers: { "Cf-Access-Authenticated-User-Email": "owner@example.com" }
  }), env);
  assert.equal(response.status, 401);
});
