package com.marketintel.marketintelligenceagent;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operational endpoints. Under /api/, so every call must pass the X-API-Key filter.
 * Lets an operator run the ingestion job on demand instead of waiting for the next schedule.
 * Safe to call repeatedly: fetching skips known filings, HTS events are only created on change,
 * and both consumers are idempotent.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final IngestionJob ingestionJob;

    public AdminController(IngestionJob ingestionJob) {
        this.ingestionJob = ingestionJob;
    }

    @PostMapping("/run-ingestion")
    public String runIngestion() {
        return ingestionJob.runOnce();
    }
}
