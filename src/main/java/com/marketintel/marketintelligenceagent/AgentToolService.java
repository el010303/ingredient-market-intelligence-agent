package com.marketintel.marketintelligenceagent;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class AgentToolService {

    private final IngredientRepository ingredientRepository;
    private final MarketEventRepository marketEventRepository;
    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingService embeddingService;

    // Retrieval ranking parameters.
    // The similarity threshold is a relevance gate: it decides WHETHER a filing is relevant.
    // Recency only re-orders filings that already passed the gate, so a brand-new but
    // irrelevant filing can never outrank an older relevant one.
    static final double SIMILARITY_THRESHOLD = 0.30;
    static final double RECENCY_HALF_LIFE_DAYS = 365.0; // a filing's recency boost halves every year
    static final double RECENCY_WEIGHT = 0.5;           // share of the score that depends on age
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

    public String getMarketEventsAsText(String country) {
        // Most recent first, capped: a country can have dozens of filings,
        // and sending all of them would bury the current ones in the context window.
        List<MarketEvent> list = getMarketEvents(country).stream()
                .sorted(Comparator.comparing(MarketEvent::getEventDate,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(MAX_EVENTS_PER_COUNTRY)
                .toList();
        if (list.isEmpty()) {
            return "No market events found for country: " + country;
        }
        StringBuilder sb = new StringBuilder();
        for (MarketEvent e : list) {
            sb.append("- ").append(e.getTitle())
              .append(" | published: ").append(e.getEventDate())
              .append(" | summary: ").append(e.getLlmSummary())
              .append(" | old rate: ").append(e.getExtractOldRate())
              .append(" | new rate: ").append(e.getExtractNewRate())
              .append(" | effective: ").append(e.getEffectiveDate())
              .append(" | source: ").append(e.getSourceUrl())
              .append("\n");
        }
        return sb.toString();
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
            queryVector, queryVector, SIMILARITY_THRESHOLD, queryVector,
            MAX_RESULTS);
        if (lines.isEmpty()) {
            return "No semantically relevant market events found.";
        }
        return String.join("\n", lines);
    }
}