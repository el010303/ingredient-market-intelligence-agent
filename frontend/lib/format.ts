export function formatTime(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === "") return "—";
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return String(value);
  return d.toLocaleString("en-US", { dateStyle: "medium", timeStyle: "short" });
}

export function formatDate(value: string | null | undefined): string {
  if (!value) return "—";
  // Plain calendar dates ("2026-10-14"): format without any time-zone conversion
  // Accept "2026-10-14" and, defensively, "2026-10-14T07:00:00Z" (use the calendar part only)
  const [y, m, d] = value.slice(0, 10).split("-").map(Number);
  if (!y || !m || !d) return value;
  return new Date(Date.UTC(y, m - 1, d)).toLocaleDateString("en-US", { dateStyle: "medium", timeZone: "UTC" });
}

export function prettyJson(raw: string): string {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

/** "4.2%" -> 4.2, "Free" -> 0, anything else -> null */
export function parseRate(rate: string | null): number | null {
  if (!rate) return null;
  if (/^free/i.test(rate.trim())) return 0;
  const m = rate.match(/([\d.]+)\s*%/);
  return m ? Number(m[1]) : null;
}

/** Turns "fetchedFR=0, fetchedRegs=16, ..." into a readable sentence. */
export function describeRun(result: string | null): string {
  if (!result) return "Still running";
  const n: Record<string, number> = {};
  for (const part of result.split(",")) {
    const [k, v] = part.split("=").map((s) => s.trim());
    if (k && v !== undefined) n[k] = Number(v);
  }
  const bits: string[] = [];
  const plural = (x: number, one: string, many: string) => `${x} ${x === 1 ? one : many}`;
  if (n.fetchedFR) bits.push(plural(n.fetchedFR, "new Federal Register filing", "new Federal Register filings"));
  if (n.fetchedRegs) bits.push(plural(n.fetchedRegs, "new USTR document", "new USTR documents"));
  if (n.htsEvents) bits.push(plural(n.htsEvents, "tariff rate recorded", "tariff rates recorded"));
  if (n.staleReset) bits.push(plural(n.staleReset, "stuck item recovered", "stuck items recovered"));
  return bits.length ? bits.join(", ") : "No changes";
}
