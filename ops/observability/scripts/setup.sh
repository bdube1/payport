#!/usr/bin/env bash
# One-time setup of the Control Centre on the monitoring host. Run as root from ops/observability.
#   1. writes secrets/ from the gateway's .env.staging (scrape token) and asks for the MySQL monitor password
#   2. starts the stack, then creates the TV playlist
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] || { cp .env.example .env; chmod 600 .env; echo "Fill in .env (passwords), then run this again."; exit 1; }
mkdir -p secrets && chmod 700 secrets
GW_ENV=${GW_ENV:-/opt/payport/staging/.env.staging}
token=$(set -a; source "$GW_ENV" >/dev/null 2>&1; echo "${PAYPORT_METRICS_TOKEN:-}")
[ -n "$token" ] || { echo "Set PAYPORT_METRICS_TOKEN in $GW_ENV (openssl rand -hex 32) and restart the gateway first."; exit 1; }
printf '%s' "$token" > secrets/metrics_token && chmod 644 secrets/metrics_token
if [ ! -f secrets/mysqld-exporter.cnf ]; then
  read -rsp "Password for the MySQL user 'pp_monitor' (see README): " pw; echo
  printf '[client]\nuser=pp_monitor\npassword=%s\nhost=127.0.0.1\nport=3306\n' "$pw" > secrets/mysqld-exporter.cnf
  chmod 644 secrets/mysqld-exporter.cnf
fi
docker compose up -d
scripts/setup-tv.sh
