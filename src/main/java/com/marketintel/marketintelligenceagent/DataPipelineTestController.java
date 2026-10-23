package com.marketintel.marketintelligenceagent;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Local-only entry point for triggering the ingestion pipeline by hand.
 * In production the same job runs on a schedule (IngestionJob.scheduledRun).
 */
@Profile("local")
@RestController
public class DataPipelineTestController {

    private final IngestionJob ingestionJob;

    public DataPipelineTestController(IngestionJob ingestionJob) {
        this.ingestionJob = ingestionJob;
    }

    @GetMapping("/test/run-ingestion")
    public String runIngestion() {
        return ingestionJob.runOnce();
    }
}
