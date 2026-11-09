# Token Usage Tracker

[![CI](https://github.com/ilker-ekin/token-usage-tracker/actions/workflows/ci.yml/badge.svg?event=pull_request)](https://github.com/ilker-ekin/token-usage-tracker/actions/workflows/ci.yml)

Track how much your team spends on LLM API calls. Applications report their token usage after each call; the tracker prices every event, aggregates the numbers, and shows spend per organization, team, project, and member on a web dashboard.

## How it works

```
                  ┌───────────────────────── API (Spring Boot) ──────────────────────────┐
 your app ──────▶ │ POST /usage                                                          │
 (clients/)       │   1. auth: member + org from JWT; project must belong to that org    │
                  │   2. price the event at event time (model_pricing, LiteLLM fallback) │
                  │   3. one transaction:  INSERT usage_event                            │
                  │                        UPSERT daily / weekly / monthly rollups ──────┼──▶ PostgreSQL
                  │   4. after commit:     INCR cache version for the org ───────────────┼──▶ Redis
                  │                                                                      │
 dashboard ─────▶ │ GET /analytics/*  ── read-through cache ── Redis hit? ─ else rollups │
 (ui/, nginx)     │ GET /usage        ── raw events, org-scoped, paginated               │
                  │                                                                      │
                  │ 02:30 UTC job: rebuild the last 2 days of rollups from usage_event   │
                  └──────────────────────────────────────────────────────────────────────┘
```

1. An application makes an LLM call and reads the token counts from the response.
2. It reports them to `POST /usage`. The tracker looks up the model's price **at that moment** and stores the event with its cost — so later price changes never rewrite history.
3. Daily, weekly, and monthly summary tables (rollups) are updated in the same transaction.
4. The dashboard reads those summaries through a short-lived Redis cache.

## Tech stack

| Layer | Technology |
|---|---|
| API | Java 21, Spring Boot 4.1, Maven |
| Database | PostgreSQL (Flyway migrations) |
| Cache | Redis |
| Auth | JWT (email + password login) |
| Dashboard | React + Vite + TypeScript (`ui/`) |
| Tests | JUnit + Testcontainers (real Postgres/Redis in Docker) |

## Getting started

### Quick start (Docker only)

```bash
docker compose up --build
```

Open http://localhost:3000 and log in with the demo accounts below. This builds the API and dashboard images, starts PostgreSQL and Redis, and seeds a demo org on first start. The API is also exposed on http://localhost:8080 for `curl` or the `clients/` CLI. `docker compose down -v` removes everything, including the demo data.

### Local development

**Prerequisites:** Docker Desktop (running), Java 21, Node.js 20+.

```bash
# 1. Start PostgreSQL and Redis
cd token-usage-tracker
docker compose up -d

# 2. Start the API with demo data (1 org, 10 members, 500 events)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,local

# 3. Start the dashboard (new terminal)
cd ../ui
npm install
npm run dev
```

Open http://localhost:5173 and log in:

| Email | Password | Role |
|---|---|---|
| `member1@acme.dev` | `seed-password` | Admin |
| `member2@acme.dev` … `member10@acme.dev` | `seed-password` | Member |

You should see 30 days of demo spend across the Overview, Trends, Members, Projects, and Teams pages (on port 3000 for the Docker quick start).

## API overview

All endpoints require a `Authorization: Bearer <token>` header except login and health.

| Endpoint | What it does |
|---|---|
| `POST /auth/token` | Log in — returns a JWT |
| `GET /auth/me` | Who am I (from the token) |
| `POST /usage` | Report a usage event (tokens in/out, cache tokens, model, project) |
| `GET /usage` | Browse raw events, filterable and paginated |
| `GET /analytics/projects` | Spend per project for a date range |
| `GET /analytics/members` | Spend per member (top spenders first) |
| `GET /analytics/teams` | Spend per team |
| `GET /analytics/daily` | Day-by-day time series |
| `POST /model-pricing` | Add a model price (admin only) |
| `GET /model-pricing` | List model prices |

Example — report an event:

```bash
curl -X POST http://localhost:8080/usage \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"projectId":"<uuid>","model":"claude-opus-4-8","inputTokens":1200,"outputTokens":450,"eventTime":"2026-07-07T12:00:00Z"}'
```

`inputTokens` is uncached input only. Prompt-cache usage goes in the optional `cacheReadTokens` and `cacheCreationTokens` fields (default 0), which are priced at the model's cache rates:

| Provider | `inputTokens` | `cacheReadTokens` | `cacheCreationTokens` |
|---|---|---|---|
| Anthropic | `usage.input_tokens` | `usage.cache_read_input_tokens` | `usage.cache_creation_input_tokens` |
| OpenAI | `prompt_tokens - prompt_tokens_details.cached_tokens` | `prompt_tokens_details.cached_tokens` | not billed, send 0 |

OpenAI's `prompt_tokens` already includes cached tokens, so subtract them or they are charged twice. `POST /model-pricing` takes optional `cacheReadPricePer1k` and `cacheCreationPricePer1k`. A pricing row without them charges cache tokens at the input price.

Send an optional `Idempotency-Key` header (1 to 255 characters, unique per org) to make a retry safe. The first request returns `201`. A repeat with the same key returns `200` with the original event and books nothing: no second event, no rollup change. The body of a repeat is not compared with the original, so use a fresh key for every distinct event.

Model prices are seeded from [LiteLLM's public catalog](https://github.com/BerriAI/litellm); unknown models are priced from the same catalog automatically on first use.

## Try it with real Claude calls (optional)

`clients/` contains a small CLI that asks Claude a question and automatically reports the request's real token usage to the tracker:

```bash
cd clients
cp .env.example .env    # then fill in the values — the file explains each one
npm install
npm run ask             # chat mode: every message is answered and tracked
npm run ask -- "one-off question"
npm run verify -- --live   # proves tracker costs match Anthropic's own usage numbers
```

Requires an [Anthropic API key](https://platform.claude.com). `.env` is gitignored, so your key stays on your machine.

## Design decisions

**Cost is snapshotted at ingest.** Each event stores the cost computed from the price that was valid at `eventTime`. Prices are versioned (`valid_from` / `valid_to`), so adding a new price never changes what past usage cost. Unknown models are priced once from LiteLLM's catalog and persisted.

**Rollups are written in the ingest transaction and recomputed nightly.** The dashboard never aggregates raw events. It sums pre-aggregated daily rows, and an event and its rollups commit together or not at all. A 02:30 UTC job rebuilds the last two days (plus their weeks and months) from `usage_event`, the source of truth, as a safety net.

**Concurrency is handled with upserts.** Each rollup write is one `INSERT … ON CONFLICT DO UPDATE SET total = total + EXCLUDED.total`, so parallel ingests for the same member, project and day can't collide on insert or lose an increment. It's backed by a test that fires 50 ingests at once. The unique key is `NULLS NOT DISTINCT` because `team_id` is nullable. With a plain `UNIQUE`, members without a team got one row per event. The nightly job takes a `SHARE ROW EXCLUSIVE` table lock, so ingests queue for its few seconds instead of racing its delete-and-rebuild.

**The read-through cache is evicted by version.** Analytics responses are cached for 5 minutes under keys that embed a per-org version and a global generation. An ingest invalidates its org with one `INCR` after commit, and the nightly job invalidates every org by bumping the generation. There is no `KEYS`/`SCAN`, and a read racing a write can't re-cache stale totals: the reader resolves the version before it queries. If Redis is down, the cache becomes a pass-through and no request fails.

**Tenant isolation comes from the token.** The org always comes from the JWT (or from the member row on ingest), never from request parameters. Every query is filtered by it. Ingest rejects a project from another org with the same 404 as an unknown project, so tenants can't probe each other's ids. Tests cover each endpoint against a second org's data.

## Limitations / next steps

- **One cache-write rate per model.** Anthropic bills 1-hour cache writes at 2x input and 5-minute writes at 1.25x. The tracker stores a single cache-creation price (the 5-minute rate from LiteLLM), so 1-hour writes are under-billed. Costs booked before cache tokens were tracked separately are not recomputed.
- **Model pricing is global.** `model_pricing` is shared by all orgs, and `POST /model-pricing` accepts the `ADMIN` role of *any* org. One customer's admin can therefore change prices for every tenant. A real deployment needs a separate operator role, or per-org price overrides.
- **Idempotency keys never expire and replays are not compared.** A key stays reserved in its org for as long as the event exists, and a repeat with a different body silently returns the original event instead of a `409`.
- **`/analytics/teams` omits team-less spend.** The query inner-joins `team`, so members without a team are missing from that view (they're still in the other views).
- **The recompute window is fixed at two days.** Events arriving later still update their rollups at ingest, but the nightly rebuild won't re-check them. The recompute query also filters `usage_event` on `event_time` alone, so it needs its own index, or monthly partitioning, as the table grows.
- **The recompute job blocks ingest briefly.** While it runs, ingest requests wait on its table lock (seconds at this data size). At larger scale it should rebuild into a staging table and swap, or recompute per org.
- **Cache version keys have no TTL.** That's one tiny key per org. If Redis evicted one under memory pressure, stale entries could be served until their 5-minute TTL expired.
- **JWT claims live for the whole token lifetime.** A member moved to another org keeps the old org in their token for up to `JWT_TTL`, and there's no revocation list.

## Running the tests

```bash
cd token-usage-tracker
./mvnw test
```

Docker must be running — the tests start their own throwaway Postgres and Redis containers, so nothing you run locally is touched. Beyond the endpoint tests, the suite includes concurrency tests (parallel ingests and ingests racing the nightly job), cross-org isolation tests, and a run with Redis unreachable. The same suite runs in CI on every pull request, and merging is blocked until it passes.

## Configuration

Everything is configured through environment variables (sensible localhost defaults for development):

| Variable | Default | Notes |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/token_usage` | |
| `DB_USERNAME` / `DB_PASSWORD` | `tut` / empty | Matches `docker-compose.yml` |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | |
| `JWT_SECRET` | dev-only fallback | **Required in production** — the app refuses to start without it outside the `dev` profile |
| `JWT_TTL` | `PT12H` | Token lifetime |

## Project layout

```
token-usage-tracker/   Spring Boot API (src/main/java/dev/ekin/tokenusage)
  ├─ auth/             JWT login, token filter
  ├─ usage/            Event ingestion + drill-down
  ├─ pricing/          Model prices, cost calculation
  ├─ analytics/        Rollups, aggregate queries, Redis cache
  └─ tenant/           Organizations, teams, members, projects
ui/                    React admin dashboard (+ nginx image for the Docker demo)
clients/               Example CLI that calls Claude and reports usage
docker-compose.yml     Full demo stack: API, UI, PostgreSQL, Redis
```
