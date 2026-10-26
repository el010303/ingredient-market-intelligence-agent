"""
Offline evaluation of the semantic retrieval behind the `search_market_events` tool.

It answers three questions about AgentToolService.searchMarketEventsAsText:
  1. Is the relevance gate (SIMILARITY_THRESHOLD) a good trade-off between showing
     irrelevant filings and missing relevant ones?
  2. Does the recency factor rank the newest relevant filing first without pushing
     less relevant filings above more relevant ones?
  3. Should HTS baseline snapshots (event_type 'hts_rate_baseline') take part in semantic
     search at all? They are dated by fetch time, not by a real event, and the base duty
     is already served by the get_tariff_rate tool.

Workflow (see eval/README.md):
  python eval/retrieval_eval.py dump        # run every config, pool the top-k results, add them to labels.csv
  # edit eval/labels.csv by hand: fill the `grade` column (0, 1 or 2) for every row
  python eval/retrieval_eval.py similarity  # similarity distribution by grade: where should the gate be?
  python eval/retrieval_eval.py score       # metrics per config as Markdown tables

The SQL below mirrors AgentToolService.searchMarketEventsAsText, including its fallback (the
closest FALLBACK_RESULTS filings when nothing passes the gate). If you change the production
query, change it here too, or the numbers stop describing production.

Queries in the `agent_query` category are searches the agent actually sent. They are pooled,
labeled and shown per query, but left out of the config tables used to choose the values, so
they work as a check on whether the tuning carries over to real agent queries.
"""

import argparse
import csv
import datetime as dt
import json
import math
import os
import re
import sys
from collections import namedtuple
from pathlib import Path

import psycopg2
import requests
from dotenv import load_dotenv

EVAL_DIR = Path(__file__).resolve().parent
REPO_DIR = EVAL_DIR.parent

EMBEDDING_MODEL = "text-embedding-3-small"   # must match EmbeddingService / embed_consumer.py
HALF_LIFE_DAYS = 365.0                       # AgentToolService.RECENCY_HALF_LIFE_DAYS
BASELINE_TYPE = "hts_rate_baseline"
PRODUCTION = (0.45, 0.25, True)              # (SIMILARITY_THRESHOLD, RECENCY_WEIGHT, exclude baselines), since 2026-10-25
BEFORE = (0.30, 0.5, False)                  # the untuned setting the first eval run measured
THRESHOLDS = [0.30, 0.35, 0.40, 0.45, 0.50, 0.55]
RECENCY_WEIGHTS = [0.5, 0.25, 0.0]           # 0.0 = recency off, score = similarity
GATE_SWEEP = [0.30, 0.35, 0.40, 0.45, 0.50, 0.55, 0.60]
FALLBACK_RESULTS = 3                         # AgentToolService.FALLBACK_RESULTS
NO_GATE = -1.0                               # the fallback runs the same query with no threshold
AGENT_CATEGORY = "agent_query"               # searches the agent actually sent; reported, never tuned on

LABEL_FIELDS = ["query_id", "query", "source_url", "title", "country", "event_date",
                "event_type", "suggested", "grade"]

Row = namedtuple("Row", "id title country event_date source_url event_type similarity score")
Label = namedtuple("Label", "grade date event_type")

# Same structure as production: the inner query is the relevance gate plus one row per source
# document; the outer query ranks by similarity x recency factor. Differences, all neutral for
# the production config:
#   - %(as_of)s replaces current_date, so a run can be reproduced later with --as-of
#   - ", id" breaks score ties deterministically
#   - %(ex)s toggles the HTS baseline exclusion (production excludes them since 2026-10-25)
SQL = """
SELECT id, title, country, event_date, source_url, event_type, similarity,
       similarity * CASE WHEN event_date IS NULL THEN (1 - %(w)s) + %(w)s * 0.5
                         ELSE (1 - %(w)s) + %(w)s * power(0.5, GREATEST(%(as_of)s::date - event_date, 0) / %(half)s)
                    END AS score
FROM (
    SELECT DISTINCT ON (source_url)
           id, title, country, event_date, source_url, event_type,
           1 - (embedding <=> %(v)s::vector) AS similarity
    FROM market_events
    WHERE embedding IS NOT NULL
      AND 1 - (embedding <=> %(v)s::vector) > %(t)s
      AND (NOT %(ex)s OR COALESCE(event_type, '') <> %(baseline)s)
    ORDER BY source_url, embedding <=> %(v)s::vector
) t
ORDER BY score DESC, id
LIMIT %(k)s
"""

