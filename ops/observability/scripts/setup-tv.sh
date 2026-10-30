#!/usr/bin/env bash
# Creates (or updates) the "PayPort TV" playlist in Grafana: the five Control Centre dashboards, 60 seconds each.
# Prints the address to open on the TV.   Usage: scripts/setup-tv.sh [seconds-per-dashboard]
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; source .env; set +a
INTERVAL=${1:-60}s
G=${GRAFANA_LOCAL_URL:-http://127.0.0.1:3300}
AUTH="${GRAFANA_ADMIN_USER:-admin}:${GRAFANA_ADMIN_PASSWORD}"
for i in $(seq 1 30); do curl -fsS "$G/api/health" >/dev/null 2>&1 && break; sleep 2; done
body=$(cat <<JSON
{"name":"PayPort TV","interval":"$INTERVAL","items":[
 {"type":"dashboard_by_uid","value":"pp-cc-overview"},
 {"type":"dashboard_by_uid","value":"pp-cc-infrastructure"},
 {"type":"dashboard_by_uid","value":"pp-cc-payments"},
 {"type":"dashboard_by_uid","value":"pp-cc-security"},
 {"type":"dashboard_by_uid","value":"pp-cc-integrations"}]}
JSON
)
uid=$(curl -fsS -u "$AUTH" "$G/api/playlists?query=PayPort%20TV" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d[0]["uid"] if d else "")')
if [ -n "$uid" ]; then
  curl -fsS -u "$AUTH" -X PUT -H 'Content-Type: application/json' "$G/api/playlists/$uid" -d "$body" >/dev/null
else
  uid=$(curl -fsS -u "$AUTH" -X POST -H 'Content-Type: application/json' "$G/api/playlists" -d "$body" | python3 -c 'import sys,json; print(json.load(sys.stdin)["uid"])')
fi
# A read-only account for the TV, so the TV never holds the administrator password.
if [ -n "${GRAFANA_TV_PASSWORD:-}" ]; then
  curl -fsS -u "$AUTH" -X POST -H 'Content-Type: application/json' "$G/api/admin/users" \
    -d "{\"name\":\"Control Centre TV\",\"login\":\"tv\",\"password\":\"$GRAFANA_TV_PASSWORD\"}" >/dev/null 2>&1 || true
fi
echo "Open on the TV (log in as 'tv'): ${GRAFANA_ROOT_URL:-$G}/playlists/play/$uid?kiosk"
