#!/usr/bin/env bash
set -euo pipefail
DIR=${COMMERCE_DIR:-$(cd "$(dirname "$0")/.." && pwd)}
PID_FILE=$DIR/commerce.pid
[ -f "$PID_FILE" ] || { echo "Commerce is not running (no pid file)"; exit 0; }
pid=$(cat "$PID_FILE")
if kill -0 "$pid" 2>/dev/null; then
  kill "$pid"
  for i in $(seq 1 20); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
  kill -0 "$pid" 2>/dev/null && kill -9 "$pid"
fi
rm -f "$PID_FILE"
echo "Commerce stopped"
