#!/usr/bin/env bash
# Starts Payport Commerce (its own process, port and database, so it stays up when the PSP is down).
#
#   cd /opt/payport/staging/commerce && mvn clean package -DskipTests && scripts/start-commerce.sh
#
# Reads .env.commerce next to this folder (override with ENV_FILE=...), mode 600.
set -euo pipefail
DIR=${COMMERCE_DIR:-$(cd "$(dirname "$0")/.." && pwd)}
ENV_FILE=${ENV_FILE:-$DIR/.env.commerce}
LOG=${LOG:-$DIR/commerce.log}
PID_FILE=$DIR/commerce.pid

[ -f "$ENV_FILE" ] || { echo "No $ENV_FILE. Copy scripts/env.commerce.example and fill it in."; exit 1; }
perm=$(stat -c %a "$ENV_FILE"); [ "$perm" = 600 ] || [ "$perm" = 400 ] || { echo "Refusing: $ENV_FILE is mode $perm; run: chmod 600 $ENV_FILE"; exit 1; }
set -a; source "$ENV_FILE"; set +a
fail() { echo "REFUSED: $*"; exit 2; }
db=$(sed -E 's#^jdbc:mysql://[^/]+/([^?]+).*#\1#' <<<"${COMMERCE_DB_URL:-}")
[[ "$db" == *commerce* ]] || fail "COMMERCE_DB_URL must point at Commerce's own database (name containing 'commerce'), not the PSP's"
[ ${#COMMERCE_TOKEN_SECRET} -ge 32 ] || fail "COMMERCE_TOKEN_SECRET must be at least 32 characters"
[ ${#PAYPORT_COMMERCE_KEY} -ge 24 ] || fail "PAYPORT_COMMERCE_KEY must be at least 24 characters (the same value in the gateway's env file)"

if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "Commerce is already running (pid $(cat "$PID_FILE")). Stop it with scripts/stop-commerce.sh"; exit 1
fi
JAR=$(find "$DIR/target" -maxdepth 1 -name "payport-commerce*.jar" ! -name "*original*" | head -1)
[ -n "$JAR" ] || { echo "No jar in $DIR/target. Run: mvn clean package -DskipTests"; exit 1; }
cp "$JAR" "$DIR/payport-commerce-run.jar"
echo "Starting Payport Commerce on port ${PORT:-8086}, database '$db'"
nohup java ${JAVA_OPTS:--Xmx384m} -jar "$DIR/payport-commerce-run.jar" > "$LOG" 2>&1 &
echo $! > "$PID_FILE"
for i in $(seq 1 60); do
  if curl -fsS "http://localhost:${PORT:-8086}/actuator/health/readiness" >/dev/null 2>&1; then
    echo "Commerce is up (pid $(cat "$PID_FILE")). Logs: tail -f $LOG"; exit 0
  fi
  kill -0 "$(cat "$PID_FILE")" 2>/dev/null || { echo "Commerce exited during start-up:"; tail -40 "$LOG"; exit 1; }
  sleep 3
done
echo "Commerce did not report ready in 3 minutes. Logs: tail -f $LOG"; exit 1
