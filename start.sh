#!/bin/bash

echo "🚀 Starting PayPort Gateway..."

# Look for the JAR file
JAR_FILE=$(find /opt/payport/gateway/target -name "*.jar" -type f | grep -v "original" | head -1)

if [ -z "$JAR_FILE" ]; then
    echo "❌ JAR file not found. Run 'mvn clean package -DskipTests' first"
    exit 1
fi

echo "📦 Using JAR: $JAR_FILE"

# Check if already running
if pgrep -f "payport-gateway.*.jar" > /dev/null; then
    echo "⚠️  Gateway is already running. Stopping it first..."
    pkill -f "payport-gateway.*.jar"
    sleep 2
fi

# Load environment variables from .env before starting -- was being done
# manually every time before this. set -a exports every variable sourced
# from .env into the environment automatically, so java -jar picks them
# up without needing --env-file or similar; set +a turns that back off
# immediately after, so nothing else in this script is affected by it.
ENV_FILE="/opt/payport/gateway/.env"
if [ ! -f "$ENV_FILE" ]; then
    echo "❌ .env file not found at $ENV_FILE -- the gateway needs its database, JWT, and provider credentials from there. Create it before starting."
    exit 1
fi
set -a
source "$ENV_FILE"
set +a

# Start the gateway
nohup java -jar "$JAR_FILE" > /opt/payport/gateway/gateway.log 2>&1 &

# Check if started successfully
sleep 3
if pgrep -f "payport-gateway.*.jar" > /dev/null; then
    echo "✅ PayPort Gateway started successfully!"
    echo "📝 Logs: tail -f /opt/payport/gateway/gateway.log"
    echo "🛑 Stop: pkill -f 'payport-gateway.*.jar'"
else
    echo "❌ Failed to start gateway. Check logs: /opt/payport/gateway/gateway.log"
    tail -20 /opt/payport/gateway/gateway.log
fi
