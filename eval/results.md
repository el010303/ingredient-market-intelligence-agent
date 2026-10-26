Retrieval eval, top-5, as of 2026-10-25. Answerable = at least one grade-2 filing in the pool.
Recency = weight of the age factor (0.00 = off).
Config tables: the 17 hand-written tuning queries. Agent-sent queries (`agent_query`) appear only in the per-query table.

### All event types (behaviour before tuning)

13 answerable queries, 4 no-answer queries.

| Threshold | Recency | Precision | Recall@5 | nDCG@5 | Newest relevant first | Answerable with 0 results | No-answer: clean | No-answer: junk/query |
|---|---|---|---|---|---|---|---|---|
| 0.30 (before tuning) | 0.50 | 0.60 | 0.73 | 0.78 | 77% | 0 | 0% | 4.25 |
| 0.35 | 0.50 | 0.60 | 0.73 | 0.78 | 77% | 0 | 0% | 3.50 |
| 0.40 | 0.50 | 0.60 | 0.73 | 0.79 | 77% | 0 | 25% | 3.00 |
| 0.45 | 0.50 | 0.66 | 0.80 | 0.82 | 69% | 0 | 25% | 3.00 |
| 0.50 | 0.50 | 0.72 | 0.77 | 0.79 | 67% | 1 | 50% | 1.75 |
| 0.55 | 0.50 | 0.81 | 0.67 | 0.71 | 80% | 3 | 75% | 1.00 |
| 0.30 | 0.25 | 0.74 | 0.88 | 0.92 | 62% | 0 | 0% | 4.25 |
| 0.35 | 0.25 | 0.74 | 0.88 | 0.92 | 62% | 0 | 0% | 3.50 |
| 0.40 | 0.25 | 0.74 | 0.88 | 0.92 | 62% | 0 | 25% | 3.00 |
| 0.45 | 0.25 | 0.75 | 0.89 | 0.93 | 62% | 0 | 25% | 3.00 |
| 0.50 | 0.25 | 0.77 | 0.82 | 0.85 | 58% | 1 | 50% | 1.75 |
| 0.55 | 0.25 | 0.83 | 0.69 | 0.72 | 70% | 3 | 75% | 1.00 |
| 0.30 | 0.00 | 0.85 | 0.98 | 0.99 | 23% | 0 | 0% | 4.25 |
| 0.35 | 0.00 | 0.85 | 0.98 | 0.99 | 23% | 0 | 0% | 3.50 |
| 0.40 | 0.00 | 0.85 | 0.98 | 0.99 | 23% | 0 | 25% | 3.00 |
| 0.45 | 0.00 | 0.85 | 0.98 | 0.99 | 23% | 0 | 25% | 3.00 |
| 0.50 | 0.00 | 0.85 | 0.89 | 0.90 | 25% | 1 | 50% | 1.75 |
| 0.55 | 0.00 | 0.93 | 0.77 | 0.77 | 30% | 3 | 75% | 1.00 |

### HTS baseline snapshots excluded from semantic search (production)

12 answerable queries, 5 no-answer queries.

