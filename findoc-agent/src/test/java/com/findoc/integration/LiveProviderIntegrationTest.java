package com.findoc.integration;

import com.findoc.service.agent.OpenRouterGenerationService;
import com.findoc.service.agent.OpenRouterGenerationService.ComparisonGeneration;
import com.findoc.service.embedding.GeminiEmbeddingService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("local-integration")
@ActiveProfiles("local-pg")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class LiveProviderIntegrationTest {

    @Autowired
    private GeminiEmbeddingService geminiEmbeddingService;

    @Autowired
    private OpenRouterGenerationService openRouterGenerationService;

    @Value("${gemini.api-key:}")
    private String geminiApiKey;

    @Value("${openrouter.api-key:}")
    private String openRouterApiKey;

    @Test
    void validatesLiveGeminiEmbeddingModelContract() {
        assumeTrue(geminiApiKey != null && !geminiApiKey.isBlank(),
            "GEMINI_API_KEY is not configured; skipping live Gemini validation");

        float[] embedding = geminiEmbeddingService.embed("Financial quarterly report revenue and expense summary");

        assertThat(embedding).isNotNull();
        assertThat(embedding).hasSize(768);

        // Verify non-trivial floating point values
        boolean hasNonZero = false;
        for (float value : embedding) {
            assertThat(Float.isFinite(value)).isTrue();
            if (value != 0.0f) {
                hasNonZero = true;
            }
        }
        assertThat(hasNonZero).isTrue();

        // Verify semantic similarity between related vs unrelated text
        float[] relatedEmbedding = geminiEmbeddingService.embed("Quarterly earnings and income statement overview");
        float[] unrelatedEmbedding = geminiEmbeddingService.embed("How to bake a chocolate cake in ten easy steps");

        double similarityRelated = cosineSimilarity(embedding, relatedEmbedding);
        double similarityUnrelated = cosineSimilarity(embedding, unrelatedEmbedding);

        assertThat(similarityRelated).isGreaterThan(similarityUnrelated);
    }

    @Test
    void validatesLiveOpenRouterGeneration() {
        assumeTrue(openRouterApiKey != null && !openRouterApiKey.isBlank(),
            "OPENROUTER_API_KEY is not configured; skipping live OpenRouter validation");

        String answer = openRouterGenerationService.generate(
            "What was the total revenue in Q3 2024?",
            "LOOKUP",
            List.of("Section 2.1: In Q3 2024, total revenue reached USD 15.2 million, representing a 12% increase year-over-year.")
        );

        assertThat(answer).isNotBlank();
        assertThat(answer).doesNotStartWith("[Offline mode");
        assertThat(answer.toLowerCase()).containsAnyOf("15.2", "revenue", "12%");
    }

    @Test
    void validatesLiveOpenRouterComparison() {
        assumeTrue(openRouterApiKey != null && !openRouterApiKey.isBlank(),
            "OPENROUTER_API_KEY is not configured; skipping live OpenRouter comparison validation");

        ComparisonGeneration comparison = openRouterGenerationService.compare(
            "management fees",
            List.of("Fund Alpha charges a 1.5% annual management fee on net assets."),
            List.of("Fund Beta charges a 0.75% annual management fee on net assets.")
        );

        assertThat(comparison).isNotNull();
        assertThat(comparison.summary()).isNotBlank();
        assertThat(comparison.summary()).doesNotStartWith("[Offline mode");
        assertThat(comparison.similarities()).isNotNull();
        assertThat(comparison.differences()).isNotNull();
    }

    private double cosineSimilarity(float[] vectorA, float[] vectorB) {
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < vectorA.length; i++) {
            dotProduct += vectorA[i] * vectorB[i];
            normA += vectorA[i] * vectorA[i];
            normB += vectorB[i] * vectorB[i];
        }
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
