package com.findoc.messaging;

import com.findoc.service.document.IngestionPipeline;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class IngestionConsumer {
    private final IngestionPipeline pipeline;
    private final KafkaTemplate<String, IngestionMessage> kafkaTemplate;
    private final String topic;
    private final String dlqTopic;
    private final int maxRetry;

    public IngestionConsumer(IngestionPipeline pipeline, KafkaTemplate<String, IngestionMessage> kafkaTemplate,
                             @Value("${ingestion.kafka.topic:findoc.ingestion}") String topic,
                             @Value("${ingestion.kafka.dlq-topic:findoc.ingestion.dlq}") String dlqTopic,
                             @Value("${ingestion.max-retry:3}") int maxRetry) {
        this.pipeline = pipeline;
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.dlqTopic = dlqTopic;
        this.maxRetry = maxRetry;
    }

    @KafkaListener(topics = "${ingestion.kafka.topic:findoc.ingestion}")
    public void consume(IngestionMessage message, Acknowledgment acknowledgment) throws Exception {
        try {
            pipeline.process(message);
            acknowledgment.acknowledge();
        } catch (Exception exception) {
            if (message.attemptNumber() >= maxRetry) {
                kafkaTemplate.send(dlqTopic, message.documentId().toString(), message);
                acknowledgment.acknowledge();
                return;
            }
            kafkaTemplate.send(topic, message.documentId().toString(), new IngestionMessage(
                message.documentId(), message.tenantId(), message.userId(), message.filePath(),
                message.fileType(), message.attemptNumber() + 1));
            acknowledgment.acknowledge();
        }
    }
}