import { test } from 'node:test';
import assert from 'node:assert/strict';
import { normalize, readUsage } from './codex-usage.mjs';

test('multiple buckets override legacy; seconds are preserved', () => {
  const result = normalize({ rateLimits: { primary: { usedPercent: 99 } }, rateLimitsByLimitId: {
    codex: { primary: { usedPercent: 25, windowDurationMins: 300, resetsAt: 1234 }, secondary: { usedPercent: 70, windowDurationMins: 10080 } },
    extra: { primary: { usedPercent: 40 } },
  } });
  assert.equal(result.windows.length, 3);
  assert.equal(result.windows[0].remainingPercent, 75);
  assert.equal(result.windows[0].resetsAt, 1234);
  assert.equal(result.windows[1].remainingPercent, 30);
});
test('missing values stay unknown and percentages clamp', () => {
  assert.equal(normalize({}).windows.length, 0);
  assert.equal(normalize({ rateLimits: { primary: {} } }).windows[0].remainingPercent, null);
  assert.equal(normalize({ rateLimits: { primary: { usedPercent: 110 } } }).windows[0].remainingPercent, 0);
});
test('missing executable fails cleanly', async () => {
  await assert.rejects(readUsage('tokenpilot-nonexistent-codex', 1000), /실행 실패/);
});
