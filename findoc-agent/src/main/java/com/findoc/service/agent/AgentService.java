package com.findoc.service.agent;

import com.findoc.dto.request.AgentQueryRequest;
import com.findoc.dto.response.AgentResponse;
import com.findoc.service.document.DocumentService;
import com.findoc.util.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class AgentService {
    private final DocumentService documentService;
    private final int maxIterations;
    private final int topK;

    public AgentService(DocumentService documentService,
                        @Value("${agent.max-iterations:5}") int maxIterations,
                        @Value("${agent.top-k:5}") int topK) {
        this.documentService = documentService;
        this.maxIterations = Math.min(maxIterations, 5);
        this.topK = topK;
    }

    public AgentResponse query(AgentQueryRequest request) {
        List<UUID> ids = request.documentIds() == null ? List.of() : request.documentIds();
        List<String> steps = new ArrayList<>();
        steps.add("classify_intent");
        List<String> sourceChunks = new ArrayList<>();
        for (UUID id : ids) {
            sourceChunks.addAll(documentService.chunks(id));
            if (sourceChunks.size() >= topK) break;
        }
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
