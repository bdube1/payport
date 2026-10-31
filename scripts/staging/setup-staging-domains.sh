#!/usr/bin/env bash
# Puts the Payport staging portals and gateway on their https addresses through Caddy, the web server already
# running on this machine (it also obtains and renews the certificates). Run as root; safe to run again.
#
#   sudo bash scripts/staging/setup-staging-domains.sh
#
#   admin-staging.payport.africa     → /opt/payport/staging-ops/dist       (built files, no Node process)
#   app-staging.payport.africa       → /opt/payport/staging-consumer/dist
#   merchant-staging.payport.africa  → /opt/payport/staging-merchant/dist
#   api-staging.payport.africa       → 127.0.0.1:8085
#
# What it does: backs up /etc/caddy/Caddyfile, removes any earlier blocks for these four addresses (nothing else
# is touched, so other sites such as the school app keep working), imports caddy/payport-staging.caddy, checks
# the result and reloads Caddy. If the check or the reload fails, the backup is put back.
set -euo pipefail
DIR=$(cd "$(dirname "$0")" && pwd)
CADDYFILE=/etc/caddy/Caddyfile
SITE=/etc/caddy/payport-staging.caddy
HOSTS="admin-staging.payport.africa app-staging.payport.africa merchant-staging.payport.africa api-staging.payport.africa"
[ "$(id -u)" = 0 ] || { echo "Run as root (sudo)"; exit 1; }
command -v caddy >/dev/null || { echo "Caddy is not installed"; exit 1; }
[ -f "$CADDYFILE" ] || { echo "No $CADDYFILE"; exit 1; }

for d in /opt/payport/staging-ops/dist /opt/payport/staging-consumer/dist /opt/payport/staging-merchant/dist; do
  [ -f "$d/index.html" ] || { echo "Missing $d/index.html: build that portal first (npm run build -- --mode staging)"; exit 1; }
done

# An nginx installed by an earlier version of this script cannot run next to Caddy: switch it off.
if systemctl list-unit-files nginx.service >/dev/null 2>&1; then
  systemctl disable --now nginx >/dev/null 2>&1 || true
  rm -f /etc/nginx/sites-enabled/payport-staging.conf
fi

backup="$CADDYFILE.bak-$(date +%Y%m%d-%H%M%S)"
cp "$CADDYFILE" "$backup"
install -m 644 "$DIR/caddy/payport-staging.caddy" "$SITE"

python3 - "$CADDYFILE" "$SITE" $HOSTS <<'PY'
import re, sys
path, site, hosts = sys.argv[1], sys.argv[2], set(sys.argv[3:])
lines = open(path).read().split('\n')
out, i, removed = [], 0, []
while i < len(lines):
    line = lines[i]
    s = line.strip()
    # a site block starts at depth 0 with an address list followed by '{'
    if s.endswith('{') and not s.startswith(('#', '(')) and line[:1] not in (' ', '\t'):
        addrs = {re.sub(r'^https?://', '', a.strip().rstrip(',')).split(':')[0] for a in s[:-1].split() if a.strip()}
        if addrs & hosts:
            if addrs - hosts:
                sys.exit(f"A block serves {sorted(addrs & hosts)} together with {sorted(addrs - hosts)}; split it by hand, then run again.")
            depth = 0
            while i < len(lines):
                depth += lines[i].count('{') - lines[i].count('}')
                i += 1
                if depth <= 0:
                    break
            removed.append(' '.join(sorted(addrs)))
            continue
    out.append(line)
    i += 1
text = '\n'.join(out).rstrip('\n')
imp = f'import {site}'
if imp not in text:
    text += f'\n\n# Payport staging (scripts/staging/setup-staging-domains.sh)\n{imp}\n'
open(path, 'w').write(text + ('\n' if not text.endswith('\n') else ''))
print('Replaced earlier blocks for: ' + (', '.join(removed) if removed else 'none'))
PY

restore() { echo "Restoring $backup"; cp "$backup" "$CADDYFILE"; systemctl reload caddy || true; exit 1; }
caddy validate --config "$CADDYFILE" --adapter caddyfile >/dev/null 2>&1 || { caddy validate --config "$CADDYFILE" --adapter caddyfile 2>&1 | tail -5; restore; }

# Caddy runs as its own user: it must be able to read the builds.
for d in /opt /opt/payport /opt/payport/staging-ops /opt/payport/staging-consumer /opt/payport/staging-merchant; do chmod o+x "$d"; done
chmod -R o+rX /opt/payport/staging-ops/dist /opt/payport/staging-consumer/dist /opt/payport/staging-merchant/dist

systemctl reload caddy || restore
sleep 3
for h in $HOSTS; do printf '%-40s %s\n' "https://$h" "$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 "https://$h/")"; done
echo "Done. After a pull, rebuild a portal with: npm run build -- --mode staging (no restart needed)."
echo "The portals' Node processes are no longer used:  pm2 delete payport-ops-staging payport-consumer-staging payport-merchant-staging && pm2 save"
