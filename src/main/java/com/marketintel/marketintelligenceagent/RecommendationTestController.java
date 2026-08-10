package com.marketintel.marketintelligenceagent;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import java.util.List;
import java.util.Map;

@RestController
public class RecommendationTestController {
    
    private final RecommendationRepository repository;

    public RecommendationTestController(RecommendationRepository repository) {
        this.repository = repository;
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
}
