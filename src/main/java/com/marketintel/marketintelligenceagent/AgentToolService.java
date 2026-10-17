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
        List<MarketEvent> list = getMarketEvents(country);
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
              .append("\n");
        }
        return sb.toString();
    }

    public String searchMarketEventsAsText(String query) {
        String queryVector = embeddingService.embed(query);
        String sql = """
            SELECT id, title, llm_summary, country, event_date, similarity FROM (
            SELECT DISTINCT ON (source_url)
                    id, title, llm_summary, country, event_date,
                    1 - (embedding <=> ?::vector) AS similarity
            FROM market_events
            WHERE embedding IS NOT NULL
                AND 1 - (embedding <=> ?::vector) > 0.30
            ORDER BY source_url, embedding <=> ?::vector
            ) t
            ORDER BY similarity DESC
            LIMIT 5
            """;
        List<String> lines = jdbcTemplate.query(sql,
            (rs, i) -> "- " + rs.getString("title")
                + " | country: " + rs.getString("country")
                + " | published: " + rs.getDate("event_date")
                + " | similarity: " + String.format("%.2f", rs.getDouble("similarity"))
                + " | summary: " + rs.getString("llm_summary"),
            queryVector, queryVector, queryVector);
        if (lines.isEmpty()) {
            return "No semantically relevant market events found.";
        }
        return String.join("\n", lines);
    }
}