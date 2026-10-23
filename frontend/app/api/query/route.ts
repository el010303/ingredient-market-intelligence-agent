// BFF endpoint: the browser calls this; this calls Spring Boot with the API key.
import { NextRequest, NextResponse } from "next/server";
import { backendFetch } from "@/lib/backend";

export const dynamic = "force-dynamic";

const MAX_QUESTION_LENGTH = 500;

// Every query costs OpenAI calls, so cap requests per client.
// In-memory and per-instance: enough for a single small deployment, not a distributed limiter.
const WINDOW_MS = 10 * 60 * 1000;
const MAX_REQUESTS_PER_WINDOW = 10;
const hits = new Map<string, number[]>();

function rateLimited(client: string): boolean {
  const now = Date.now();
  const recent = (hits.get(client) ?? []).filter((t) => now - t < WINDOW_MS);
  if (recent.length >= MAX_REQUESTS_PER_WINDOW) {
    hits.set(client, recent);
    return true;
  }
  recent.push(now);
  hits.set(client, recent);
  return false;
}

export async function GET(req: NextRequest) {
  const q = req.nextUrl.searchParams.get("q")?.trim() ?? "";
  if (!q || q.length > MAX_QUESTION_LENGTH) {
    return NextResponse.json(
      { error: `Question must be 1-${MAX_QUESTION_LENGTH} characters.` },
      { status: 400 },
    );
  }

  const client = req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() ?? "local";
  if (rateLimited(client)) {
    return NextResponse.json({ error: "Too many questions. Try again in a few minutes." }, { status: 429 });
  }

  try {
    const res = await backendFetch(`/api/agent/query?q=${encodeURIComponent(q)}`);
    if (!res.ok) {
      return NextResponse.json({ error: `Backend returned ${res.status}.` }, { status: 502 });
    }
    return NextResponse.json(await res.json());
  } catch {
    return NextResponse.json({ error: "Backend is unreachable." }, { status: 502 });
  }
}
