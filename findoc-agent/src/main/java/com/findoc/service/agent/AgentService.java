package com.findoc.service.agent;

import com.findoc.dto.request.AgentQueryRequest;
import com.findoc.dto.request.DocumentComparisonRequest;
import com.findoc.dto.response.AgentSessionResponse;
import com.findoc.dto.response.AgentResponse;
import com.findoc.dto.response.AgentSourceResponse;
import com.findoc.dto.response.AgentTraceResponse;
import com.findoc.dto.response.AgentTraceStepResponse;
import com.findoc.dto.response.DocumentComparisonResponse;
import com.findoc.dto.response.SessionMessageResponse;
import com.findoc.entity.AgentSession;
import com.findoc.entity.Document;
import com.findoc.entity.DocumentChunk;
import com.findoc.entity.QueryTrace;
import com.findoc.entity.SessionMessage;
import com.findoc.entity.User;
import com.findoc.repository.AgentSessionRepository;
import com.findoc.repository.DocumentRepository;
import com.findoc.repository.DocumentChunkRepository;
import com.findoc.repository.QueryTraceRepository;
import com.findoc.repository.SessionMessageRepository;
import com.findoc.repository.UserRepository;
import com.findoc.service.embedding.EmbeddingService;
import com.findoc.util.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;

@Service
public class AgentService {
    private static final int MAX_HISTORY_MESSAGES = 10;

    private final DocumentChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;
    private final EmbeddingService embeddingService;
    private final UserRepository userRepository;
    private final AgentSessionRepository sessionRepository;
    private final SessionMessageRepository messageRepository;
    private final QueryTraceRepository traceRepository;
    private final OpenRouterGenerationService generationService;
    private final IntentClassifier intentClassifier;
    private final ObjectMapper objectMapper;
    private final int maxIterations;
    private final int topK;

    public AgentService(DocumentChunkRepository chunkRepository,
                        DocumentRepository documentRepository,
                        EmbeddingService embeddingService,
                        UserRepository userRepository,
                        AgentSessionRepository sessionRepository,
                        SessionMessageRepository messageRepository,
                        QueryTraceRepository traceRepository,
                        OpenRouterGenerationService generationService,
                        IntentClassifier intentClassifier,
                        @Value("${agent.max-iterations:5}") int maxIterations,
                        @Value("${agent.top-k:5}") int topK) {
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
        this.embeddingService = embeddingService;
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.traceRepository = traceRepository;
        this.generationService = generationService;
        this.intentClassifier = intentClassifier;
        this.objectMapper = new ObjectMapper();
        this.maxIterations = Math.min(maxIterations, 5);
        this.topK = topK;
    }

    @Transactional
    public AgentResponse query(AgentQueryRequest request) {
        long startedAt = System.nanoTime();
        UUID tenantId = TenantContext.tenantId();
        UUID userId = TenantContext.userId();
        List<UUID> ids = request.documentIds() == null ? List.of() : request.documentIds();
        long classificationStartedAt = System.nanoTime();
        String intent = intentClassifier.classify(request.query());
        List<String> steps = new ArrayList<>();
        steps.add("classify_intent");
        List<AgentTraceStepResponse> traceSteps = new ArrayList<>();
        traceSteps.add(traceStep(1, "classify_intent",
            objectNode().put("query", bounded(request.query(), 500)),
            objectNode().put("intent", intent),
            elapsedMs(classificationStartedAt)));

        long vectorSearchStartedAt = System.nanoTime();
        float[] queryEmbedding = embeddingService.embed(request.query());
        List<DocumentChunk> matches = ids.isEmpty()
            ? chunkRepository.searchSimilar(queryEmbedding, tenantId, topK)
            : chunkRepository.searchSimilarInDocuments(queryEmbedding, tenantId, ids, topK);

        List<String> sourceChunks = matches.stream().map(DocumentChunk::getContent).limit(topK).toList();
        steps.add("vector_search");
        ArrayNode scores = objectMapper.createArrayNode();
        matches.stream().limit(topK).map(match -> cosineSimilarity(match.getEmbedding(), queryEmbedding))
            .forEach(scores::add);
        ObjectNode vectorInput = objectNode().put("topK", topK);
        ArrayNode documentIds = objectMapper.createArrayNode();
        ids.forEach(id -> documentIds.add(id.toString()));
        vectorInput.set("documentIds", documentIds);
        traceSteps.add(traceStep(2, "vector_search", vectorInput,
            objectNode().put("chunksFound", matches.size())
                .put("topScore", scores.isEmpty() ? 0.0 : scores.get(0).asDouble())
                .set("scores", scores),
            elapsedMs(vectorSearchStartedAt)));

        User user = userRepository.findByIdAndTenantIdAndDeletedAtIsNull(userId, tenantId)
            .orElseThrow(() -> new IllegalStateException("User not found in tenant context"));

        UUID sessionId = request.sessionId();
        AgentSession session = sessionId == null
            ? sessionRepository.save(new AgentSession(user.getTenant(), user))
            : sessionRepository.findByIdAndTenantIdAndUserIdAndDeletedAtIsNull(sessionId, tenantId, userId)
                .orElseThrow(() -> new NoSuchElementException("Session not found"));

        if (sessionId == null) {
            sessionId = session.getId();
        }

        steps.add("generate_report");

        List<SessionMessage> history = sessionId.equals(request.sessionId())
            ? recentHistory(messageRepository.findBySessionIdAndTenantIdAndUserIdOrderByCreatedAtAsc(sessionId, tenantId, userId))
            : List.of();
        long generationStartedAt = System.nanoTime();
        String answer = generationService.generate(request.query(), intent, sourceChunks, history);
        if (answer == null || answer.isBlank()) {
            answer = sourceChunks.isEmpty() ? "No indexed content matched the query." : "Relevant content found in " + sourceChunks.size() + " chunk(s).";
        }
        traceSteps.add(traceStep(3, "generate_report",
            objectNode().put("chunkCount", sourceChunks.size()).put("format", "summary"),
            objectNode().put("answerLength", answer.length()),
            elapsedMs(generationStartedAt)));

        messageRepository.save(new SessionMessage(session, "user", request.query()));
        messageRepository.save(new SessionMessage(session, "assistant", answer));

        QueryTrace trace = new QueryTrace(
            session,
            user.getTenant(),
            request.query(),
            intent,
            serializeTrace(traceSteps),
            answer,
            sourceChunks.isEmpty() ? BigDecimal.ZERO : new BigDecimal("0.75"),
            (int) ((System.nanoTime() - startedAt) / 1_000_000)
        );
        QueryTrace savedTrace = traceRepository.save(trace);

        List<AgentSourceResponse> sources = matches.stream()
            .limit(topK)
            .map(match -> toSourceResponse(match, queryEmbedding))
            .toList();
        return new AgentResponse(savedTrace.getId(), session.getId(), answer, intent, sources, List.copyOf(steps), sources.isEmpty() ? 0.0 : 0.75);
    }