# Similarity of a labeled document = its best-matching row, the same row DISTINCT ON keeps.
DOC_SIMILARITY_SQL = """
SELECT max(1 - (embedding <=> %(v)s::vector))
FROM market_events
WHERE source_url = %(url)s AND embedding IS NOT NULL
"""


# ---------------------------------------------------------------- data access

def load_queries(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)["queries"]


def embed(text, cache):
    key = f"{EMBEDDING_MODEL}:{text}"
    if key not in cache:
        response = requests.post(
            "https://api.openai.com/v1/embeddings",
            headers={"Authorization": f"Bearer {os.environ['OPENAI_API_KEY']}"},
            json={"model": EMBEDDING_MODEL, "input": text},
            timeout=30,
        )
        response.raise_for_status()
        cache[key] = response.json()["data"][0]["embedding"]
    return "[" + ",".join(str(x) for x in cache[key]) + "]"


def load_cache(path):
    if path.exists():
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    return {}


def save_cache(path, cache):
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(cache, f)


def connect():
    # Same variables as embedding-script/.env, plus optional DB_PORT (e.g. an SSH tunnel to EC2).
    # psycopg2 ignores keyword args whose value is None.
    return psycopg2.connect(dbname=os.getenv("DB_NAME"), user=os.getenv("DB_USER"),
                            host=os.getenv("DB_HOST", "localhost"), port=os.getenv("DB_PORT"),
                            password=os.getenv("DB_PASSWORD"))


def retrieve(cur, vector, threshold, recency_weight, k, as_of, exclude_baselines=False):
    cur.execute(SQL, {"v": vector, "t": threshold, "w": recency_weight, "half": HALF_LIFE_DAYS,
                      "k": k, "as_of": as_of, "ex": exclude_baselines, "baseline": BASELINE_TYPE})
    return [Row(*r) for r in cur.fetchall()]


def doc_similarity(cur, vector, source_url):
    cur.execute(DOC_SIMILARITY_SQL, {"v": vector, "url": source_url})
    return cur.fetchone()[0]


def configs():
    return [(t, w, ex) for ex in (False, True) for w in RECENCY_WEIGHTS for t in THRESHOLDS]


def run_all(queries, retriever):
    """{(threshold, weight, exclude_baselines): {query_id: [Row, ...]}} for every config."""
    return {cfg: {q["id"]: retriever(q, *cfg) for q in queries} for cfg in configs()}


def production_fallback(queries, results, retriever):
    """{query_id: [Row, ...]} for queries where nothing passes the production gate: production
    then returns the FALLBACK_RESULTS closest filings, ranked the same way."""
    _, w, ex = PRODUCTION
    return {q["id"]: retriever(q, NO_GATE, w, ex, FALLBACK_RESULTS)
            for q in queries if not results[PRODUCTION][q["id"]]}


# ---------------------------------------------------------------- labels

def mentions(text, term):
    return re.search(r"\b" + re.escape(term.lower()) + r"\b", text) is not None


def suggest_grade(query, row):
    """A first guess to speed up labeling. The human-entered `grade` is what gets scored."""
    title = (row.title or "").lower()
    same_country = query["country"] is None or (row.country or "").lower() == query["country"].lower()
    country_wide = row.event_type == "trade_policy_ustr"
    if query["intent"] == "country_wide":
        if country_wide and same_country:
            return 2
        return 1 if same_country else 0
    if any(mentions(title, t) for t in query["terms"]):
        return 2 if same_country else 1
    if country_wide and query["country"] and same_country:
        return 1
    return 0


def read_labels(path):
    if not path.exists():
        return []
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def write_labels(path, rows):
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=LABEL_FIELDS)
        writer.writeheader()
        writer.writerows(rows)


def merge_pool(existing, queries, results):
    """Add every (query, document) pair any config returned; keep grades already entered."""
    order = {q["id"]: i for i, q in enumerate(queries)}
    seen = {(r["query_id"], r["source_url"]) for r in existing}
    rows, added = list(existing), 0
    by_id = {q["id"]: q for q in queries}
    for per_query in results.values():
        for qid, hits in per_query.items():
            for h in hits:
                if (qid, h.source_url) in seen:
                    continue
                seen.add((qid, h.source_url))
                rows.append({"query_id": qid, "query": by_id[qid]["text"], "source_url": h.source_url,
                             "title": h.title, "country": h.country or "",
                             "event_date": h.event_date.isoformat() if h.event_date else "",
                             "event_type": h.event_type or "", "suggested": suggest_grade(by_id[qid], h),
                             "grade": ""})
                added += 1
    rows.sort(key=lambda r: (order.get(r["query_id"], 999), r["query_id"]))
    return rows, added


