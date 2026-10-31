#!/usr/bin/env bash
# PayPort Store go-live check (read-only: changes nothing, prints no secret values).
#
#   API=https://api.payport.africa STORE=https://store.payport.africa ADMIN_TOKEN=<an ADMIN login token> scripts/golive/check-store-live.sh
#
# Exits 0 only when every item is ready. The live switch itself is pressed in the ops portal (Channels and product
# lines > Store payments) by an ADMIN, with the owner's approval written in.
set -uo pipefail
API=${API:-http://127.0.0.1:8085}
STORE=${STORE:-http://127.0.0.1:8086}
COMMERCE_LOCAL=${COMMERCE_LOCAL:-http://127.0.0.1:8086}
fail=0
ok()   { printf '  [ok]   %s\n' "$1"; }
bad()  { printf '  [todo] %s\n' "$1"; fail=1; }
[ -n "${ADMIN_TOKEN:-}" ] || { echo "Set ADMIN_TOKEN (an ADMIN staff login token)"; exit 2; }
command -v jq >/dev/null || { echo "Install jq first"; exit 2; }

echo "PayPort Store go-live check"
echo "1. Services"
curl -fsS "$API/actuator/health/readiness" >/dev/null 2>&1 && ok "Gateway answers ($API)" || bad "Gateway does not answer at $API"
curl -fsS "$COMMERCE_LOCAL/actuator/health/readiness" >/dev/null 2>&1 && ok "Commerce answers ($COMMERCE_LOCAL)" || bad "Commerce does not answer at $COMMERCE_LOCAL"
code=$(curl -s -o /dev/null -w '%{http_code}' "$STORE/")
[ "$code" = 200 ] && ok "Store front page ($STORE) answers 200" || bad "Store front page answered $code"

echo "2. Payment providers"
r=$(curl -fsS -H "Authorization: Bearer $ADMIN_TOKEN" "$API/api/v1/admin/payments/live-readiness") || { bad "Readiness could not be read (is the token an ADMIN one?)"; r='{}'; }
env=$(jq -r '.data.environment // "unknown"' <<<"$r")
live=$(jq -r '.data.storePaymentsLive // false' <<<"$r")
echo "  environment: $env    store payments live: $live"
jq -r '.data.methods[]? | "\(.name)|\(.switchedOn)|\(.credentialsInPlace)|\(.missing|join(", "))"' <<<"$r" | while IFS='|' read -r name on creds missing; do
  if [ "$on" = true ] && [ "$creds" = true ]; then printf '  [ok]   %s: switched on, credentials in place\n' "$name"
  elif [ "$on" != true ]; then printf '  [--]   %s: switched off in the ops portal\n' "$name"
  else printf '  [todo] %s: missing %s\n' "$name" "$missing"; fi
done
[ "$(jq -r '.data.readyForLive // false' <<<"$r")" = true ] && ok "At least one provider is ready for live payments" || bad "No provider is ready: add the credentials to the gateway's env file and restart"

echo "3. Safety settings"
[ "$env" = production ] && ok "Gateway runs as production" || echo "  [--]   Gateway runs as $env (fine for staging; production must say production)"
# The gateway's env file (default: staging's). Only two setting names are read; nothing is printed from it.
ENV_FILE=${ENV_FILE:-/opt/payport/staging/.env.staging}
if [ -r "$ENV_FILE" ]; then
  mode=$(grep -E '^SETTLEMENT_MODE=' "$ENV_FILE" | tail -1 | cut -d= -f2-); mode=${mode:-PARALLEL}
  auto=$(grep -E '^SETTLEMENT_AUTOMATION_ENABLED=' "$ENV_FILE" | tail -1 | cut -d= -f2-); auto=${auto:-false}
  [ "$mode" = PARALLEL ] && ok "Settlement mode PARALLEL" || echo "  [!!]   Settlement mode is $mode"
  [ "$auto" = false ] && ok "Automatic settlement off (no payouts without people)" || echo "  [!!]   Automatic settlement is on"
else
  echo "  [--]   $ENV_FILE not readable here: check SETTLEMENT_MODE=PARALLEL and SETTLEMENT_AUTOMATION_ENABLED=false by hand"
fi

echo "4. Monitoring"
curl -fsS "$COMMERCE_LOCAL/internal/metrics" | grep -q '^commerce_psp_up 1' && ok "Commerce reaches PayPort Payments" || bad "Commerce cannot reach PayPort Payments (check PAYPORT_COMMERCE_KEY on both sides)"
curl -fsS http://127.0.0.1:9090/-/ready >/dev/null 2>&1 && ok "Prometheus is running" || echo "  [--]   Prometheus not found on this machine (alerts for the store need ops/observability)"

echo
if [ $fail = 0 ]; then echo "Ready. Switch store payments to live in the ops portal with the approval written in."; else echo "Not ready yet: fix the [todo] items above."; fi
exit $fail
