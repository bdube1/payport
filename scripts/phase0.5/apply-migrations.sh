#!/usr/bin/env bash
# Applies src/main/resources/db/migration/V*.sql in version order with the mysql client,
# stopping at the first error. Used by CI and for testing upgrades on a COPY of the
# production schema. Never point this at production: Flyway does that at deploy time.
#
# usage: apply-migrations.sh <host> <port> <database> <user> <password> [from_version]
#   from_version: skip migrations below this version (e.g. 32 to apply only new ones
#                 on top of a restored copy that is already at V31).
set -euo pipefail
HOST=$1 PORT=$2 DB=$3 USER=$4 PASS=$5 FROM=${6:-0}
DIR="$(cd "$(dirname "$0")/../.." && pwd)/src/main/resources/db/migration"
export MYSQL_PWD="$PASS"
for f in $(ls "$DIR"/V*__*.sql | sort -V); do
  v=$(basename "$f" | sed -E 's/^V([0-9]+)__.*/\1/')
  if [ "$v" -lt "$FROM" ]; then continue; fi
  echo "applying $(basename "$f")"
  mysql --host="$HOST" --port="$PORT" --user="$USER" --protocol=TCP "$DB" < "$f"
done
echo "all migrations applied"
