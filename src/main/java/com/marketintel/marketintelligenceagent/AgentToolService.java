package com.marketintel.marketintelligenceagent;

import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class AgentToolService {
    
    private final IngredientRepository ingredientRepository;
    private final MarketEventRepository marketEventRepository;

    public AgentToolService(IngredientRepository ingredientRepository, MarketEventRepository marketEventRepository) {
        this.ingredientRepository = ingredientRepository;
        this.marketEventRepository = marketEventRepository;
    }

    // get_ingredient_info
    public List<Ingredient> getIngredientInfo(String name) {
        return ingredientRepository.findAll().stream()
                .filter(i -> i.getName().equalsIgnoreCase(name))
                .toList();
}

    // get_market_event_info
    public List<MarketEvent> getMarketEvents(String country) {
        return marketEventRepository.findAll().stream()
                .filter(e -> e.getCountry().equalsIgnoreCase(country))
                .toList();
    }
}
