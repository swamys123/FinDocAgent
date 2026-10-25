package com.findoc.correctness;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

/** Topic wiring shared by the tests that run against one common ingestion topic and Spring context. */
abstract class SharedPipelineTestSupport extends CorrectnessTestSupport {
    static final String TOPIC = "correctness-ingestion-" + UUID.randomUUID();
    static final String DLQ_TOPIC = TOPIC + ".dlq";
    static final String GROUP = TOPIC + "-group";

    @DynamicPropertySource
    static void registerTopics(DynamicPropertyRegistry registry) {
        registry.add("findoc.ingestion.topic", () -> TOPIC);
        registry.add("findoc.ingestion.dlq", () -> DLQ_TOPIC);
        registry.add("findoc.ingestion.group", () -> GROUP);
    }
}
