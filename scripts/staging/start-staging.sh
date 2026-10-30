#!/usr/bin/env bash
# Starts the PayPort gateway in STAGING (Phase 11), next to production and isolated from it.
#
#   cd /opt/payport/staging && mvn clean package -DskipTests && scripts/staging/start-staging.sh
#
# - Reads /opt/payport/staging/.env.staging (override with ENV_FILE=...).
# - Refuses to start unless the settings are staging-safe (below). This is the guard that keeps a
#   staging test from touching the production database, settling money or posing as production.
# - Runs the jar under its own name (payport-staging.jar) with a PID file, so production's stop.sh
#   (pkill -f payport-gateway) and this script never stop each other's process.
set -euo pipefail
DIR=${STAGING_DIR:-/opt/payport/staging}
ENV_FILE=${ENV_FILE:-$DIR/.env.staging}
LOG=${LOG:-$DIR/staging.log}
PID_FILE=$DIR/staging.pid

[ -f "$ENV_FILE" ] || { echo "No $ENV_FILE. Copy scripts/staging/env.staging.example and fill it in."; exit 1; }
perm=$(stat -c %a "$ENV_FILE"); [ "$perm" = 600 ] || [ "$perm" = 400 ] || { echo "Refusing: $ENV_FILE is mode $perm; run: chmod 600 $ENV_FILE"; exit 1; }
set -a; source "$ENV_FILE"; set +a

fail() { echo "REFUSED (staging guard): $*"; exit 2; }
[ "${PAYPORT_ENV:-}" = staging ] || fail "PAYPORT_ENV must be 'staging' (is '${PAYPORT_ENV:-unset}')"
db=$(sed -E 's#^jdbc:mysql://[^/]+/([^?]+).*#\1#' <<<"${DB_URL:-}")
[[ "$db" == *staging* ]] || fail "the database name must contain 'staging' (DB_URL points at '$db')"
[ "${SETTLEMENT_MODE:-PARALLEL}" = PARALLEL ] || fail "SETTLEMENT_MODE must be PARALLEL in staging"
[ "${SETTLEMENT_AUTOMATION_ENABLED:-false}" = false ] || fail "SETTLEMENT_AUTOMATION_ENABLED must be false in staging"
[ -n "${SERVER_PORT:-}" ] && [ "$SERVER_PORT" != 8084 ] || fail "SERVER_PORT must be set and must not be production's 8084"
[ -n "${PAYPORT_DATA_KEY:-}" ] || fail "PAYPORT_DATA_KEY is not set (openssl rand -base64 32)"
[ -n "${JWT_SECRET:-}" ] && [ ${#JWT_SECRET} -ge 32 ] || fail "JWT_SECRET must be set (32+ characters, not production's)"
if [ -f /opt/payport/gateway/.env ]; then
  prod_jwt=$(set -a; source /opt/payport/gateway/.env >/dev/null 2>&1; echo "${JWT_SECRET:-}")
  [ "$prod_jwt" != "$JWT_SECRET" ] || fail "JWT_SECRET is the same as production's: a staging token would work in production"
fi

if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "Staging is already running (pid $(cat "$PID_FILE")). Stop it with scripts/staging/stop-staging.sh"; exit 1
fi
JAR=$(find "$DIR/target" -maxdepth 1 -name "*.jar" ! -name "*original*" | head -1)
[ -n "$JAR" ] || { echo "No jar in $DIR/target. Run: mvn clean package -DskipTests"; exit 1; }
cp "$JAR" "$DIR/payport-staging.jar"

echo "Starting STAGING on port $SERVER_PORT, database '$db', settlement $SETTLEMENT_MODE, automation off"
# Traces for the Operations Control Centre (Tempo): set OTEL_AGENT_JAR and OTEL_EXPORTER_OTLP_ENDPOINT in .env.staging
# (see ops/observability/README.md). Without them the gateway runs exactly as before.
JAVA_OPTS=()
if [ -n "${OTEL_AGENT_JAR:-}" ] && [ -f "$OTEL_AGENT_JAR" ]; then
  JAVA_OPTS+=("-javaagent:$OTEL_AGENT_JAR" "-Dotel.service.name=${OTEL_SERVICE_NAME:-payport-gateway-staging}"
              "-Dotel.exporter.otlp.endpoint=${OTEL_EXPORTER_OTLP_ENDPOINT:-http://127.0.0.1:4318}" "-Dotel.exporter.otlp.protocol=http/protobuf"
              "-Dotel.metrics.exporter=none" "-Dotel.logs.exporter=none" "-Dotel.instrumentation.jdbc.statement-sanitizer.enabled=true")
  echo "Tracing on: $OTEL_AGENT_JAR -> ${OTEL_EXPORTER_OTLP_ENDPOINT:-http://127.0.0.1:4318}"
fi
nohup java "${JAVA_OPTS[@]}" -jar "$DIR/payport-staging.jar" > "$LOG" 2>&1 &
echo $! > "$PID_FILE"
for i in $(seq 1 60); do
  if curl -fsS "http://localhost:$SERVER_PORT/actuator/health/readiness" >/dev/null 2>&1; then
    echo "Staging is up (pid $(cat "$PID_FILE")). Logs: tail -f $LOG"; exit 0
  fi
  kill -0 "$(cat "$PID_FILE")" 2>/dev/null || { echo "Staging exited during start-up:"; tail -40 "$LOG"; exit 1; }
  sleep 3
done
echo "Staging did not report ready within 3 minutes (first start applies migrations V32-V52; check the log):"; tail -20 "$LOG"
