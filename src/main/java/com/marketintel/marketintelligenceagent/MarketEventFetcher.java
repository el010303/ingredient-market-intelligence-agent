package com.marketintel.marketintelligenceagent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

@Component
public class MarketEventFetcher {

    private final MarketEventRepository repository;
    private final RestTemplate restTemplate = new RestTemplate();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private static final Pattern RATE_PATTERN = Pattern.compile("(\\d+)\\s*percent");

    public MarketEventFetcher(MarketEventRepository repository) {
        this.repository = repository;
    }

    public void fetchAndStore(String country) {
        String query = country.replace(" ", "+") + "+tariff";
        String url = "https://www.federalregister.gov/api/v1/documents.json?conditions[term]="
                + query + "&per_page=5";

        String rawResponse = restTemplate.getForObject(url, String.class);

        try {
            JsonNode root = jsonMapper.readTree(rawResponse);
            JsonNode results = root.get("results");

            if (results == null || !results.isArray()) {
                System.out.println("No results found for " + country);
                return;
            }

            int savedCount = 0;
            for (JsonNode doc : results) {
                String title = getTextOrNull(doc, "title");
                String abstractText = getTextOrNull(doc, "abstract");

                String combinedText = (title == null ? "" : title) + " "
                        + (abstractText == null ? "" : abstractText);
                if (!combinedText.toLowerCase().contains(country.toLowerCase())) {
                    continue;
                }

                MarketEvent event = new MarketEvent();
                event.setEventType("tariff_policy");
                event.setCountry(country);
                event.setTitle(title);
                event.setRawText(abstractText);
                event.setSourceUrl(getTextOrNull(doc, "html_url"));

                String pubDate = getTextOrNull(doc, "publication_date");
                if (pubDate != null) {
                    event.setEventDate(LocalDate.parse(pubDate));
                }

                if (abstractText != null) {
                    Matcher matcher = RATE_PATTERN.matcher(abstractText);
                    if (matcher.find()) {
                        event.setNewRate(Double.parseDouble(matcher.group(1)));
                    }
                }

                repository.save(event);
                savedCount++;
            }
            System.out.println("Saved " + savedCount + " market events for " + country);

        } catch (Exception e) {
            System.out.println("Failed to parse response for " + country + ": " + e.getMessage());
        }
    }

    public int fetchByKeywords(List<String> keywords, int perPage) {
    Set<String> seenUrls = new HashSet<>();
    for (MarketEvent e : repository.findAll()) {
        if (e.getSourceUrl() != null) seenUrls.add(e.getSourceUrl());
    }

    int total = 0;
    for (String keyword : keywords) {
        String url = "https://www.federalregister.gov/api/v1/documents.json?conditions[term]="
                + keyword.replace(" ", "+")
                + "&conditions[publication_date][gte]=2024-01-01"
                + "&per_page=" + perPage;
        try {
            String raw = restTemplate.getForObject(url, String.class);
            JsonNode results = jsonMapper.readTree(raw).get("results");
            if (results == null || !results.isArray()) continue;

            int saved = 0;
            for (JsonNode doc : results) {
                String title = getTextOrNull(doc, "title");
                String abstractText = getTextOrNull(doc, "abstract");
                String htmlUrl = getTextOrNull(doc, "html_url");
                if (htmlUrl == null || seenUrls.contains(htmlUrl)) continue;

                String text = ((title == null ? "" : title) + " "
                        + (abstractText == null ? "" : abstractText)).toLowerCase();
                if (!text.contains(keyword.toLowerCase())) continue;
                if (!(text.contains("antidumping") || text.contains("countervailing")
                    || text.contains("tariff") || text.contains("duty")
                    || text.contains("section 301") || text.contains("less-than-fair-value"))) continue;

                try {
                    MarketEvent event = new MarketEvent();
                    event.setEventType("tariff_policy");
                    event.setCountry("Unknown");   
                    event.setTitle(title);
                    event.setRawText(abstractText);
                    event.setSourceUrl(htmlUrl);
                    String pub = getTextOrNull(doc, "publication_date");
                    if (pub != null) event.setEventDate(LocalDate.parse(pub));
                    repository.save(event);
                    seenUrls.add(htmlUrl);
                    saved++;
                } catch (Exception e) {
                    System.out.println("Skip one doc for '" + keyword + "': " + e.getMessage());
                }
            }
            System.out.println("Keyword '" + keyword + "': saved " + saved);
            total += saved;
        } catch (Exception e) {
            System.out.println("Keyword '" + keyword + "' failed: " + e.getMessage());
        }
    }
    return total;
}
    private String getTextOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asText();
    }
}
