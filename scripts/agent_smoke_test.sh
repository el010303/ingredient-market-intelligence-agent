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
    # A failing tool does not fail the request: the backend hands the error to the model,
    # which then writes a normal-looking answer. Catch it here.
    if str(c.get("resultPreview", "")).startswith("Tool error"):
        print("  FAIL  %s returned an error (check the backend log)" % c["tool"])
print()
print(r["answer"])
if r["answer"].startswith("Agent could not complete"):
    print("  FAIL  no answer (tool-call round limit reached and the final answer call failed)")
'
  echo
}

# Questions not tied to one country, so the model should pick search_market_events (the
# semantic search tuned with eval/). The ingredient questions above never reach it: they name a
# country, so the model uses get_market_events. Prints PASS, WARN or FAIL per question.
search_check() {
  echo "=================================================================="
  echo "Q: $1"
  echo "   expect top search result about: ${2:-any relevant product}"
  echo "------------------------------------------------------------------"
  curl -s -G "$BASE/api/agent/query" -H "X-API-Key: $KEY" --data-urlencode "q=$1" \
    | EXPECT="$2" python3 -c '
import json, os, sys
expect = os.environ.get("EXPECT", "").lower()
raw = sys.stdin.read()
try:
    r = json.loads(raw)
except Exception:
    print("  FAIL  not JSON:", raw[:300]); sys.exit()
calls = r.get("toolCalls", [])
for c in calls:
    print("  tool  round %s  %-20s %s" % (c["round"], c["tool"], c["arguments"]))
searches = [c for c in calls if c["tool"] == "search_market_events"]
if not searches:
    print("  WARN  search_market_events was not called, so this run did not test semantic search")
    sys.exit()
for c in searches:
    lines = str(c.get("resultPreview", "")).split("\n")
    # When nothing passes the similarity gate, the tool returns the closest filings under a
    # "LOW CONFIDENCE" header instead of an empty result.
    fallback = lines[0].startswith("LOW CONFIDENCE")
    top = lines[1] if fallback and len(lines) > 1 else lines[0]
    print("  top result: " + top[:200])
    if top.startswith("Tool error"):
        print("  FAIL  search_market_events returned an error (check the backend log)")
    elif top.startswith("No semantically relevant"):
        print("  FAIL  search returned nothing")
    elif expect and expect not in top.lower():
        print("  WARN  top result does not mention %r" % expect)
    elif fallback:
        print("  PASS  (nothing passed the gate; closest matches returned as low confidence)")
    else:
        print("  PASS")
'
  echo
}

ask "Any tariff risk for stevia from China?"
ask "What are the trade risks for fish collagen from China?"
ask "What duty do I pay importing glycine from India?"
ask "Are there any recent filings on xanthan gum from China?"
ask "Any tariff risk for stevia?"

echo "=================== semantic search (search_market_events) ==================="
search_check "Any recent dumping cases on food thickeners?" "xanthan"
search_check "What trade cases involve amino acids used in supplements?" "glycine"
search_check "Which food additives have had recent antidumping or countervailing duty cases?"
# No filing in the data is about vitamins. An open question like this goes to search_market_events,
# which can only return near-misses (low confidence, or other products that passed the gate). Read
# the answer: it must say no filing is about vitamins and must not present another product's case.
ask "Which vitamins have had antidumping or countervailing duty cases?"
# Used to spend all 4 tool rounds on lookups and return no answer (R19). Must now answer, saying
# the data has no filing on ascorbic acid rather than guessing.
ask "Are there duties on ascorbic acid (vitamin C) imports?"

echo "=================== consistency: same question 3 times ==================="
for i in 1 2 3; do
  curl -s -G "$BASE/api/agent/query" -H "X-API-Key: $KEY" \
    --data-urlencode "q=Any tariff risk for stevia from China?" \
    | python3 -c 'import json,sys; r=json.load(sys.stdin); print(" | ".join(c["tool"]+c["arguments"] for c in r["toolCalls"]))'
done
