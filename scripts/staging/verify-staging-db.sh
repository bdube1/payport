#!/usr/bin/env bash
# Phase 11: proves the restored staging database survived the migrations intact and the ledger rules hold.
#
#   MYSQL_PWD=<admin password> scripts/staging/verify-staging-db.sh <host> <port> <admin_user> [staging_db]
#
# Run after the first staging start (which applies V32-V52), and again after every test session.
# Exit code 0 = all checks pass; the report goes to staging-evidence/verify-<db>-<time>.txt.
set -uo pipefail
HOST=$1 PORT=$2 USER=$3 DB=${4:-gateway_staging}
[[ "$DB" == *staging* ]] || { echo "refusing: not a staging database"; exit 2; }
OUT=${OUT_DIR:-$PWD/staging-evidence}; mkdir -p "$OUT"
REPORT="$OUT/verify-$DB-$(date +%Y%m%d-%H%M%S).txt"
PRE="$OUT/pre-migration-$DB.txt"
m() { mysql --host="$HOST" --port="$PORT" --user="$USER" --protocol=TCP -N "$DB" -e "$1"; }
failures=0
check() {  # name, actual, expected
  if [ "$2" = "$3" ]; then echo "PASS  $1"; else echo "FAIL  $1: got '$2', expected '$3'"; failures=$((failures+1)); fi
}

{
  echo "PayPort staging verification, $DB, $(date -u +%FT%TZ)"
  echo
  echo "== migrations"
  check "no failed migration" "$(m "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0")" 0
  expected=$(ls "$(cd "$(dirname "$0")/../.." && pwd)/src/main/resources/db/migration" 2>/dev/null | sed -nE 's/^V([0-9]+)__.*/\1/p' | sort -n | tail -1)
  check "latest migration" "$(m "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank DESC LIMIT 1")" "${expected:-53}"

  echo
  echo "== nothing lost or changed by the migrations (against $PRE)"
  if [ -f "$PRE" ]; then
    for t in transactions checkout_sessions settlements users merchants; do
      before=$(awk -v t="$t" '$1=="rows" && $2==t {print $3}' "$PRE")
      [ -n "$before" ] && check "rows in $t (staging traffic adds rows; re-baseline after testing)" "$(m "SELECT COUNT(*) FROM \`$t\`")" "$before"
    done
    diff <(grep '^tx ' "$PRE") <(m "SELECT CONCAT('tx ', status, ' ', currency, ' ', COUNT(*), ' ', SUM(amount_cents)) FROM transactions GROUP BY status, currency ORDER BY status, currency") >/dev/null \
      && echo "PASS  transaction amounts per status and currency identical" || { echo "FAIL  transaction totals differ:"; diff <(grep '^tx ' "$PRE") <(m "SELECT CONCAT('tx ', status, ' ', currency, ' ', COUNT(*), ' ', SUM(amount_cents)) FROM transactions GROUP BY status, currency ORDER BY status, currency"); failures=$((failures+1)); }
    diff <(grep '^stl ' "$PRE") <(m "SELECT CONCAT('stl ', status, ' ', currency, ' ', COUNT(*), ' ', SUM(net_amount_cents)) FROM settlements GROUP BY status, currency ORDER BY status, currency") >/dev/null \
      && echo "PASS  legacy settlement totals identical" || { echo "FAIL  legacy settlement totals differ"; failures=$((failures+1)); }
  else
    echo "SKIP  no pre-migration snapshot (run build-staging-db.sh first)"
  fi

  echo
  echo "== ledger invariants"
  check "every journal balances per currency" "$(m "SELECT COUNT(*) FROM (SELECT entry_id, currency FROM ledger_lines GROUP BY entry_id, currency HAVING SUM(IF(direction='DEBIT', amount_minor, -amount_minor)) <> 0) x")" 0
  check "no wallet or agent float below zero" "$(m "SELECT COUNT(*) FROM ledger_accounts a JOIN ledger_balances b ON b.account_id = a.id WHERE a.category IN ('CUSTOMER_WALLET','AGENT_FLOAT','MERCHANT_PAYABLE') AND b.balance < 0")" 0
  check "balances equal the sum of their lines" "$(m "SELECT COUNT(*) FROM ledger_balances b LEFT JOIN (SELECT account_id, SUM(IF(direction='DEBIT', amount_minor, -amount_minor)) s FROM ledger_lines GROUP BY account_id) l ON l.account_id = b.account_id WHERE b.debit_total - b.credit_total <> COALESCE(l.s, 0)")" 0
  violations=$(m "CALL ledger_integrity_report()" | grep -c . || true)
  check "ledger_integrity_report() is empty" "$violations" 0

  echo
  echo "== money controls"
  check "no settlement batch instructed (PARALLEL mode)" "$(m "SELECT COUNT(*) FROM stl_batches WHERE instructed_at IS NOT NULL")" 0
  echo "INFO  product lines switched on: $(m "SELECT IFNULL(GROUP_CONCAT(code), 'none') FROM service_lines WHERE enabled = TRUE")"
  echo
  [ "$failures" = 0 ] && echo "RESULT: PASS" || echo "RESULT: FAIL ($failures)"
} | tee "$REPORT"
grep -q "RESULT: PASS" "$REPORT"
