# Known Issues & Limitations

> Last updated: 2026-10-25
>
> This file tracks issues that are **known but not yet fixed**. When an issue is resolved, it moves to the [Changelog](#changelog) at the bottom with a note on how it was fixed.
>
> Severity: 🔴 correctness / security · 🟡 quality / maintainability · ⚪ cosmetic or accepted

---

## Priorities

| ID | Issue | Why it's next | Est. effort |
|----|-------|---------------|-------------|
| D1 | API served over plain HTTP; the Vercel → EC2 hop carries the API key unencrypted | Highest remaining security gap now that the dashboard is public | 2–3 h |
| T1 | No unit tests | Safety net for ranking, extraction parsing and auth | 2–3 h |
| R13 | The similarity gate cannot separate relevant from irrelevant filings | Biggest remaining retrieval-quality gap; the eval set makes the fix measurable | 3–4 h |

---

## 1. Deployment & Security

| ID | Sev. | Issue | Current state / workaround | Planned fix |
|----|------|-------|----------------------------|-------------|
| D1 | 🔴 | HTTP only, no TLS | API key travels in plaintext | Domain + Nginx reverse proxy + Let's Encrypt |
| D2 | 🟡 | Ephemeral public IP (no Elastic IP) | IP changes on instance stop/start | Attach an Elastic IP before publishing a demo URL |
| D3 | 🟡 | SSH inbound rule is pinned to a single client IP | SSH times out from a new network until the Security Group rule is updated (never opened to `0.0.0.0/0`) | Move to SSM Session Manager / EC2 Instance Connect and close port 22 |
| D7 | 🟡 | t3 instances default to "unlimited" CPU credits; building on the server could incur charges | JAR is built locally and copied over with `scp` | Keep builds off the server (CI later) |
| D9 | ⚪ | `pg_restore` reports `must be owner of extension vector` on the `COMMENT` step | Harmless; data restores fully | Won't fix |
| D10 | 🟡 | Hibernate `show-sql` is on; `DataSeeder` prints every ingredient to stdout | Noisy logs, including in journald on EC2 | Disable `show-sql` outside the `local` profile; switch prints to debug-level logging |
| D11 | 🟡 | Memory headroom on the 1 GB t3.micro is thin (~217 MiB available with JVM, Postgres, RabbitMQ and the Python consumer running) | Swap configured; RabbitMQ management plugin intentionally not installed | Monitor; move to t3.small or managed services (RDS / Amazon MQ) before adding components |
| D12 | 🟡 | The dashboard's rate limiter is in-memory, i.e. per serverless instance | A determined client could exceed 10 questions / 10 min by hitting different instances | Shared store (Redis / Vercel KV) or an OpenAI monthly spend cap as a backstop |

## 2. Data Pipeline

| ID | Sev. | Issue | Current state / workaround | Planned fix |
|----|------|-------|----------------------------|-------------|
| P2 | 🟡 | USITC HTS data explored but not integrated | — | Deferred |
| P3 | 🟡 | Data is concentrated in 7 AD/CVD cases, mostly China-origin | 100 filings → 122 rows (one row per affected country) across 11 countries | Broaden keyword set as more sources come online |
| P4 | ⚪ | ~9 filings have no abstract; summaries fall back to the title | Lower-quality summaries for those rows | Optional: fetch full text |
| P6 | 🟡 | Edited summaries are not re-embedded | `embed_events.py` only processes rows where `embedding IS NULL` | Track `embedded_at` or a content hash |
| P7 | 🟡 | `normalizeCountry` is a hand-maintained mapping | New variants (e.g. "Kingdom of Thailand") slip through | Map to ISO 3166 country codes |
| P8 | 🟡 | The LLM occasionally returns the issuing country (U.S.) as the affected country | One row was corrected manually | Post-extraction validation: flag `country == "United States"` |
| P9 | ⚪ | One `effective_date` failed to parse | `parseDate` handles ISO and `MMMM d, yyyy` only | Add formats, or require ISO-8601 in the prompt |
| P11 | ⚪ | Regex-extracted rate columns (`old_rate` / `new_rate`) are still present | Kept intentionally for a regex-vs-LLM extraction comparison | Remove after the comparison is recorded |
| P12 | 🟡 | Duplicate check in `summarizeAndStore` uses `findAll().stream()` — O(n) per row | Fine at 122 rows; won't scale | Derived query `existsByCountryAndSourceUrl` + unique index on `(source_url, country)` |
| P13 | 🟡 | `summarizeAndStore` is not `@Transactional` | A multi-country filing could be written partially if a save fails mid-loop | Wrap DB writes in a transaction; keep the LLM call outside it |
| P14 | ⚪ | 7 of 12 keywords returned 0 new filings (sucralose, ascorbic acid, collagen, gelatin, stevia, sorbitol, pea protein*) | Expected — no recent AD/CVD activity for those products | None. *Pea protein rows come from an earlier fetch |
| P15 | 🟡 | Java `SummarizeConsumer` retries permanent errors (e.g. malformed JSON) 3× before dead-lettering | Wastes ~6 s per poison message; observed in a failure drill | Throw `AmqpRejectAndDontRequeueException` for non-retryable errors so they go straight to the DLQ (the Python consumer already does this) |
| P16 | 🟡 | Rows that exhaust retries stay `PENDING`; the scheduled run re-publishes them indefinitely | No `FAILED` transition yet | Track attempt count; mark `FAILED` after N runs and alert |
| P17 | ⚪ | Legacy `embed_events.py` backfill writes embeddings without updating `status` | Kept only as a manual backfill tool | Update `status` in the script, or remove it |
| P18 | ⚪ | DLQ has no tooling | Inspect and re-publish by hand in the management UI | Small re-drive script / endpoint |
| P19 | 🟡 | A filing that names several countries is stored as one row per country; some multi-country USTR notices end up as `Unknown` or `Various Economies` | Search keeps one row per filing, so the country shown is whichever row is closest to the query; labeling found Section 301 actions covering China shown as Malaysia or Unknown | One row per filing in `market_events` plus an `event_countries(event_id, country)` table; embed once per filing; return all countries with `array_agg`. Also fixes R3 |
| P20 | 🟡 | HTS rows store the placeholder `All (MFN)` in `country` | Every country filter has to remember to exclude it; the eval's first-pass labels treated it as a wrong country | Make `country` nullable and add an explicit `applies_to` column (`COUNTRY` / `ALL_MFN`) |
| P21 | 🟡 | `event_type` depends on the source API, not the document: the same kind of Section 301 notice is `trade_policy_ustr` from Regulations.gov but `tariff_policy` from the Federal Register, and cross-source dedup keeps the Federal Register copy | `get_market_events` uses `event_type` to separate product filings from country-wide actions, so these notices land in the wrong group | Classify by issuing agency and content (USTR, "Section 301"), independent of the source |
| P22 | 🟡 | Extraction can assign a country that is not in the filing: "Citric Acid ... From Canada and India" was stored as China | Found while labeling the eval set | Post-extraction check that the stored country appears in the title or abstract; extends P8 |

## 3. Retrieval & Agent

| ID | Sev. | Issue | Current state / workaround | Planned fix |
|----|------|-------|----------------------------|-------------|
| R3 | 🟡 | `DISTINCT ON (source_url)` keeps one row per filing | Multi-country filings may surface the wrong country | Fixed by the P19 data model. `DISTINCT ON (source_url, country)` was considered and rejected: one filing could fill several of the five result slots |
| R4 | 🟡 | `get_ingredient_info` / `get_market_events` load all rows and filter in memory | Acceptable at current size | Repository queries |
| R5 | 🟡 | Country lookup is exact-match and depends on normalization | Unnormalized variants are missed | Fixed together with P7 |
| R6 | ⚪ | `query_type` is hard-coded; `recommended_options` stores free text | Works, but not structured | Intent classification + structured options |
| R8 | 🟡 | Occasional over-inference (e.g. price trends not present in the data) | — | Stricter "tool results only" instruction + groundedness check |
| R9 | 🟡 | No evaluation of answer quality (retrieval is evaluated in `eval/`) | Answers are judged by the smoke test and manual inspection | Groundedness check: every claim traceable to a tool result, scored on a small labeled set |
| R10 | 🟡 | The agent does not reliably flag superseded filings (e.g. a preliminary result followed by the final result for the same review period) | Prompted to, but the model misses it from free text | Extract case, review period and stage (preliminary/final) as structured fields at summarization time; mark superseded rows in code |
| R11 | 🔴 | Extraction can confuse the **period of review** with the **effective date** (e.g. a CVD review for POR 2024 stored as "effective 2024-01-01"); the agent then repeats it | Grounding rules can't catch wrong source data | Extract `period_of_review` and `effective_date` as separate fields with explicit definitions in the prompt; add labeled test cases |
| R12 | ⚪ | The model sometimes omits the source link for the HTS base duty even though the tool returns it | — | Tighten the prompt or append citations in code from the tool trace |
| R13 | 🟡 | The similarity gate cannot separate relevant from irrelevant filings: their similarities overlap between 0.43 and 0.60 | At the tuned gate (0.45), queries with no matching filing still get ~3.6 irrelevant results; the prompt forbids citing other products' filings | Add a second signal, e.g. require a keyword match on the product name or rerank the candidates; re-measure with `eval/` |
| R14 | 🟡 | Semantic search barely distinguishes countries: "antidumping duty on glycine from China" ranks India filings first (nDCG@5 0.48 after tuning) | The agent is required to also call `get_market_events`, which filters by country | Accept an optional country argument in `search_market_events` and apply it as a SQL filter; re-measure with `eval/` |
| R15 | 🟡 | Search results lack diversity: one case produces many similar filings, so "Which food additives have recent AD/CVD cases?" returned five citric-acid filings and the answer said the cases "primarily focus on citric acid", while xanthan gum, MSG, vanillin, glycine and pea protein also have recent cases | Precision@5 is 1.0 for such a query, so the eval does not catch it | Collapse results by product/case (keep the newest filing per case) or rerank with MMR; add list-style queries with a coverage metric to `eval/` |

## 4. Testing & Code Quality

| ID | Sev. | Issue | Planned fix |
|----|------|-------|-------------|
| T1 | 🔴 | No unit tests; builds use `-DskipTests` | Start with pure functions and the auth filter: `normalizeCountry`, `parseDate`, `stripMarkdownCodeBlock`, `ApiKeyFilter` |
| T2 | ⚪ | Logging via `System.out.println` | SLF4J `Logger` |
| T3 | ⚪ | Each service creates its own `new RestTemplate()` | Single shared `@Bean` with timeouts configured |
| T4 | 🟡 | OpenAI calls have no timeout or retry | Connect/read timeouts; exponential backoff on 429 / 5xx |
| T5 | ⚪ | `AgentOrchestrationService` uses a deprecated API (compiler warning) | Recompile with `-Xlint:deprecation` and migrate |

---

## Changelog

| Date | Issue | Fix |
|------|-------|-----|
| 2026-10-25 | **R2** — Similarity threshold (0.30) was a heuristic; **R9 (retrieval part)** — no eval set | Built `eval/`: 17 queries, 164 hand-graded pairs pooled across configurations. Found that the 0.30 gate removed nothing, so recency (weight 0.5) promoted new irrelevant filings. Gate raised to 0.45, recency weight lowered to 0.25: precision 0.60 → 0.82, recall@5 0.73 → 0.90, nDCG@5 0.78 → 0.93, newest relevant filing first 77% → 75%. See `eval/results.md` |
| 2026-10-25 | HTS baseline snapshots are dated by fetch time, so recency ranked them as the newest documents and they appeared in unrelated queries | Excluded `hts_rate_baseline` from semantic search; the base duty is served by `get_tariff_rate`. Rate-change events are still searchable |
| 2026-10-24 | Agent cited filings about other products: `get_market_events` filtered by country only, so a stevia question listed the xanthan-gum antidumping case; it also once queried "United States" instead of the exporting country | Tool takes the ingredient and returns two labeled groups (filings naming the ingredient / country-wide Section 301 actions, coverage marked unknown); "United States" rejected with a hint; empty result says "no filing in this data", not "no duties". Verified with `scripts/agent_smoke_test.sh` (5 questions incl. two positive cases) |
| 2026-10-24 | Same question gave different tool calls and answers | `temperature: 0` + `seed`; prompt requires both market-event and tariff tools for risk/duty questions. 3 identical runs produced identical tool calls |
| 2026-10-24 | Answers quoted column-2 (Cuba/North Korea) and special-program rates that don't apply to the sourcing countries | Removed from the tool output instead of relying on a prompt rule |
| 2026-10-24 | Ask page lost the answer after visiting the data-sources page | Last question and answer kept in `sessionStorage` (per tab), wrapped in try/catch |
| 2026-10-23 | **F1** — No web dashboard | Next.js 15 + TypeScript dashboard on Vercel: BFF route handler + Server Component (API key server-only), reasoning trail, data-sources page, ingestion run history |
| 2026-10-23 | First Vercel deploy blocked: pinned Next.js 15.5.4 had CVE-2025-66478 (RCE) | Upgraded to patched 15.5.x; `npm audit` then flagged postcss and sharp, assessed as not reachable here and fixed via `overrides` instead of a major-version jump |
| 2026-10-23 | **P1** — Only Federal Register as a source | Added Regulations.gov (USTR Section 301 notices, title enriched with FR abstract, cross-source dedup by FR document number) as source 3. Verified: 16 documents ingested, LLM-extracted countries, second run fetched 0 (dedup) |
| 2026-10-23 | A failing tool (e.g. a DB permission error) turned the whole agent request into HTTP 500 | Tool calls are wrapped; errors go back to the model as a tool result so it can say the data is unavailable instead of guessing |
| 2026-10-23 | Production migration created `tariff_rates` owned by `postgres` (run via `sudo -u postgres`), so the app got `permission denied`; per-line error isolation kept the job running | Ownership reassigned to the app user; migration header documents running as the app user |
| 2026-10-23 | **P1 (partial)** — Only one data source | Added USITC HTS REST API as data source 2: base (MFN) duty per ingredient, change detection against a `tariff_rates` snapshot, baseline/change events sent straight to embedding (no LLM for structured data), 8-digit rate inheritance for 10-digit statistical suffixes, new `get_tariff_rate` agent tool |
| 2026-10-22 | **R1** — No recency weighting; older filings outranked newer ones | Two-stage retrieval: similarity threshold as a relevance gate, then rank by `similarity × (0.5 + 0.5 × 0.5^(age_days/365))`. Per-country lookup returns the 10 most recent filings. Verified: glycine query now returns 2026 filings first |
| 2026-10-22 | **R7** — Answers had no source links; no system prompt | Tools return `source_url`; new system prompt (with today's date) requires a date and `[title](url)` for every filing, tool-results-only answers, and a bottom line |
| 2026-10-22 | Pipeline deployed to EC2 | `deploy/setup_pipeline.sh` (idempotent): RabbitMQ with a dedicated generated-password user (`guest` removed), migration, Python venv, `market-intel-embed` systemd unit; verified end-to-end in production |
| 2026-10-22 | **P5** — Summarization and embedding were triggered by hand | RabbitMQ pipeline: `events.summarize` (Java consumer) → `events.embed` (Python consumer), explicit `status` column, conditional-update claims, retries, dead-letter queue, scheduled producer + reconciler. Verified with failure drills (poison message → DLQ; SIGKILL between DB commit and ack → redelivery is a no-op) |
| 2026-10-22 | Stale-claim reconciler never fired locally: `status_updated_at` was `TIMESTAMP` (no zone), written from sessions in different time zones (JVM vs. psql) | Column changed to `TIMESTAMPTZ`; the database is the only writer; the staleness threshold is computed with `now()` in SQL |
| 2026-10-22 | **P12 (partial)** — no uniqueness guarantee on `(source_url, country)` | Unique index `uq_event_source_country` |
| 2026-10-20 | **P10** — If the first extracted country was blank, or the LLM omitted `summary`, `llm_summary` stayed `null` and the row was re-sent to the LLM on every run | Fall back to the original row's country; write an explicit `[no summary extracted]` marker so "processed, no result" is distinguishable from "not yet processed" |
| 2026-10-20 | **D5** — Spring Security logged a generated default password at startup | Provide a `UserDetailsService` bean that rejects all usernames (auth is API-key based) |
| 2026-10-20 | **D6** — `spring.jpa.open-in-view` warning | Set `spring.jpa.open-in-view=false`; verified no `LazyInitializationException` on the agent endpoint |
| 2026-10-20 | Deploys overwrote the running JAR in place | Upload as `app.jar.new` → stop → keep `app.jar.bak` for rollback → swap → start |
| 2026-10 | Agent answers were generic because tool results were entity `toString()` output | Added `*AsText` formatters for tool results |
| 2026-10 | Broad keywords pulled in unrelated FDA notices (23 rows) | Removed the noise; added a trade-remedy term filter (antidumping / countervailing / tariff / duty / Section 301) |
| 2026-10 | "PRC" and "China" stored as separate countries | Data fix + `normalizeCountry` |
| 2026-10 | Multi-country notices returned a JSON array → rows ended up with `Unknown` country and no summary | Rewrote `summarizeAndStore` to write one row per country |
| 2026-10 | Dates like "July 29, 2024" failed to parse | Added `MMMM d, yyyy` format to `parseDate` |
| 2026-10 | `/test/*` endpoints were reachable in production | Restricted to `@Profile("local")` |
| 2026-10 | API had no authentication | `ApiKeyFilter` (`X-API-Key` header, constant-time comparison) |
