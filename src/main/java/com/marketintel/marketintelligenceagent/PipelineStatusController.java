package com.marketintel.marketintelligenceagent;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only operational view of the ingestion pipeline (behind the API-key filter). */
@RestController
public class PipelineStatusController {

    private final JdbcTemplate jdbc;

    public PipelineStatusController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/pipeline/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();

        // Rows per source and pipeline stage. Sources are identified by event_type.
        out.put("eventsBySource", jdbc.queryForList("""
                SELECT CASE
                         WHEN event_type = 'trade_policy_ustr' THEN 'Regulations.gov (USTR)'
                         WHEN event_type LIKE 'hts_rate_%'     THEN 'USITC HTS'
                         ELSE 'Federal Register'
                       END AS source,
                       count(*)                                  AS rows,
                       count(DISTINCT source_url)                AS documents,
                       count(*) FILTER (WHERE status = 'EMBEDDED') AS embedded,
                       count(*) FILTER (WHERE status <> 'EMBEDDED') AS in_progress,
                       max(event_date)::text                     AS latest_event_date
                FROM market_events
                GROUP BY 1
                ORDER BY 1
                """));

        out.put("statusCounts", jdbc.queryForList(
                "SELECT status, count(*) AS rows FROM market_events GROUP BY status ORDER BY status"));

        out.put("tariffRates", jdbc.queryForList("""
                SELECT string_agg(DISTINCT i.name, ', ') AS ingredients, t.hts_code, t.general_rate,
                       t.fetched_at, t.changed_at
                FROM tariff_rates t JOIN ingredients i ON i.hts_code = t.hts_code
                GROUP BY t.hts_code, t.general_rate, t.fetched_at, t.changed_at
                ORDER BY ingredients
                """));

        out.put("recentRuns", jdbc.queryForList("""
                SELECT started_at, finished_at, trigger, result, error
                FROM ingestion_runs ORDER BY started_at DESC LIMIT 10
                """));
        return out;
    }
}