    @Transactional
    public DocumentComparisonResponse compare(DocumentComparisonRequest request) {
        if (request.documentIdA().equals(request.documentIdB())) {
            throw new IllegalArgumentException("Document IDs must be different");
        }
        long startedAt = System.nanoTime();
        UUID tenantId = TenantContext.tenantId();
        UUID userId = TenantContext.userId();
        Document documentA = readyDocument(request.documentIdA(), tenantId);
        Document documentB = readyDocument(request.documentIdB(), tenantId);
        User user = userRepository.findByIdAndTenantIdAndDeletedAtIsNull(userId, tenantId)
            .orElseThrow(() -> new IllegalStateException("User not found in tenant context"));

        float[] embedding = embeddingService.embed(request.aspect());
        float[] queryEmbedding = embedding;
        long documentASearchStartedAt = System.nanoTime();
        List<DocumentChunk> chunksA = chunkRepository.searchSimilarInDocuments(queryEmbedding, tenantId, List.of(documentA.getId()), topK);
        int documentASearchDurationMs = elapsedMs(documentASearchStartedAt);
        long documentBSearchStartedAt = System.nanoTime();
        List<DocumentChunk> chunksB = chunkRepository.searchSimilarInDocuments(queryEmbedding, tenantId, List.of(documentB.getId()), topK);
        int documentBSearchDurationMs = elapsedMs(documentBSearchStartedAt);
        List<String> sourcesA = chunksA.stream().map(DocumentChunk::getContent).toList();
        List<String> sourcesB = chunksB.stream().map(DocumentChunk::getContent).toList();
        List<String> comparisonContext = new ArrayList<>();
        comparisonContext.addAll(sourcesA);
        comparisonContext.addAll(sourcesB);

        long generationStartedAt = System.nanoTime();
        OpenRouterGenerationService.ComparisonGeneration comparison = generationService.compare(request.aspect(), sourcesA, sourcesB);
        String summary = comparison.summary();
        List<AgentTraceStepResponse> traceSteps = List.of(
            traceStep(1, "vector_search_document_a",
                objectNode().put("topK", topK).put("documentId", documentA.getId().toString()),
                objectNode().put("chunksFound", chunksA.size()), documentASearchDurationMs),
            traceStep(2, "vector_search_document_b",
                objectNode().put("topK", topK).put("documentId", documentB.getId().toString()),
                objectNode().put("chunksFound", chunksB.size()), documentBSearchDurationMs),
            traceStep(3, "generate_report",
                objectNode().put("chunkCount", comparisonContext.size()).put("format", "comparison"),
                objectNode().put("answerLength", summary == null ? 0 : summary.length()), elapsedMs(generationStartedAt))
        );
        AgentSession session = sessionRepository.save(new AgentSession(user.getTenant(), user));
        messageRepository.save(new SessionMessage(session, "user", request.aspect()));
        messageRepository.save(new SessionMessage(session, "assistant", summary));
        QueryTrace trace = traceRepository.save(new QueryTrace(
            session,
            user.getTenant(),
            request.aspect(),
            "COMPARE",
            serializeTrace(traceSteps),
            summary,
            comparisonContext.isEmpty() ? BigDecimal.ZERO : new BigDecimal("0.75"),
            (int) ((System.nanoTime() - startedAt) / 1_000_000)
        ));

        return new DocumentComparisonResponse(
            trace.getId(),
            comparison.similarities(),
            comparison.differences(),
            summary,
            chunksA.stream().map(chunk -> toSourceResponse(chunk, queryEmbedding)).toList(),
            chunksB.stream().map(chunk -> toSourceResponse(chunk, queryEmbedding)).toList()
        );
    }

