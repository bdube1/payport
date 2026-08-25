#!/bin/bash

if pgrep -f "PayPortGatewayApplication" > /dev/null; then
    echo "✅ Gateway is RUNNING"
    PID=$(pgrep -f "PayPortGatewayApplication")
    echo "PID: $PID"
    echo ""
    curl -s http://localhost:8084/actuator/health | python3 -m json.tool 2>/dev/null || curl -s http://localhost:8084/actuator/health
else
    echo "❌ Gateway is NOT running"
fi
