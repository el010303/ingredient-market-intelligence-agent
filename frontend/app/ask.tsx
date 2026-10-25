"use client";
// Ask page (client part). Runs in the browser and holds no secrets: it calls our own /api/query
// route, which forwards to Spring Boot with the API key on the server.

import { useEffect, useMemo, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
import type { AgentResult, ToolCall } from "@/lib/types";
import { describeToolCall, sourceOf } from "@/lib/tools";

export interface IngredientTag {
  name: string;
  rate: string | null;
  hts: string;
}
import { Children, type ReactNode } from "react";

const RATE = /(\d+(?:\.\d+)?\s?%|\d+(?:\.\d+)?\s?percent|\bFree\b)/g;

/** Wraps duty figures inside plain-text children in a highlighter span. */
function highlightRates(children: ReactNode): ReactNode {
  return Children.map(children, (child) => {
    if (typeof child !== "string") return child;
    const parts = child.split(RATE);
    return parts.map((part, i) =>
      i % 2 === 1 ? (
        <span key={i} className="rate">
          {part}
        </span>
      ) : (
        part
      ),
    );
  });
}

const LAST_ASK_KEY = "duty-desk:last-ask";

const EXAMPLES = [
  "What duty do I pay importing glycine from India?",
  "Are there Section 301 actions on imports from China?",
  "Any recent filings on xanthan gum from China?",
];

export default function Ask({ tags }: { tags: IngredientTag[] }) {
  const [question, setQuestion] = useState("");
  const [asked, setAsked] = useState("");
  const [result, setResult] = useState<AgentResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const below = useRef<HTMLDivElement>(null);
  const justAsked = useRef(false);

  // The answer appears under the hero, out of view: bring it into view, but only after the person
  // asked something (not when a saved answer is restored on page load).
  useEffect(() => {
    if (!justAsked.current || !(result || error)) return;
    justAsked.current = false;
    const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    below.current?.scrollIntoView({ behavior: reduce ? "auto" : "smooth", block: "start" });
  }, [result, error]);

  // Keep the last answer when the user visits another page and comes back.
  // sessionStorage = this tab only, cleared when the tab closes; it can throw in private
  // modes, so every access is wrapped and the page works without it.
  useEffect(() => {
    try {
      const saved = sessionStorage.getItem(LAST_ASK_KEY);
      if (!saved) return;
      const { asked: q, result: r } = JSON.parse(saved) as { asked: string; result: AgentResult };
      setQuestion(q);
      setAsked(q);
      setResult(r);
    } catch {
      /* nothing saved or storage unavailable: start empty */
    }
  }, []);

  async function ask(q: string) {
    const trimmed = q.trim();
    if (!trimmed || loading) return;
    setQuestion(trimmed);
    setAsked(trimmed);
    justAsked.current = true;
    setLoading(true);
    setError(null);
    setResult(null);
    try {
      const res = await fetch(`/api/query?q=${encodeURIComponent(trimmed)}`);
      const body = await res.json();
      if (!res.ok) throw new Error(body.error ?? `Request failed (${res.status}).`);
      setResult(body as AgentResult);
      try {
        sessionStorage.setItem(LAST_ASK_KEY, JSON.stringify({ asked: trimmed, result: body }));
      } catch {
        /* storage unavailable: the answer still shows, it just won't survive navigation */
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : "The request failed. Try again.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <>
      <section className="hero">
        <div className="hero-inner">
          <div className="hero-copy">
            <h1>Know the duty before you place the order.</h1>
            <p className="lede">
              Ask about an ingredient and where it comes from. The answer is built only from U.S. trade filings
              and the tariff schedule, and links to every source it used.
            </p>

            <form
              className="ask"
              onSubmit={(e) => {
                e.preventDefault();
                ask(question);
              }}
            >
              <input
                value={question}
                onChange={(e) => setQuestion(e.target.value)}
                placeholder="For example: stevia from China"
                maxLength={500}
                aria-label="Your question"
              />
              <button type="submit" disabled={loading || !question.trim()}>
                {loading ? "Checking…" : "Ask"}
              </button>
            </form>

            <ul className="examples" aria-label="Example questions">
              {EXAMPLES.map((ex) => (
                <li key={ex}>
                  <button type="button" onClick={() => ask(ex)} disabled={loading}>
                    {ex}
                  </button>
                </li>
              ))}
            </ul>
          </div>

          {tags.length > 0 && (
            <div className="tags">
              <p className="tags-note">Ingredients with a tracked tariff line. Pick one to ask about it.</p>
              <ul>
                {tags.map((t) => (
                  <li key={t.name}>
                    <button
                      type="button"
                      className="tag"
                      disabled={loading}
                      onClick={() => ask(`What duty do I pay importing ${t.name.toLowerCase()}?`)}
                    >
                      <span className="tag-name">{t.name}</span>
                      <span className="tag-rate">{t.rate ?? "—"}</span>
                      <span className="tag-hts">HTS {t.hts}</span>
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      </section>

      <div className="below" ref={below}>
        {loading && (
          <div className="working" role="status">
            <span className="working-bar" aria-hidden="true" />
            Checking the tariff schedule and recent filings. This usually takes 5 to 20 seconds.
          </div>
        )}

        {error && (
          <p className="notice notice-error" role="alert">
            {error}
          </p>
        )}

        {result && <Result question={asked} result={result} />}
      </div>
    </>
  );
}

function Result({ question, result }: { question: string; result: AgentResult }) {
  // Tool calls made in the same round ran in parallel: show them as one step.
  const steps = useMemo(() => {
    const byRound = new Map<number, ToolCall[]>();
    for (const c of result.toolCalls) byRound.set(c.round, [...(byRound.get(c.round) ?? []), c]);
    return [...byRound.entries()].sort((a, b) => a[0] - b[0]).map(([, calls]) => calls);
  }, [result]);

  return (
    <div className="result">
      <article className="answer">
        <p className="asked">{question}</p>
        <ReactMarkdown
          components={{
            a: ({ href, children }) => (
              <a href={href} target="_blank" rel="noopener noreferrer">
                {children}
              </a>
            ),
            // Duty figures ("4.2%", "25 percent", "Free") get the highlighter, wherever they appear
            p: ({ children }) => <p>{highlightRates(children)}</p>,
            li: ({ children }) => <li>{highlightRates(children)}</li>,
            strong: ({ children }) => <strong>{highlightRates(children)}</strong>,
          }}
        >
          {result.answer}
        </ReactMarkdown>
      </article>

      <aside className="trail" aria-label="How this answer was checked">
        <h2>How this answer was checked</h2>
        <ol className="steps">
          {steps.map((calls, i) => (
            <li key={i} className="step">
              {calls.map((c, j) => (
                <div key={j} className="lookup">
                  <p className="lookup-title">{describeToolCall(c.tool, c.arguments)}</p>
                  <p className="lookup-source">{sourceOf(c.tool)}</p>
                  <details>
                    <summary>See what it found</summary>
                    <pre className="raw">{c.resultPreview}</pre>
                  </details>
                </div>
              ))}
            </li>
          ))}
          <li className="step step-final">
            <p className="lookup-title">Wrote the answer from these results only</p>
          </li>
        </ol>
      </aside>
    </div>
  );
}
