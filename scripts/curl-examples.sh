#!/bin/bash

# Tennis Levelr API - cURL Examples
# Collection of useful cURL commands for testing

BASE_URL="http://localhost:8080"

echo "🎾 Tennis Levelr API - cURL Examples"
echo "====================================="
echo ""
echo "Base URL: $BASE_URL"
echo ""

cat << 'EOF'
📋 Basic Requests:
------------------

1. Test root endpoint:
   curl http://localhost:8080/

2. Test health endpoint:
   curl http://localhost:8080/health

3. Get with verbose output (shows headers):
   curl -v http://localhost:8080/

4. Get with timing information:
   curl -w "\nTime: %{time_total}s\n" http://localhost:8080/

5. Silent mode (only response):
   curl -s http://localhost:8080/health


📋 Future POST Examples (for ranking calculation):
---------------------------------------------------

When you add the ranking calculation endpoint:

1. Calculate NTRP ranking:
   curl -X POST http://localhost:8080/calculate-ranking \
     -H "Content-Type: application/json" \
     -d '{
       "player1Ranking": 4.5,
       "player2Ranking": 4.0,
       "matchScore": "6-4, 6-3",
       "ratingSystem": "NTRP"
     }'

2. Calculate UTR ranking:
   curl -X POST http://localhost:8080/calculate-ranking \
     -H "Content-Type: application/json" \
     -d '{
       "player1Ranking": 8.5,
       "player2Ranking": 7.2,
       "matchScore": "6-4, 6-3",
       "ratingSystem": "UTR"
     }'

3. Pretty print JSON response:
   curl -X POST http://localhost:8080/calculate-ranking \
     -H "Content-Type: application/json" \
     -d '{"player1Ranking": 4.5, "player2Ranking": 4.0, "matchScore": "6-4, 6-3"}' \
     | jq


📋 Testing Tips:
-----------------

1. Save response to file:
   curl http://localhost:8080/ > response.txt

2. Follow redirects:
   curl -L http://localhost:8080/

3. Include response headers in output:
   curl -i http://localhost:8080/

4. Set custom headers:
   curl -H "X-Custom-Header: value" http://localhost:8080/

5. Test with timeout:
   curl --max-time 5 http://localhost:8080/


📋 HTTPie Alternative (if installed):
--------------------------------------

HTTPie is a more user-friendly alternative to curl.
Install: brew install httpie

1. Simple GET:
   http :8080/health

2. POST with JSON:
   http POST :8080/calculate-ranking \
     player1Ranking:=4.5 \
     player2Ranking:=4.0 \
     matchScore="6-4, 6-3"

EOF

echo ""
echo "💡 Run any of these commands in your terminal!"
