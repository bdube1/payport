#!/bin/bash

echo "🛑 Stopping PayPort Gateway..."

# start.sh runs the JAR with nohup + & directly -- it never writes a PID
# file anywhere, so a PID-file check here was dead code that silently
# never triggered. Stopping the gateway has only ever actually worked
# via these two pkill lines matching on process name -- this just
# removes the misleading dead branch, no functional change.
pkill -f "payport-gateway" 2>/dev/null
pkill -f "PayPortGatewayApplication" 2>/dev/null

sleep 2
echo "✅ Gateway stopped"
