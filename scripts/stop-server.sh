#!/bin/bash
# SPDX-FileCopyrightText: 2026 Lange Pantoja
# SPDX-License-Identifier: AGPL-3.0-or-later


# Stop Tennis Levelr API Server

echo "🛑 Stopping Tennis Levelr API..."
echo ""

# Check if server is running
if ! lsof -Pi :8080 -sTCP:LISTEN -t >/dev/null 2>&1; then
    echo "ℹ️  No server running on port 8080"
    exit 0
fi

# Show what's running
echo "Process running on port 8080:"
lsof -i :8080
echo ""

# Kill the process
echo "Killing process..."
lsof -ti:8080 | xargs kill -9

if [ $? -eq 0 ]; then
    echo "✅ Server stopped successfully"
else
    echo "❌ Error stopping server"
    exit 1
fi
