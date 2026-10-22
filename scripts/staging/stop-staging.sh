#!/usr/bin/env bash
# Stops only the staging gateway (by its PID file). Never touches production.
DIR=${STAGING_DIR:-/opt/payport/staging}
PID_FILE=$DIR/staging.pid
[ -f "$PID_FILE" ] || { echo "Staging is not running (no $PID_FILE)"; exit 0; }
pid=$(cat "$PID_FILE")
if kill -0 "$pid" 2>/dev/null && tr '\0' ' ' < /proc/$pid/cmdline | grep -q payport-staging.jar; then
  kill "$pid"   # graceful: in-flight requests finish (server.shutdown=graceful, 30 s)
  for i in $(seq 1 35); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
  kill -0 "$pid" 2>/dev/null && { echo "Still running after 35 s; sending KILL"; kill -9 "$pid"; }
  echo "Staging stopped"
else
  echo "PID $pid is not the staging gateway; nothing stopped"
fi
rm -f "$PID_FILE"
