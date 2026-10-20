package com.marketintel.marketintelligenceagent;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
public class DataPipelineTestController {

    private final MarketEventFetcher marketEventFetcher;
    private final TariffSummarizerService tariffSummarizerService;

    public DataPipelineTestController(MarketEventFetcher marketEventFetcher,
                                       TariffSummarizerService tariffSummarizerService) {
        this.marketEventFetcher = marketEventFetcher;
        this.tariffSummarizerService = tariffSummarizerService;
    }

    @GetMapping("/test/fetch-keywords")
    public String fetchKeywords() {
        List<String> keywords = List.of("sucralose", "citric acid", "xanthan gum",
            "glycine", "vanillin", "monosodium glutamate", "ascorbic acid",
            "collagen", "gelatin", "stevia", "sorbitol", "pea protein");
        int saved = marketEventFetcher.fetchByKeywords(keywords, 20);
        return "Saved " + saved + " new events";
    }

    @GetMapping("/test/summarize-pending")
    public String summarizePending() {
        tariffSummarizerService.summarizePending();
        return "Done";
    }
}