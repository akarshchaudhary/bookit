#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
ADMIN_TOKEN="${ADMIN_TOKEN:-admin-secret-token}"
HOT_SEAT="${HOT_SEAT:-A12}"
CONCURRENCY="${CONCURRENCY:-200}"
USERS="${USERS:-200}"

echo "==> Burst against ${BASE_URL}"
echo "==> Waiting for readiness..."
for i in $(seq 1 60); do
  if curl -sf "${BASE_URL}/readyz" >/dev/null; then
    break
  fi
  sleep 1
  if [[ "$i" -eq 60 ]]; then
    echo "Service never became ready"
    exit 1
  fi
done

SEATS_JSON='["A1","A2","A3","A4","A5","A6","A7","A8","A9","A10","A11","A12","A13","A14","A15","A16","A17","A18","A19","A20"]'
SHOW_RESP=$(curl -sf -X POST "${BASE_URL}/shows" \
  -H "Content-Type: application/json" \
  -H "X-Admin-Token: ${ADMIN_TOKEN}" \
  -d "{\"name\":\"burst-$(date +%s)\",\"seats\":${SEATS_JSON},\"price_paise\":25000,\"per_user_limit\":4}")
SHOW_ID=$(echo "$SHOW_RESP" | sed -n 's/.*"id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
echo "==> Created show ${SHOW_ID}"

TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

echo "==> Hot-seat storm: ${CONCURRENCY} requests for seat ${HOT_SEAT}"
seq 1 "$CONCURRENCY" | xargs -P "$CONCURRENCY" -I{} bash -c '
  BASE_URL="$0"; SHOW_ID="$1"; HOT_SEAT="$2"; USERS="$3"; TMP_DIR="$4"; N="$5"
  USER_NUM=$(( (N % USERS) + 1 ))
  TOKEN=$(printf "user-token-%03d" "$USER_NUM")
  KEY="burst-${N}-$(date +%s%N)"
  CODE=$(curl -s -o "$TMP_DIR/body-$N.json" -w "%{http_code}" \
    -X POST "${BASE_URL}/shows/${SHOW_ID}/reserve" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -H "X-Request-Id: burst-${N}" \
    -d "{\"seats\":[\"${HOT_SEAT}\"],\"idempotency_key\":\"${KEY}\"}" || echo 000)
  echo "$CODE" > "$TMP_DIR/code-$N.txt"
' "$BASE_URL" "$SHOW_ID" "$HOT_SEAT" "$USERS" "$TMP_DIR" {}

CONFIRMED=0
DECLINED=0
SERVER=0
OTHER=0
for f in "$TMP_DIR"/code-*.txt; do
  code=$(cat "$f")
  case "$code" in
    201) CONFIRMED=$((CONFIRMED+1)) ;;
    409|400|403|401) DECLINED=$((DECLINED+1)) ;;
    5*|000) SERVER=$((SERVER+1)) ;;
    *) OTHER=$((OTHER+1)) ;;
  esac
done

# Idempotent retry of first confirmed winner's key is hard to extract; do a dedicated replay check
REPLAY_TOKEN="user-token-001"
REPLAY_KEY="idem-replay-demo"
curl -s -o /dev/null -X POST "${BASE_URL}/shows/${SHOW_ID}/reserve" \
  -H "Authorization: Bearer ${REPLAY_TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{\"seats\":[\"A2\"],\"idempotency_key\":\"${REPLAY_KEY}\"}" || true
REPLAY_CODE=$(curl -s -o "$TMP_DIR/replay.json" -w "%{http_code}" \
  -X POST "${BASE_URL}/shows/${SHOW_ID}/reserve" \
  -H "Authorization: Bearer ${REPLAY_TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{\"seats\":[\"A2\"],\"idempotency_key\":\"${REPLAY_KEY}\"}")

STATE=$(curl -sf "${BASE_URL}/shows/${SHOW_ID}")
TOTAL=$(echo "$STATE" | sed -n 's/.*"total_seats"[[:space:]]*:[[:space:]]*\([0-9]*\).*/\1/p')
AVAILABLE=$(echo "$STATE" | sed -n 's/.*"available"[[:space:]]*:[[:space:]]*\([0-9]*\).*/\1/p')
HELD=$(echo "$STATE" | sed -n 's/.*"held"[[:space:]]*:[[:space:]]*\([0-9]*\).*/\1/p')
CONF=$(echo "$STATE" | sed -n 's/.*"confirmed"[[:space:]]*:[[:space:]]*\([0-9]*\).*/\1/p')
SUM=$((AVAILABLE + HELD + CONF))

echo ""
echo "=== Outcome distribution (hot-seat storm) ==="
echo "confirmed(201): $CONFIRMED"
echo "declined(4xx):  $DECLINED"
echo "server(5xx):    $SERVER"
echo "other:          $OTHER"
echo "idempotent replay HTTP: $REPLAY_CODE"
echo ""
echo "=== Reconciliation ==="
echo "available=$AVAILABLE held=$HELD confirmed=$CONF total=$TOTAL sum=$SUM"
if [[ "$SUM" -eq "$TOTAL" && "$CONFIRMED" -eq 1 && "$SERVER" -eq 0 ]]; then
  echo "PASS: invariant holds, exactly one hot-seat winner, zero 5xx"
  exit 0
fi
echo "FAIL: check distribution / invariant"
exit 1
