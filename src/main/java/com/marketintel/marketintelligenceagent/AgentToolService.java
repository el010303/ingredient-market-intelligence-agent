package com.marketintel.marketintelligenceagent;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AgentToolService {

    private final IngredientRepository ingredientRepository;
    private final MarketEventRepository marketEventRepository;
    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingService embeddingService;

    // Retrieval ranking parameters, tuned with the labeled eval set in eval/ (see eval/results.md).
    // The similarity threshold is a relevance gate: it decides WHETHER a filing is passed on.
    // Recency then re-orders what passed. Recency can only be trusted to re-order relevant
    // filings if the gate actually removes irrelevant ones: at 0.30 it removed none, and
    // weight 0.5 let new irrelevant filings outrank older relevant ones (nDCG@5 0.78).
    //   0.45: keeps 95% of relevant pairs, removes 44% of irrelevant ones. Relevant and
    //         irrelevant similarities overlap (0.43-0.60), so a higher gate starts dropping
    //         relevant filings; see KNOWN_ISSUES R13 for the next step (keyword signal).
    //   0.25: keeps "newest relevant filing first" at 75% (vs 42% with recency off) while
    //         losing little relevance (nDCG@5 0.93 vs 0.99).
    static final double SIMILARITY_THRESHOLD = 0.45;
    static final double RECENCY_HALF_LIFE_DAYS = 365.0; // a filing's recency boost halves every year
    static final double RECENCY_WEIGHT = 0.25;          // share of the score that depends on age
    // HTS baseline snapshots are dated by fetch time, so recency treated them as the newest
    // documents in every query. The base duty is served by get_tariff_rate instead.
    static final String EXCLUDED_FROM_SEARCH = "hts_rate_baseline";
    static final int MAX_RESULTS = 5;
    static final int MAX_EVENTS_PER_COUNTRY = 10;

    public AgentToolService(IngredientRepository ingredientRepository,
                             MarketEventRepository marketEventRepository,
                             JdbcTemplate jdbcTemplate,
                             EmbeddingService embeddingService) {
        this.ingredientRepository = ingredientRepository;
        this.marketEventRepository = marketEventRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingService = embeddingService;
    }

    // get_ingredient_info
    public List<Ingredient> getIngredientInfo(String name) {
        return ingredientRepository.findAll().stream()
                .filter(i -> name.equalsIgnoreCase(i.getName()))
                .toList();
    }

    // get_market_events
    public List<MarketEvent> getMarketEvents(String country) {
        return marketEventRepository.findAll().stream()
                .filter(e -> country.equalsIgnoreCase(e.getCountry()))
                .toList();
    }

    public String getIngredientInfoAsText(String name) {
        List<Ingredient> list = getIngredientInfo(name);
        if (list.isEmpty()) {
            return "No ingredient found with name: " + name;
        }
        StringBuilder sb = new StringBuilder();
        for (Ingredient i : list) {
            sb.append("- ").append(i.getName())
              .append(" | category: ").append(i.getCategory())
              .append(" | origin: ").append(i.getOriginCountry())
              .append(" | specs: ").append(i.getSpecs())
              .append("\n");
        }
        return sb.toString();
    }

    static final int MAX_COUNTRY_WIDE_ACTIONS = 3;

    /**
     * Filings for one country, split into two clearly labeled groups so the model cannot
     * present an unrelated product's case as relevant to the ingredient being asked about:
     *  1) ingredient-specific: AD/CVD and other filings whose title or summary names the ingredient
     *  2) country-wide: USTR Section 301 actions that apply to many products from the country;
     *     whether they cover this ingredient's HTS line is NOT known to this system.
     * Without an ingredient, the newest filings for the country are returned, each with its product named.
     */
    public String getMarketEventsAsText(String country, String ingredient) {
        // Every filing here is a U.S. action ON imports from a foreign country, so "United States"
        // is never a valid value. The model sometimes confuses importer and exporter.
        if (country == null || country.isBlank()
                || country.trim().matches("(?i)(the\\s+)?(united states( of america)?|usa?|u\\.s\\.(a\\.)?)")) {
            return "Invalid country: '" + country + "'. Pass the EXPORTING country the ingredient is imported from"
                + " (for example 'China' or 'India'), not the United States. If unsure, call get_ingredient_info"
                + " and use its origin.";
        }
        StringBuilder sb = new StringBuilder();
        boolean hasIngredient = ingredient != null && !ingredient.isBlank();

        if (hasIngredient) {
            String like = "%" + ingredient.trim() + "%";
            List<String> specific = jdbcTemplate.query("""
                    SELECT title, event_date, llm_summary, extract_old_rate, extract_new_rate,
                           effective_date, source_url
                    FROM market_events
                    WHERE lower(country) = lower(?)
                      AND COALESCE(event_type, '') NOT LIKE 'hts_rate%'
                      AND COALESCE(event_type, '') <> 'trade_policy_ustr'
                      AND (title ILIKE ? OR llm_summary ILIKE ?)
                    ORDER BY event_date DESC NULLS LAST
                    LIMIT ?
                    """,
                (rs, i) -> formatEventLine(rs),
                country, like, like, MAX_EVENTS_PER_COUNTRY);
            sb.append("INGREDIENT-SPECIFIC filings for ").append(ingredient).append(" from ").append(country).append(":\n");
            if (specific.isEmpty()) {
                sb.append("- None. No antidumping, countervailing or other product-specific filing in this system names ")
                  .append(ingredient).append(". This does NOT mean no such duties exist: this system tracks a")
                  .append(" limited set of filings. Report it as 'no filing in this data', not as 'no duties'.\n");
            } else {
                specific.forEach(line -> sb.append(line).append("\n"));
            }
        } else {
            List<String> recent = jdbcTemplate.query("""
                    SELECT title, event_date, llm_summary, extract_old_rate, extract_new_rate,
                           effective_date, source_url
                    FROM market_events
                    WHERE lower(country) = lower(?)
                      AND COALESCE(event_type, '') NOT LIKE 'hts_rate%'
                      AND COALESCE(event_type, '') <> 'trade_policy_ustr'
                    ORDER BY event_date DESC NULLS LAST
                    LIMIT ?
                    """,
                (rs, i) -> formatEventLine(rs),
                country, MAX_EVENTS_PER_COUNTRY);
            sb.append("Most recent product-specific filings for ").append(country)
              .append(" (each applies only to the product named in its title):\n");
            if (recent.isEmpty()) {
                sb.append("- None.\n");
            } else {
                recent.forEach(line -> sb.append(line).append("\n"));
            }
        }

        List<String> countryWide = jdbcTemplate.query("""
                SELECT title, event_date, llm_summary, extract_old_rate, extract_new_rate,
                       effective_date, source_url
                FROM market_events
                WHERE lower(country) = lower(?)
                  AND event_type = 'trade_policy_ustr'
                ORDER BY event_date DESC NULLS LAST
                LIMIT ?
                """,
            (rs, i) -> formatEventLine(rs),
            country, MAX_COUNTRY_WIDE_ACTIONS);
        if (!countryWide.isEmpty()) {
            sb.append("COUNTRY-WIDE trade actions for ").append(country)
              .append(" (Section 301 / USTR). These apply to lists of products; this system does NOT know")
              .append(" whether a given ingredient's HTS line is covered:\n");
            countryWide.forEach(line -> sb.append(line).append("\n"));
        }
        return sb.toString();
    }

    private String formatEventLine(java.sql.ResultSet rs) throws java.sql.SQLException {
        return "- " + rs.getString("title")
            + " | published: " + rs.getDate("event_date")
            + " | summary: " + rs.getString("llm_summary")
            + " | old rate: " + rs.getObject("extract_old_rate")
            + " | new rate: " + rs.getObject("extract_new_rate")
            + " | effective: " + rs.getDate("effective_date")
            + " | source: " + rs.getString("source_url");
    }

    public String searchMarketEventsAsText(String query) {
        String queryVector = embeddingService.embed(query);
        // 1) Inner query: relevance gate (similarity threshold) + one row per source document.
        // 2) Outer query: rank by similarity x recency factor, where
        //      recency factor = (1 - w) + w * 0.5^(age_days / half_life)
        //    -> 1.0 for a filing published today, 0.75 after one half-life, -> 0.5 for very old ones.
        //    Filings without a date get the neutral midpoint 0.75.
        String sql = """
            SELECT id, title, llm_summary, country, event_date, source_url, similarity,
                   similarity * CASE WHEN event_date IS NULL THEN (1 - ?) + ? * 0.5
                                     ELSE (1 - ?) + ? * power(0.5, GREATEST(current_date - event_date, 0) / ?)
                                END AS score
            FROM (
                SELECT DISTINCT ON (source_url)
                       id, title, llm_summary, country, event_date, source_url,
                       1 - (embedding <=> ?::vector) AS similarity
                FROM market_events
                WHERE embedding IS NOT NULL
                  AND 1 - (embedding <=> ?::vector) > ?
                  AND COALESCE(event_type, '') <> ?
                ORDER BY source_url, embedding <=> ?::vector
            ) t
            ORDER BY score DESC
            LIMIT ?
            """;
        List<String> lines = jdbcTemplate.query(sql,
            (rs, i) -> "- " + rs.getString("title")
                + " | country: " + rs.getString("country")
                + " | published: " + rs.getDate("event_date")
                + " | similarity: " + String.format("%.2f", rs.getDouble("similarity"))
                + " | score: " + String.format("%.2f", rs.getDouble("score"))
                + " | summary: " + rs.getString("llm_summary")
                + " | source: " + rs.getString("source_url"),
            RECENCY_WEIGHT, RECENCY_WEIGHT,
            RECENCY_WEIGHT, RECENCY_WEIGHT, RECENCY_HALF_LIFE_DAYS,
            queryVector, queryVector, SIMILARITY_THRESHOLD, EXCLUDED_FROM_SEARCH, queryVector,
            MAX_RESULTS);
        if (lines.isEmpty()) {
            return "No semantically relevant market events found.";
        }
        return String.join("\n", lines);
    }

    // get_tariff_rate: U.S. base (general/MFN) duty from the USITC HTS for an ingredient
    public String getTariffRateAsText(String ingredient) {
        List<String> lines = jdbcTemplate.query("""
                SELECT DISTINCT i.name, t.hts_code, t.description, t.general_rate, t.special_rate,
                       t.other_rate, t.fetched_at
                FROM ingredients i
                JOIN tariff_rates t ON t.hts_code = i.hts_code
                WHERE lower(i.name) = lower(?)
                """,
            (rs, n) -> "- " + rs.getString("name")
                + " | HTS: " + rs.getString("hts_code")
                + " (" + rs.getString("description") + ")"
                + " | general (MFN) rate: " + rs.getString("general_rate")
                // Column-2 (Cuba, North Korea) and special-program rates are left out on purpose:
                // they don't apply to the sourcing countries here, and the model kept quoting them.
                + " | checked: " + rs.getObject("fetched_at")
                + " | source: " + HtsRateFetcher.SEARCH_PAGE + rs.getString("hts_code"),
            ingredient);
        if (lines.isEmpty()) {
            return "No HTS base-rate mapping for ingredient: " + ingredient
                + ". The ingredient may have no dedicated HTS line in this system.";
        }
        return String.join("\n", lines)
            + "\nNote: this is the base duty only. Antidumping/countervailing duties (see market events)"
            + " and other additional duties (e.g. Section 301 / Chapter 99) are separate and not included here.";
    }
}
