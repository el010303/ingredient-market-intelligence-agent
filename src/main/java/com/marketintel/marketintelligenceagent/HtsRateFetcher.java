package com.marketintel.marketintelligenceagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Data source 2: USITC Harmonized Tariff Schedule REST API.
 *
 * The API already returns structured fields, so no LLM is involved: rows are written as
 * SUMMARIZED with a summary built in code and only go through the embedding stage.
 *
 * Change detection: the latest rates per HTS line are kept in tariff_rates. A market event is
 * created the first time a line is observed (baseline) and whenever any rate changes after that.
 */
@Component
public class HtsRateFetcher {

    private static final Logger log = LoggerFactory.getLogger(HtsRateFetcher.class);
    static final String API = "https://hts.usitc.gov/reststop/search";
    static final String SEARCH_PAGE = "https://hts.usitc.gov/search?query=";

    private final JdbcTemplate jdbc;
    private final TariffRateRepository rates;
    private final MarketEventRepository events;
    private final EventPublisher publisher;
    private final RestTemplate restTemplate = new RestTemplate();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public HtsRateFetcher(JdbcTemplate jdbc, TariffRateRepository rates,
                          MarketEventRepository events, EventPublisher publisher) {
        this.jdbc = jdbc;
        this.rates = rates;
        this.events = events;
        this.publisher = publisher;
    }

    /** @return number of market events created (baselines + changes) */
    public int fetchAll() {
        // hts_code -> ingredient names sharing that line (e.g. several Pea Protein rows)
        List<Map<String, Object>> lines = jdbc.queryForList("""
                SELECT hts_code, string_agg(DISTINCT name, ', ') AS names
                FROM ingredients WHERE hts_code IS NOT NULL
                GROUP BY hts_code ORDER BY hts_code
                """);
        int created = 0;
        for (Map<String, Object> line : lines) {
            String code = (String) line.get("hts_code");
            String names = (String) line.get("names");
            try {
                JsonNode hit = lookup(code);
                if (hit == null) {
                    log.warn("HTS {} ({}) not found in API response", code, names);
                    continue;
                }
                if (record(code, names, hit)) created++;
            } catch (RuntimeException e) {
                // One bad line must not stop the others; the next scheduled run retries it.
                log.warn("HTS {} ({}) failed: {}", code, names, e.getMessage());
            }
        }
        log.info("HTS rates: {} line(s) checked, {} event(s) created", lines.size(), created);
        return created;
    }

    /**
     * Returns the API row for {@code code}. Duty rates are set at the 8-digit tariff line;
     * 10-digit statistical suffixes often carry no rate of their own (e.g. 3913.90.20.15 Xanthan gum
     * under 3913.90.20). In that case the rates are inherited from the 8-digit parent.
     */
    private JsonNode lookup(String code) {
        JsonNode row = find(search(code), code);
        if (row == null || hasRate(row) || code.length() <= 10) return row;

        String parentCode = code.substring(0, 10);            // "3913.90.20.15" -> "3913.90.20"
        JsonNode parent = find(search(code), parentCode);
        if (parent == null) parent = find(search(parentCode), parentCode);
        if (parent == null || !hasRate(parent)) return row;

        tools.jackson.databind.node.ObjectNode merged = (tools.jackson.databind.node.ObjectNode) row.deepCopy();
        merged.put("general", parent.path("general").asText(""));
        merged.put("special", parent.path("special").asText(""));
        merged.put("other", parent.path("other").asText(""));
        return merged;
    }

    private JsonNode search(String keyword) {
        String url = UriComponentsBuilder.fromUriString(API).queryParam("keyword", keyword).toUriString();
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", "market-intelligence-agent/1.0");
        String body = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
        return jsonMapper.readTree(body);
    }

    private static JsonNode find(JsonNode rows, String code) {
        for (JsonNode row : rows) {
            if (code.equals(row.path("htsno").asText(""))) return row;
        }
        return null;
    }

    private static boolean hasRate(JsonNode row) {
        return !row.path("general").asText("").isBlank();
    }

    /** @return true if a market event was created */
    private boolean record(String code, String names, JsonNode hit) {
        String description = clean(hit.path("description").asText(null));
        String general = clean(hit.path("general").asText(null));
        String special = clean(hit.path("special").asText(null));
        String other = clean(hit.path("other").asText(null));
        OffsetDateTime now = OffsetDateTime.now();

        TariffRate previous = rates.findById(code).orElse(null);
        boolean changed = previous == null
                || !Objects.equals(previous.getGeneralRate(), general)
                || !Objects.equals(previous.getSpecialRate(), special)
                || !Objects.equals(previous.getOtherRate(), other);

        TariffRate current = previous != null ? previous : new TariffRate();
        current.setHtsCode(code);
        current.setDescription(description);
        current.setFetchedAt(now);
        if (!changed) {
            rates.save(current);          // only the "last checked" time moves
            return false;
        }
        current.setGeneralRate(general);
        current.setSpecialRate(special);
        current.setOtherRate(other);
        current.setChangedAt(now);
        rates.save(current);

        String summary = previous == null
                ? String.format("U.S. base (general/MFN) duty for %s (HTS %s, \"%s\") is %s; "
                        + "column-2 rate %s; free or reduced under special programs: %s. "
                        + "AD/CVD and other additional duties are separate.",
                        names, code, description, nz(general), nz(other), nz(special))
                : String.format("U.S. base duty for %s (HTS %s) changed: general %s -> %s; "
                        + "column-2 %s -> %s; special programs: %s.",
                        names, code, nz(previous.getGeneralRate()), nz(general),
                        nz(previous.getOtherRate()), nz(other), nz(special));

        MarketEvent event = new MarketEvent();
        event.setEventType(previous == null ? "hts_rate_baseline" : "hts_rate_change");
        event.setCountry("All (MFN)");
        event.setTitle((previous == null ? "HTS base duty: " : "HTS base duty change: ") + names + " (" + code + ")");
        event.setRawText(summary);
        event.setLlmSummary(summary);
        // Unique per (line, day) so a later change is a separate event with its own source link
        event.setSourceUrl(SEARCH_PAGE + code + "#" + LocalDate.now());
        event.setEventDate(LocalDate.now());
        event.setStatus(EventStatus.SUMMARIZED);   // structured source: skip the LLM stage
        MarketEvent saved = events.save(event);
        publisher.publishEmbed(saved.getId());
        log.info("HTS {} ({}): {}", code, names, previous == null ? "baseline recorded" : "rate change recorded");
        return true;
    }

    private static String clean(String s) {
        if (s == null) return null;
        String t = s.replaceAll("<[^>]+>", "").trim();   // descriptions can contain HTML tags
        return t.isEmpty() ? null : t;
    }

    private static String nz(String s) {
        return s == null ? "n/a" : s;
    }
}
