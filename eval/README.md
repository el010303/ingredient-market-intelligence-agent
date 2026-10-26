# Retrieval evaluation

Measures the semantic retrieval behind the `search_market_events` tool
(`AgentToolService.searchMarketEventsAsText`). Retrieval has two separate jobs, and each one gets its own metrics:

| Job | Production setting | Question | Metrics |
|---|---|---|---|
| **Relevance gate** | similarity > 0.45, one row per source document, HTS baseline snapshots excluded (before tuning: 0.30, baselines included) | Does it let relevant filings through and keep irrelevant ones out? | Precision, Recall@5, no-answer junk |
| **Ranking** | `similarity × (0.75 + 0.25 × 0.5^(age_days / 365))` (before tuning: weight 0.5) | Does recency put the newest relevant filing first without lifting less relevant filings above more relevant ones? | nDCG@5, newest relevant first |

The script runs every combination of threshold (0.30–0.55) and recency weight (0.5, 0.25 = production, 0 = off), each with and without HTS baseline snapshots (`hts_rate_baseline`), so the production setting is compared against its neighbours instead of being judged in isolation. Baseline snapshots are tested separately because they are dated by fetch time, not by a real event, and the base duty is already served by the `get_tariff_rate` tool.

## Workflow

```bash
# Uses the same variables as the embed consumer: OPENAI_API_KEY, DB_NAME, DB_USER, DB_HOST, DB_PASSWORD (+ optional DB_PORT)
python eval/retrieval_eval.py dump                    # default --env embedding-script/.env
# label: fill the `grade` column in eval/labels.csv (rules below)
python eval/retrieval_eval.py similarity --out eval/similarity.md   # where should the gate be?
python eval/retrieval_eval.py score --out eval/results.md
```

- **Run it against the data you want to describe.** Locally, the default `.env` works. For the EC2 data, open a tunnel (`ssh -L 5433:localhost:5432 ubuntu@<host>`) and point an env file at it with `DB_HOST=localhost` and `DB_PORT=5433`, or run it on the server with `--env ~/app/consumer.env` using the consumer's venv.
- **Pooling.** `dump` collects the top-5 of *every* configuration and adds the union to `labels.csv`. Every result any configuration can return is therefore labeled, so configurations are compared on the same judgments. Re-running `dump` only adds new pairs and never overwrites a grade. If the data changes, run `dump` again before `score`; unlabeled results are scored as 0 and reported as a warning.
- **Reproducibility.** Recency depends on the date. `--as-of YYYY-MM-DD` replaces `current_date`, so a recorded run can be reproduced later. Query embeddings are cached in `eval/.cache/` (not committed).
- **Cost.** One embedding call per query, cached after the first run: well under one cent.

## Labeling rules

`suggested` is a first guess from the title, the country and the event type. **`grade` is your judgment and is the only column that gets scored.** Read the title and fix the suggestion where it is wrong.

| Grade | Meaning |
|---|---|
| **2** relevant | The filing is about the product in the query and, if the query names a country, about imports from that country. For a country-wide query (`q11`, `q12`): a country-wide action (Section 301 / USTR) for that country. |
| **1** partially useful | Right product but a different country; a country-wide action for the queried country (it may or may not cover the product); for a country-wide query, a product filing for that country. |
| **0** not useful | Another product, or another country with no link to the query. |

Notes:
- Paraphrase queries (`q09` "amino acid supplement", `q10` "food thickener") are graded by the product they describe (glycine, xanthan gum), not by the words used.
- Multi-country filings share one `source_url`, and the gate keeps one row per URL (known issue R3), so the `country` column may show one of several countries. Grade by what the title says.
- Queries in the `no_answer` and `out_of_domain` categories are expected to have no grade-2 filing. Whether a query counts as answerable is decided by the labels, not by its category: if the data does contain a stevia filing, `q13` simply becomes answerable.

## Metrics

All computed on the top 5 results, the same cutoff as production (`MAX_RESULTS`).

- **Precision**: grade-2 results ÷ results returned. Of what the agent sees, how much is on topic. Undefined when nothing is returned.
- **Recall@5**: grade-2 results returned ÷ min(5, grade-2 filings in the pool). How much of what exists is found.
- **nDCG@5**: ranking quality with graded gains (2^grade − 1), normalized by the best possible order of the labeled pool. 1.0 means no filing is placed above a more relevant one.
- **Newest relevant first**: share of answerable queries where the first grade-2 result is the most recent grade-2 filing in the pool. This is what the recency factor is for: the latest preliminary or final result in a case is what a sourcing decision depends on.
- **Similarity by grade** (`similarity` command): the cosine similarity of every labeled pair, split by grade, and for each threshold the share of relevant pairs it keeps and junk pairs it removes. This evaluates the gate on its own, independent of ranking. If the grade-2 and grade-0 ranges overlap heavily, no single threshold separates them and the gate needs another signal (keyword match, a reranker).
- **No-answer clean / junk per query**: for queries with no grade-2 filing, the share that return no grade-0 result, and the average number of grade-0 results returned. This is what the threshold protects against: an agent that is handed irrelevant filings tends to cite them.

Raising the threshold should trade Recall for Precision and cleaner no-answer behaviour; the table shows where that trade-off is best for this data. Turning recency on should raise "newest relevant first" while leaving nDCG roughly unchanged, because recency only re-orders filings that already passed the gate.

## Files

| File | Committed | Purpose |
|---|---|---|
| `queries.json` | yes | 17 queries: exact, abbreviation, paraphrase, country-wide, no-answer, out-of-domain |
| `retrieval_eval.py` | yes | `dump` and `score`; the SQL mirrors production |
| `labels.csv` | yes | Human relevance grades (the eval set) |
| `results.md` | yes | Output of the last `score --out` run |
| `similarity.md` | yes | Output of the last `similarity --out` run |
| `.cache/` | no | Cached query embeddings |
