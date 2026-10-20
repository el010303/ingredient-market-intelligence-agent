package com.marketintel.marketintelligenceagent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
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

        String prompt = "Extract the following information from this U.S. tariff "
        + "policy announcement. Note: these announcements are issued by "
        + "the United States government, but the 'country' field should "
        + "be the FOREIGN country whose imports are affected by this "
        + "policy (e.g. the country goods are imported FROM), not the "
        + "United States itself. Return ONLY a JSON object with these "
        + "fields: country, old_rate (number or null), new_rate (number "
        + "or null), effective_date (string or null), summary (one sentence)."
        + "\n\nText: " + rawText;

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

    String input = original.getRawText() != null ? original.getRawText() : original.getTitle();
    String llmResultJson = stripMarkdownCodeBlock(summarize(input));
    JsonNode result = jsonMapper.readTree(llmResultJson);

    // 统一成"每个国家一条"的列表：数组时每个元素一条；单个对象时按国家字符串拆分
    List<JsonNode> items = new java.util.ArrayList<>();
    List<String> countries = new java.util.ArrayList<>();
    if (result.isArray()) {
        for (JsonNode item : result) {
            items.add(item);
            countries.add(normalizeCountry(item.path("country").asText("")));
        }
    } else {
        for (String c : result.path("country").asText("").split("\\s+and\\s+|,\\s*")) {
            if (!c.isBlank()) {
                items.add(result);
                countries.add(normalizeCountry(c));
            }
        }
    }

    if (items.isEmpty()) {
        items.add(result);
        countries.add(original.getCountry());
    }

    for (int i = 0; i < items.size(); i++) {
        JsonNode item = items.get(i);
        String country = countries.get(i);
        if (country == null || country.isBlank()) continue;

        MarketEvent target;
        if (i == 0) {
            target = original;
        } else {
            boolean exists = marketEventRepository.findAll().stream()
                    .anyMatch(e -> country.equals(e.getCountry())
                            && original.getSourceUrl().equals(e.getSourceUrl()));
            if (exists) continue;
            target = new MarketEvent();
            target.setEventType(original.getEventType());
            target.setTitle(original.getTitle());
            target.setRawText(original.getRawText());
            target.setSourceUrl(original.getSourceUrl());
            target.setEventDate(original.getEventDate());
        }

        target.setCountry(country);
        target.setExtractOldRate(item.path("old_rate").isNumber() ? item.path("old_rate").asDouble() : null);
        target.setExtractNewRate(item.path("new_rate").isNumber() ? item.path("new_rate").asDouble() : null);
        target.setEffectiveDate(parseDate(item.path("effective_date").asText(null)));
        target.setLlmSummary(item.path("summary").asText(null));
        marketEventRepository.save(target);
    }
}

    private String normalizeCountry(String c) {
        String s = c.trim().replaceFirst("(?i)^the\\s+", "");
        if (s.equalsIgnoreCase("People's Republic of China") || s.equalsIgnoreCase("PRC")) return "China";
        if (s.equalsIgnoreCase("Republic of Indonesia")) return "Indonesia";
        if (s.equalsIgnoreCase("Republic of Korea") || s.equalsIgnoreCase("South Korea")) return "Korea";
        return s;
    }

    public void summarizePending() {
        List<Long> ids = marketEventRepository.findAll().stream()
                .filter(e -> e.getLlmSummary() == null)
                .map(MarketEvent::getId)
                .toList();
        System.out.println("Summarizing " + ids.size() + " events");
        for (Long id : ids) {
            try {
                summarizeAndStore(id);
            } catch (Exception e) {
                System.out.println("Summarize failed for id=" + id + ": " + e.getMessage());
            }
            }
    }

    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(dateStr);
        } catch (Exception ignored) {
        }
        try {
            return LocalDate.parse(dateStr.trim(),
                    java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy", java.util.Locale.ENGLISH));
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