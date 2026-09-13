package com.findoc.service.agent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenRouterGenerationServiceTest {
    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
    private Logger serviceLogger;

    @BeforeEach
    void attachLogAppender() {
        serviceLogger = (Logger) LoggerFactory.getLogger(OpenRouterGenerationService.class);
        logAppender.start();
        serviceLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        serviceLogger.detachAppender(logAppender);
    }

    @Test
    void generateFallsBackToLocalSummaryWhenApiKeyBlank() {
        OpenRouterGenerationService service = new OpenRouterGenerationService("", "any-model", "https://openrouter.ai");

        String answer = service.generate("What is the fee?", "LOOKUP", List.of("Monthly fee is SGD 100."));

        assertThat(answer).startsWith("[Offline mode");
        assertThat(answer).contains("Monthly fee is SGD 100.");
    }

    @Test
    void generateReturnsOfflineMessageWhenNoSourcesMatched() {
        OpenRouterGenerationService service = new OpenRouterGenerationService("", "any-model", "https://openrouter.ai");

        String answer = service.generate("What is the fee?", "LOOKUP", List.of());

        assertThat(answer).isEqualTo("[Offline mode \u2014 LLM unavailable] No indexed content matched the query.");
    }

    @Test
    void generateLogsWarningAndFallsBackWhenProviderCallFails() {
        // Unreachable port triggers an immediate connection failure without a real network call.
        OpenRouterGenerationService service = new OpenRouterGenerationService("test-key", "any-model", "http://localhost:1");

        String answer = service.generate("What is the fee?", "LOOKUP", List.of("Monthly fee is SGD 100."));

        assertThat(answer).startsWith("[Offline mode");
        assertThat(logAppender.list)
            .anyMatch(event -> event.getLevel() == Level.WARN && event.getFormattedMessage().contains("OpenRouter generation call failed"));
    }
}
