package com.marketintel.marketintelligenceagent;

import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class AgentToolService {

    private final IngredientRepository ingredientRepository;
    private final MarketEventRepository marketEventRepository;

    public AgentToolService(IngredientRepository ingredientRepository,
                             MarketEventRepository marketEventRepository) {
        this.ingredientRepository = ingredientRepository;
        this.marketEventRepository = marketEventRepository;
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
}