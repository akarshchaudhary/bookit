# Seat Reservation at Scale

Minimal Spring Boot 17 JSON API that sells assigned seats under contention. Atomic claims live in PostgreSQL (`SELECT … FOR UPDATE` in sorted seat order + status guards). Multi-seat requests are **all-or-nothing**.

## Quick start (Docker)

```bash
docker compose up --build
```

- API: http://localhost:8080  
- Liveness: `GET /healthz`  
- Readiness (DB): `GET /readyz`  
- Metrics: `GET /metrics`  

Admin token (default): `admin-secret-token`  
User tokens: `user-token-001` … `user-token-500`

## API

### Create show (admin)

```bash
curl -s -X POST http://localhost:8080/shows \
  -H "Content-Type: application/json" \
  -H "X-Admin-Token: admin-secret-token" \
  -d '{"name":"friday-night","seats":["A1","A2","A12"],"price_paise":25000,"per_user_limit":4}'
```

### Reserve (user)

Identity comes **only** from `Authorization: Bearer <token>` (never from the body).

```bash
curl -s -X POST http://localhost:8080/shows/<SHOW_ID>/reserve \
  -H "Authorization: Bearer user-token-001" \
  -H "Content-Type: application/json" \
  -d '{"seats":["A12"],"idempotency_key":"req-1"}'
```

- Success: `201` with `status: confirmed`
- Idempotent replay (same key + same seats): `200` with `Idempotent-Replay: true` and the original body
- Key may come from body `idempotency_key` or `Idempotency-Key` header (header wins)
- Seat taken / over limit / idempotency body mismatch: `409` with `reason`
- Malformed JSON / validation: `400`, DB down: `503` (never `500` for domain outcomes)
- Partial multi-seat: **all-or-nothing** (if any seat is unavailable, none are taken)

### Cancel (owner)

```bash
curl -s -X POST http://localhost:8080/reservations/<RESERVATION_ID>/cancel \
  -H "Authorization: Bearer user-token-001"
```

### Show state

```bash
curl -s http://localhost:8080/shows/<SHOW_ID>
```

Invariant: `available + held + confirmed == total_seats` (held is always `0` in this model — reserves confirm immediately).

## One-command burst

```bash
chmod +x burst.sh
./burst.sh http://localhost:8080
```

Optional env: `ADMIN_TOKEN`, `HOT_SEAT` (default `A12`), `CONCURRENCY` (default `200`), `USERS` (default `200`).

On Windows (Git Bash / WSL):

```bash
./burst.sh http://localhost:8080
```

Or PowerShell helper:

```powershell
.\burst.ps1 http://localhost:8080
```

The script creates a show, storms one hot seat with many users, prints outcome distribution (201 / 4xx / 5xx), checks an idempotent replay, and verifies reconciliation.

## Local build without Compose

Requires Java 17+ and Postgres.

```bash
./mvnw spring-boot:run
# or
./mvnw -DskipTests package && java -jar target/seat-reservation-1.0.0.jar
```

Concurrency IT (needs Docker for Testcontainers):

```bash
./mvnw -Dgroups=concurrency verify
```

## Deploy

### Option A — Render (Blueprint)

1. Push this repo to GitHub (public).  
2. In Render: **New → Blueprint** → select the repo (`render.yaml`).  
3. Set/confirm `ADMIN_TOKEN`.  
4. After deploy, hit `https://<service>.onrender.com/readyz`.

### Option B — Railway

```bash
railway login
railway init
railway add --database postgres
railway up
railway domain
```

Set `ADMIN_TOKEN`. `DATABASE_URL` from the Postgres plugin is accepted (`postgres://…` is rewritten to JDBC).

### Env vars

- `DATABASE_URL` — `jdbc:postgresql://…` or `postgres://user:pass@host:port/db`  
- `DATABASE_USERNAME` / `DATABASE_PASSWORD` if not embedded in the URL  
- `ADMIN_TOKEN`  
- `PORT` (platform default)

### Live URL

See [WRITEUP.md](WRITEUP.md) for the deployed URL once live.

## Observability

- Structured logs include `request_id` (echoed as `X-Request-Id`)  
- Prometheus metrics on `/metrics`:
  - `reservations_confirmed_total`
  - `reservations_declined_total{reason=…}` (`seat_taken`, `per_user_limit`, `idempotent_replay`, `idempotent_conflict`, …)
  - `seats_available`

## Design notes

See [WRITEUP.md](WRITEUP.md) for the atomic decision, idempotency, and AI usage notes.
