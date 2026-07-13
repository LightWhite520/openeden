import assert from "node:assert/strict";
import test from "node:test";

import {
  buildRequests,
  countBy,
  generationPrompt,
} from "../user-affect-corpus-lib.mjs";

test("8192 requests cover five confidence bands", () => {
  const requests = buildRequests(8192, 0xaffec726);

  assert.equal(requests.length, 8192);
  assert.deepEqual(countBy(requests, "confidenceBand"), {
    very_low: 1639,
    low: 1639,
    gate_transition: 1638,
    moderate: 1638,
    explicit: 1638,
  });
  assert.ok(requests.filter((item) => item.nearRuntimeGate).length >= 2048);
});

test("confidence prompt defines text observability", () => {
  const prompt = generationPrompt([buildRequests(1, 7)[0]], []);

  assert.match(prompt, /仅根据这段用户文本/);
  assert.doesNotMatch(prompt, /annotation reliability/i);
});
