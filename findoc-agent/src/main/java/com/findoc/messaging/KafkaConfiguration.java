package com.findoc.messaging;

import com.findoc.service.document.IngestionService;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.beans.factory.annotation.Value;

@Configuration
public class KafkaConfiguration {
    @Bean
    DefaultErrorHandler ingestionErrorHandler(KafkaTemplate<String, IngestionJob> kafkaTemplate,
                                               IngestionService ingestionService,
                                               @Value("${findoc.ingestion.dlq:findoc.ingestion.dlq}") String dlqTopic) {
        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate, (record, exception) -> {
            if (record.value() instanceof IngestionJob job) {
                ingestionService.recordFailure(job, exception.getMessage(), true);
            }
            return new TopicPartition(dlqTopic, record.partition());
        });
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, IngestionJob> kafkaListenerContainerFactory(
            ConsumerFactory<String, IngestionJob> consumerFactory,
            DefaultErrorHandler ingestionErrorHandler) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, IngestionJob>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(ingestionErrorHandler);
        return factory;
    }
}