package com.findoc.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

@Service
public class OpenRouterGenerationService {
    private final ObjectMapper objectMapper = new ObjectMapper();
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

    public ComparisonGeneration compare(String aspect, List<String> documentASources, List<String> documentBSources) {
        if (apiKey.isBlank()) {
            return localComparison(aspect, documentASources, documentBSources);
        }
        JsonNode response = restClient.post()
            .uri("/api/v1/chat/completions")
            .header("Authorization", "Bearer " + apiKey)
            .header("HTTP-Referer", "https://localhost")
            .header("X-Title", "FinDoc Agent")
            .body(new OpenRouterRequest(
                model,
                List.of(new Message("user", comparisonPrompt(aspect, documentASources, documentBSources)))
            ))
            .retrieve()
            .body(JsonNode.class);
        String content = response == null ? "" : response.path("choices").path(0).path("message").path("content").asText();
        try {
            JsonNode result = objectMapper.readTree(stripCodeFence(content));
            return new ComparisonGeneration(
                result.path("summary").asText(),
                objectMapper.convertValue(result.path("similarities"), objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)),
                objectMapper.convertValue(result.path("differences"), objectMapper.getTypeFactory().constructCollectionType(List.class, String.class))
            );
        } catch (Exception exception) {
            return localComparison(aspect, documentASources, documentBSources);
        }
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

    private String comparisonPrompt(String aspect, List<String> documentASources, List<String> documentBSources) {
        return "Compare the documents only using the supplied excerpts for this aspect: " + aspect
            + ". Return valid JSON with string fields summary and arrays similarities and differences. "
            + "Do not add markdown or any text outside the JSON.\n\nDocument A:\n"
            + String.join("\n---\n", documentASources)
            + "\n\nDocument B:\n"
            + String.join("\n---\n", documentBSources);
    }

    private ComparisonGeneration localComparison(String aspect, List<String> documentASources, List<String> documentBSources) {
        if (documentASources.isEmpty() && documentBSources.isEmpty()) {
            return new ComparisonGeneration("No indexed content matched the comparison aspect.", List.of(), List.of());
        }
        List<String> similarities = documentASources.isEmpty() || documentBSources.isEmpty()
            ? List.of()
            : List.of("Both documents contain content relevant to " + aspect + ".");
        List<String> differences = new java.util.ArrayList<>();
        if (!documentASources.isEmpty()) {
            differences.add("Document A: " + excerpt(documentASources.get(0)));
        }
        if (!documentBSources.isEmpty()) {
            differences.add("Document B: " + excerpt(documentBSources.get(0)));
        }
        return new ComparisonGeneration("Comparison completed for " + aspect + ".", similarities, List.copyOf(differences));
    }

    private String excerpt(String source) {
        return source.substring(0, Math.min(source.length(), 240));
    }

    private String stripCodeFence(String content) {
        return content.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
    }

    private record OpenRouterRequest(String model, List<Message> messages) {}
    private record Message(String role, String content) {}
    public record ComparisonGeneration(String summary, List<String> similarities, List<String> differences) {}
}
