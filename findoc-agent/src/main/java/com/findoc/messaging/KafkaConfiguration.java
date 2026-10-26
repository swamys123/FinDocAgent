package com.findoc.messaging;

import com.findoc.service.document.IngestionService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;

@Configuration
public class KafkaConfiguration {
    @Bean
    DefaultErrorHandler ingestionErrorHandler(KafkaTemplate<String, IngestionJob> kafkaTemplate,
                                               IngestionService ingestionService,
                                               MeterRegistry meterRegistry,
                                               @Value("${findoc.ingestion.dlq:findoc.ingestion.dlq}") String dlqTopic,
                                               @Value("${findoc.ingestion.retry.initial-interval-ms:1000}") long initialIntervalMs,
                                               @Value("${findoc.ingestion.retry.multiplier:2.0}") double multiplier,
                                               @Value("${findoc.ingestion.retry.max-interval-ms:10000}") long maxIntervalMs,
                                               @Value("${findoc.ingestion.retry.max-retries:2}") int maxRetries) {
        var deadLetterPublisher = new DeadLetterPublishingRecoverer(kafkaTemplate, (record, exception) ->
            new TopicPartition(dlqTopic, record.partition()));
        deadLetterPublisher.setFailIfSendResultIsError(true);
        deadLetterPublisher.addHeadersFunction((record, exception) -> {
            if (!(record.value() instanceof IngestionJob job)) {
                return new RecordHeaders();
            }
            return new RecordHeaders().add("findoc-retry-count",
                Integer.toString(ingestionService.retryCount(job)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        });
        Counter deadLetterAttempts = Counter.builder("findoc.ingestion.dlq.published")
            .description("Number of ingestion records successfully published to the dead-letter topic")
            .tag("topic", dlqTopic)
            .register(meterRegistry);
        ConsumerAwareRecordRecoverer recoverer = (record, consumer, exception) -> {
            deadLetterPublisher.accept(record, consumer, exception);
            if (record.value() instanceof IngestionJob job) {
                ingestionService.recordFailure(job, failureMessage(exception), true);
                deadLetterAttempts.increment();
            }
        };
        var backOff = new ExponentialBackOffWithMaxRetries(maxRetries);
        backOff.setInitialInterval(initialIntervalMs);
        backOff.setMultiplier(multiplier);
        backOff.setMaxInterval(maxIntervalMs);
        return new DefaultErrorHandler(recoverer, backOff);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, IngestionJob> kafkaListenerContainerFactory(
            ConsumerFactory<String, IngestionJob> consumerFactory,
            DefaultErrorHandler ingestionErrorHandler) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, IngestionJob>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(ingestionErrorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }

    private static String failureMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }
}