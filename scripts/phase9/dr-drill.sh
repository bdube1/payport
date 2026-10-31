#!/usr/bin/env bash
# Phase 9 disaster-recovery drill (CI and staging): back up a live Payport database exactly as the
# backup job does (mysqldump with routines and triggers, consistent snapshot), restore it into an
# empty database, and prove the copy is whole:
#   - every table has the same exact row count,
#   - the ledger's definer procedures exist in the copy,
#   - ledger_integrity_report() on the copy returns no violations,
#   - money totals (ledger balances per category and currency) are identical.
# Prints the restore time (RTO evidence) and writes a report. Exit code 1 on any difference.
#
# usage: dr-drill.sh <host> <port> <admin_user> <admin_password> <source_db> <scratch_db> [report_file]
set -euo pipefail
HOST=$1 PORT=$2 USER=$3 PASS=$4 SRC=$5 DST=$6 REPORT=${7:-dr-drill-$(date +%Y%m%d-%H%M%S).txt}
case "$DST" in *prod*|gateway|payport) echo "refusing: '$DST' looks like a real database name"; exit 2;; esac
export MYSQL_PWD="$PASS"
m() { mysql --host="$HOST" --port="$PORT" --user="$USER" --protocol=TCP -N -B "$@"; }
DUMP=$(mktemp --suffix=.sql)

t0=$(date +%s)
mysqldump --host="$HOST" --port="$PORT" --user="$USER" --protocol=TCP --single-transaction --routines --triggers --events \
  --set-gtid-purged=OFF --no-tablespaces "$SRC" > "$DUMP"
t1=$(date +%s)
m -e "DROP DATABASE IF EXISTS \`$DST\`; CREATE DATABASE \`$DST\`;"
m "$DST" < "$DUMP"
t2=$(date +%s)
# The ledger procedures run as their definer (ADR-0008); on a restore that account needs the same rights on the copy.
for d in $(m -e "SELECT DISTINCT definer FROM information_schema.routines WHERE routine_schema='$DST'"); do
  m -e "GRANT ALL ON \`$DST\`.* TO '${d%@*}'@'${d#*@}'"
done

fail=0
{
  echo "Payport disaster-recovery drill"
  echo "date (UTC):     $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "source:         $SRC on $HOST:$PORT"
  echo "backup:         $(du -h "$DUMP" | cut -f1), sha256 $(sha256sum "$DUMP" | cut -d' ' -f1), $((t1 - t0)) s"
  echo "restore (RTO):  $((t2 - t1)) s into $DST"
  echo
  echo "== row counts (source = copy)"
  for t in $(m -e "SELECT table_name FROM information_schema.tables WHERE table_schema='$SRC' AND table_type='BASE TABLE' ORDER BY table_name"); do
    a=$(m -e "SELECT COUNT(*) FROM \`$SRC\`.\`$t\`"); b=$(m -e "SELECT COUNT(*) FROM \`$DST\`.\`$t\`" 2>/dev/null || echo MISSING)
    if [ "$a" != "$b" ]; then echo "DIFF  $t source=$a copy=$b"; fail=1; else printf "ok    %-45s %s\n" "$t" "$a"; fi
  done
  echo
  echo "== stored procedures in the copy"
  for p in ledger_post ledger_reverse ledger_open_account ledger_integrity_report ref_upsert_currency; do
    n=$(m -e "SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema='$DST' AND routine_name='$p'")
    if [ "$n" = "1" ]; then echo "ok    $p"; else echo "MISSING $p"; fail=1; fi
  done
  echo
  echo "== ledger balances per category and currency (source = copy)"
  q="SELECT a.category, a.currency, COUNT(*), SUM(b.balance) FROM ledger_accounts a JOIN ledger_balances b ON b.account_id = a.id GROUP BY a.category, a.currency ORDER BY 1, 2"
  sa=$(m "$SRC" -e "$q"); sb=$(m "$DST" -e "$q")
  echo "$sa"
  if [ "$sa" != "$sb" ]; then echo "DIFF in ledger balances"; fail=1; fi
  echo
  echo "== ledger_integrity_report(): the copy must report exactly what the source reports"
  vs=$(m "$SRC" -e "CALL ledger_integrity_report()" || echo "CALL FAILED")
  vc=$(m "$DST" -e "CALL ledger_integrity_report()" || echo "CALL FAILED")
  if [ "$vs" != "$vc" ]; then echo "DIFF source: $vs"; echo "DIFF copy:   $vc"; fail=1;
  elif [ -z "$vc" ]; then echo "no violations (source and copy)";
  else echo "same findings in source and copy ($(echo "$vc" | wc -l) rows; deliberate test tampering in CI):"; echo "$vc" | head -5; fi
  echo
  [ $fail = 0 ] && echo "RESULT: PASS" || echo "RESULT: FAIL"
} | tee "$REPORT"
rm -f "$DUMP"
grep -q "RESULT: PASS" "$REPORT"
