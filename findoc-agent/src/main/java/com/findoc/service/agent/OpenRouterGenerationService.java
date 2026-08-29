package com.findoc.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

@Service
public class OpenRouterGenerationService {
    private final RestClient restClient;
    private final String apiKey;
    private final String model;

    public OpenRouterGenerationService(
        @Value("${openrouter.api-key:}") String apiKey,
        @Value("${openrouter.model:mistralai/mistral-7b-instruct:free}") String model,
        @Value("${openrouter.base-url:https://openrouter.ai}") String baseUrl) {
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
    }

    public String generate(String query, String intent, List<String> sources) {
        if (apiKey.isBlank()) {
            return summarizeLocally(query, intent, sources);
        }

        JsonNode response = restClient.post()
            .uri("/api/v1/chat/completions")
            .header("Authorization", "Bearer " + apiKey)
            .header("HTTP-Referer", "https://localhost")
            .header("X-Title", "FinDoc Agent")
            .body(new OpenRouterRequest(
                model,
                List.of(new Message("user", buildPrompt(query, intent, sources)))
            ))
            .retrieve()
            .body(JsonNode.class);

        if (response == null || response.path("choices").isEmpty()) {
            throw new IllegalStateException("OpenRouter returned an empty response");
        }

        return response.path("choices").get(0).path("message").path("content").asText();
    }

    private String buildPrompt(String query, String intent, List<String> sources) {
        String context = sources.isEmpty() ? "No sources matched." : String.join("\n\n---\n\n", sources);
        return "You are FinDoc Agent. Answer the user's query using the provided context.\n\nIntent: " + intent + "\n\nQuery: " + query + "\n\nContext:\n" + context;
    }

    private String summarizeLocally(String query, String intent, List<String> sources) {
        if (sources.isEmpty()) {
            return "No indexed content matched the query.";
        }
        String joined = String.join(" ", sources);
        return "Based on the retrieved context, the answer for '" + query + "' is: " + joined.substring(0, Math.min(joined.length(), 280));
    }

    private record OpenRouterRequest(String model, List<Message> messages) {}
    private record Message(String role, String content) {}
}
