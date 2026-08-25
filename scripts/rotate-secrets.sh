#!/bin/bash

echo "🔄 Rotating JWT Secret..."

# Generate new JWT secret
NEW_SECRET=$(openssl rand -base64 32)

# Update .env
sed -i "s/JWT_SECRET=.*/JWT_SECRET=$NEW_SECRET/" /opt/payport/.env

# Restart gateway to apply
docker-compose restart gateway

echo "✅ JWT Secret rotated. Gateway restarted."
