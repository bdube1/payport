#!/usr/bin/env bash
# D1: has production drifted from the migrations? (spring.jpa.hibernate.ddl-auto=update lets
# Hibernate add columns/tables that no migration records.)
#
# 1. On production, dump the STRUCTURE ONLY (no data):
#      mysqldump --no-data --routines --triggers --skip-add-drop-table -h <prod> -u <readonly> -p <db> > prod-schema.sql
# 2. Load it into a scratch database on a NON-production server:  mysql scratch_prod < prod-schema.sql
# 3. Build a reference database from the migrations at the same version (e.g. up to V31):
#      scripts/phase0.5/apply-migrations.sh ... scratch_ref ...   (stop at the version production is on)
# 4. Compare:  schema-diff.sh <host> <port> <user> <password> scratch_prod scratch_ref
#
# Output: lines starting with "<" exist only in production, ">" only in the migrations.
# Exit code 0 = identical, 1 = drift found.
set -euo pipefail
HOST=$1 PORT=$2 USER=$3 PASS=$4 PROD=$5 REF=$6
export MYSQL_PWD="$PASS"
q() {
  mysql --host="$HOST" --port="$PORT" --user="$USER" --protocol=TCP -N -B -e "
    SELECT CONCAT('table ', table_name) FROM information_schema.tables
     WHERE table_schema='$1' AND table_name <> 'flyway_schema_history'
    UNION ALL
    SELECT CONCAT('column ', table_name, '.', column_name, ' ', column_type, ' null=', is_nullable,
                  ' default=', IFNULL(column_default,'<none>'), ' extra=', extra)
      FROM information_schema.columns WHERE table_schema='$1' AND table_name <> 'flyway_schema_history'
    UNION ALL
    SELECT CONCAT('index ', table_name, '.', index_name, ' unique=', 1-non_unique, ' cols=',
                  GROUP_CONCAT(column_name ORDER BY seq_in_index))
      FROM information_schema.statistics WHERE table_schema='$1' AND table_name <> 'flyway_schema_history'
      GROUP BY table_name, index_name, non_unique
    UNION ALL
    SELECT CONCAT('check ', constraint_name, ' ', check_clause)
      FROM information_schema.check_constraints WHERE constraint_schema='$1'
    UNION ALL
    SELECT CONCAT('trigger ', trigger_name, ' ', event_manipulation, ' ', event_object_table)
      FROM information_schema.triggers WHERE trigger_schema='$1'" | sort
}
if diff <(q "$PROD") <(q "$REF"); then
  echo "no drift: $PROD matches $REF"
else
  echo "DRIFT FOUND (< only in $PROD, > only in $REF)"; exit 1
fi
