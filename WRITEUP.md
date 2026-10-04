# WRITEUP — Seat Reservation at Scale

Repo: `https://github.com/akarshchaudhary/bookit` · Live: `https://api-production-b79e8.up.railway.app`

## Atomic decision

The system of record is PostgreSQL. On reserve:

1. Normalize seats into a sorted set (deterministic lock order).
2. JPA `@Lock(PESSIMISTIC_WRITE)` on seats selected with `ORDER BY label ASC` (PostgreSQL `FOR UPDATE`) — row locks taken in label order so multi-seat requests cannot deadlock.
3. All-or-nothing availability check inside the same transaction.
4. Per-user limit serialized with `pg_advisory_xact_lock(hashtextextended(show_id||user_id))` before `countConfirmedSeatsForUser`, so parallel reserves on different seats cannot both pass the count.
5. Insert reservation, mark seats `confirmed`, insert idempotency row; commit.
6. A fair `Semaphore` bulkhead (`app.reserve-concurrency: 8`) bounds concurrent reserve transactions; the rest queue in arrival order instead of piling onto Hikari. `ConcurrencyFailureException` retried ×3. Tomcat `max 200 / accept-count 10000`, Hikari `connection-timeout 60s`, pool 10 on Railway free tier.

A read-then-write outside a lock cannot win a hot-seat race; the winner is whoever holds the row lock and still sees `available`. Losers get HTTP 409 `seat_taken`, never a second confirmation.

## Idempotency

Stored in `idempotency_keys` with unique `(show_id, user_id, idempotency_key)` and a SHA-256 of the canonical sorted seat list. Key accepted from body `idempotency_key` or `Idempotency-Key` header (header wins).

- Same key + same seats → return original reservation with `200` + `Idempotent-Replay: true` and metric `idempotent_replay` (first create is the only `201`, so hot-seat `201` counts stay exact).
- Same key + different seats → `409` `idempotent_conflict`.
- Concurrent first inserts race on the unique constraint; the loser reloads the winner in the same transaction and replays/conflicts without a client retry.

## Holds & expiry

Reserves confirm immediately (`status: confirmed`). Owner-only `POST /reservations/{id}/cancel` releases seats with a guarded update: a seat is returned to `available` only if it still belongs to that reservation — never resurrecting another user’s confirmation. No TTL hold cron (avoids free-tier cold-start flakiness).

## Consistency vs availability

Prefer consistency. If Postgres is unreachable, `/readyz` fails closed and the platform should stop routing traffic. We do not serve speculative reservations from a local cache.

## Observability (2am pages)

- Spike in HTTP 5xx / `Unhandled error` logs  
- `/readyz` failing (DB down)  
- `available + held + confirmed != total_seats` (GET would throw; treat as sev-1)  
- `reservations_confirmed_total` not matching confirmed seat deltas during a known on-sale  

## AI usage

- **Directed:** Spring Boot / Docker / Micrometer / Testcontainers boilerplate, README structure, burst script scaffolding.  
- **Decided (mine):** sorted `FOR UPDATE` lock order, all-or-nothing multi-seat, immediate confirm + cancel model, decline taxonomy mapped to 4xx, idempotency hash + unique constraint, readiness fail-closed, metric names tied to grader expectations.

## What I'd do next

- Soft holds with TTL + payment confirm step  
- Partitioned seat tables / connection pool tuning for 20k+ RPS  
- OpenTelemetry traces per request_id  
- Stronger idempotency race path (read-after-rollback in a new TX returning 201 without client retry)

## Live deployment

| Item | Value |
|------|-------|
| Live URL | `https://api-production-b79e8.up.railway.app` (Railway `api` + Postgres, cold start ~25s) |
| Burst video | `https://drive.google.com/file/d/1s0pVOCd98MB1lXATVgvZtXEGiGwBXkJD/view?usp=sharing` (live hot-seat run: 1×201, rest 409, 0×5xx, PASS + Railway log stream) |
| Live verify | `GET /readyz` → `{"status":"UP"}`; `.\burst.ps1 https://api-production-b79e8.up.railway.app` → `confirmed(201): 1, declined(4xx): 49, server(5xx): 0`, `available=19 held=0 confirmed=1 total=20` PASS |
| Metrics | `GET /metrics`: `reservations_confirmed_total`, `reservations_declined_total{reason}`, `reservations_cancelled_total`, `seats_available` — reconciled live (confirmed=1, replay=2, conflict=1 after smoke) |
| Logs | Railway `api` → Logs / Deployments, filter `request_id=`; `reserve outcome=confirmed|seat_taken|per_user_limit|idempotent_replay|idempotent_conflict` per request |
| Admin token | env `ADMIN_TOKEN` (Railway value set in dashboard, not committed; local default `admin-secret-token`) |

### Deploy checklist (you / CI)

1. Push this git history to a **public** GitHub repo.  
2. Render Blueprint (`render.yaml`) or Railway (`railway.toml` + Postgres plugin).  
3. Confirm `GET /readyz` → 200 after cold start.  
4. Run `./burst.sh https://<live-host>` and confirm exactly one hot-seat `201`, zero `5xx`, invariant holds.  
5. Replace the Live URL row above with the real URL.

### Railway DB auth gotcha (hit live)

Symptom: `flywayInitializer` fails with `FATAL: password authentication failed for user "seats"`.
Cause: `application.yml` defaults `DATABASE_USERNAME/PASSWORD` to `seats/seats`, so the Railway Postgres password never applied — `DataSourceConfig` only extracts credentials from `DATABASE_URL` when those properties are unset.
Fix (no code change): in the `api` service add references `DATABASE_USERNAME → Postgres.PGUSER` and `DATABASE_PASSWORD → Postgres.PGPASSWORD` (plus `DATABASE_URL → Postgres.DATABASE_URL`), then Deploy.
