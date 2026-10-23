package com.marketintel.marketintelligenceagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Data source 3: Regulations.gov API (USTR documents, e.g. Section 301 tariff actions).
 *
 * Complements the Federal Register keyword search, which is scoped to specific ingredients:
 * Section 301 actions apply to whole countries / product lists and would otherwise be missed.
 *
 * Rows are saved as PENDING and go through the full LLM summarization + embedding pipeline.
 * Cross-source dedup: a document that is also a Federal Register filing we already hold
 * (matched on the FR document number) is skipped.
 */
@Component
public class RegulationsGovFetcher {

    private static final Logger log = LoggerFactory.getLogger(RegulationsGovFetcher.class);
    static final String API = "https://api.regulations.gov/v4/documents";
    static final String DOC_PAGE = "https://www.regulations.gov/document/";
    static final String FR_DOC_API = "https://www.federalregister.gov/api/v1/documents/";

    static final String AGENCY = "USTR";
    static final List<String> SEARCH_TERMS = List.of("section 301", "tariff", "exclusion");
    static final Set<String> DOCUMENT_TYPES = Set.of("Notice", "Rule", "Proposed Rule");
    static final List<String> RELEVANT_TITLE_TERMS =
            List.of("301", "tariff", "duty", "duties", "exclusion", "modification of action", "continuation of action");
    static final int PAGE_SIZE = 25;

    private final String apiKey;
    private final JdbcTemplate jdbc;
    private final MarketEventRepository events;
    private final RestTemplate restTemplate = new RestTemplate();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public RegulationsGovFetcher(@Value("${regulations.key:}") String apiKey,
                                 JdbcTemplate jdbc, MarketEventRepository events) {
        this.apiKey = apiKey;
        this.jdbc = jdbc;
        this.events = events;
    }

    /** @return number of new documents saved as PENDING */
    public int fetch() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("regulations.key not set; skipping Regulations.gov");
            return 0;
        }
        String since = LocalDate.now().minusYears(1).toString();
        Set<String> seenIds = new HashSet<>();
        int saved = 0;

        for (String term : SEARCH_TERMS) {
            JsonNode data;
            try {
                URI uri = UriComponentsBuilder.fromUriString(API)
                        .queryParam("filter[agencyId]", AGENCY)
                        .queryParam("filter[searchTerm]", term)
                        .queryParam("filter[postedDate][ge]", since)
                        .queryParam("sort", "-postedDate")
                        .queryParam("page[size]", PAGE_SIZE)
                        .queryParam("api_key", apiKey)
                        .encode().build().toUri();
                data = jsonMapper.readTree(restTemplate.getForObject(uri, String.class)).path("data");
            } catch (RuntimeException e) {
                // Never log the URI: it contains the API key
                log.warn("Regulations.gov search '{}' failed: {}", term, e.getClass().getSimpleName());
                continue;
            }

            for (JsonNode doc : data) {
                String id = doc.path("id").asText();
                if (!seenIds.add(id)) continue;                       // returned by an earlier term
                JsonNode a = doc.path("attributes");
                String title = a.path("title").asText("");
                String type = a.path("documentType").asText("");
                if (!DOCUMENT_TYPES.contains(type) || !isRelevant(title)) continue;

                String sourceUrl = DOC_PAGE + id;
                String frDocNum = a.path("frDocNum").isNull() ? null : a.path("frDocNum").asText(null);
                if (alreadyStored(sourceUrl, frDocNum)) continue;

                try {
                    MarketEvent event = new MarketEvent();
                    event.setEventType("trade_policy_ustr");
                    event.setCountry("Unknown");                       // LLM extraction fills it in
                    event.setTitle(title);
                    event.setRawText(enrich(title, frDocNum));
                    event.setSourceUrl(sourceUrl);
                    String posted = a.path("postedDate").asText("");
                    if (posted.length() >= 10) event.setEventDate(LocalDate.parse(posted.substring(0, 10)));
                    events.save(event);                                // status defaults to PENDING
                    saved++;
                } catch (RuntimeException e) {
                    log.warn("Regulations.gov document {} skipped: {}", id, e.getMessage());
                }
            }
        }
        log.info("Regulations.gov: {} new document(s)", saved);
        return saved;
    }

    private static boolean isRelevant(String title) {
        String t = title.toLowerCase(Locale.ROOT);
        return RELEVANT_TITLE_TERMS.stream().anyMatch(t::contains);
    }

    /** Same document already held, either from this source or as a Federal Register filing. */
    private boolean alreadyStored(String sourceUrl, String frDocNum) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM market_events
                WHERE source_url = ?
                   OR (CAST(? AS text) IS NOT NULL AND source_url LIKE '%/' || CAST(? AS text) || '/%')
                """, Integer.class, sourceUrl, frDocNum, frDocNum);
        return n != null && n > 0;
    }

    /** Regulations.gov lists titles only; pull the abstract from the Federal Register when available. */
    private String enrich(String title, String frDocNum) {
        if (frDocNum == null) return title;
        try {
            JsonNode fr = jsonMapper.readTree(restTemplate.getForObject(FR_DOC_API + frDocNum + ".json", String.class));
            String abstractText = fr.path("abstract").asText(null);
            return abstractText == null || abstractText.isBlank() ? title : title + "\n\n" + abstractText;
        } catch (RuntimeException e) {
            return title;
        }
    }
}
