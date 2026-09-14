package com.findoc.service.agent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.findoc.service.agent.OpenRouterGenerationService.ComparisonGeneration;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    void generateReturnsContentOnSuccessfulProviderResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/chat/completions", exchange -> {
            String responseJson = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"The monthly fee is SGD 100 according to Section 3.\"}}]}";
            byte[] bytes = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        try {
            OpenRouterGenerationService service = new OpenRouterGenerationService(
                "test-key",
                "test-model",
                "http://127.0.0.1:" + server.getAddress().getPort()
            );

            String answer = service.generate("What is the fee?", "LOOKUP", List.of("Monthly fee is SGD 100."));

            assertThat(answer).isEqualTo("The monthly fee is SGD 100 according to Section 3.");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void generateFallsBackOnEmptyChoicesOrMalformedResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/chat/completions", exchange -> {
            String responseJson = "{\"choices\":[]}";
            byte[] bytes = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        try {
            OpenRouterGenerationService service = new OpenRouterGenerationService(
                "test-key",
                "test-model",
                "http://127.0.0.1:" + server.getAddress().getPort()
            );

            String answer = service.generate("What is the fee?", "LOOKUP", List.of("Monthly fee is SGD 100."));

            assertThat(answer).startsWith("[Offline mode");
            assertThat(logAppender.list)
                .anyMatch(event -> event.getLevel() == Level.WARN && event.getFormattedMessage().contains("OpenRouter generation call failed"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void generateTripsCircuitBreakerOnRepeatedHttpErrorsAndFallsBackImmediately() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger callCount = new AtomicInteger();

        server.createContext("/api/v1/chat/completions", exchange -> {
            callCount.incrementAndGet();
            String responseJson = "{\"error\":{\"code\":429,\"message\":\"Rate limit exceeded\"}}";
            byte[] bytes = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(429, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        try {
            // Failure threshold = 2, open duration = 60s
            OpenRouterGenerationService service = new OpenRouterGenerationService(
                "test-key",
                "test-model",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                2,
                60
            );

            // Attempt 1: 429
            String answer1 = service.generate("query 1", "LOOKUP", List.of("source 1"));
            assertThat(answer1).startsWith("[Offline mode");

            // Attempt 2: 429 -> reaches threshold
            String answer2 = service.generate("query 2", "LOOKUP", List.of("source 2"));
            assertThat(answer2).startsWith("[Offline mode");

            assertThat(callCount.get()).isEqualTo(2);

            // Attempt 3: Circuit is OPEN -> CircuitOpenException caught, immediately falls back without HTTP call
            String answer3 = service.generate("query 3", "LOOKUP", List.of("source 3"));
            assertThat(answer3).startsWith("[Offline mode");

            assertThat(callCount.get()).isEqualTo(2);
            assertThat(logAppender.list)
                .anyMatch(event -> event.getLevel() == Level.WARN && event.getFormattedMessage().contains("OpenRouter circuit is open"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void compareParsesSuccessfulJsonResponseWithCodeFences() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/chat/completions", exchange -> {
            String responseBody = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"```json\\n{\\\"summary\\\":\\\"Both cover interest rates.\\\",\\\"similarities\\\":[\\\"Both charge interest\\\"],\\\"differences\\\":[\\\"Doc A has 5%\\\",\\\"Doc B has 7%\\\"]}\\n```\"}}]}";
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        try {
            OpenRouterGenerationService service = new OpenRouterGenerationService(
                "test-key",
                "test-model",
                "http://127.0.0.1:" + server.getAddress().getPort()
            );

            ComparisonGeneration result = service.compare("interest rates", List.of("Doc A interest rate is 5%."), List.of("Doc B interest rate is 7%."));

            assertThat(result.summary()).isEqualTo("Both cover interest rates.");
            assertThat(result.similarities()).containsExactly("Both charge interest");
            assertThat(result.differences()).containsExactly("Doc A has 5%", "Doc B has 7%");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void compareFallsBackToLocalWhenApiKeyIsBlank() {
        OpenRouterGenerationService service = new OpenRouterGenerationService("", "any-model", "https://openrouter.ai");

        ComparisonGeneration result = service.compare("fees", List.of("Doc A fee is 10."), List.of("Doc B fee is 20."));

        assertThat(result.summary()).contains("Comparison completed for fees");
        assertThat(result.similarities()).isNotEmpty();
        assertThat(result.differences()).hasSize(2);
    }

    @Test
    void compareFallsBackToLocalWhenProviderFailsOrReturnsInvalidJson() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/chat/completions", exchange -> {
            String responseBody = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"This is plain text and not valid JSON\"}}]}";
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        try {
            OpenRouterGenerationService service = new OpenRouterGenerationService(
                "test-key",
                "test-model",
                "http://127.0.0.1:" + server.getAddress().getPort()
            );

            ComparisonGeneration result = service.compare("fees", List.of("Doc A fee is 10."), List.of("Doc B fee is 20."));

            assertThat(result.summary()).contains("Comparison completed for fees");
            assertThat(logAppender.list)
                .anyMatch(event -> event.getLevel() == Level.WARN && event.getFormattedMessage().contains("Failed to parse OpenRouter comparison response"));
        } finally {
            server.stop(0);
        }
    }
}
