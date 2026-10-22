#!/usr/bin/env bash
# Verifies the ISO 20022 schemas in <dest> (default src/main/resources/iso20022/xsd) against the
# SHA-256 pins in scripts/iso20022/schemas.sha256. Every pinned file must be present and match;
# CI fails otherwise. Provenance of each file: scripts/iso20022/PROVENANCE.md.
#
# To replace a schema: put the new file in place, check its targetNamespace, update the pin in the
# same commit, and record where it came from in PROVENANCE.md.
set -euo pipefail
DEST="${1:-src/main/resources/iso20022/xsd}"
PINS="$(cd "$(dirname "$0")" && pwd)/schemas.sha256"
status=0
while read -r sha path; do
  [ -z "$sha" ] && continue
  f="$DEST/$path"
  if [ ! -s "$f" ]; then echo "::error title=iso20022::$path is missing"; status=1; continue; fi
  ns="$(basename "$path" .xsd | sed 's/-.*//')"
  if ! grep -q "targetNamespace=\"urn:iso:std:iso:20022:tech:xsd:$ns\"" "$f"; then
    echo "::error title=iso20022::$path is not the $ns schema"; status=1; continue
  fi
  actual="$(sha256sum "$f" | cut -d' ' -f1)"
  if [ "$actual" != "$sha" ]; then echo "::error title=iso20022::$path SHA-256 $actual does not match the pin $sha"; status=1; else echo "verified: $path"; fi
done < "$PINS"
exit $status
