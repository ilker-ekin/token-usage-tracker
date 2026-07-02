# Token Usage Tracker

A self-hosted API that ingests LLM token usage events and exposes cost and consumption analytics per organization, team, project, and member.

## Stack

- Java 21, Spring Boot 4.1
- PostgreSQL + Flyway migrations
- Redis
- Testcontainers (integration tests)

## Running tests

```bash
cd token-usage-tracker
./mvnw test
```

Docker must be running — Testcontainers spins up PostgreSQL automatically.

## CI

Tests run automatically on every pull request to `main`. Merging is blocked until CI passes.

## Security

All endpoints require authentication except `GET /actuator/health`, which is intentionally public for load balancer health probes.
