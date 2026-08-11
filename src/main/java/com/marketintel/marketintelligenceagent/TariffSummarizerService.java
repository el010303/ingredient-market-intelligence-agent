package com.marketintel.marketintelligenceagent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

@Service
public class TariffSummarizerService {
    
    @Value("${openai.api.key}")
    private String openaiApiKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final MarketEventRepository marketEventRepository;

    public TariffSummarizerService(MarketEventRepository marketEventRepository) {
        this.marketEventRepository = marketEventRepository;
    }

    public String summarize(String rawText) {
        String url = "https://api.openai.com/v1/chat/completions";

        String prompt = "Extract the following information from this tariff "
                + "policy announcement, and return ONLY a JSON object with "
                + "these fields: country, old_rate (number or null), "
                + "new_rate (number or null), effective_date (string or null), "
                + "summary (one sentence).\n\nText: " + rawText;

        String requestBody = """
                {
                "model": "gpt-4o-mini",
                "messages": [{"role": "user", "content": %s}],
                "temperature": 0
                }
                """.formatted(jsonMapper.valueToTree(prompt).toString());
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(openaiApiKey);

        HttpEntity<String> request = new HttpEntity<>(requestBody, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
        
        
        JsonNode root = jsonMapper.readTree(response.getBody());
        return root.path("choices").get(0).path("message").path("content").asText();
    }

    // Summary + analyst + separate country
    public void summarizeAndStore(Long eventId) {
        MarketEvent original = marketEventRepository.findById(eventId).orElseThrow();

        String llmResultJson = summarize(original.getRawText());
        llmResultJson = stripMarkdownCodeBlock(llmResultJson);
        JsonNode result = jsonMapper.readTree(llmResultJson);

        String countryRaw = result.path("country").asText("");
        Double oldRate = result.path("old_rate").isNull() ? null : result.path("old_rate").asDouble();
        Double newRate = result.path("new_rate").isNull() ? null : result.path("new_rate").asDouble();
        String summary = result.path("summary").asText(null);
        LocalDate effectiveDate = parseDate(result.path("effective_date").asText(null));

        List<String> countries = Arrays.stream(countryRaw.split("\\s+and\\s+|,\\s*"))
                .map(String::trim)
                .filter(c -> !c.isBlank())
                .toList();

        if (countries.isEmpty()) {
            countries = List.of(original.getCountry());
        }

        for (int i = 0; i < countries.size(); i++) {
            String country = countries.get(i);
            MarketEvent target;

            if (i == 0) {
                target = original;
                target.setCountry(country);
            } else {
                boolean exists = marketEventRepository.findAll().stream()
                        .anyMatch(e -> country.equals(e.getCountry())
                                && original.getSourceUrl().equals(e.getSourceUrl()));
                if (exists) {
                    continue;
                }

                target = new MarketEvent();
                target.setEventType(original.getEventType());
                target.setCountry(country);
                target.setTitle(original.getTitle());
                target.setRawText(original.getRawText());
                target.setSourceUrl(original.getSourceUrl());
                target.setEventDate(original.getEventDate());
            }

            target.setExtractOldRate(oldRate);
            target.setExtractNewRate(newRate);
            target.setEffectiveDate(effectiveDate);
            target.setLlmSummary(summary);

            marketEventRepository.save(target);
        }
    }

    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(dateStr);
        } catch (Exception e) {
            System.out.println("Failed to parse effective_date: " + dateStr);
            return null;
        }
    }

    private String stripMarkdownCodeBlock(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {

            trimmed = trimmed.replaceFirst("^```[a-zA-Z]*\\s*", "");

            trimmed = trimmed.replaceFirst("```\\s*$", "");
        }
        return trimmed;
    }
}