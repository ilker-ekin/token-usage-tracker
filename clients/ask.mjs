#!/usr/bin/env node
/**
 * Call Claude and report every request's token usage to the
 * token-usage-tracker so it shows up on the dashboard.
 *
 * One-shot (answers once, reports, exits):
 *   npm run ask -- [--model <model-id>] "Explain BigDecimal rounding"
 *
 * Chat mode (no prompt argument — multi-turn REPL, reports each turn):
 *   npm run ask
 *   > why is my docker build slow?
 *   > /exit
 *
 * Credentials come from the gitignored clients/.env (see .env.example),
 * which keeps the API key out of shell history.
 *
 * Env:
 *   ANTHROPIC_API_KEY    Claude API key (read by the SDK)
 *   TRACKER_URL          default http://localhost:8080
 *   TRACKER_EMAIL        tracker member login
 *   TRACKER_PASSWORD     tracker member password
 *   TRACKER_PROJECT_ID   project UUID to book the usage under
 *   CLAUDE_MODEL         model when --model is absent (default claude-opus-4-8)
 *
 * The tracker must have a pricing row for the chosen model or ingest
 * returns 422 — seeded models: claude-opus-4-8, claude-sonnet-4-6,
 * claude-haiku-4-5. Add others via POST /model-pricing (admin).
 */
import Anthropic from "@anthropic-ai/sdk";
import readline from "node:readline/promises";

const TRACKER_URL = process.env.TRACKER_URL ?? "http://localhost:8080";

const args = process.argv.slice(2);
let model = process.env.CLAUDE_MODEL ?? "claude-opus-4-8";
const modelFlag = args.indexOf("--model");
if (modelFlag !== -1) {
  model = args[modelFlag + 1];
  args.splice(modelFlag, 2);
}
const prompt = args.join(" ");

const client = new Anthropic();
let trackerToken = null;
let sessionCost = 0;

if (prompt) {
  await turn([{ role: "user", content: prompt }]);
} else {
  await chat();
}

/** Streams one exchange, reports its usage, returns the assistant message. */
async function turn(messages) {
  // Adaptive thinking exists on Opus 4.6+, Sonnet 4.6, and Fable 5;
  // older/smaller models (e.g. Haiku 4.5) reject the parameter with a 400.
  const supportsAdaptive = /opus-4-[6-9]|sonnet-4-6|fable|mythos/.test(model);
  const stream = client.messages.stream({
    model,
    max_tokens: 64000,
    ...(supportsAdaptive ? { thinking: { type: "adaptive" } } : {}),
    messages,
  });

  for await (const event of stream) {
    if (event.type === "content_block_delta" && event.delta.type === "text_delta") {
      process.stdout.write(event.delta.text);
    }
  }
  const message = await stream.finalMessage();
  process.stdout.write("\n");

  // usage.input_tokens is uncached input only. Cache reads and writes are
  // billed at their own rates, so they are reported as separate fields and
  // the tracker prices each class on its own.
  const usage = message.usage;
  const tokens = {
    inputTokens: usage.input_tokens,
    outputTokens: usage.output_tokens,
    cacheReadTokens: usage.cache_read_input_tokens ?? 0,
    cacheCreationTokens: usage.cache_creation_input_tokens ?? 0,
  };

  try {
    const cost = await reportUsage(message.model, tokens);
    sessionCost += cost;
    console.error(
      `[usage] in=${tokens.inputTokens} out=${tokens.outputTokens}` +
      ` cache_read=${tokens.cacheReadTokens} cache_write=${tokens.cacheCreationTokens}` +
      ` cost=$${cost.toFixed(6)}` +
      (sessionCost !== cost ? ` session=$${sessionCost.toFixed(6)}` : ""),
    );
  } catch (e) {
    // Never fail the user's request because telemetry is down.
    console.error(`[usage] tracker report failed: ${e.message}`);
  }
  return message;
}

async function chat() {
  console.error(`Chat mode — model ${model}, /exit or Ctrl+D to quit.`);
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  const messages = [];

  while (true) {
    let line;
    try {
      line = await rl.question("\n> ");
    } catch {
      break; // Ctrl+C
    }
    if (line === undefined || line.trim() === "/exit") break;
    if (line.trim() === "") continue;

    messages.push({ role: "user", content: line });
    try {
      const message = await turn(messages);
      // Append the full content (incl. thinking blocks) — required for
      // correct multi-turn behavior on thinking models.
      messages.push({ role: "assistant", content: message.content });
    } catch (e) {
      messages.pop(); // keep history consistent with what the model saw
      console.error(`[error] ${e.message ?? e}`);
    }
  }
  rl.close();
  console.error(`\nSession total booked: $${sessionCost.toFixed(6)}`);
}

async function reportUsage(usedModel, tokens, idempotencyKey = crypto.randomUUID()) {
  const { TRACKER_EMAIL, TRACKER_PASSWORD, TRACKER_PROJECT_ID } = process.env;
  if (!TRACKER_EMAIL || !TRACKER_PASSWORD || !TRACKER_PROJECT_ID) {
    throw new Error("TRACKER_EMAIL / TRACKER_PASSWORD / TRACKER_PROJECT_ID not set");
  }

  if (!trackerToken) {
    const loginRes = await fetch(`${TRACKER_URL}/auth/token`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email: TRACKER_EMAIL, password: TRACKER_PASSWORD }),
    });
    if (!loginRes.ok) throw new Error(`login failed: ${loginRes.status}`);
    trackerToken = (await loginRes.json()).token;
  }

  const ingestRes = await fetch(`${TRACKER_URL}/usage`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${trackerToken}`,
      // Same key on the retry below, so a turn is never booked twice.
      "Idempotency-Key": idempotencyKey,
    },
    body: JSON.stringify({
      projectId: TRACKER_PROJECT_ID,
      model: usedModel,
      ...tokens,
      eventTime: new Date().toISOString(),
    }),
  });
  if (ingestRes.status === 401) {
    trackerToken = null; // token expired mid-session; retry once fresh
    return reportUsage(usedModel, tokens, idempotencyKey);
  }
  if (!ingestRes.ok) throw new Error(`ingest failed: ${ingestRes.status}`);
  return Number((await ingestRes.json()).costUsd);
}
