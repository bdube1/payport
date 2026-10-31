#!/usr/bin/env bash
# D8 restore drill: proves a production backup can actually be restored, and how long it takes.
# Run on a NON-production server. Records evidence (timings, counts, money totals) in a report.
#
# usage: restore-drill.sh <host> <port> <admin_user> <admin_password> <backup.sql[.gz]> <scratch_db> [report_file]
#   backup: a mysqldump of the production database (data included), as your backup job produces it.
#   scratch_db: created fresh (any existing database with that name is DROPPED - never use a real name).
set -euo pipefail
HOST=$1 PORT=$2 USER=$3 PASS=$4 BACKUP=$5 DB=$6 REPORT=${7:-restore-drill-$(date +%Y%m%d-%H%M%S).txt}
case "$DB" in *prod*|gateway|payport) echo "refusing: '$DB' looks like a real database name"; exit 2;; esac
export MYSQL_PWD="$PASS"
m() { mysql --host="$HOST" --port="$PORT" --user="$USER" --protocol=TCP "$@"; }

m -e "DROP DATABASE IF EXISTS \`$DB\`; CREATE DATABASE \`$DB\`;"
start=$(date +%s)
if [[ "$BACKUP" == *.gz ]]; then gunzip -c "$BACKUP" | m "$DB"; else m "$DB" < "$BACKUP"; fi
end=$(date +%s)

{
  echo "Payport restore drill"
  echo "date (UTC):        $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "backup file:       $BACKUP ($(du -h "$BACKUP" | cut -f1)), sha256 $(sha256sum "$BACKUP" | cut -d' ' -f1)"
  echo "restored into:     $DB on $HOST:$PORT"
  echo "restore duration:  $((end - start)) s"
  echo
  echo "== latest applied migration"
  m -N -e "SELECT CONCAT('V', version, ' ', description, ' (', installed_on, ')') FROM \`$DB\`.flyway_schema_history WHERE success = 1 ORDER BY installed_rank DESC LIMIT 1" 2>/dev/null || echo "(no flyway_schema_history table)"
  echo
  echo "== tables and exact row counts"
  for t in $(m -N -e "SELECT table_name FROM information_schema.tables WHERE table_schema='$DB' AND table_type='BASE TABLE' ORDER BY table_name"); do
    printf "%-45s %s\n" "$t" "$(m -N -e "SELECT COUNT(*) FROM \`$DB\`.\`$t\`")"
  done
  echo
  echo "== money totals (compare with the same queries on production at backup time)"
  m -t -e "SELECT status, currency, COUNT(*) n, SUM(amount_cents) cents FROM \`$DB\`.transactions GROUP BY status, currency ORDER BY status, currency" 2>/dev/null || true
  m -t -e "SELECT status, currency, COUNT(*) n, SUM(net_amount_cents) net_cents FROM \`$DB\`.settlements GROUP BY status, currency" 2>/dev/null || true
  echo
  echo "== latest record timestamps (how fresh is this backup?)"
  m -N -e "SELECT CONCAT('transactions.max(created_at)=', IFNULL(MAX(created_at),'-')) FROM \`$DB\`.transactions" 2>/dev/null || true
  m -N -e "SELECT CONCAT('checkout_sessions.max(created_at)=', IFNULL(MAX(created_at),'-')) FROM \`$DB\`.checkout_sessions" 2>/dev/null || true
} | tee "$REPORT"
echo "report written to $REPORT"
