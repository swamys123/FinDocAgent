package com.findoc.service.agent;

import com.findoc.dto.request.AgentQueryRequest;
import com.findoc.dto.response.AgentResponse;
import com.findoc.entity.AgentSession;
import com.findoc.entity.DocumentChunk;
import com.findoc.entity.QueryTrace;
import com.findoc.entity.SessionMessage;
import com.findoc.entity.User;
import com.findoc.repository.AgentSessionRepository;
import com.findoc.repository.DocumentChunkRepository;
import com.findoc.repository.QueryTraceRepository;
import com.findoc.repository.SessionMessageRepository;
import com.findoc.repository.UserRepository;
import com.findoc.service.embedding.EmbeddingService;
import com.findoc.util.TenantContext;
import com.pgvector.PGvector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Service
public class AgentService {
    private final DocumentChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;
    private final UserRepository userRepository;
    private final AgentSessionRepository sessionRepository;
    private final SessionMessageRepository messageRepository;
    private final QueryTraceRepository traceRepository;
    private final OpenRouterGenerationService generationService;
    private final int maxIterations;
    private final int topK;

    public AgentService(DocumentChunkRepository chunkRepository,
                        EmbeddingService embeddingService,
                        UserRepository userRepository,
                        AgentSessionRepository sessionRepository,
                        SessionMessageRepository messageRepository,
                        QueryTraceRepository traceRepository,
                        OpenRouterGenerationService generationService,
                        @Value("${agent.max-iterations:5}") int maxIterations,
                        @Value("${agent.top-k:5}") int topK) {
        this.chunkRepository = chunkRepository;
        this.embeddingService = embeddingService;
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.traceRepository = traceRepository;
        this.generationService = generationService;
        this.maxIterations = Math.min(maxIterations, 5);
        this.topK = topK;
    }

    @Transactional
    public AgentResponse query(AgentQueryRequest request) {
        UUID tenantId = TenantContext.tenantId();
        UUID userId = TenantContext.userId();
        List<UUID> ids = request.documentIds() == null ? List.of() : request.documentIds();
        String intent = classify(request.query());
        List<String> steps = new ArrayList<>();
        steps.add("classify_intent");

        PGvector queryEmbedding = new PGvector(embeddingService.embed(request.query()));
        List<DocumentChunk> matches = ids.isEmpty()
            ? chunkRepository.searchSimilar(queryEmbedding, tenantId, topK)
            : chunkRepository.searchSimilarInDocuments(queryEmbedding, tenantId, ids, topK);

        List<String> sourceChunks = matches.stream().map(DocumentChunk::getContent).limit(topK).toList();
        steps.add("vector_search");

        User user = userRepository.findByIdAndTenantIdAndDeletedAtIsNull(userId, tenantId)
            .orElseThrow(() -> new IllegalStateException("User not found in tenant context"));

        UUID sessionId = request.sessionId() == null ? null : request.sessionId();
        AgentSession session = sessionId == null
            ? sessionRepository.save(new AgentSession(user.getTenant(), user))
            : sessionRepository.findByIdAndTenantIdAndUserIdAndDeletedAtIsNull(sessionId, tenantId, userId)
                .orElseGet(() -> sessionRepository.save(new AgentSession(user.getTenant(), user)));

        if (request.sessionId() == null || !request.sessionId().equals(session.getId())) {
            sessionId = session.getId();
        }

        steps.add("generate_report");

        String answer = generationService.generate(request.query(), intent, sourceChunks);
        if (answer == null || answer.isBlank()) {
            answer = sourceChunks.isEmpty() ? "No indexed content matched the query." : "Relevant content found in " + sourceChunks.size() + " chunk(s).";
        }

        messageRepository.save(new SessionMessage(session, "user", request.query()));
        messageRepository.save(new SessionMessage(session, "assistant", answer));

        QueryTrace trace = new QueryTrace(
            session,
            user.getTenant(),
            request.query(),
            intent,
            String.join("|", steps),
            answer,
            sourceChunks.isEmpty() ? BigDecimal.ZERO : new BigDecimal("0.75"),
            0
        );
        traceRepository.save(trace);

        return new AgentResponse(UUID.randomUUID(), session.getId(), answer, intent, sourceChunks, List.copyOf(steps), sourceChunks.isEmpty() ? 0.0 : 0.75);
    }

    private String classify(String query) {
        String normalized = query.toLowerCase(Locale.ROOT);
        if (normalized.contains("compare") || normalized.contains("difference")) return "COMPARE";
        if (normalized.contains("summar")) return "SUMMARISE";
        if (normalized.contains("report")) return "REPORT";
        return "LOOKUP";
    }
}
