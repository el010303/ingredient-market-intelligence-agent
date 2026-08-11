# Ingredient & Trade Market Intelligence Agent

**Status: Phase 0 in progress** — data layer implemented and verified,
real-time ingestion pipeline live, AI agent layer (tool calling + RAG)
in active development.

## What this is

An AI agent that replicates, in software, the market-analysis judgment
I used to do manually in a prior role as a Business Operations
Analyst at an international
ingredients distributor: given tariff policy changes, commodity price
trends, and supply-demand signals, the agent produces sourcing and
procurement recommendations with a visible reasoning trail.

This isn't a chat wrapper around an LLM. AI is a core product
capability here — tool calling, retrieval, and (in progress) memory
and reasoning traces — built on top of a production-style backend, not
a demo script.

**Why this domain**: I secured a $1M purchase order as a Business
Operations Analyst using exactly this kind of manual analysis —
tracking tariff policy and supply-demand signals to advise a client
on ingredient sourcing. This project rebuilds that judgment process
as a real system, informed by first-hand understanding of what the
analysis is actually supposed to accomplish.

## Architecture

```
External data sources (tariff policy, commodity prices, market signals)
        |
        v
Ingestion Pipeline (scheduled jobs -> RabbitMQ queue, async processing)
        |
        v
PostgreSQL + pgvector (structured data + vector embeddings, one database)
        |
        v
AI Agent layer (hand-rolled state machine: tool calling + retrieval +
                memory + reasoning trace)
        |
        v
Next.js Dashboard (recommendations + explainability view)
```

