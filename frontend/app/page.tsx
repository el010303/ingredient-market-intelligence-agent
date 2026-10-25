// Ask page. A Server Component: it reads the tracked tariff lines from Spring Boot (API key stays on
// the server) and hands them to the interactive client part.
import { backendFetch } from "@/lib/backend";
import type { PipelineStatus } from "@/lib/types";
import Ask, { type IngredientTag } from "./ask";

export const dynamic = "force-dynamic";

async function loadTags(): Promise<IngredientTag[]> {
  try {
    const res = await backendFetch("/api/pipeline/status");
    if (!res.ok) return [];
    const status = (await res.json()) as PipelineStatus;
    return status.tariffRates
      .flatMap((t) => t.ingredients.split(", ").map((name) => ({ name, rate: t.general_rate, hts: t.hts_code })))
      .sort((a, b) => a.name.localeCompare(b.name));
  } catch {
    // The page still works without the tags; only the shortcut list is missing.
    return [];
  }
}

export default async function Home() {
  return <Ask tags={await loadTags()} />;
}
