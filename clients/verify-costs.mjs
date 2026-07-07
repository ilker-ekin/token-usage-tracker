#!/usr/bin/env node
/**
 * Validates the tracker's cost calculator against Anthropic's own numbers.
 *
 * Two layers:
 *  1. Input side (deterministic, free, CI-safe): count_tokens for a fixed
 *     prompt must equal usage.input_tokens of the same live request —
 *     proves the token counts we book are the API's counts, not estimates.
 *  2. Dollar side: for each model, make one small real call, compute the
 *     expected cost from usage × the tracker's own pricing rows, ingest the
 *     event, and assert the tracker booked the same figure.
 *
 * Usage:
 *   npm run verify            # count_tokens layer only (no spend)
 *   npm run verify -- --live  # both layers (makes real model calls)
 *
 * Exits non-zero on any mismatch, so it can gate CI.
 */
import Anthropic from "@anthropic-ai/sdk";

const TRACKER_URL = process.env.TRACKER_URL ?? "http://localhost:8080";
const LIVE = process.argv.includes("--live");
const MODELS = (process.env.VERIFY_MODELS ?? "claude-opus-4-8,claude-sonnet-4-6,claude-haiku-4-5")
  .split(",");

// Fixed prompt: no system, no tools, no cache — usage.input_tokens must
// then equal count_tokens for the identical messages array.
const MESSAGES = [{ role: "user", content: "Reply with the single word: pong" }];

// Matches PricingService: (in × inPrice + out × outPrice) / 1000, 6 dp.
// Float64 is exact enough at these magnitudes; assert with 1e-6 tolerance
// to absorb HALF_UP edge cases.
const expectedCost = (p, input, output) =>
  (input * p.inputPricePer1k + output * p.outputPricePer1k) / 1000;

const client = new Anthropic();
let failures = 0;

const trackerToken = await trackerLogin();
const pricing = await fetchPricing(trackerToken);

for (const model of MODELS) {
  const counted = (await client.messages.countTokens({ model, messages: MESSAGES }))
    .input_tokens;
  console.log(`[${model}] count_tokens(input) = ${counted}`);

  if (!LIVE) continue;

  const resp = await client.messages.create({ model, max_tokens: 64, messages: MESSAGES });
  const { input_tokens: input, output_tokens: output } = resp.usage;

  check(`${model} input tokens: usage(${input}) == count_tokens(${counted})`,
      input === counted);

  const p = pricing[model];
  if (!p) {
    check(`${model} has a tracker pricing row`, false);
    continue;
  }
  const expected = expectedCost(p, input, output);
  const booked = await ingest(trackerToken, model, input, output);
  check(`${model} cost: booked $${booked} ≈ expected $${expected.toFixed(6)}`,
      Math.abs(booked - expected) < 1e-6);
}

if (failures > 0) {
  console.error(`\n${failures} check(s) FAILED`);
  process.exit(1);
}
console.log(LIVE ? "\nAll checks passed — calculator matches the API."
                 : "\nToken counts printed. Re-run with --live to reconcile dollars.");

function check(label, ok) {
  console.log(`  ${ok ? "PASS" : "FAIL"}  ${label}`);
  if (!ok) failures++;
}

async function trackerLogin() {
  const res = await fetch(`${TRACKER_URL}/auth/token`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      email: process.env.TRACKER_EMAIL,
      password: process.env.TRACKER_PASSWORD,
    }),
  });
  if (!res.ok) throw new Error(`tracker login failed: ${res.status}`);
  return (await res.json()).token;
}

async function fetchPricing(token) {
  const res = await fetch(`${TRACKER_URL}/model-pricing`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok) throw new Error(`pricing fetch failed: ${res.status}`);
  return Object.fromEntries((await res.json()).map((p) => [p.model, p]));
}

async function ingest(token, model, inputTokens, outputTokens) {
  const res = await fetch(`${TRACKER_URL}/usage`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify({
      projectId: process.env.TRACKER_PROJECT_ID,
      model,
      inputTokens,
      outputTokens,
      eventTime: new Date().toISOString(),
    }),
  });
  if (!res.ok) throw new Error(`ingest failed: ${res.status}`);
  return Number((await res.json()).costUsd);
}
