package com.marketintel.marketintelligenceagent;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PathVariable;

@RestController
public class RecommendationTestController {
    
    private final RecommendationRepository repository;
    private final TariffSummarizerService tariffSummarizerService;
    private final MarketEventRepository marketEventRepository;
    private final AgentToolService agentToolService;

    @Value("${openai.api.key}")
    private String openaiApiKey;

    public RecommendationTestController(RecommendationRepository repository, TariffSummarizerService tariffSummarizerService, MarketEventRepository marketEventRepository, AgentToolService agentToolService) {
        this.repository = repository;
        this.tariffSummarizerService = tariffSummarizerService;
        this.marketEventRepository = marketEventRepository;
        this.agentToolService = agentToolService;
    }

    @GetMapping("/test/openai-key")
    public String testKeyLoaded() {
        if (openaiApiKey == null || openaiApiKey.isBlank()) {
            return "Key not loaded";
        }
        return "Key loaded, starts with: " + openaiApiKey.substring(0, 7) + "...";
    }

    @GetMapping("/test/recommendation")
    public List<Recommendation> testSaveAndFetch(){
        Recommendation testRec = new Recommendation();
        testRec.setQueryType("candidate_generation");
        testRec.setCustomerContext("test: new protein bar line");
        testRec.setIngredientCategory("plant_protein");
        testRec.setRecommendedOptions(List.of(
            Map.of("ingredient", "pea protein", "origin_country",  "Canada", "purity", 90),
            Map.of("ingredient", "rice protein", "origin_country", "China", "purity", 85)
        ));
        testRec.setReasoningTrace(Map.of(
            "tool_called", "get_ingredient_info",
                "note", "manual test record"
        ));
        repository.save(testRec);
        return repository.findAll();
    }

    @GetMapping("/test/summarize/{id}")
    public String testSummarize(@PathVariable Long id) {
        MarketEvent event = marketEventRepository.findById(id).orElseThrow();
        return tariffSummarizerService.summarize(event.getRawText());
    }

    @GetMapping("/test/summarize-and-store/{id}")
    public String testSummarizeAndStore(@PathVariable Long id) {
        try{
            tariffSummarizerService.summarizeAndStore(id);
            return "Done summarizing and storing for event ID: " + id;
        } catch (Exception e) {
            return "Failed: " + e.getMessage();
        }
    }

    @GetMapping("/test/agent-tools/{ingredientName}")
    public String testAgentTools(@PathVariable String ingredientName) {
        List<Ingredient> ingredients = agentToolService.getIngredientInfo(ingredientName);
        if (ingredients.isEmpty()) {
            return "No ingredients found for: " + ingredientName;
        }
        
        Ingredient ingredient = ingredients.get(0);
        List<MarketEvent> events = agentToolService.getMarketEvents(ingredient.getOriginCountry());

        StringBuilder result = new StringBuilder();
        result.append("Ingredient: ").append(ingredient.getName())
          .append(" | Origin: ").append(ingredient.getOriginCountry())
          .append(" | Specs: ").append(ingredient.getSpecs())
          .append("\n\nRelated market events (").append(events.size()).append("):\n");

        for (MarketEvent e : events) {
            result.append("- ").append(e.getTitle())
              .append(" | Summary: ").append(e.getLlmSummary())
              .append("\n");
        }

        return result.toString();
    }
}
