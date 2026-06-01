#!/bin/bash

# Tennis Levelr API Test Script
# Tests all available endpoints

BASE_URL="http://localhost:8080"

echo "🎾 Testing Tennis Levelr API..."
echo "================================"
echo ""

# Check if server is running
if ! curl -s --connect-timeout 2 "$BASE_URL/health" > /dev/null 2>&1; then
    echo "❌ Error: Server is not running on $BASE_URL"
    echo "   Start the server with: ./gradlew run"
    exit 1
fi

echo "✅ Server is running"
echo ""

# Test root endpoint
echo "1️⃣  Testing ROOT endpoint (GET /):"
echo "   URL: $BASE_URL/"
RESPONSE=$(curl -s -w "\n%{http_code}" "$BASE_URL/")
HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | sed '$d')
echo "   Response: $BODY"
echo "   Status: $HTTP_CODE"
if [ "$HTTP_CODE" = "200" ]; then
    echo "   ✅ PASSED"
else
    echo "   ❌ FAILED"
fi
echo ""

# Test health endpoint
echo "2️⃣  Testing HEALTH endpoint (GET /health):"
echo "   URL: $BASE_URL/health"
RESPONSE=$(curl -s -w "\n%{http_code}" "$BASE_URL/health")
HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | sed '$d')
echo "   Response: $BODY"
echo "   Status: $HTTP_CODE"
if [ "$HTTP_CODE" = "200" ]; then
    echo "   ✅ PASSED"
else
    echo "   ❌ FAILED"
fi
echo ""

# Performance test
echo "3️⃣  Performance Test:"
echo "   Measuring response time..."
TIME=$(curl -s -w "%{time_total}" -o /dev/null "$BASE_URL/")
echo "   Response time: ${TIME}s"
echo "   ✅ COMPLETED"
echo ""

echo "================================"
echo "✅ All tests complete!"
