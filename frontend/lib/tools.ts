// Plain-language descriptions of the agent's tools, for the reasoning trace.
// The raw tool name is still shown, smaller, for developers.

export function describeToolCall(tool: string, rawArgs: string): string {
  let args: Record<string, unknown> = {};
  try {
    args = JSON.parse(rawArgs);
  } catch {
    /* keep empty */
  }
  const v = (k: string) => (typeof args[k] === "string" ? (args[k] as string) : "");

  switch (tool) {
    case "get_tariff_rate":
      return `Checked the base tariff for ${v("ingredient") || "the ingredient"}`;
    case "get_market_events":
      return v("ingredient")
        ? `Read trade filings on ${v("ingredient")} from ${v("country") || "the country"}`
        : `Read recent trade filings for ${v("country") || "the country"}`;
    case "search_market_events":
      return v("query") ? `Searched filings about “${v("query")}”` : "Searched trade filings";
    case "get_ingredient_info":
      return `Looked up ${v("name") || "the ingredient"} in the ingredient catalog`;
    default:
      return tool;
  }
}

export function sourceOf(tool: string): string {
  switch (tool) {
    case "get_tariff_rate":
      return "USITC Harmonized Tariff Schedule";
    case "get_market_events":
    case "search_market_events":
      return "Federal Register and Regulations.gov";
    case "get_ingredient_info":
      return "Ingredient catalog";
    default:
      return "";
  }
}
