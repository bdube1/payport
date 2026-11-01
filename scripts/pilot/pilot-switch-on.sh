#!/usr/bin/env bash
# Controlled pilot (owner approval and regulator approval, 2 Nov 2026): switches every service, channel, product,
# payment method, market and currency on in the pilot environment (the staging servers), and every Payport
# Intelligence feature on. Settlement stays PARALLEL with automation off: payouts are posted to the sponsor bank by
# hand from the approved settlement batches, as the regulator approved.
#
#   cd /opt/payport/staging && scripts/pilot/pilot-switch-on.sh --approved-by "Full Name"
#
# Two things are switched in the ops portal instead, because they are recorded in the tamper-evident audit log:
#   - store payments to live: Channels & Product Lines > Store payments > Switch to live (Administrator);
#   - any compliance rule from observe to enforce (two compliance officers).
set -euo pipefail
APPROVER=""
while [ $# -gt 0 ]; do case "$1" in --approved-by) APPROVER="$2"; shift 2;; *) echo "Unknown option $1"; exit 1;; esac; done
[ -n "$APPROVER" ] || { echo "REFUSED: give the approver: --approved-by \"Full Name\""; exit 2; }
DIR=${STAGING_DIR:-/opt/payport/staging}
ENV_FILE=${ENV_FILE:-$DIR/.env.staging}
[ -f "$ENV_FILE" ] || { echo "No $ENV_FILE"; exit 1; }
set -a; source "$ENV_FILE"; set +a
[ "${SETTLEMENT_AUTOMATION_ENABLED:-false}" = false ] || { echo "REFUSED: settlement automation must stay off (payouts are posted by hand)"; exit 2; }
"$DIR/scripts/staging/enable-everything-for-testing.sh"
url=${DB_URL:?DB_URL not set}
hostport=$(sed -E 's#^jdbc:mysql://([^/]+)/.*#\1#' <<<"$url"); db=$(sed -E 's#^jdbc:mysql://[^/]+/([^?]+).*#\1#' <<<"$url")
host=${hostport%%:*}; port=${hostport##*:}; [ "$port" = "$hostport" ] && port=3306
by="pilot:$(echo "$APPROVER" | tr -cd 'A-Za-z .-' | cut -c1-60)"
MYSQL_PWD="${DB_PASSWORD:-}" mysql --host="$host" --port="$port" --user="${DB_USERNAME:?}" --protocol=TCP "$db" <<SQL
UPDATE ai_features SET enabled = TRUE, updated_by = '$by', updated_at = NOW(6);
SELECT feature, enabled FROM ai_features;
SQL
echo
echo "Pilot switched on by $APPROVER. Next, in the ops portal (Administrator):"
echo "  1. Channels & Product Lines > Store payments > Switch to live"
echo "  2. Markets & Currencies: check every market shows LIVE"
echo "  3. Restart so cached settings reload: scripts/staging/stop-staging.sh && scripts/staging/start-staging.sh"
