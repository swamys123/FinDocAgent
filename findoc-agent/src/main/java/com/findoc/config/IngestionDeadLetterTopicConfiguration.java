package com.findoc.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class IngestionDeadLetterTopicConfiguration {
    @Bean
    NewTopic ingestionDeadLetterTopic(
            @Value("${findoc.ingestion.dlq:findoc.ingestion.dlq}") String topic,
            @Value("${findoc.ingestion.dlq-partitions:3}") int partitions,
            @Value("${findoc.ingestion.dlq-replication-factor:1}") int replicationFactor,
            @Value("${findoc.ingestion.dlq-retention-ms:604800000}") long retentionMs) {
        if (partitions < 1 || replicationFactor < 1 || retentionMs < 1) {
            throw new IllegalArgumentException("DLQ partitions, replication factor, and retention must be positive");
        }
        return TopicBuilder.name(topic)
            .partitions(partitions)
            .replicas(replicationFactor)
            .config("retention.ms", Long.toString(retentionMs))
            .build();
    }
}
