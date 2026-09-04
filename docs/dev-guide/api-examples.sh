#!/usr/bin/env bash
set -euo pipefail

TENANT_ID="00000000-0000-0000-0000-000000000001"
USERNAME="demo@findoc.local"
PASSWORD="demo123"
HOST="http://localhost:8080"

curl -sS "$HOST/actuator/health"
echo

echo "Requesting JWT..."
TOKEN=$(curl -sS -X POST "$HOST/api/v1/auth/token" \
  -H 'Content-Type: application/json' \
  -d "{\"tenantId\":\"$TENANT_ID\",\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}" | python3 -c 'import sys, json; print(json.load(sys.stdin)["accessToken"])')

echo "Token: $TOKEN"

echo "Listing documents..."
curl -sS "$HOST/api/v1/documents" -H "Authorization: Bearer $TOKEN"
echo

echo "Uploading sample document..."
printf 'Quarterly risk review: cash flow remains under pressure and margin risk persists.\n' > /tmp/findoc-sample.txt
curl -sS -X POST "$HOST/api/v1/documents/upload" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/tmp/findoc-sample.txt"
echo

echo "Querying agent..."
curl -sS -X POST "$HOST/api/v1/agent/query" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"query":"Summarize the financial risks","documentIds":[],"sessionId":"11111111-1111-1111-1111-111111111111"}'
echo
