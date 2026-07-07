# Token Usage Tracker

Track how much your team spends on LLM API calls. Applications report their token usage after each call; the tracker prices every event, aggregates the numbers, and shows spend per organization, team, project, and member on a web dashboard.

## How it works

```
your app ──POST /usage──▶  API  ──▶  PostgreSQL (events + rollups)
                            │
dashboard ◀─/analytics──────┴──▶  Redis (5-min cache)
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

**Prerequisites:** Docker Desktop (running), Java 21, Node.js 20+.

```bash
# 1. Start PostgreSQL and Redis
cd token-usage-tracker
docker compose up -d

# 2. Start the API with demo data (1 org, 10 members, 500 events)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,local

# 3. Start the dashboard (new terminal)
cd ui
npm install
npm run dev
```

Open http://localhost:5173 and log in:

| Email | Password | Role |
|---|---|---|
| `member1@acme.dev` | `seed-password` | Admin |
| `member2@acme.dev` … `member10@acme.dev` | `seed-password` | Member |

You should see 30 days of demo spend across the Overview, Trends, Members, Projects, and Teams pages.

## API overview

All endpoints require a `Authorization: Bearer <token>` header except login and health.

| Endpoint | What it does |
|---|---|
| `POST /auth/token` | Log in — returns a JWT |
| `GET /auth/me` | Who am I (from the token) |
| `POST /usage` | Report a usage event (tokens in/out, model, project) |
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
  -d '{"projectId":"<uuid>","model":"claude-opus-4-8","inputTokens":1200,"outputTokens":450,"eventTime":"2026-07-07T12:00:00Z"}'
```

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

## Running the tests

```bash
cd token-usage-tracker
./mvnw test
```

Docker must be running — the tests start their own throwaway Postgres and Redis containers, so nothing you run locally is touched. The same suite runs in CI on every pull request, and merging is blocked until it passes.

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
ui/                    React admin dashboard
clients/               Example CLI that calls Claude and reports usage
```