def grades_by_query(label_rows):
    """{query_id: {source_url: Label(grade, date, event_type)}} for rows that have a grade."""
    out = {}
    for r in label_rows:
        if r["grade"].strip() == "":
            continue
        g = int(r["grade"])
        if g not in (0, 1, 2):
            sys.exit(f"grade must be 0, 1 or 2 (query {r['query_id']}, {r['source_url']})")
        date = dt.date.fromisoformat(r["event_date"]) if r["event_date"] else None
        out.setdefault(r["query_id"], {})[r["source_url"]] = Label(g, date, r.get("event_type", ""))
    return out


def without_baselines(label_map):
    """The labeled pool as it looks when baseline snapshots cannot be retrieved."""
    return {qid: {u: lab for u, lab in labels.items() if lab.event_type != BASELINE_TYPE}
            for qid, labels in label_map.items()}


# ---------------------------------------------------------------- metrics

def dcg(gains):
    return sum((2 ** g - 1) / math.log2(i + 2) for i, g in enumerate(gains))


def query_metrics(hits, labels, k):
    """Metrics for one query and one config. `labels`: {source_url: Label} from the pool."""
    urls = [h.source_url for h in hits][:k]
    grades = [labels[u].grade if u in labels else 0 for u in urls]
    unlabeled = sum(1 for u in urls if u not in labels)
    relevant_pool = [u for u, lab in labels.items() if lab.grade == 2]
    m = {"returned": len(urls), "relevant_returned": grades.count(2), "relevant_total": len(relevant_pool),
         "junk": grades.count(0), "unlabeled": unlabeled}
    m["answerable"] = len(relevant_pool) > 0
    m["precision"] = grades.count(2) / len(urls) if urls else None
    m["recall"] = grades.count(2) / min(k, len(relevant_pool)) if relevant_pool else None
    ideal = dcg(sorted((lab.grade for lab in labels.values()), reverse=True)[:k])
    m["ndcg"] = dcg(grades) / ideal if ideal > 0 else None
    # Newest relevant first: is the first grade-2 result the most recent grade-2 filing in the pool?
    dated = [labels[u].date for u in relevant_pool if labels[u].date]
    first_relevant = next((u for u, g in zip(urls, grades) if g == 2), None)
    if dated and first_relevant:
        m["newest_first"] = 1.0 if labels[first_relevant].date == max(dated) else 0.0
    else:
        m["newest_first"] = None
    return m


def mean(values):
    values = [v for v in values if v is not None]
    return sum(values) / len(values) if values else None


def summarize(per_query):
    answerable = [m for m in per_query.values() if m["answerable"]]
    no_answer = [m for m in per_query.values() if not m["answerable"]]
    return {
        "precision": mean(m["precision"] for m in answerable),
        "recall": mean(m["recall"] for m in answerable),
        "ndcg": mean(m["ndcg"] for m in answerable),
        "newest_first": mean(m["newest_first"] for m in answerable),
        "empty_answerable": sum(1 for m in answerable if m["returned"] == 0),
        "clean_no_answer": mean(1.0 if m["junk"] == 0 else 0.0 for m in no_answer),
        "junk_no_answer": mean(m["junk"] for m in no_answer),
        "n_answerable": len(answerable), "n_no_answer": len(no_answer),
        "unlabeled": sum(m["unlabeled"] for m in per_query.values()),
    }


def fmt(x, pct=False):
    if x is None:
        return "–"
    return f"{x:.0%}" if pct else f"{x:.2f}"


