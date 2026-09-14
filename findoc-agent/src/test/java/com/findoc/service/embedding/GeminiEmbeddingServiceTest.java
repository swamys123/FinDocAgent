package com.findoc.service.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.findoc.service.ProviderCircuitBreaker;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientResponseException;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiEmbeddingServiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void throwsExceptionWhenApiKeyIsBlank() {
        GeminiEmbeddingService service = new GeminiEmbeddingService("", "gemini-embedding-001", "https://generativelanguage.googleapis.com");

        assertThatThrownBy(() -> service.embed("hello world"))
            .isInstanceOf(GeminiEmbeddingService.EmbeddingException.class)
            .hasMessageContaining("Gemini API key is not configured");
    }

    @Test
    void sendsSupportedGeminiModelAnd768DimensionalOutput() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> requestBody = new AtomicReference<>();

        server.createContext("/v1beta/models/gemini-embedding-001:embedContent", exchange -> {
            try {
                requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                StringBuilder values = new StringBuilder();
                values.append("[");
                for (int i = 0; i < 768; i++) {
                    if (i > 0) { values.append(","); }
                    values.append(i / 100.0d);
                }
                values.append("]");
                String responseJson = "{\"embedding\":{\"values\":" + values + "}}";
                byte[] responseBytes = responseJson.getBytes(StandardCharsets.UTF_8);

                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, responseBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBytes);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        server.start();

        try {
            GeminiEmbeddingService service = new GeminiEmbeddingService(
                "test-key",
                "gemini-embedding-001",
                "http://127.0.0.1:" + server.getAddress().getPort()
            );

            float[] embedding = service.embed("hello world");

            assertThat(embedding).hasSize(768);
            assertThat(embedding[0]).isEqualTo(0.0f);
            assertThat(embedding[767]).isNotZero();

            JsonNode request = MAPPER.readTree(requestBody.get());
            assertThat(request.path("model").asText()).isEqualTo("models/gemini-embedding-001");
            assertThat(request.path("outputDimensionality").asInt()).isEqualTo(768);
            assertThat(request.path("content").path("parts").get(0).path("text").asText()).isEqualTo("hello world");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void throwsEmbeddingExceptionOnInvalidDimensionResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1beta/models/gemini-embedding-001:embedContent", exchange -> {
            // Return only 3 values instead of 768
            String responseJson = "{\"embedding\":{\"values\":[0.1, 0.2, 0.3]}}";
            byte[] responseBytes = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        });
        server.start();

        try {
            GeminiEmbeddingService service = new GeminiEmbeddingService(
                "test-key",
                "gemini-embedding-001",
                "http://127.0.0.1:" + server.getAddress().getPort()
            );

            assertThatThrownBy(() -> service.embed("hello world"))
                .isInstanceOf(GeminiEmbeddingService.EmbeddingException.class)
                .hasMessageContaining("invalid embedding dimension");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void throwsEmbeddingExceptionOnNonNumericValuesInResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1beta/models/gemini-embedding-001:embedContent", exchange -> {
            StringBuilder values = new StringBuilder("[");
            for (int i = 0; i < 767; i++) {
                values.append("0.1,");
            }
            values.append("\"not-a-number\"]");
            String responseJson = "{\"embedding\":{\"values\":" + values + "}}";
            byte[] responseBytes = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        });
        server.start();

        try {
            GeminiEmbeddingService service = new GeminiEmbeddingService(
                "test-key",
                "gemini-embedding-001",
                "http://127.0.0.1:" + server.getAddress().getPort()
            );

            assertThatThrownBy(() -> service.embed("hello world"))
                .isInstanceOf(GeminiEmbeddingService.EmbeddingException.class)
                .hasMessageContaining("non-numeric embedding value");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void tripsCircuitBreakerOnRepeatedHttpErrors() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger callCount = new AtomicInteger();

        server.createContext("/v1beta/models/gemini-embedding-001:embedContent", exchange -> {
            callCount.incrementAndGet();
            String errorJson = "{\"error\":{\"code\":429,\"message\":\"Rate limit exceeded\"}}";
            byte[] bytes = errorJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(429, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        try {
            // Failure threshold = 2, open duration = 60s
            GeminiEmbeddingService service = new GeminiEmbeddingService(
                "test-key",
                "gemini-embedding-001",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                2,
                60
            );

            // Attempt 1: HTTP 429
            assertThatThrownBy(() -> service.embed("first"))
                .isInstanceOf(RestClientResponseException.class);

            // Attempt 2: HTTP 429 -> reaches threshold
            assertThatThrownBy(() -> service.embed("second"))
                .isInstanceOf(RestClientResponseException.class);

            assertThat(callCount.get()).isEqualTo(2);

            // Attempt 3: Circuit is OPEN -> CircuitOpenException thrown without network call
            assertThatThrownBy(() -> service.embed("third"))
                .isInstanceOf(ProviderCircuitBreaker.CircuitOpenException.class);

            assertThat(callCount.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void tripsCircuitBreakerOnServer500Errors() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger callCount = new AtomicInteger();

        server.createContext("/v1beta/models/gemini-embedding-001:embedContent", exchange -> {
            callCount.incrementAndGet();
            String errorJson = "{\"error\":{\"code\":503,\"message\":\"Service Unavailable\"}}";
            byte[] bytes = errorJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(503, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        try {
            GeminiEmbeddingService service = new GeminiEmbeddingService(
                "test-key",
                "gemini-embedding-001",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                2,
                60
            );

            assertThatThrownBy(() -> service.embed("first"))
                .isInstanceOf(RestClientResponseException.class);

            assertThatThrownBy(() -> service.embed("second"))
                .isInstanceOf(RestClientResponseException.class);

            assertThat(callCount.get()).isEqualTo(2);

            assertThatThrownBy(() -> service.embed("third"))
                .isInstanceOf(ProviderCircuitBreaker.CircuitOpenException.class);

            assertThat(callCount.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }
}
