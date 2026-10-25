#!/usr/bin/env bash
# STAGING ONLY. Switches every service, channel, product, payment method, currency and USSD network on for
# end-to-end testing.
#
#   cd /opt/payport/staging && scripts/staging/enable-everything-for-testing.sh
#
# Reads the database settings from .env.staging and refuses unless PAYPORT_ENV=staging and the database name
# contains "staging". Settlement stays PARALLEL with automation off; no payout is switched on.
set -euo pipefail
DIR=${STAGING_DIR:-/opt/payport/staging}
ENV_FILE=${ENV_FILE:-$DIR/.env.staging}
[ -f "$ENV_FILE" ] || { echo "No $ENV_FILE"; exit 1; }
set -a; source "$ENV_FILE"; set +a
[ "${PAYPORT_ENV:-}" = staging ] || { echo "REFUSED: PAYPORT_ENV is '${PAYPORT_ENV:-unset}', not staging"; exit 2; }
url=${DB_URL:?DB_URL not set}
hostport=$(sed -E 's#^jdbc:mysql://([^/]+)/.*#\1#' <<<"$url")
db=$(sed -E 's#^jdbc:mysql://[^/]+/([^?]+).*#\1#' <<<"$url")
host=${hostport%%:*}; port=${hostport##*:}; [ "$port" = "$hostport" ] && port=3306
[[ "$db" == *staging* ]] || { echo "REFUSED: database '$db' is not a staging database"; exit 2; }
[ "${SETTLEMENT_AUTOMATION_ENABLED:-false}" = false ] || { echo "REFUSED: settlement automation must stay off in staging"; exit 2; }
echo "Switching everything on in '$db' at $host:$port"
MYSQL_PWD="${DB_PASSWORD:-}" mysql --host="$host" --port="$port" --user="${DB_USERNAME:?DB_USERNAME not set}" --protocol=TCP "$db" \
  < "$(dirname "$0")/enable-everything-for-testing.sql"
echo "Done. Restart staging so cached settings reload: scripts/staging/stop-staging.sh && scripts/staging/start-staging.sh"
