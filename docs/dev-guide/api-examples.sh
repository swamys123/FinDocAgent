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
DOCUMENT_ID=$(curl -sS -X POST "$HOST/api/v1/documents/upload" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/tmp/findoc-sample.txt" | python3 -c 'import sys, json; print(json.load(sys.stdin)["documentId"])')
echo "Document ID: $DOCUMENT_ID"
echo

echo "Wait for the document to reach READY, then querying agent..."
read -r -p "Press Enter when document status is READY. "
QUERY_RESPONSE=$(curl -sS -X POST "$HOST/api/v1/agent/query" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d "{\"query\":\"Summarize the financial risks\",\"documentIds\":[\"$DOCUMENT_ID\"]}")
echo "$QUERY_RESPONSE"
QUERY_ID=$(printf '%s' "$QUERY_RESPONSE" | python3 -c 'import sys, json; print(json.load(sys.stdin)["queryId"])')
SESSION_ID=$(printf '%s' "$QUERY_RESPONSE" | python3 -c 'import sys, json; print(json.load(sys.stdin)["sessionId"])')
echo

echo "Retrieving session history..."
curl -sS "$HOST/api/v1/agent/sessions/$SESSION_ID" -H "Authorization: Bearer $TOKEN"
echo

echo "Retrieving query explanation..."
curl -sS "$HOST/api/v1/agent/explain/$QUERY_ID" -H "Authorization: Bearer $TOKEN"
echo
