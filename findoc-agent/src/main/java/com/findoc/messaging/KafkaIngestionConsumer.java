package com.findoc.messaging;

import com.findoc.service.document.IngestionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;

@Component
public class KafkaIngestionConsumer {
    private static final int MAX_RETRIES = 3;

    private final IngestionService ingestionService;
    private final KafkaTemplate<String, IngestionJob> kafkaTemplate;
    private final String topic;
    private final String dlqTopic;

    public KafkaIngestionConsumer(IngestionService ingestionService,
                                  KafkaTemplate<String, IngestionJob> kafkaTemplate,
                                  @Value("${findoc.ingestion.topic:findoc.ingestion}") String topic,
                                  @Value("${findoc.ingestion.dlq:findoc.ingestion.dlq}") String dlqTopic) {
        this.ingestionService = ingestionService;
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.dlqTopic = dlqTopic;
    }

    @KafkaListener(topics = "${findoc.ingestion.topic:findoc.ingestion}", groupId = "${findoc.ingestion.group:findoc-ingestion}")
    public void consume(IngestionJob job) throws Exception {
        try {
            ingestionService.ingest(job);
        } catch (Exception exception) {
            boolean exhausted = job.attemptNumber() >= MAX_RETRIES;
            ingestionService.recordFailure(job, exception.getMessage(), exhausted);
            if (exhausted) {
                sendAndConfirm(dlqTopic, job);
            } else {
                sendAndConfirm(topic, new IngestionJob(
                    job.documentId(), job.tenantId(), job.userId(), job.attemptNumber() + 1));
            }
        }
    }

    public void sendToDlq(IngestionJob job) {
        sendAndConfirm(dlqTopic, job);
    }

    private void sendAndConfirm(String destination, IngestionJob job) {
        try {
            kafkaTemplate.send(destination, job.documentId().toString(), job).get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while confirming DLQ send", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Unable to confirm DLQ send", exception);
        }
    }

    public int maxRetries() {
        return MAX_RETRIES;
    }

    String dlqTopic() {
        return dlqTopic;
    }
}