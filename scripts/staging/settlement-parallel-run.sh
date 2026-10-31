#!/usr/bin/env bash
# Phase 11 settlement parallel run against STAGING: drafts a settlement batch per currency in PARALLEL mode
# (never instructed: the gateway refuses to instruct in PARALLEL mode) and compares it with what the
# existing (legacy) settlement recorded for the same checkout sessions. Run it after each legacy settlement
# cycle on a freshly restored staging copy, so both sides see the same data.
#
#   BASE=http://localhost:8085 FINANCE_TOKEN=<FINANCE staff login on staging> scripts/staging/settlement-parallel-run.sh [USD ZWG]
#
# Output: staging-evidence/settlement-parallel-<time>.csv and a summary of discrepancies.
# Nothing here moves money: drafting reserves eligible funds in the staging copy only.
set -euo pipefail
BASE=${BASE:-http://localhost:8085}
case "$BASE" in *:8084*|*api.payport*) echo "refusing: run the parallel comparison on staging, not production ($BASE)"; exit 2;; esac
: "${FINANCE_TOKEN:?FINANCE_TOKEN (a FINANCE staff login on staging) is required}"
CURRENCIES=${*:-USD ZWG}
OUT=${OUT_DIR:-$PWD/staging-evidence}; mkdir -p "$OUT"
CSV="$OUT/settlement-parallel-$(date +%Y%m%d-%H%M%S).csv"
echo "currency,batch_ref,mode,merchant_id,sessions,sessions_in_legacy,new_net_minor,legacy_net_minor,difference_minor,finding" > "$CSV"
api() { curl -sS -H "Authorization: Bearer $FINANCE_TOKEN" -H 'Content-Type: application/json' "$@"; }

for ccy in $CURRENCIES; do
  draft=$(api -X POST "$BASE/api/v1/admin/finance/settlement/batches" -d "{\"currency\":\"$ccy\"}")
  id=$(python3 -c 'import json,sys; d=json.loads(sys.argv[1]).get("data") or {}; print(d.get("id") or "")' "$draft")
  if [ -z "$id" ]; then echo "$ccy: $(python3 -c 'import json,sys; d=json.loads(sys.argv[1]); print((d.get("data") or {}).get("message") or d.get("error") or d)' "$draft")"; continue; fi
  api "$BASE/api/v1/admin/finance/settlement/batches/$id" | python3 -c '
import json, sys
ccy, csv = sys.argv[1], sys.argv[2]
d = json.load(sys.stdin)["data"]; b = d["batch"]
if b.get("mode") != "PARALLEL": sys.exit("STOP: batch %s is not in PARALLEL mode (%s); check SETTLEMENT_MODE" % (b.get("batchRef"), b.get("mode")))
rows = d["legacyComparison"]; diffs = 0
with open(csv, "a") as f:
    for r in rows:
        new, legacy = int(r["new_net_minor"] or 0), int(r["legacy_net_minor_full_sessions"] or 0)
        n, nl = int(r["sessions"]), int(r["sessions_in_legacy"])
        finding = "MATCH" if nl == n and new == legacy else ("NOT_YET_SETTLED_BY_LEGACY" if nl == 0 else ("PARTLY_IN_LEGACY" if nl < n else "AMOUNT_DIFFERS"))
        diffs += finding != "MATCH"
        f.write("%s,%s,%s,%s,%d,%d,%d,%d,%d,%s\n" % (ccy, b.get("batchRef"), b.get("mode"), r["merchant_id"], n, nl, new, legacy, new - legacy, finding))
print("%s: batch %s (PARALLEL, %d merchants): %d discrepancies" % (ccy, b.get("batchRef"), len(rows), diffs))
' "$ccy" "$CSV"
done
echo "details: $CSV"
echo "Each discrepancy goes to the Phase 11 report with its cause (fee rule, timing cut-off, refund, legacy error, Payport error)."