**Sync vs. async boundary**: real-time user queries ("what's the
tariff risk on this ingredient right now") are synchronous —
the agent decides which tools to call and returns a response in the
same request. Background data ingestion (scheduled scraping of
tariff/price/news updates) is asynchronous via RabbitMQ, so it never
blocks the request path and can retry independently on failure.

## Current progress (Phase 0)

**Done:**
- Data layer: `ingredients`, `market_events`, `recommendations` tables
  — PostgreSQL + JSONB for heterogeneous, category-specific schemas
  (e.g. collagen tracks solubility/color/taste; plant protein tracks
  purity only)
- Real-time ingestion from the **U.S. Federal Register API** — live,
  not mocked. Country-level relevance filtering (title/abstract text
  matched against sourcing countries) to surface tariff events
  relevant to supply-chain risk
- 22 seeded ingredient records across 4 product categories (sweeteners,
  plant proteins, collagens, inulin/Jerusalem artichoke), reflecting
  real sourcing origins (China, Brazil, Sri Lanka, Canada, Finland)
- `summarize_tariff_event`: LLM-based extraction of tariff rate
  changes from unstructured policy text — regex alone couldn't handle
  real-world phrasing like *"initial tariff level of 0 percent,
  increasing in 18 months...to a rate to be announced"*. Verified
  against real Federal Register data across three distinct cases:
  a rate increase with an unresolved future number (correctly
  extracts what's known, returns `null` for the undecided rate rather
  than guessing), a multi-country revocation notice (correctly
  splits into per-country records), and a procedural notice with no
  tariff data at all (correctly returns all fields `null` instead of
  hallucinating numbers)

**In progress:**
- Tool-calling layer: `get_ingredient_info`, `get_market_events`
- End-to-end loop: query → tool calls → generated recommendation

**Not started yet:** hand-rolled state machine orchestration, memory
(recommendation-outcome tracking), retrieval quality control
(similarity thresholding / recency weighting / dedup), frontend.
See **Future Work** below for what's deliberately out of scope.

## Architecture Decision Records

### ADR-001: PostgreSQL + pgvector instead of a separate vector database

**Context**: The agent needs both structured queries (ingredient
specs, price history) and semantic search over market event text.

**Decision**: Store both in PostgreSQL using the `pgvector` extension,
rather than running a dedicated vector database alongside it.

**Consequences**: One database to operate instead of two — lower
operational complexity for a solo project, and structured/vector
queries can be joined in the same SQL statement. Trade-off: less
specialized vector-search tooling than a dedicated vector DB would
offer, which is an acceptable cost at this project's scale.

### ADR-002: RabbitMQ instead of Kafka

**Context**: Background data ingestion (scheduled pulls from
external APIs) needs to run asynchronously so it never blocks the
request path.

**Decision**: Use RabbitMQ for the ingestion queue.

**Consequences**: This is a scheduled, task-oriented workload (fetch
a few sources, process, store) — not a high-throughput,
multi-consumer streaming workload. RabbitMQ's queue model fits that
shape directly and has a lower operational/learning cost for a solo
build than Kafka would. Kafka would add complexity this workload
doesn't need.

### ADR-003: Deliberate polyglot — Spring Boot core, Python for data processing

**Context**: The core backend (REST API, auth, caching, retry,
orchestration) needs production-grade reliability; the data
processing / embedding-generation layer benefits from a mature
ecosystem of tooling.

**Decision**: Keep the core backend in Java/Spring Boot; use Python
only for a narrow, isolated embedding-generation script that reads
text from Postgres, calls an embedding API, and writes back to
`pgvector`.

**Consequences**: This isn't a language-loyalty choice — it's
choosing where to go deep versus where to use the strongest tool
for a narrow job. Spring Boot depth is reused directly from prior
backend reliability work; Python is scoped tightly enough that it
doesn't dilute that depth.

### ADR-004: Hand-rolled state machine instead of LangGraph

**Context**: Agent orchestration needs to coordinate tool calls,
retrieval, and recommendation generation across multiple steps.

**Decision**: Implement a simplified state machine by hand rather
than using LangGraph.

**Consequences**: This project's information-processing flow has a
dependency structure that can be defined upfront (parse intent →
fetch ingredient data → fetch market events → generate
recommendation), which fits a graph/state-machine pattern — favoring
controllability, replayability, and clear failure paths — better
than an open-ended agent loop would. Implementing it by hand (rather
than depending on a library) also means being able to explain the
orchestration's actual mechanics, not just that a framework was
used.

### ADR-005: LLM-based extraction over regex for tariff rate parsing

**Context**: `MarketEventFetcher` originally used a regex
(`\d+\s*percent`) to pull tariff rates out of Federal Register
abstracts. Real data broke this quickly — announcements routinely
phrase rate changes as multi-clause sentences with unresolved future
values (e.g. a rate that "increases in 18 months to a rate to be
announced"), which no fixed pattern can parse correctly.

**Decision**: Extract tariff rate changes via an LLM call
(`summarize_tariff_event`) instead, prompted to return a structured
JSON object (country, old/new rate, effective date, one-line summary)
and to return `null` rather than guess when a value isn't stated in
the source text.

**Consequences**: This required handling two real-world quirks the
regex version never had to deal with: (1) the model sometimes wraps
JSON output in a markdown code fence even when explicitly told not
to — handled by stripping the fence before parsing; (2) a single
policy announcement can reference multiple countries (e.g. a
revocation notice naming both India and China) — handled by
splitting the LLM's country field and writing one `market_events`
row per country, sharing the same `source_url` so it's traceable
back to a single source document. The regex-vs-LLM extraction
success rate on real Federal Register data is being tracked as a
concrete before/after comparison (see project notes).

## Future Work (explicitly out of scope for now)

- **Streaming responses (SSE/WebSocket)** — would require frontend
  complexity (stream rendering, reconnect handling) this project
  deliberately isn't investing in; backend depth is the priority
- **Fine-tuning** — this project's focus is backend + AI system
  integration, not ML research; fine-tuning without a proper
  evaluation framework would add risk without real signal
- **Multi-agent architectures, full LangChain stack** — a single,
  explainable agent workflow is a better fit for this project's
  reliability-focused narrative than stacking multiple loosely
  defined agents
- **Kubernetes, microservices, Terraform/CI-CD pipelines** — not
  warranted at this project's scale
- **Manufacturer-level spec comparison system** — a real part of the
  underlying business problem, deliberately left undesigned here to
  keep scope bounded; happy to discuss the design in an interview

## Tech stack

Spring Boot (Java 17) · PostgreSQL + pgvector · OpenAI API
(`gpt-4o-mini`, tariff extraction) · RabbitMQ · Redis · Spring
Security · Python (embedding generation) · Next.js + TypeScript
(planned) · AWS EC2 (planned)