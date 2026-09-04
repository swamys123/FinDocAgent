package com.findoc.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;

@Component
public class KafkaIngestionProducer implements IngestionProducer {
    private final KafkaTemplate<String, IngestionJob> kafkaTemplate;
    private final String topic;

    public KafkaIngestionProducer(KafkaTemplate<String, IngestionJob> kafkaTemplate,
                                  @Value("${findoc.ingestion.topic:findoc.ingestion}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Override
    public void publish(IngestionJob job) {
        try {
            kafkaTemplate.send(topic, job.documentId().toString(), job).get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while confirming ingestion publication", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Unable to confirm ingestion publication", exception);
        }
    }
}