package com.findoc.messaging;

import com.findoc.service.document.IngestionService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class KafkaIngestionConsumer {
    private final IngestionService ingestionService;

    public KafkaIngestionConsumer(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @KafkaListener(topics = "${findoc.ingestion.topic:findoc.ingestion}", groupId = "${findoc.ingestion.group:findoc-ingestion}")
    public void consume(IngestionJob job) throws Exception {
        ingestionService.ingest(job);
    }

}