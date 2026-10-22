#!/usr/bin/env bash
# Phase 11 provider sandbox runs against STAGING, through PayPort's own public API (the same path a
# customer takes), so each run tests the adapter, the lifecycle, callbacks/queries and delivery together.
#
#   BASE=http://localhost:8085 scripts/staging/provider-sandbox.sh run <METHOD> <PRODUCT> <RECIPIENT> <AMOUNT_CENTS> <CURRENCY> <PAYER_PHONE>
#   BASE=http://localhost:8085 ADMIN_TOKEN=... scripts/staging/provider-sandbox.sh record <PROVIDER_CODE> <flow> pass|fail <evidence-file>
#
# Examples (sandbox numbers/accounts from each provider's test pack; amounts are test values):
#   run ECOCASH  AIRTIME_ECONET 0771111111 100 USD +263771111111   # EcoCash collection, then Econet airtime delivery
#   run ONEMONEY AIRTIME_ECONET 0771111111 100 USD +263711111111   # OneMoney (over the OneMoney VPN)
#   run ECOCASH  ZESA <test meter> 500 USD +263771111111            # e-Solutions ZESA token vend (test environment)
#
# "run" initiates, pays, then follows the payment to a final status (or 5 minutes), and writes the
# timeline to staging-evidence/sandbox-<reference>.json. Whether that run PASSED is a person's judgement
# against the provider's test case; record it with "record", which files it in the integration register
# (POST /api/v1/admin/integrations/{code}/verifications, environment SANDBOX). Raising a provider to
# SANDBOX_VERIFIED in register.json is a separate reviewed change (Phase 5 report §4).
set -euo pipefail
BASE=${BASE:-http://localhost:8085}
case "$BASE" in *:8084*|*api.payport*) [ "${I_KNOW_THIS_IS_NOT_PRODUCTION:-}" = yes ] || { echo "refusing: $BASE looks like production"; exit 2; };; esac
OUT=${OUT_DIR:-$PWD/staging-evidence}; mkdir -p "$OUT"
json() { python3 -c "import json,sys; d=json.load(sys.stdin); print(eval(sys.argv[1]))" "$1"; }

cmd=${1:-}; shift || true
case "$cmd" in
run)
  METHOD=$1 PRODUCT=$2 RECIPIENT=$3 AMOUNT=$4 CCY=$5 PHONE=$6
  REF=$(printf "PP-WEB-%s-%06d" "$(date +%Y%m%d)" "$(shuf -i 0-999999 -n 1)")
  body=$(python3 -c 'import json,sys; print(json.dumps({"reference":sys.argv[1],"productTypeCode":sys.argv[2],"recipientIdentifier":sys.argv[3],"amountCents":int(sys.argv[4]),"currency":sys.argv[5],"customerPhone":sys.argv[6],"channel":"WEB"}))' "$REF" "$PRODUCT" "$RECIPIENT" "$AMOUNT" "$CCY" "$PHONE")
  init=$(curl -sS -X POST "$BASE/api/v1/transactions/initiate" -H 'Content-Type: application/json' -d "$body")
  TOKEN=$(echo "$init" | json "d.get('data',{}).get('accessToken') or ''")
  [ -n "$TOKEN" ] || { echo "initiate refused: $init"; exit 1; }
  echo "initiated $REF"
  pay=$(python3 -c 'import json,sys; print(json.dumps({"reference":sys.argv[1],"paymentMethodCode":sys.argv[2],"customerPhone":sys.argv[3]}))' "$REF" "$METHOD" "$PHONE")
  curl -sS -X POST "$BASE/api/v1/transactions/pay" -H 'Content-Type: application/json' -H "X-Transaction-Token: $TOKEN" -d "$pay" \
    | json "'pay: ' + str(d.get('data',{}).get('status')) + ' ' + str(d.get('data',{}).get('failureReason') or d.get('error',{}).get('message') or '')"
  echo "approve the prompt on the test phone if the provider sends one; following the payment for up to 5 minutes..."
  status=""
  for i in $(seq 1 60); do
    r=$(curl -sS "$BASE/api/v1/transactions/$REF" -H "X-Transaction-Token: $TOKEN")
    s=$(echo "$r" | json "d.get('data',{}).get('status')")
    [ "$s" != "$status" ] && { echo "  $(date +%T) $s"; status=$s; }
    case "$s" in COMPLETED|PAYMENT_FAILED|PAYMENT_TIMEOUT|FULFILMENT_FAILED|MANUAL_FULFILMENT|REFUNDED|CANCELLED) break;; esac
    sleep 5
  done
  curl -sS "$BASE/api/v1/transactions/$REF/timeline" -H "X-Transaction-Token: $TOKEN" > "$OUT/sandbox-$REF.json"
  echo "final: $status   evidence: $OUT/sandbox-$REF.json"
  echo "check it against the provider's test case, then: $0 record <PROVIDER_CODE> <flow> pass|fail $OUT/sandbox-$REF.json"
  ;;
record)
  CODE=$1 FLOW=$2 RESULT=$3 FILE=$4
  : "${ADMIN_TOKEN:?ADMIN_TOKEN (an ADMIN staff login token for staging) is required}"
  [ -f "$FILE" ] || { echo "no evidence file $FILE"; exit 1; }
  pass=$([ "$RESULT" = pass ] && echo true || echo false)
  evidence="staging run $(basename "$FILE") sha256 $(sha256sum "$FILE" | cut -c1-16) by $(whoami)@$(hostname) $(date -u +%FT%TZ)"
  body=$(python3 -c 'import json,sys; print(json.dumps({"environment":"SANDBOX","flow":sys.argv[1],"pass":sys.argv[2]=="true","evidence":sys.argv[3]}))' "$FLOW" "$pass" "$evidence")
  curl -sS -X POST "$BASE/api/v1/admin/integrations/$CODE/verifications" -H 'Content-Type: application/json' -H "Authorization: Bearer $ADMIN_TOKEN" -d "$body" \
    | json "'recorded' if d.get('success', d.get('data') is not None) else d"
  ;;
*) sed -n 2,22p "$0"; exit 1;;
esac
