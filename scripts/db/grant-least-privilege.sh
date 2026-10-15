#!/usr/bin/env bash
# Least-privilege grants for the APPLICATION database user (ADR-0008).
#
# - Gateway tables:            SELECT, INSERT, UPDATE, DELETE   (no DROP/ALTER/TRUNCATE/TRIGGER)
# - Ledger and reference data: SELECT only (ledger_*, ref_*, flyway_schema_history)
# - Ledger procedures:         EXECUTE on the public ones only (never ledger_post_internal)
#
# Migrations must run as a SEPARATE migration user (spring.flyway.user / FLYWAY_USER), which
# owns the schema and is the DEFINER of the ledger procedures.
#
# Re-run after every release that adds tables (it is idempotent: revokes, then re-grants).
# usage: grant-least-privilege.sh <host> <port> <admin_user> <admin_password> <database> <app_user> [app_user_host] [--print]
set -euo pipefail
HOST=$1 PORT=$2 ADMIN=$3 PASS=$4 DB=$5 APP=$6 APPHOST=${7:-%} MODE=${8:-apply}
export MYSQL_PWD="$PASS"
m() { mysql --host="$HOST" --port="$PORT" --user="$ADMIN" --protocol=TCP -N -B "$@"; }

PUBLIC_PROCS="ledger_open_account ledger_post ledger_reverse ledger_close_period ledger_reopen_period ledger_seal ledger_integrity_report"

{
  echo "REVOKE ALL PRIVILEGES, GRANT OPTION FROM '$APP'@'$APPHOST';"
  m -e "SELECT table_name FROM information_schema.tables WHERE table_schema='$DB' AND table_type='BASE TABLE' ORDER BY table_name" |
  while read -r t; do
    case "$t" in
      ledger_*|ref_*|flyway_schema_history) echo "GRANT SELECT ON \`$DB\`.\`$t\` TO '$APP'@'$APPHOST';" ;;
      *)                                    echo "GRANT SELECT, INSERT, UPDATE, DELETE ON \`$DB\`.\`$t\` TO '$APP'@'$APPHOST';" ;;
    esac
  done
  for p in $PUBLIC_PROCS; do
    echo "GRANT EXECUTE ON PROCEDURE \`$DB\`.\`$p\` TO '$APP'@'$APPHOST';"
  done
} > /tmp/grant-least-privilege.$$.sql

if [ "$MODE" = "--print" ]; then cat /tmp/grant-least-privilege.$$.sql; else m < /tmp/grant-least-privilege.$$.sql; echo "grants applied for '$APP'@'$APPHOST' on $DB"; fi
rm -f /tmp/grant-least-privilege.$$.sql