    @Transactional(readOnly = true)
    public AgentSessionResponse sessionHistory(UUID sessionId) {
        UUID tenantId = TenantContext.tenantId();
        UUID userId = TenantContext.userId();
        if (sessionRepository.findByIdAndTenantIdAndUserIdAndDeletedAtIsNull(sessionId, tenantId, userId).isEmpty()) {
            throw new NoSuchElementException("Session not found");
        }
        List<SessionMessageResponse> messages = messageRepository
            .findBySessionIdAndTenantIdAndUserIdOrderByCreatedAtAsc(sessionId, tenantId, userId)
            .stream()
            .map(message -> new SessionMessageResponse(message.getRole(), message.getContent(), message.getCreatedAt()))
            .toList();
        return new AgentSessionResponse(sessionId, messages);
    }

    @Transactional(readOnly = true)
    public AgentTraceResponse explain(UUID queryId) {
        UUID tenantId = TenantContext.tenantId();
        UUID userId = TenantContext.userId();
        QueryTrace trace = traceRepository.findByIdAndTenantIdAndUserId(queryId, tenantId, userId)
            .orElseThrow(() -> new NoSuchElementException("Query trace not found"));
        List<AgentTraceStepResponse> steps = deserializeTrace(trace.getSteps());
        return new AgentTraceResponse(trace.getId(), trace.getQuery(), trace.getIntent(), steps, trace.getDurationMs());
    }

    private AgentTraceStepResponse traceStep(int step, String tool, JsonNode input, JsonNode output, Integer durationMs) {
        return new AgentTraceStepResponse(step, tool, input, output, durationMs);
    }

    private String serializeTrace(List<AgentTraceStepResponse> trace) {
        try {
            return objectMapper.writeValueAsString(trace);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize query trace", exception);
        }
    }

    private List<AgentTraceStepResponse> deserializeTrace(String serializedTrace) {
        if (serializedTrace == null || serializedTrace.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(serializedTrace);
            if (node.isArray()) {
                return objectMapper.readerForListOf(AgentTraceStepResponse.class).readValue(node);
            }
        } catch (IOException exception) {
            return legacyTrace(serializedTrace);
        }
        return legacyTrace(serializedTrace);
    }

    private List<AgentTraceStepResponse> legacyTrace(String serializedTrace) {
        List<AgentTraceStepResponse> result = new ArrayList<>();
        String[] legacySteps = serializedTrace.split("\\|");
        for (int index = 0; index < legacySteps.length; index++) {
            result.add(traceStep(index + 1, legacySteps[index], objectNode(), objectNode(), null));
        }
        return result;
    }

    private ObjectNode objectNode() {
        return objectMapper.createObjectNode();
    }

    private int elapsedMs(long startedAt) {
        return (int) ((System.nanoTime() - startedAt) / 1_000_000);
    }

    private String bounded(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private AgentSourceResponse toSourceResponse(DocumentChunk chunk, float[] queryEmbedding) {
        return new AgentSourceResponse(
            chunk.getId(),
            chunk.getDocument().getId(),
            chunk.getDocument().getFilename(),
            chunk.getContent(),
            cosineSimilarity(chunk.getEmbedding(), queryEmbedding),
            pageNumber(chunk.getMetadata())
        );
    }

    private List<SessionMessage> recentHistory(List<SessionMessage> messages) {
        int firstIncludedIndex = Math.max(0, messages.size() - MAX_HISTORY_MESSAGES);
        return messages.subList(firstIncludedIndex, messages.size());
    }

    private Document readyDocument(UUID documentId, UUID tenantId) {
        Document document = documentRepository.findByIdAndTenantIdAndDeletedAtIsNull(documentId, tenantId)
            .orElseThrow(() -> new NoSuchElementException("Document not found"));
        if (document.getStatus() != Document.Status.READY) {
            throw new IllegalArgumentException("Document is not ready for comparison");
        }
        return document;
    }

    private double cosineSimilarity(float[] source, float[] query) {
        if (source == null || query == null || source.length != query.length) {
            return 0.0;
        }
        double dotProduct = 0.0;
        double sourceMagnitude = 0.0;
        double queryMagnitude = 0.0;
        for (int index = 0; index < source.length; index++) {
            dotProduct += source[index] * query[index];
            sourceMagnitude += source[index] * source[index];
            queryMagnitude += query[index] * query[index];
        }
        if (sourceMagnitude == 0.0 || queryMagnitude == 0.0) {
            return 0.0;
        }
        return dotProduct / Math.sqrt(sourceMagnitude * queryMagnitude);
    }

    private Integer pageNumber(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(metadata).path("pageNumber").canConvertToInt()
                ? objectMapper.readTree(metadata).path("pageNumber").asInt()
                : null;
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

}
