#!/usr/bin/env bash
# Phase 11 staging load test with connection-pool and concurrency watch.
#
#   BASE=http://localhost:8085 METRICS_TOKEN=<PAYPORT_METRICS_TOKEN> [CUSTOMER_TOKEN=<a staging customer login>] \
#     scripts/staging/load-test.sh
#
# Runs scripts/phase9/k6-staging.js (thresholds = SLOs) while sampling the gateway's HikariCP pool every
# 5 s from /actuator/prometheus, then scans the staging log for pool timeouts, deadlocks and lock waits
# raised during the run. Exit 0 only if k6 met every threshold AND no pool/concurrency error occurred.
# Output: staging-evidence/load-<time>/ (k6 summary, pool samples CSV, log excerpts, report.txt).
set -uo pipefail
BASE=${BASE:-http://localhost:8085}
case "$BASE" in *:8084*|*api.payport*) echo "refusing: never load-test production ($BASE)"; exit 2;; esac
: "${METRICS_TOKEN:?METRICS_TOKEN (the staging PAYPORT_METRICS_TOKEN) is required to watch the pool}"
LOG=${LOG:-/opt/payport/staging/staging.log}
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${OUT_DIR:-$PWD/staging-evidence}/load-$(date +%Y%m%d-%H%M%S); mkdir -p "$OUT"
command -v k6 >/dev/null || { echo "k6 is not installed: https://grafana.com/docs/k6/latest/set-up/install-k6/"; exit 1; }

log_start=$( [ -f "$LOG" ] && wc -l < "$LOG" || echo 0 )
echo "time,active,idle,pending,max,timeouts_total" > "$OUT/pool.csv"
(
  while true; do
    curl -sS -H "Authorization: Bearer $METRICS_TOKEN" "$BASE/actuator/prometheus" 2>/dev/null | awk -v t="$(date +%T)" '
      /^hikaricp_connections_active[{ ]/ {a=$NF} /^hikaricp_connections_idle[{ ]/ {i=$NF}
      /^hikaricp_connections_pending[{ ]/ {p=$NF} /^hikaricp_connections_max[{ ]/ {m=$NF}
      /^hikaricp_connections_timeout_total[{ ]/ {to=$NF}
      END {printf "%s,%s,%s,%s,%s,%s\n", t, a, i, p, m, to}' >> "$OUT/pool.csv"
    sleep 5
  done
) & sampler=$!

k6 run -e BASE="$BASE" -e CUSTOMER_TOKEN="${CUSTOMER_TOKEN:-}" --summary-export "$OUT/k6-summary.json" "$HERE/../phase9/k6-staging.js" | tee "$OUT/k6.txt"
k6_exit=${PIPESTATUS[0]}
sleep 6; kill $sampler 2>/dev/null

[ -f "$LOG" ] && tail -n +"$((log_start + 1))" "$LOG" | grep -nE "Connection is not available|HikariPool.*timed out|Deadlock found|Lock wait timeout|CannotAcquireLock|PessimisticLockingFailure" > "$OUT/concurrency-errors.txt"
errors=$(grep -c . "$OUT/concurrency-errors.txt" 2>/dev/null || echo 0)
peak_pending=$(awk -F, 'NR>1 && $4!="" {if ($4+0>m) m=$4+0} END {print m+0}' "$OUT/pool.csv")
peak_active=$(awk -F, 'NR>1 && $2!="" {if ($2+0>m) m=$2+0} END {print m+0}' "$OUT/pool.csv")
timeouts=$(awk -F, 'NR>1 && $6!="" {first = (first=="" ? $6 : first); last=$6} END {print (last-first)+0}' "$OUT/pool.csv")
{
  echo "PayPort staging load test, $BASE, $(date -u +%FT%TZ)"
  echo "k6 thresholds (SLOs):          $([ "$k6_exit" = 0 ] && echo MET || echo "BREACHED (exit $k6_exit)")"
  echo "pool: peak active / max:       $peak_active / $(awk -F, 'NR>1 && $5!="" {m=$5} END {print m}' "$OUT/pool.csv")"
  echo "pool: peak waiting threads:    $peak_pending"
  echo "pool: connection timeouts:     $timeouts"
  echo "log: pool/deadlock/lock errors: $errors (see concurrency-errors.txt)"
  if [ "$k6_exit" = 0 ] && [ "$errors" = 0 ] && [ "$timeouts" = 0 ]; then echo "RESULT: PASS"; else echo "RESULT: FAIL - investigate before go-live (record each cause and fix in the Phase 11 report)"; fi
} | tee "$OUT/report.txt"
grep -q "RESULT: PASS" "$OUT/report.txt"
