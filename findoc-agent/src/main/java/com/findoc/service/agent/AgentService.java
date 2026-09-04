package com.findoc.service.agent;

import com.findoc.dto.request.AgentQueryRequest;
import com.findoc.dto.response.AgentResponse;
import com.findoc.entity.DocumentChunk;
import com.findoc.repository.DocumentChunkRepository;
import com.findoc.service.embedding.EmbeddingService;
import com.findoc.util.TenantContext;
import com.pgvector.PGvector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class AgentService {
    private final DocumentChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;
    private final int maxIterations;
    private final int topK;

    public AgentService(DocumentChunkRepository chunkRepository,
                        EmbeddingService embeddingService,
                        @Value("${agent.max-iterations:5}") int maxIterations,
                        @Value("${agent.top-k:5}") int topK) {
        this.chunkRepository = chunkRepository;
        this.embeddingService = embeddingService;
        this.maxIterations = Math.min(maxIterations, 5);
        this.topK = topK;
    }

    public AgentResponse query(AgentQueryRequest request) {
        List<UUID> ids = request.documentIds() == null ? List.of() : request.documentIds();
        List<String> steps = new ArrayList<>();
        steps.add("classify_intent");
        PGvector queryEmbedding = new PGvector(embeddingService.embed(request.query()));
        List<DocumentChunk> matches = ids.isEmpty()
            ? chunkRepository.searchSimilar(queryEmbedding, TenantContext.tenantId(), topK)
            : chunkRepository.searchSimilarInDocuments(queryEmbedding, TenantContext.tenantId(), ids, topK);
        List<String> sourceChunks = matches.stream().map(DocumentChunk::getContent).toList();
        steps.add("vector_search");
        steps.add("generate_report");
        String answer = sourceChunks.isEmpty() ? "No indexed content matched the query." : "Relevant content found in " + Math.min(sourceChunks.size(), topK) + " chunks.";
        return new AgentResponse(UUID.randomUUID(), request.sessionId() == null ? UUID.randomUUID() : request.sessionId(), answer, classify(request.query()), sourceChunks.stream().limit(topK).toList(), List.copyOf(steps), sourceChunks.isEmpty() ? 0.0 : 0.75);
    }

    private String classify(String query) {
        String normalized = query.toLowerCase(Locale.ROOT);
        if (normalized.contains("compare") || normalized.contains("difference")) return "COMPARE";
        if (normalized.contains("summar")) return "SUMMARISE";
        if (normalized.contains("report")) return "REPORT";
        return "LOOKUP";
    }
}
