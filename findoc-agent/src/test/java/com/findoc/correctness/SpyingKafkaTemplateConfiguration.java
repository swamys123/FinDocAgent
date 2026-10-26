package com.findoc.correctness;

import org.mockito.Mockito;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;

/** Wraps the application's KafkaTemplate in a Mockito spy so tests can make selected sends fail deterministically. */
@TestConfiguration
class SpyingKafkaTemplateConfiguration {
    @Bean
    static BeanPostProcessor kafkaTemplateSpyingPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return bean instanceof KafkaTemplate<?, ?> ? Mockito.spy(bean) : bean;
            }
        };
    }
}