def config_table(summaries, title):
    any_s = next(iter(summaries.values()))[0]
    lines = [f"### {title}", "",
             f"{any_s['n_answerable']} answerable queries, {any_s['n_no_answer']} no-answer queries.", "",
             "| Threshold | Recency | Precision | Recall@5 | nDCG@5 | Newest relevant first | Answerable with 0 results | No-answer: clean | No-answer: junk/query |",
             "|---|---|---|---|---|---|---|---|---|"]
    for (t, w, ex), (s, _) in summaries.items():
        mark = " **(production)**" if (t, w, ex) == PRODUCTION else " (before tuning)" if (t, w, ex) == BEFORE else ""
        lines.append(f"| {t:.2f}{mark} | {w:.2f} | {fmt(s['precision'])} | {fmt(s['recall'])} | "
                     f"{fmt(s['ndcg'])} | {fmt(s['newest_first'], True)} | {s['empty_answerable']} | "
                     f"{fmt(s['clean_no_answer'], True)} | {fmt(s['junk_no_answer'])} |")
    return lines


def report(results, fallback, label_map, queries, k, as_of):
    tuning_ids = {q["id"] for q in queries if q["category"] != AGENT_CATEGORY}
    lines = [f"Retrieval eval, top-{k}, as of {as_of}. Answerable = at least one grade-2 filing in the pool.",
             "Recency = weight of the age factor (0.00 = off).",
             f"Config tables: the {len(tuning_ids)} hand-written tuning queries. Agent-sent queries "
             f"(`{AGENT_CATEGORY}`) appear only in the per-query table.", ""]
    pools = {False: label_map, True: without_baselines(label_map)}
    summaries = {False: {}, True: {}}
    for (t, w, ex), per_query in results.items():
        metrics = {qid: query_metrics(hits, pools[ex].get(qid, {}), k) for qid, hits in per_query.items()}
        tuning = {qid: m for qid, m in metrics.items() if qid in tuning_ids}
        summaries[ex][(t, w, ex)] = (summarize(tuning), metrics)
    prod_pool = pools[PRODUCTION[2]]
    fallback_metrics = {qid: query_metrics(hits, prod_pool.get(qid, {}), k) for qid, hits in fallback.items()}
    lines += config_table(summaries[False], "All event types (behaviour before tuning)")
    lines += [""] + config_table(summaries[True], "HTS baseline snapshots excluded from semantic search (production)")

    total_unlabeled = (sum(m["unlabeled"] for group in summaries.values() for _, ms in group.values()
                           for m in ms.values())
                       + sum(m["unlabeled"] for m in fallback_metrics.values()))
    if total_unlabeled:
        lines += ["", f"WARNING: {total_unlabeled} returned results have no grade and were scored as 0. "
                      "Run `dump` again and label the new rows."]

    prod_metrics = summaries[PRODUCTION[2]][PRODUCTION][1]
    cat = {q["id"]: q["category"] for q in queries}
    lines += ["", "### Production config, per query", "",
              "| Query | Category | Returned | Relevant returned / in pool | nDCG@5 | Junk |",
              "|---|---|---|---|---|---|"]
    for q in queries:
        m = fallback_metrics.get(q["id"], prod_metrics[q["id"]])
        mark = " †" if q["id"] in fallback_metrics else ""
        ndcg = fmt(m["ndcg"]) if m["answerable"] else "–"   # nDCG is only meaningful when a relevant filing exists
        lines.append(f"| {q['id']} {q['text']} | {cat[q['id']]} | {m['returned']}{mark} | "
                     f"{m['relevant_returned']} / {m['relevant_total']} | {ndcg} | {m['junk']} |")
    if fallback_metrics:
        lines += ["", f"† Nothing passed the gate. Production then returns the {FALLBACK_RESULTS} closest "
                      "filings, labeled as low confidence; those are what is scored here."]
    return "\n".join(lines)


def percentile(sorted_values, p):
    if not sorted_values:
        return None
    i = min(len(sorted_values) - 1, max(0, round(p * (len(sorted_values) - 1))))
    return sorted_values[i]


def grade_table(sims_by_grade):
    lines = ["| Grade | Pairs | Min | 10th pct | Median | 90th pct | Max |", "|---|---|---|---|---|---|---|"]
    for g in (2, 1, 0):
        s = sorted(sims_by_grade.get(g, []))
        lines.append(f"| {g} | {len(s)} | {fmt(percentile(s, 0))} | {fmt(percentile(s, 0.1))} | "
                     f"{fmt(percentile(s, 0.5))} | {fmt(percentile(s, 0.9))} | {fmt(percentile(s, 1))} |")
    return lines


