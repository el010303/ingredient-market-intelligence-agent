// Data sources page: a Server Component. It runs on the server, calls Spring Boot directly with
// the API key, and sends only rendered HTML to the browser.
import { backendFetch } from "@/lib/backend";
import { describeRun, formatDate, formatTime, parseRate } from "@/lib/format";
import type { PipelineStatus } from "@/lib/types";

export const dynamic = "force-dynamic";

const SOURCE_INFO: Record<string, { agency: string; answers: string }> = {
  "Federal Register": {
    agency: "Department of Commerce",
    answers: "Antidumping and countervailing duty cases on specific products",
  },
  "Regulations.gov (USTR)": {
    agency: "U.S. Trade Representative",
    answers: "Section 301 tariff actions on whole countries",
  },
  "USITC HTS": {
    agency: "U.S. International Trade Commission",
    answers: "The base tariff every importer pays before extra duties",
  },
};

async function loadStatus(): Promise<PipelineStatus | null> {
  try {
    const res = await backendFetch("/api/pipeline/status");
    return res.ok ? ((await res.json()) as PipelineStatus) : null;
  } catch {
    return null;
  }
}

export default async function PipelinePage() {
  const status = await loadStatus();
  if (!status) {
    return (
      <section className="hero hero-short">
        <div className="hero-inner">
          <div className="hero-copy">
            <h1>Where the answers come from</h1>
            <p className="notice notice-error">
              The data service didn&apos;t respond. Check that the backend is running, then reload this page.
            </p>
          </div>
        </div>
      </section>
    );
  }

  const total = status.statusCounts.reduce((n, s) => n + Number(s.rows), 0);
  const searchable = Number(status.statusCounts.find((s) => s.status === "EMBEDDED")?.rows ?? 0);
  const rates = status.tariffRates
    .map((t) => ({ ...t, value: parseRate(t.general_rate) }))
    .sort((a, b) => (b.value ?? -1) - (a.value ?? -1));
  const maxRate = Math.max(1, ...rates.map((r) => r.value ?? 0));

  return (
    <>
      <section className="hero hero-short">
        <div className="hero-inner">
          <div className="hero-copy">
            <h1>Where the answers come from</h1>
            <p className="lede">
              A U.S. import duty is set by three agencies. Each one publishes in a different place, and the
              agent reads all three. New documents are collected every weekday morning.
            </p>
            <p className="health">
              {searchable === total
                ? `All ${total} records are searchable.`
                : `${searchable} of ${total} records are searchable; ${total - searchable} are still being processed.`}
            </p>
          </div>
        </div>
      </section>

      <div className="below">

      <section className="sources" aria-label="Sources">
        {status.eventsBySource.map((s) => {
          const info = SOURCE_INFO[s.source];
          return (
            <div key={s.source} className="source">
              <h2>{s.source}</h2>
              <p className="source-agency">{info?.agency}</p>
              <p className="source-count">
                {s.documents}
                <span>{Number(s.documents) === 1 ? "document" : "documents"}</span>
              </p>
              <p className="source-answers">{info?.answers}</p>
              <p className="source-latest">Latest: {formatDate(s.latest_event_date)}</p>
            </div>
          );
        })}
      </section>

      <section className="block">
        <h2>Base tariff by ingredient</h2>
        <p className="block-note">
          General (most-favored-nation) rate from the Harmonized Tariff Schedule. Antidumping, countervailing
          and Section 301 duties are charged on top.
        </p>
        <div className="table-card">
        <table className="rates">
          <thead>
            <tr>
              <th scope="col">Ingredient</th>
              <th scope="col" className="col-rate">Rate</th>
              <th scope="col" className="col-bar"><span className="sr-only">Relative rate</span></th>
              <th scope="col">HTS line</th>
              <th scope="col">Checked</th>
            </tr>
          </thead>
          <tbody>
            {rates.map((t) => (
              <tr key={t.hts_code}>
                <td>{t.ingredients}</td>
                <td className="col-rate">
                  <span className="rate">{t.general_rate ?? "—"}</span>
                </td>
                <td className="col-bar">
                  <span className="bar" style={{ width: `${((t.value ?? 0) / maxRate) * 100}%` }} />
                </td>
                <td className="hts">{t.hts_code}</td>
                <td className="muted">{formatTime(t.fetched_at)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        </div>
      </section>

      <section className="block">
        <h2>Recent collection runs</h2>
        {status.recentRuns.length === 0 ? (
          <p className="block-note">No runs recorded yet. The next scheduled run is weekday 9:00 a.m. Eastern.</p>
        ) : (
          <ul className="runs">
            {status.recentRuns.map((r, i) => (
              <li key={i}>
                <time>{formatTime(r.started_at)}</time>
                <span className="run-trigger">{r.trigger === "schedule" ? "Scheduled" : "Manual"}</span>
                <span className={r.error ? "run-result notice-error" : "run-result"}>
                  {r.error ? `Failed: ${r.error}` : describeRun(r.result)}
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>
      </div>
    </>
  );
}
