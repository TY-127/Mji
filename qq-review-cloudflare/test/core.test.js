import test from "node:test";
import assert from "node:assert/strict";
import { publicQuestions, scoreQuiz, signToken, verifyToken } from "../src/core.js";
import { concentrationQuestions, introQuestions } from "../src/questions.js";

test("题目评分和答案脱敏", () => {
  const concentrationAnswers = concentrationQuestions.map((question) => question.answer);
  const introAnswers = introQuestions.map((question) => question.type === "text" ? question.answerText : question.answer);
  assert.equal(scoreQuiz(concentrationQuestions, concentrationAnswers), 10);
  assert.equal(scoreQuiz(introQuestions, introAnswers), 12);
  assert.ok(publicQuestions(concentrationQuestions).every((question) => !("answer" in question)));
  assert.ok(publicQuestions(introQuestions).every((question) => !("answerText" in question)));
});

test("签名凭证不可篡改", async () => {
  const secret = "a-secure-test-secret-with-more-than-32-characters";
  const token = await signToken(secret, { id: "app-1", qq: "12345", stage: "quizzes" });
  assert.equal((await verifyToken(secret, token)).stage, "quizzes");
  assert.equal(await verifyToken(secret, `${token.slice(0, -1)}x`), null);
});
