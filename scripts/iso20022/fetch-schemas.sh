#!/usr/bin/env bash
# Fetches the official ISO 20022 message schemas from iso20022.org, checks each one is the
# expected message (targetNamespace) and, when a pin exists in schemas.sha256, that its
# SHA-256 matches. Writes <dest>/<message>.xsd. Fails on any mismatch.
#
#   scripts/iso20022/fetch-schemas.sh src/main/resources/iso20022/xsd
#
# The download may be the XSD itself or a zip containing it; both are handled.
set -euo pipefail
DEST="${1:?destination directory}"
HERE="$(cd "$(dirname "$0")" && pwd)"
PINS="$HERE/schemas.sha256"
mkdir -p "$DEST"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# message  iso20022.org download id
MESSAGES="camt.053.001.08 12736
pain.001.001.09 14346
pain.002.001.10 14396"

status=0
while read -r msg id; do
  [ -z "$msg" ] && continue
  out="$DEST/$msg.xsd"
  if [ -s "$out" ] && grep -q "urn:iso:std:iso:20022:tech:xsd:$msg" "$out"; then
    echo "present: $msg"
  else
    d="$WORK/$msg"; mkdir -p "$d"
    url="https://www.iso20022.org/message/$id/download"
    code="$(curl -sSL --retry 3 --max-time 120 -o "$d/download" -w '%{http_code}' \
      -A "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36" \
      -H "Accept: application/xml,application/zip,application/octet-stream,*/*" \
      -H "Referer: https://www.iso20022.org/catalogue-messages/iso-20022-messages-archive" "$url" || echo "000")"
    if [ "$code" != "200" ]; then
      echo "::error title=iso20022::$msg: $url answered HTTP $code ($(head -c 200 "$d/download" 2>/dev/null | tr -d '\n\r' | tr -c '[:print:]' ' '))"
      status=1; continue
    fi
    if unzip -tq "$d/download" >/dev/null 2>&1; then
      unzip -q -o "$d/download" -d "$d/x"
      found="$(grep -rl --include='*.xsd' "targetNamespace=\"urn:iso:std:iso:20022:tech:xsd:$msg\"" "$d/x" | head -1 || true)"
    elif grep -q "targetNamespace=\"urn:iso:std:iso:20022:tech:xsd:$msg\"" "$d/download"; then
      found="$d/download"
    else
      found=""
    fi
    if [ -z "$found" ]; then
      echo "::error title=iso20022::$msg: download $id is not the $msg schema ($(file -b "$d/download" | head -c 80))"
      status=1; continue
    fi
    cp "$found" "$out"
  fi
  sha="$(sha256sum "$out" | cut -d' ' -f1)"
  pin="$(grep -E "^[0-9a-f]{64}  $msg\$" "$PINS" 2>/dev/null | cut -d' ' -f1 || true)"
  if [ -n "$pin" ]; then
    if [ "$pin" != "$sha" ]; then
      echo "::error title=iso20022::$msg SHA-256 $sha does not match the pinned $pin"
      status=1
    else
      echo "verified: $msg $sha"
    fi
  else
    echo "::notice title=iso20022 pin::$sha  $msg (not pinned yet: add to scripts/iso20022/schemas.sha256)"
  fi
done <<< "$MESSAGES"
exit $status
