package com.findoc.service.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.findoc.service.ProviderCircuitBreaker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Service
public class GeminiEmbeddingService implements EmbeddingService {
    private static final int DIMENSIONS = 768;

    private final RestClient restClient;
    private final String apiKey;
    private final String model;
    private final ProviderCircuitBreaker circuitBreaker;

    public GeminiEmbeddingService(String apiKey, String model, String baseUrl) {
        this(apiKey, model, baseUrl, 3, 30);
    }

    @Autowired
    public GeminiEmbeddingService(
            @Value("${gemini.api-key:}") String apiKey,
            @Value("${gemini.embedding-model:gemini-embedding-001}") String model,
            @Value("${gemini.base-url:https://generativelanguage.googleapis.com}") String baseUrl,
            @Value("${gemini.circuit-breaker.failure-threshold:3}") int failureThreshold,
            @Value("${gemini.circuit-breaker.open-duration-seconds:30}") long openDurationSeconds) {
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(requestFactory)
            .build();
        this.apiKey = apiKey;
        this.model = model;
        this.circuitBreaker = new ProviderCircuitBreaker(failureThreshold, Duration.ofSeconds(openDurationSeconds));
    }

    @Override
    public float[] embed(String content) {
        if (apiKey.isBlank()) {
            throw new EmbeddingException("Gemini API key is not configured");
        }
        JsonNode response = circuitBreaker.execute(() -> restClient.post()
            .uri(uriBuilder -> uriBuilder.path("/v1beta/models/{model}:embedContent")
                .queryParam("key", apiKey).build(model))
            .body(new GeminiEmbeddingRequest("models/" + model, DIMENSIONS, new GeminiContent(new GeminiPart(content))))
            .retrieve()
            .body(JsonNode.class));
        JsonNode values = response == null ? null : response.path("embedding").path("values");
        if (values == null || !values.isArray() || values.size() != DIMENSIONS) {
            throw new EmbeddingException("Gemini returned an invalid embedding dimension");
        }
        float[] embedding = new float[DIMENSIONS];
        for (int index = 0; index < DIMENSIONS; index++) {
            if (!values.get(index).isNumber()) {
                throw new EmbeddingException("Gemini returned a non-numeric embedding value");
            }
            embedding[index] = values.get(index).floatValue();
        }
        return embedding;
    }

    private record GeminiEmbeddingRequest(String model, int outputDimensionality, GeminiContent content) {}
    private record GeminiContent(GeminiPart[] parts) {
        GeminiContent(GeminiPart part) { this(new GeminiPart[]{part}); }
    }
    private record GeminiPart(String text) {}

    public static class EmbeddingException extends RuntimeException {
        public EmbeddingException(String message) { super(message); }
    }
}