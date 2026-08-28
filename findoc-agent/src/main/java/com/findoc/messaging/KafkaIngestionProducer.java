package com.findoc.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

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
        kafkaTemplate.send(topic, job.documentId().toString(), job);
    }
}