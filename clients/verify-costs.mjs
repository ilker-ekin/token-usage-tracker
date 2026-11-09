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
 *  3. Cache side (--live only): send the same cacheable system prompt
 *     twice. The first call must report a cache write, the second a cache
 *     read, and the tracker must book both at the cache prices.
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

// Matches PricingService: the four token classes at their own per-1k
// prices, summed, / 1000, 6 dp. A pricing row without a cache price charges
// those tokens at the input price. Float64 is exact enough at these
// magnitudes; assert with 1e-6 tolerance to absorb HALF_UP edge cases.
const expectedCost = (p, t) =>
  (t.inputTokens * p.inputPricePer1k +
    t.outputTokens * p.outputPricePer1k +
    t.cacheReadTokens * (p.cacheReadPricePer1k ?? p.inputPricePer1k) +
    t.cacheCreationTokens * (p.cacheCreationPricePer1k ?? p.inputPricePer1k)) / 1000;

const tokensOf = (usage) => ({
  inputTokens: usage.input_tokens,
  outputTokens: usage.output_tokens,
  cacheReadTokens: usage.cache_read_input_tokens ?? 0,
  cacheCreationTokens: usage.cache_creation_input_tokens ?? 0,
});

// The largest minimum cacheable prefix of any current model is 4096 tokens;
// a shorter prompt is silently not cached. The filler is far above that, and
// the run nonce in front makes the first call of every run a cache write
// rather than a hit on an entry left by an earlier run.
const CACHE_MIN_TOKENS = 4096;
const cacheSystem = () => [{
  type: "text",
  text: `verify-costs run ${crypto.randomUUID()}\n` +
    "The tracker books uncached input, output, cache reads and cache writes separately. "
      .repeat(600),
  cache_control: { type: "ephemeral" },
}];

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
  const tokens = tokensOf(resp.usage);

  check(`${model} input tokens: usage(${tokens.inputTokens}) == count_tokens(${counted})`,
      tokens.inputTokens === counted);

  const p = pricing[model];
  if (!p) {
    check(`${model} has a tracker pricing row`, false);
    continue;
  }
  await reconcile(model, "cost", p, tokens);

  // Cache layer: identical request twice, strictly one after the other (an
  // entry is only readable once the response that wrote it has started).
  const system = cacheSystem();
  const systemTokens = (await client.messages.countTokens({ model, system, messages: MESSAGES }))
    .input_tokens;
  if (systemTokens <= CACHE_MIN_TOKENS) {
    check(`${model} cache prompt is long enough to cache (${systemTokens} tokens)`, false);
    continue;
  }
  const write = tokensOf((await client.messages.create(
      { model, max_tokens: 64, system, messages: MESSAGES })).usage);
  check(`${model} first call wrote the cache (cache_creation=${write.cacheCreationTokens})`,
      write.cacheCreationTokens > 0);
  await reconcile(model, "cache-write cost", p, write);

  const read = tokensOf((await client.messages.create(
      { model, max_tokens: 64, system, messages: MESSAGES })).usage);
  check(`${model} second call read the cache (cache_read=${read.cacheReadTokens})`,
      read.cacheReadTokens > 0);
  await reconcile(model, "cache-read cost", p, read);
}

if (failures > 0) {
  console.error(`\n${failures} check(s) FAILED`);
  process.exit(1);
}
console.log(LIVE ? "\nAll checks passed — calculator matches the API."
                 : "\nToken counts printed. Re-run with --live to reconcile dollars.");

/** Ingests the event and asserts the tracker booked what the formula expects. */
async function reconcile(model, label, p, tokens) {
  const expected = expectedCost(p, tokens);
  const booked = await ingest(trackerToken, model, tokens);
  check(`${model} ${label}: booked $${booked} ≈ expected $${expected.toFixed(6)}`,
      Math.abs(booked - expected) < 1e-6);
}

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

async function ingest(token, model, tokens) {
  const res = await fetch(`${TRACKER_URL}/usage`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify({
      projectId: process.env.TRACKER_PROJECT_ID,
      model,
      ...tokens,
      eventTime: new Date().toISOString(),
    }),
  });
  if (!res.ok) throw new Error(`ingest failed: ${res.status}`);
  return Number((await res.json()).costUsd);
}
