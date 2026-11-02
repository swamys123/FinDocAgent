package com.findoc.messaging;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class IngestionMetrics {
    private static final Logger LOGGER = LoggerFactory.getLogger(IngestionMetrics.class);
    private static final long QUERY_TIMEOUT_SECONDS = 10;

    private final JdbcTemplate jdbcTemplate;
    private final AdminClient adminClient;
    private final String ingestionTopic;
    private final String ingestionGroup;
    private final String deadLetterTopic;
    private final AtomicLong pendingOutboxCount = new AtomicLong(-1);
    private final AtomicLong oldestPendingOutboxAgeMillis = new AtomicLong(-1);
    private final AtomicLong ingestionConsumerLag = new AtomicLong(-1);
    private final AtomicLong deadLetterDepth = new AtomicLong(-1);

    public IngestionMetrics(
            JdbcTemplate jdbcTemplate,
            KafkaAdmin kafkaAdmin,
            MeterRegistry meterRegistry,
            @Value("${findoc.ingestion.topic:findoc.ingestion}") String ingestionTopic,
            @Value("${findoc.ingestion.group:findoc-ingestion}") String ingestionGroup,
            @Value("${findoc.ingestion.dlq:findoc.ingestion.dlq}") String deadLetterTopic) {
        this.jdbcTemplate = jdbcTemplate;
        this.adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties());
        this.ingestionTopic = ingestionTopic;
        this.ingestionGroup = ingestionGroup;
        this.deadLetterTopic = deadLetterTopic;
        Gauge.builder("findoc.ingestion.outbox.pending", pendingOutboxCount, AtomicLong::get)
            .description("Number of unsent ingestion outbox events; -1 means the first refresh has not succeeded")
            .register(meterRegistry);
        Gauge.builder("findoc.ingestion.outbox.oldest.pending.age", oldestPendingOutboxAgeMillis, AtomicLong::get)
            .description("Age in milliseconds of the oldest pending outbox event; -1 means the first refresh has not succeeded")
            .baseUnit("milliseconds")
            .register(meterRegistry);
        Gauge.builder("findoc.ingestion.kafka.consumer.lag", ingestionConsumerLag, AtomicLong::get)
            .description("Unconsumed records in the configured ingestion consumer group; -1 means the first refresh has not succeeded")
            .tag("topic", ingestionTopic)
            .tag("group", ingestionGroup)
            .register(meterRegistry);
        Gauge.builder("findoc.ingestion.dlq.depth", deadLetterDepth, AtomicLong::get)
            .description("Retained records in the ingestion dead-letter topic; -1 means the first refresh has not succeeded")
            .tag("topic", deadLetterTopic)
            .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${findoc.observability.metrics.refresh-interval-ms:30000}",
        scheduler = "metricsTaskScheduler")
    public void refresh() {
        refreshOutboxMetrics();
        refreshKafkaMetrics();
    }

    private void refreshOutboxMetrics() {
        try {
            jdbcTemplate.query(
                "select count(*) as pending_count, "
                    + "coalesce(extract(epoch from (current_timestamp - min(created_at))) * 1000, 0) as oldest_age_ms "
                    + "from outbox where sent_at is null",
                resultSet -> {
                    if (resultSet.next()) {
                        pendingOutboxCount.set(resultSet.getLong("pending_count"));
                        oldestPendingOutboxAgeMillis.set(resultSet.getLong("oldest_age_ms"));
                    }
                    return null;
                });
        } catch (RuntimeException exception) {
            LOGGER.warn("Unable to refresh ingestion outbox metrics; retaining the last successful values", exception);
        }
    }

    private void refreshKafkaMetrics() {
        try {
            var topicDescriptions = adminClient.describeTopics(List.of(ingestionTopic, deadLetterTopic))
                .allTopicNames().get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<TopicPartition> ingestionPartitions = partitions(topicDescriptions.get(ingestionTopic));
            List<TopicPartition> deadLetterPartitions = partitions(topicDescriptions.get(deadLetterTopic));
            Map<TopicPartition, OffsetSpec> latestRequests = offsetRequests(ingestionPartitions, OffsetSpec.latest());
            deadLetterPartitions.forEach(partition -> latestRequests.put(partition, OffsetSpec.latest()));
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> latestOffsets =
                adminClient.listOffsets(latestRequests).all().get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            Map<TopicPartition, OffsetSpec> beginningRequests = offsetRequests(deadLetterPartitions, OffsetSpec.earliest());
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> beginningOffsets =
                adminClient.listOffsets(beginningRequests).all().get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            long dlqRecords = deadLetterPartitions.stream()
                .mapToLong(partition -> latestOffsets.get(partition).offset() - beginningOffsets.get(partition).offset())
                .sum();

            Map<TopicPartition, OffsetAndMetadata> committedOffsets =
                adminClient.listConsumerGroupOffsets(ingestionGroup).partitionsToOffsetAndMetadata()
                    .get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            long consumerLag = ingestionPartitions.stream()
                .mapToLong(partition -> Math.max(0,
                    latestOffsets.get(partition).offset()
                        - (committedOffsets.containsKey(partition) ? committedOffsets.get(partition).offset() : 0)))
                .sum();
            deadLetterDepth.set(dlqRecords);
            ingestionConsumerLag.set(consumerLag);
        } catch (Exception exception) {
            LOGGER.warn("Unable to refresh ingestion Kafka metrics; retaining the last successful values", exception);
        }
    }

    private static List<TopicPartition> partitions(
            org.apache.kafka.clients.admin.TopicDescription description) {
        if (description == null) {
            throw new IllegalStateException("Configured ingestion metric topic was not found");
        }
        return description.partitions().stream()
            .map(partition -> new TopicPartition(description.name(), partition.partition()))
            .toList();
    }

    private static Map<TopicPartition, OffsetSpec> offsetRequests(
            List<TopicPartition> partitions, OffsetSpec offsetSpec) {
        Map<TopicPartition, OffsetSpec> requests = new HashMap<>();
        partitions.forEach(partition -> requests.put(partition, offsetSpec));
        return requests;
    }

    @PreDestroy
    public void close() {
        adminClient.close(Duration.ofSeconds(2));
    }
}
