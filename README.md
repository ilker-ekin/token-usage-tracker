# Token Usage Tracker

A self-hosted API that ingests LLM token usage events and exposes cost and consumption analytics per organization, team, project, and member — with a React admin dashboard.

## Stack

- Java 21, Spring Boot 4.1 (Maven)
- PostgreSQL + Flyway migrations
- Redis (analytics cache, 5-min TTL)
- JWT auth (HS256)
- React + Vite + TypeScript dashboard (`ui/`)
- Testcontainers integration tests

## API

| Endpoint | Description |
|---|---|
| `POST /auth/token` | Email + password → JWT |
| `GET /auth/me` | Current member info |
| `POST /usage` | Ingest a usage event (cost computed from pricing at event time) |
| `GET /usage` | Paginated raw events, filterable by member/project/time |
| `GET /analytics/{projects\|members\|teams\|daily}` | Org-scoped aggregates for a date range |
| `POST /model-pricing` | Add a pricing entry (admin only) |
| `GET /model-pricing` | List pricing entries |

All endpoints require a Bearer token except `POST /auth/token` and `GET /actuator/health`.

## Running locally

```bash
# 1. Start Postgres + Redis
cd token-usage-tracker
docker compose up -d

# 2. Run the API with seed data (1 org, 10 members, 500 events)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,local

# 3. Run the dashboard
cd ../ui
npm install
npm run dev   # http://localhost:5173, proxies to the API on :8080
```

Seeded login: `member1@acme.dev` / `seed-password` (admin).

## Tests

```bash
cd token-usage-tracker
./mvnw test
```

Docker must be running — Testcontainers starts throwaway Postgres and Redis containers.

CI runs the same suite on every pull request to `main`; merging is blocked until it passes.

## Configuration

| Env var | Default | Notes |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/token_usage` | |
| `DB_USERNAME` / `DB_PASSWORD` | `tut` / empty | |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | |
| `JWT_SECRET` | dev-only fallback | Required outside the `dev` profile — startup fails without it |
| `JWT_TTL` | `PT12H` | Token lifetime |
