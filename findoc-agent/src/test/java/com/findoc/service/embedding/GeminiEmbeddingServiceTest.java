package com.findoc.service.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiEmbeddingServiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
}
