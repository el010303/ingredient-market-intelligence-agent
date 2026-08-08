package com.marketintel.marketintelligenceagent;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.List;

@Component
public class DataSeeder implements CommandLineRunner {

    private final IngredientRepository repository;
    private final MarketEventFetcher marketEventFetcher;

    public DataSeeder(IngredientRepository repository, MarketEventFetcher marketEventFetcher) {
        this.repository = repository;
        this.marketEventFetcher = marketEventFetcher;
    }

    @Override
    public void run(String... args) {

        if (repository.count() > 0) {
            System.out.println("=== Ingredients already seeded, skipping ===");
            printAll();
        } else {
            // ---- Sweetener (China sources) ----
            seed("Maltitol Syrup", "sweetener", "China",
                    Map.of("form", "syrup", "purity", 90));
            seed("Maltitol Syrup", "sweetener", "China",
                    Map.of("form", "syrup", "purity", 95));
            seed("Erythritol", "sweetener", "China",
                    Map.of("form", "powder", "purity", 90));
            seed("Erythritol", "sweetener", "China",
                    Map.of("form", "powder", "purity", 99));
            seed("Monk Fruit Extract", "sweetener", "China",
                    Map.of("form", "powder", "purity", 50));
            seed("Stevia", "sweetener", "China",
                    Map.of("form", "powder", "purity", 90));
            seed("Sucralose", "sweetener", "China",
                    Map.of("form", "powder", "purity", 99));
            seed("Allulose", "sweetener", "China",
                    Map.of("form", "syrup", "purity", 70));

            // ---- Plant-based Protein ----
            seed("Pea Protein", "plant_protein", "China",
                    Map.of("type", "pea", "purity", 80));
            seed("Pea Protein", "plant_protein", "China",
                    Map.of("type", "pea", "purity", 85));
            seed("Pea Protein", "plant_protein", "Canada",
                    Map.of("type", "pea", "purity", 90));
            seed("Rice Protein", "plant_protein", "China",
                    Map.of("type", "rice", "purity", 80));
            seed("Rice Protein", "plant_protein", "Sri Lanka",
                    Map.of("type", "rice", "purity", 85));
            seed("Soy Protein", "plant_protein", "China",
                    Map.of("type", "soy", "purity", 90));
            seed("Chickpea Protein", "plant_protein", "China",
                    Map.of("type", "chickpea", "purity", 80));
            seed("Faba Bean Protein", "plant_protein", "Canada",
                    Map.of("type", "faba_bean", "purity", 85));

            // ---- Collagen ----
            seed("Bovine Collagen", "collagen", "Brazil",
                    Map.of("source", "bovine", "purity", "grade_a",
                            "solubility", "high", "color", "light", "taste", "mild"));
            seed("Bovine Collagen", "collagen", "China",
                    Map.of("source", "bovine", "purity", "grade_a",
                            "solubility", "medium", "color", "dark", "taste", "mild"));
            seed("Chicken Collagen", "collagen", "China",
                    Map.of("source", "chicken", "purity", "grade_a",
                            "solubility", "high", "color", "light", "taste", "strong"));
            seed("Fish Collagen", "collagen", "China",
                    Map.of("source", "fish", "purity", "grade_a",
                            "solubility", "high", "color", "light", "taste", "mild"));

            // ---- Inulin / Jerusalem Artichoke ----
            seed("Inulin", "inulin_jerusalem_artichoke", "China",
                    Map.of("form", "powder"));
            seed("Jerusalem Artichoke Extract", "inulin_jerusalem_artichoke", "Finland",
                    Map.of("form", "powder"));

            System.out.println("=== Seeded " + repository.count() + " ingredients ===");
            printAll();
        }

        // 触发一次真实的 Federal Register 抓取
        marketEventFetcher.fetchAndStore("China");
        marketEventFetcher.fetchAndStore("Brazil");
    }

    private void seed(String name, String category, String originCountry, Map<String, Object> specs) {
        Ingredient ingredient = new Ingredient();
        ingredient.setName(name);
        ingredient.setCategory(category);
        ingredient.setOriginCountry(originCountry);
        ingredient.setSpecs(specs);
        repository.save(ingredient);
    }

    private void printAll() {
        List<Ingredient> all = repository.findAll();
        for (Ingredient i : all) {
            System.out.println(i.getCategory() + " | " + i.getName() + " | "
                    + i.getOriginCountry() + " | " + i.getSpecs());
        }
    }
}