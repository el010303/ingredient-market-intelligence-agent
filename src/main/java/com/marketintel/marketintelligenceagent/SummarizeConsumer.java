package com.marketintel.marketintelligenceagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

@Component
public class SummarizeConsumer {

    private static final Logger log = LoggerFactory.getLogger(SummarizeConsumer.class);

    private final MarketEventRepository repo;
    private final TariffSummarizerService summarizer;
    private final EventPublisher publisher;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public SummarizeConsumer(MarketEventRepository repo,
                             TariffSummarizerService summarizer,
                             EventPublisher publisher) {
        this.repo = repo;
        this.summarizer = summarizer;
        this.publisher = publisher;
    }

    @RabbitListener(queues = RabbitConfig.SUMMARIZE_QUEUE)
    public void onMessage(String body) {
        Long id = jsonMapper.readTree(body).path("eventId").asLong();
        MarketEvent event = repo.findById(id).orElse(null);

        if (event == null) {
            log.warn("Event {} not found, dropping message", id);
            return;                                    // 正常返回 = ack
        }

        if (event.getStatus() == EventStatus.SUMMARIZED) {
            log.info("Event {} already summarized, re-publishing embed", id);
            publisher.publishEmbed(id);
            return;
        }
        if (event.getStatus() == EventStatus.EMBEDDED) {
            log.info("Event {} already embedded, skipping", id);
            return;
        }

        if (repo.transition(id, EventStatus.PENDING, EventStatus.SUMMARIZING) == 0) {
            log.info("Event {} claimed by another consumer, skipping", id);
            return;
        }

        try {
            List<Long> savedIds = summarizer.summarizeAndStore(id);
            repo.transition(id, EventStatus.SUMMARIZING, EventStatus.SUMMARIZED);
            savedIds.forEach(publisher::publishEmbed);
            log.info("Event {} summarized → {} row(s) queued for embedding", id, savedIds.size());
        } catch (RuntimeException ex) {
            repo.transition(id, EventStatus.SUMMARIZING, EventStatus.PENDING);
            log.error("Event {} failed: {}", id, ex.getMessage());
            throw ex;
        }
    }
}