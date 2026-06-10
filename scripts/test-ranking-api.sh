#!/bin/bash

# Tennis Levelr - Ranking API Test Script

BASE_URL="http://localhost:8080"

echo "🎾 Testing Ranking Calculation API..."
echo "====================================="
echo ""

# Check if server is running
if ! curl -s --connect-timeout 2 "$BASE_URL/health" > /dev/null 2>&1; then
    echo "❌ Error: Server is not running on $BASE_URL"
    echo "   Start the server with: ./scripts/start-server.sh"
    exit 1
fi

echo "✅ Server is running"
echo ""

# Test 1: Valid ranking calculation
echo "1️⃣  Testing VALID ranking calculation (NTRP):"
echo "   POST $BASE_URL/api/v1/calculate-ranking"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/calculate-ranking" \
  -H "Content-Type: application/json" \
  -d '{
  "players": {
    "P123": {
      "playerId": "P123",
      "name": "John Doe",
      "rating": {
        "value": 4.5,
        "system": "NTRP"
      }
    },
    "P456": {
      "playerId": "P456",
      "name": "Jane Smith",
      "rating": {
        "value": 4.0,
        "system": "NTRP"
      }
    }
  },
  "matchScore": {
    "sets": [
      {
        "games": {
          "P123": 6,
          "P456": 4
        },
        "winnerTeamId": "P123"
      }
    ]
  }
}')

HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | sed '$d')

echo "   Status: $HTTP_CODE"
if [ "$HTTP_CODE" = "200" ]; then
    echo "   ✅ PASSED"
    echo "   Response (formatted):"
    echo "$BODY" | jq '.' 2>/dev/null || echo "$BODY"
else
    echo "   ❌ FAILED"
    echo "   Response: $BODY"
fi
echo ""

# Test 2: With tiebreak
echo "2️⃣  Testing ranking calculation WITH TIEBREAK (UTR):"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/calculate-ranking" \
  -H "Content-Type: application/json" \
  -d '{
  "players": {
    "P789": {
      "playerId": "P789",
      "name": "Mike Wilson",
      "rating": {
        "value": 8.5,
        "system": "UTR"
      }
    },
    "P101": {
      "playerId": "P101",
      "name": "Sarah Lee",
      "rating": {
        "value": 8.2,
        "system": "UTR"
      }
    }
  },
  "matchScore": {
    "sets": [
      {
        "games": {
          "P789": 7,
          "P101": 6
        },
        "tiebreak": {
          "points": {
            "P789": 7,
            "P101": 5
          },
          "winnerTeamId": "P789"
        },
        "winnerTeamId": "P789"
      }
    ]
  }
}')

HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | sed '$d')

echo "   Status: $HTTP_CODE"
if [ "$HTTP_CODE" = "200" ]; then
    echo "   ✅ PASSED"
    echo "   Response (formatted):"
    echo "$BODY" | jq '.' 2>/dev/null || echo "$BODY"
else
    echo "   ❌ FAILED"
    echo "   Response: $BODY"
fi
echo ""

# Test 3: Invalid rating (out of range)
echo "3️⃣  Testing INVALID rating (NTRP out of range):"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/calculate-ranking" \
  -H "Content-Type: application/json" \
  -d '{
  "players": {
    "P123": {
      "playerId": "P123",
      "name": "John Doe",
      "rating": {
        "value": 8.5,
        "system": "NTRP"
      }
    },
    "P456": {
      "playerId": "P456",
      "name": "Jane Smith",
      "rating": {
        "value": 4.0,
        "system": "NTRP"
      }
    }
  },
  "matchScore": {
    "sets": [
      {
        "games": {
          "P123": 6,
          "P456": 4
        },
        "winnerTeamId": "P123"
      }
    ]
  }
}')

HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | sed '$d')

echo "   Status: $HTTP_CODE"
if [ "$HTTP_CODE" = "400" ]; then
    echo "   ✅ PASSED (correctly rejected)"
    echo "   Error: $BODY"
else
    echo "   ❌ FAILED (should return 400)"
    echo "   Response: $BODY"
fi
echo ""

# Test 4: Different rating systems
echo "4️⃣  Testing INVALID (different rating systems):"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/calculate-ranking" \
  -H "Content-Type: application/json" \
  -d '{
  "players": {
    "P123": {
      "playerId": "P123",
      "name": "John Doe",
      "rating": {
        "value": 4.5,
        "system": "NTRP"
      }
    },
    "P456": {
      "playerId": "P456",
      "name": "Jane Smith",
      "rating": {
        "value": 8.0,
        "system": "UTR"
      }
    }
  },
  "matchScore": {
    "sets": [
      {
        "games": {
          "P123": 6,
          "P456": 4
        },
        "winnerTeamId": "P123"
      }
    ]
  }
}')

HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | sed '$d')

echo "   Status: $HTTP_CODE"
if [ "$HTTP_CODE" = "400" ]; then
    echo "   ✅ PASSED (correctly rejected)"
    echo "   Error: $BODY"
else
    echo "   ❌ FAILED (should return 400)"
    echo "   Response: $BODY"
fi
echo ""

echo "====================================="
echo "✅ Manual API tests complete!"
echo ""
echo "💡 Note: These tests use hardcoded responses."
echo "   Actual ranking algorithm will be implemented next."