def similarity_report(sims_by_grade, agent_sims=None):
    """sims_by_grade / agent_sims: {grade: [similarity, ...]} for the labeled (query, document)
    pairs of the tuning queries and of the agent-sent queries."""
    lines = ["### Similarity of labeled pairs by grade (tuning queries)", ""] + grade_table(sims_by_grade)
    rel, junk = sims_by_grade.get(2, []), sims_by_grade.get(0, [])
    lines += ["", "### What a gate at each threshold would do", "",
              "| Threshold | Relevant (grade 2) kept | Junk (grade 0) removed |", "|---|---|---|"]
    for t in GATE_SWEEP:
        kept = sum(1 for s in rel if s > t) / len(rel) if rel else None
        removed = sum(1 for s in junk if s <= t) / len(junk) if junk else None
        mark = " **(production)**" if t == PRODUCTION[0] else " (before tuning)" if t == BEFORE[0] else ""
        lines.append(f"| {t:.2f}{mark} | {fmt(kept, True)} | {fmt(removed, True)} |")
    if agent_sims:
        agent_rel = agent_sims.get(2, [])
        kept = sum(1 for s in agent_rel if s > PRODUCTION[0]) / len(agent_rel) if agent_rel else None
        lines += ["", f"### Agent-sent queries (`{AGENT_CATEGORY}`, not used for tuning)", ""] + grade_table(agent_sims)
        lines += ["", f"The production gate ({PRODUCTION[0]:.2f}) keeps {fmt(kept, True)} of their relevant pairs."]
    return "\n".join(lines)


# ---------------------------------------------------------------- CLI

def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("command", choices=["dump", "similarity", "score"])
    p.add_argument("--queries", type=Path, default=EVAL_DIR / "queries.json")
    p.add_argument("--labels", type=Path, default=EVAL_DIR / "labels.csv")
    p.add_argument("--env", type=Path, default=REPO_DIR / "embedding-script" / ".env",
                   help="file with OPENAI_API_KEY and DB_* variables (same as the embed consumer)")
    p.add_argument("--k", type=int, default=5, help="top-k cutoff; production MAX_RESULTS is 5")
    p.add_argument("--as-of", type=dt.date.fromisoformat, default=dt.date.today(),
                   help="date used for recency instead of current_date (YYYY-MM-DD), for reproducible runs")
    p.add_argument("--out", type=Path, help="also write the report to this Markdown file")
    args = p.parse_args(argv)

    load_dotenv(args.env)
    if not os.getenv("OPENAI_API_KEY") or not os.getenv("DB_NAME"):
        sys.exit(f"OPENAI_API_KEY and DB_NAME must be set (looked in {args.env})")

    queries = load_queries(args.queries)
    cache_path = EVAL_DIR / ".cache" / "embeddings.json"
    cache = load_cache(cache_path)
    conn = connect()
    cur = conn.cursor()
    try:
        if args.command == "similarity":
            label_rows = [r for r in read_labels(args.labels) if r["grade"].strip()]
            if not label_rows:
                sys.exit(f"No graded rows in {args.labels}. Run `dump` and label first.")
            texts = {q["id"]: q["text"] for q in queries}
            agent_ids = {q["id"] for q in queries if q["category"] == AGENT_CATEGORY}
            sims, agent_sims = {}, {}
            for r in label_rows:
                s = doc_similarity(cur, embed(texts[r["query_id"]], cache), r["source_url"])
                if s is not None:
                    target = agent_sims if r["query_id"] in agent_ids else sims
                    target.setdefault(int(r["grade"]), []).append(s)
            text = similarity_report(sims, agent_sims)
        else:
            def retriever(q, t, w, ex, k=args.k):
                return retrieve(cur, embed(q["text"], cache), t, w, k, args.as_of, ex)
            results = run_all(queries, retriever)
            fallback = production_fallback(queries, results, retriever)
            if args.command == "dump":
                rows, added = merge_pool(read_labels(args.labels), queries, {**results, "fallback": fallback})
                write_labels(args.labels, rows)
                todo = sum(1 for r in rows if r["grade"].strip() == "")
                print(f"{added} new (query, filing) pairs added to {args.labels}; {todo} rows still need a grade.")
                print("Fill the `grade` column (0/1/2, rules in eval/README.md), then run `score`.")
                return
            label_rows = read_labels(args.labels)
            if not label_rows:
                sys.exit(f"No labels in {args.labels}. Run `dump` first.")
            text = report(results, fallback, grades_by_query(label_rows), queries, args.k, args.as_of)
    finally:
        conn.close()
        save_cache(cache_path, cache)

    print(text)
    if args.out:
        args.out.write_text(text + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