| Threshold | Recency | Precision | Recall@5 | nDCG@5 | Newest relevant first | Answerable with 0 results | No-answer: clean | No-answer: junk/query |
|---|---|---|---|---|---|---|---|---|
| 0.30 | 0.50 | 0.62 | 0.70 | 0.75 | 92% | 0 | 0% | 4.40 |
| 0.35 | 0.50 | 0.62 | 0.70 | 0.75 | 92% | 0 | 0% | 3.60 |
| 0.40 | 0.50 | 0.62 | 0.70 | 0.75 | 92% | 0 | 20% | 3.60 |
| 0.45 | 0.50 | 0.70 | 0.78 | 0.81 | 83% | 0 | 20% | 3.60 |
| 0.50 | 0.50 | 0.80 | 0.78 | 0.79 | 82% | 1 | 40% | 2.20 |
| 0.55 | 0.50 | 0.87 | 0.67 | 0.69 | 89% | 3 | 60% | 1.20 |
| 0.30 | 0.25 | 0.78 | 0.87 | 0.91 | 75% | 0 | 0% | 4.60 |
| 0.35 | 0.25 | 0.78 | 0.87 | 0.91 | 75% | 0 | 0% | 3.80 |
| 0.40 | 0.25 | 0.78 | 0.87 | 0.91 | 75% | 0 | 20% | 3.60 |
| 0.45 **(production)** | 0.25 | 0.82 | 0.90 | 0.93 | 75% | 0 | 20% | 3.60 |
| 0.50 | 0.25 | 0.84 | 0.82 | 0.85 | 73% | 1 | 40% | 2.20 |
| 0.55 | 0.25 | 0.89 | 0.68 | 0.71 | 78% | 3 | 60% | 1.20 |
| 0.30 | 0.00 | 0.90 | 0.98 | 0.99 | 42% | 0 | 0% | 4.60 |
| 0.35 | 0.00 | 0.90 | 0.98 | 0.99 | 42% | 0 | 0% | 3.80 |
| 0.40 | 0.00 | 0.90 | 0.98 | 0.99 | 42% | 0 | 20% | 3.60 |
| 0.45 | 0.00 | 0.90 | 0.98 | 0.99 | 42% | 0 | 20% | 3.60 |
| 0.50 | 0.00 | 0.91 | 0.88 | 0.89 | 45% | 1 | 40% | 2.20 |
| 0.55 | 0.00 | 0.98 | 0.75 | 0.75 | 44% | 3 | 60% | 1.20 |

### Production config, per query

| Query | Category | Returned | Relevant returned / in pool | nDCG@5 | Junk |
|---|---|---|---|---|---|
| q01 glycine tariff risk from India | exact | 5 | 5 / 7 | 1.00 | 0 |
| q02 antidumping duty on glycine from China | exact | 5 | 1 / 5 | 0.48 | 0 |
| q03 countervailing duty on xanthan gum from China | exact | 5 | 5 / 8 | 1.00 | 0 |
| q04 citric acid import duties | exact | 5 | 5 / 8 | 1.00 | 0 |
| q05 vanillin from China trade remedy | exact | 5 | 5 / 8 | 1.00 | 0 |
| q06 monosodium glutamate antidumping Indonesia | exact | 5 | 4 / 4 | 1.00 | 0 |
| q07 pea protein imports from China duties | exact | 5 | 5 / 6 | 1.00 | 0 |
| q08 MSG tariff China | abbreviation | 5 | 4 / 6 | 0.86 | 0 |
| q09 amino acid supplement duties from India | paraphrase | 5 | 4 / 6 | 0.85 | 1 |
| q10 food thickener dumping case China | paraphrase | 5 | 5 / 7 | 1.00 | 0 |
| q11 Section 301 tariffs on Chinese imports | country_wide | 5 | 5 / 8 | 1.00 | 0 |
| q12 recent U.S. trade actions affecting India | country_wide | 5 | 1 / 1 | 1.00 | 4 |
| q13 stevia tariff risk China | no_answer | 5 | 0 / 0 | – | 5 |
| q14 gelatin import duty Brazil | no_answer | 5 | 0 / 0 | – | 3 |
| q15 sucralose antidumping China | no_answer | 5 | 0 / 0 | – | 5 |
| q16 ascorbic acid vitamin C duties | no_answer | 3 † | 0 / 0 | – | 3 |
| q17 coffee bean tariffs Colombia | out_of_domain | 5 | 0 / 0 | – | 5 |
| q18 amino acids supplements | agent_query | 3 † | 3 / 9 | 0.72 | 0 |
| q19 dumping cases food thickeners | agent_query | 1 | 1 / 7 | 0.34 | 0 |

† Nothing passed the gate. Production then returns the 3 closest filings, labeled as low confidence; those are what is scored here.
