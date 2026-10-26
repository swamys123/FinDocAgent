package com.findoc.correctness;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.config.ConfigResource;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit 3 (e1): the DLQ is provisioned with explicit partition and retention settings.
 */
@TestPropertySource(properties = {
    "findoc.ingestion.dlq-partitions=3",
    "findoc.ingestion.dlq-retention-ms=604800000"
})
class DeadLetterTopicProvisioningIntegrationTest extends CorrectnessTestSupport {
    static final String TOPIC = "correctness-dlq-topic-" + UUID.randomUUID();
    static final String DLQ_TOPIC = TOPIC + ".dlq";
    static final int SOURCE_PARTITIONS = 3;

    static {
        try (AdminClient admin = admin()) {
            admin.createTopics(List.of(new NewTopic(TOPIC, SOURCE_PARTITIONS, (short) 1))).all().get(30, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create the multi-partition source topic", exception);
        }
    }

    @DynamicPropertySource
    static void registerTopics(DynamicPropertyRegistry registry) {
        registry.add("findoc.ingestion.topic", () -> TOPIC);
        registry.add("findoc.ingestion.dlq", () -> DLQ_TOPIC);
        registry.add("findoc.ingestion.group", () -> TOPIC + "-group");
    }

    static AdminClient admin() {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        return AdminClient.create(props);
    }

    /** Audit 3 (e1): partitions and retention are configured explicitly for the DLQ topic. */
    @Test
    void deadLetterTopicIsDeclaredWithExplicitPartitionsAndRetention() throws Exception {
        try (AdminClient admin = admin()) {
            assertThat(admin.listTopics().names().get(30, TimeUnit.SECONDS))
                .as("DLQ topic must be provisioned at startup, not on first failure").contains(DLQ_TOPIC);

            TopicDescription description = admin.describeTopics(List.of(DLQ_TOPIC)).allTopicNames().get(30, TimeUnit.SECONDS).get(DLQ_TOPIC);
            assertThat(description.partitions().size()).isEqualTo(SOURCE_PARTITIONS);

            ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, DLQ_TOPIC);
            ConfigEntry retention = admin.describeConfigs(List.of(resource)).all().get(30, TimeUnit.SECONDS)
                .get(resource).get("retention.ms");
            assertThat(retention.source()).as("retention.ms is set explicitly for the DLQ")
                .isEqualTo(ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG);
            assertThat(retention.value()).isEqualTo("604800000");
        }
    }
}
