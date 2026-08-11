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

    @Value("${openai.api.key}")
    private String openaiApiKey;

    public RecommendationTestController(RecommendationRepository repository, TariffSummarizerService tariffSummarizerService, MarketEventRepository marketEventRepository) {
        this.repository = repository;
        this.tariffSummarizerService = tariffSummarizerService;
        this.marketEventRepository = marketEventRepository;
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
}
