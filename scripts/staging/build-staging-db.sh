#!/usr/bin/env bash
# Phase 11: builds the ISOLATED staging database from a real backup, and records restore evidence.
#
#   MYSQL_PWD=<admin password> scripts/staging/build-staging-db.sh <host> <port> <admin_user> <backup.sql[.gz]> [staging_db]
#
# <backup.sql[.gz]> should be the output of your normal backup job (that is what the restore drill is meant
# to prove). If you have none yet, take one first, read-only, from production:
#   mysqldump --single-transaction --routines --triggers --no-tablespaces -h <host> -u <user> -p gateway | gzip > gateway-$(date +%F).sql.gz
#
# Steps: restore into <staging_db> (default gateway_staging; dropped and recreated) with the Phase 0.5
# restore drill, then save the pre-migration money totals so verify-staging-db.sh can prove the
# migrations (V32-V52, applied when staging first starts) changed no amount and lost no row.
# Production is only ever read (by the dump above), never written.
set -euo pipefail
HOST=$1 PORT=$2 USER=$3 BACKUP=$4 DB=${5:-gateway_staging}
[[ "$DB" == *staging* ]] || { echo "refusing: the staging database name must contain 'staging'"; exit 2; }
: "${MYSQL_PWD?set MYSQL_PWD to the admin password}"
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${OUT_DIR:-$PWD/staging-evidence}; mkdir -p "$OUT"; chmod 700 "$OUT"
stamp=$(date +%Y%m%d-%H%M%S)

bash "$HERE/../phase0.5/restore-drill.sh" "$HOST" "$PORT" "$USER" "$MYSQL_PWD" "$BACKUP" "$DB" "$OUT/restore-drill-$stamp.txt"

m() { mysql --host="$HOST" --port="$PORT" --user="$USER" --protocol=TCP -N "$DB" -e "$1" 2>/dev/null || true; }
{
  echo "# pre-migration snapshot of $DB ($stamp)"
  echo "## row counts"
  for t in $(mysql --host="$HOST" --port="$PORT" --user="$USER" --protocol=TCP -N -e \
      "SELECT table_name FROM information_schema.tables WHERE table_schema='$DB' AND table_type='BASE TABLE' ORDER BY table_name"); do
    echo "rows $t $(m "SELECT COUNT(*) FROM \`$t\`")"
  done
  echo "## money"
  m "SELECT CONCAT('tx ', status, ' ', currency, ' ', COUNT(*), ' ', SUM(amount_cents)) FROM transactions GROUP BY status, currency ORDER BY status, currency"
  m "SELECT CONCAT('cs ', status, ' ', currency, ' ', COUNT(*), ' ', SUM(amount_cents)) FROM checkout_sessions GROUP BY status, currency ORDER BY status, currency"
  m "SELECT CONCAT('stl ', status, ' ', currency, ' ', COUNT(*), ' ', SUM(net_amount_cents)) FROM settlements GROUP BY status, currency ORDER BY status, currency"
} > "$OUT/pre-migration-$DB.txt"

cat <<EOF

Restored into '$DB'. Evidence: $OUT/restore-drill-$stamp.txt, $OUT/pre-migration-$DB.txt

This database holds production customer data: give staging the same access controls as production
(or mask it before anyone else uses staging; see docs/phase11/STAGING_RUNBOOK.md §2).

Create the staging-only MySQL user (rights on '$DB' only), as a MySQL administrator:
  CREATE USER 'payport_staging'@'localhost' IDENTIFIED BY '<new password>';
  GRANT ALL PRIVILEGES ON \`$DB\`.* TO 'payport_staging'@'localhost';
  -- (no SUPER needed: CI applies every migration with binary logging on as a non-SUPER user)

Next: start staging (scripts/staging/start-staging.sh), which applies V32-V52, then run
  MYSQL_PWD=... scripts/staging/verify-staging-db.sh $HOST $PORT $USER $DB
EOF
