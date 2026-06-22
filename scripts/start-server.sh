#!/bin/bash
# SPDX-FileCopyrightText: 2026 Lange Pantoja
# SPDX-License-Identifier: AGPL-3.0-or-later


# Start Tennis Levelr API Server

echo "🎾 Starting Tennis Levelr API..."
echo ""

# Check if port 8080 is already in use
if lsof -Pi :8080 -sTCP:LISTEN -t >/dev/null 2>&1; then
    echo "⚠️  Warning: Port 8080 is already in use"
    echo ""
    echo "Process using port 8080:"
    lsof -i :8080
    echo ""
    read -p "Kill existing process and start server? (y/N) " -n 1 -r
    echo ""
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        echo "Killing process on port 8080..."
        lsof -ti:8080 | xargs kill -9
        echo "Process killed"
        echo ""
    else
        echo "Exiting without starting server"
        exit 1
    fi
fi

# Start the server
echo "Starting server on http://localhost:8080"
echo ""
echo "Press Ctrl+C to stop the server"
echo "================================"
echo ""

cd "$(dirname "$0")/.." && ./gradlew run
