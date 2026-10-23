"use client";
// Ask page. Runs in the browser and holds no secrets: it calls our own /api/query route,
// which forwards to Spring Boot with the API key on the server.

import { useMemo, useState } from "react";
import ReactMarkdown from "react-markdown";
import type { AgentResult, ToolCall } from "@/lib/types";
import { prettyJson } from "@/lib/format";
import { describeToolCall, sourceOf } from "@/lib/tools";
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

const EXAMPLES = [
  "What duty do I pay importing glycine from India?",
  "Are there Section 301 tariff actions on imports from China?",
  "What is the base duty on xanthan gum?",
];

export default function AskPage() {
  const [question, setQuestion] = useState("");
  const [asked, setAsked] = useState("");
  const [result, setResult] = useState<AgentResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function ask(q: string) {
    const trimmed = q.trim();
    if (!trimmed || loading) return;
    setQuestion(trimmed);
    setAsked(trimmed);
    setLoading(true);
    setError(null);
    setResult(null);
    try {
      const res = await fetch(`/api/query?q=${encodeURIComponent(trimmed)}`);
      const body = await res.json();
      if (!res.ok) throw new Error(body.error ?? `Request failed (${res.status}).`);
      setResult(body as AgentResult);
    } catch (e) {
      setError(e instanceof Error ? e.message : "The request failed. Try again.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <>
      <section className="intro">
        <h1>What will it cost to import this ingredient?</h1>
        <p className="lede">
          Answers come only from U.S. trade filings and the tariff schedule. Every figure links to its
          source, and you can see each step the agent took.
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
            placeholder="Ask about an ingredient, a country or a tariff action"
            maxLength={500}
            aria-label="Your question"
          />
          <button type="submit" disabled={loading || !question.trim()}>
            {loading ? "Checking sources" : "Ask"}
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
      </section>

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

      <aside className="trail" aria-label="How this answer was built">
        <h2>How this answer was built</h2>
        <ol className="steps">
          {steps.map((calls, i) => (
            <li key={i} className="step" style={{ animationDelay: `${i * 120}ms` }}>
              <p className="step-title">
                {calls.length > 1 ? `${calls.length} lookups in parallel` : describeToolCall(calls[0].tool, calls[0].arguments)}
              </p>
              {calls.map((c, j) => (
                <div key={j} className="lookup">
                  {calls.length > 1 && <p className="lookup-title">{describeToolCall(c.tool, c.arguments)}</p>}
                  <p className="lookup-meta">
                    {sourceOf(c.tool)} <code>{c.tool}</code>
                  </p>
                  <details>
                    <summary>Show the data it returned</summary>
                    <pre className="code">{prettyJson(c.arguments)}</pre>
                    <pre className="code returned">{c.resultPreview}</pre>
                  </details>
                </div>
              ))}
            </li>
          ))}
          <li className="step step-final" style={{ animationDelay: `${steps.length * 120}ms` }}>
            <p className="step-title">Wrote the answer from these results only</p>
          </li>
        </ol>
      </aside>
    </div>
  );
}
