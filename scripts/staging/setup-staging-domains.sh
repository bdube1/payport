#!/usr/bin/env bash
# Puts the staging portals and gateway on their https addresses (run once as root on the staging server).
#
#   sudo EMAIL=you@payport.africa scripts/staging/setup-staging-domains.sh
#
# Before running: in DNS, point these four A records at this server's IP (68.183.134.5):
#   admin-staging, app-staging, merchant-staging, api-staging  (.payport.africa)
# Installs nginx and certbot if missing, installs the site, gets Let's Encrypt certificates and turns on
# the http→https redirect. Certificates renew automatically (certbot's timer).
set -euo pipefail
DIR=$(cd "$(dirname "$0")" && pwd)
DOMAINS=(admin-staging.payport.africa app-staging.payport.africa merchant-staging.payport.africa api-staging.payport.africa)
[ "$(id -u)" = 0 ] || { echo "Run as root (sudo)"; exit 1; }
: "${EMAIL:?set EMAIL=<address for certificate notices>}"

ip=$(curl -fsS https://api.ipify.org || true)
for d in "${DOMAINS[@]}"; do
  got=$(getent hosts "$d" | awk '{print $1}' | head -1)
  [ "$got" = "$ip" ] || { echo "DNS: $d points at '${got:-nothing}', not this server ($ip). Fix the A record first."; exit 2; }
done

command -v nginx >/dev/null || { apt-get update -y && apt-get install -y nginx; }
command -v certbot >/dev/null || apt-get install -y certbot python3-certbot-nginx

install -m 644 "$DIR/nginx/payport-proxy.conf" /etc/nginx/snippets/payport-proxy.conf
install -m 644 "$DIR/nginx/payport-staging.conf" /etc/nginx/sites-available/payport-staging.conf
ln -sf /etc/nginx/sites-available/payport-staging.conf /etc/nginx/sites-enabled/payport-staging.conf
nginx -t && systemctl reload nginx

if command -v ufw >/dev/null && ufw status | grep -q active; then ufw allow 'Nginx Full' >/dev/null; fi

args=(); for d in "${DOMAINS[@]}"; do args+=(-d "$d"); done
certbot --nginx --non-interactive --agree-tos --redirect -m "$EMAIL" "${args[@]}"
systemctl reload nginx
for d in "${DOMAINS[@]}"; do printf '%-36s %s\n' "https://$d" "$(curl -s -o /dev/null -w '%{http_code}' "https://$d/")"; done
echo "Done. Rebuild the portals with: npm run build -- --mode staging (they then call https://api-staging.payport.africa)."
