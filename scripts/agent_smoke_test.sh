#!/usr/bin/env bash
# Smoke test for the agent: asks a fixed set of questions and prints, for each one,
# the tool calls the model made and the answer. Run against a local backend:
#   ./scripts/agent_smoke_test.sh            (defaults below)
#   BASE=http://localhost:8080 KEY=... ./scripts/agent_smoke_test.sh
BASE="${BASE:-http://localhost:8080}"
KEY="${KEY:-local-dev-key-change-me}"

ask() {
  echo "=================================================================="
  echo "Q: $1"
  echo "------------------------------------------------------------------"
  curl -s -G "$BASE/api/agent/query" -H "X-API-Key: $KEY" --data-urlencode "q=$1" | python3 -c '
import json, sys
raw = sys.stdin.read()
try:
    r = json.loads(raw)
except Exception:
    print("NOT JSON:", raw[:500]); sys.exit()
if "answer" not in r:
    print("ERROR:", raw[:500]); sys.exit()
for c in r["toolCalls"]:
    print("  tool  round %s  %-20s %s" % (c["round"], c["tool"], c["arguments"]))
print()
print(r["answer"])
'
  echo
}

ask "Any tariff risk for stevia from China?"
ask "What are the trade risks for fish collagen from China?"
ask "What duty do I pay importing glycine from India?"
ask "Are there any recent filings on xanthan gum from China?"
ask "Any tariff risk for stevia?"

echo "=================== consistency: same question 3 times ==================="
for i in 1 2 3; do
  curl -s -G "$BASE/api/agent/query" -H "X-API-Key: $KEY" \
    --data-urlencode "q=Any tariff risk for stevia from China?" \
    | python3 -c 'import json,sys; r=json.load(sys.stdin); print(" | ".join(c["tool"]+c["arguments"] for c in r["toolCalls"]))'
done
