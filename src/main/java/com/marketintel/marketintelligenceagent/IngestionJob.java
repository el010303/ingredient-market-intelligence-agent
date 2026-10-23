package com.marketintel.marketintelligenceagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Scheduled ingestion: acts as both the producer and the reconciler of the pipeline.
 *
 *   1. Fetch new filings          -> rows saved as PENDING (Federal Register + Regulations.gov)
 *   2. Reset stale claims          -> SUMMARIZING for too long => PENDING (consumer crashed)
 *   3. Publish every PENDING row   -> events.summarize
 *   4. Re-publish SUMMARIZED rows  -> events.embed (covers a crash between DB write and publish)
 *   5. Check USITC HTS base rates  -> baseline / change events, straight to events.embed (no LLM)
 *
 * Re-publishing is safe because both consumers are idempotent.
 */
@Component
public class IngestionJob {

    private static final Logger log = LoggerFactory.getLogger(IngestionJob.class);

    static final List<String> KEYWORDS = List.of(
            "sucralose", "citric acid", "xanthan gum", "glycine", "vanillin",
            "monosodium glutamate", "ascorbic acid", "collagen", "gelatin",
            "stevia", "sorbitol", "pea protein");
    static final int PER_PAGE = 20;
    static final Duration STALE_AFTER = Duration.ofMinutes(10);

    private final MarketEventFetcher fetcher;
    private final MarketEventRepository repo;
    private final EventPublisher publisher;
    private final HtsRateFetcher htsRateFetcher;
    private final RegulationsGovFetcher regulationsGovFetcher;
    private final JdbcTemplate jdbc;
    private final boolean scheduleEnabled;

    public IngestionJob(MarketEventFetcher fetcher,
                        MarketEventRepository repo,
                        EventPublisher publisher,
                        HtsRateFetcher htsRateFetcher,
                        RegulationsGovFetcher regulationsGovFetcher,
                        JdbcTemplate jdbc,
                        @Value("${app.ingestion.enabled:false}") boolean scheduleEnabled) {
        this.fetcher = fetcher;
        this.repo = repo;
        this.publisher = publisher;
        this.htsRateFetcher = htsRateFetcher;
        this.regulationsGovFetcher = regulationsGovFetcher;
        this.jdbc = jdbc;
        this.scheduleEnabled = scheduleEnabled;
    }

    // Federal Register publishes on business days; run once each weekday morning (Eastern).
    @Scheduled(cron = "${app.ingestion.cron:0 0 9 * * MON-FRI}", zone = "America/New_York")
    public void scheduledRun() {
        if (!scheduleEnabled) {
            log.debug("Scheduled ingestion disabled (app.ingestion.enabled=false)");
            return;
        }
        runOnce("schedule");
    }

    public String runOnce() {
        return runOnce("manual");
    }

    /** Runs the job and records it in ingestion_runs (start, finish, result or error). */
    public String runOnce(String trigger) {
        Long runId = jdbc.queryForObject(
                "INSERT INTO ingestion_runs (trigger) VALUES (?) RETURNING id", Long.class, trigger);
        try {
            String result = doRun();
            jdbc.update("UPDATE ingestion_runs SET finished_at = now(), result = ? WHERE id = ?", result, runId);
            return result;
        } catch (RuntimeException e) {
            jdbc.update("UPDATE ingestion_runs SET finished_at = now(), error = ? WHERE id = ?",
                    e.getClass().getSimpleName() + ": " + e.getMessage(), runId);
            throw e;
        }
    }

    private String doRun() {
        int fetched = fetcher.fetchByKeywords(KEYWORDS, PER_PAGE);
        int fetchedRegs = regulationsGovFetcher.fetch();

        int reset = repo.resetStale(EventStatus.SUMMARIZING.name(), EventStatus.PENDING.name(),
                (int) STALE_AFTER.toMinutes());

        List<MarketEvent> pending = repo.findByStatus(EventStatus.PENDING);
        pending.forEach(e -> publisher.publishSummarize(e.getId()));

        List<MarketEvent> summarized = repo.findByStatus(EventStatus.SUMMARIZED);
        summarized.forEach(e -> publisher.publishEmbed(e.getId()));

        // Last, so its freshly published rows aren't re-published again in step 4
        int htsEvents = htsRateFetcher.fetchAll();

        String result = String.format(
                "fetchedFR=%d, fetchedRegs=%d, staleReset=%d, publishedSummarize=%d, republishedEmbed=%d, htsEvents=%d",
                fetched, fetchedRegs, reset, pending.size(), summarized.size(), htsEvents);
        log.info("Ingestion run: {}", result);
        return result;
    }
}
