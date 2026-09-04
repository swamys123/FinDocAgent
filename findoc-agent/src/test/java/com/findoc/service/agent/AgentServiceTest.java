package com.findoc.service.agent;

import com.findoc.dto.request.AgentQueryRequest;
import com.findoc.dto.request.DocumentComparisonRequest;
import com.findoc.dto.response.AgentResponse;
import com.findoc.dto.response.DocumentComparisonResponse;
import com.findoc.entity.AgentSession;
import com.findoc.entity.Document;
import com.findoc.entity.DocumentChunk;
import com.findoc.entity.QueryTrace;
import com.findoc.entity.Tenant;
import com.findoc.entity.User;
import com.findoc.repository.AgentSessionRepository;
import com.findoc.repository.DocumentChunkRepository;
import com.findoc.repository.DocumentRepository;
import com.findoc.repository.QueryTraceRepository;
import com.findoc.repository.SessionMessageRepository;
import com.findoc.repository.UserRepository;
import com.findoc.service.embedding.EmbeddingService;
import com.findoc.util.TenantContext;
import com.pgvector.PGvector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentServiceTest {
    private final DocumentChunkRepository chunkRepository = mock(DocumentChunkRepository.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final EmbeddingService embeddingService = mock(EmbeddingService.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AgentSessionRepository sessionRepository = mock(AgentSessionRepository.class);
    private final SessionMessageRepository messageRepository = mock(SessionMessageRepository.class);
    private final QueryTraceRepository traceRepository = mock(QueryTraceRepository.class);
    private final OpenRouterGenerationService generationService = mock(OpenRouterGenerationService.class);

    private final AgentService service = new AgentService(
        chunkRepository,
        documentRepository,
        embeddingService,
        userRepository,
        sessionRepository,
        messageRepository,
        traceRepository,
        generationService,
        5,
        5
    );

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void generatesAnswerAndPersistsSessionTrace() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Tenant tenant = new Tenant("Demo");
        User user = new User(tenant, "demo@findoc.local", "demo@findoc.local", "hash");
        TenantContext.set(tenantId, userId);

        Document document = new Document(tenant, user, "compliance.txt", "text/plain");
        setId(document, UUID.randomUUID());
        DocumentChunk chunk = new DocumentChunk(document, tenant, 0, "The merger creates quarterly compliance obligations.");
        setId(chunk, UUID.randomUUID());
        chunk.setEmbedding(new float[768]);
        chunk.setMetadata("{\"pageNumber\": 1}");
        when(embeddingService.embed(any(String.class))).thenReturn(new float[768]);
        when(chunkRepository.searchSimilar(any(PGvector.class), any(UUID.class), any(Integer.class))).thenReturn(List.of(chunk));
        when(userRepository.findByIdAndTenantIdAndDeletedAtIsNull(userId, tenantId)).thenReturn(java.util.Optional.of(user));
        when(sessionRepository.save(any())).thenAnswer(invocation -> {
            Object value = invocation.getArgument(0);
            setId(value, UUID.randomUUID());
            return value;
        });
        when(traceRepository.save(any(QueryTrace.class))).thenAnswer(invocation -> {
            QueryTrace trace = invocation.getArgument(0);
            setId(trace, UUID.randomUUID());
            return trace;
        });
        when(generationService.generate(any(String.class), any(String.class), any(List.class))).thenReturn("This document requires quarterly compliance review.");

        AgentResponse response = service.query(new AgentQueryRequest("Summarise the compliance obligations", List.of(), null));

        assertThat(response.answer()).isEqualTo("This document requires quarterly compliance review.");
        assertThat(response.sessionId()).isNotNull();
        assertThat(response.intent()).isEqualTo("SUMMARISE");
        assertThat(response.queryId()).isNotNull();
        assertThat(response.sources()).singleElement().satisfies(source -> {
            assertThat(source.chunkId()).isEqualTo(chunk.getId());
            assertThat(source.documentId()).isEqualTo(document.getId());
            assertThat(source.filename()).isEqualTo("compliance.txt");
            assertThat(source.pageNumber()).isEqualTo(1);
        });
        verify(messageRepository, org.mockito.Mockito.times(2)).save(any());
        verify(traceRepository).save(any());
    }

    @Test
    void comparesTenantOwnedReadyDocumentsIndependently() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Tenant tenant = new Tenant("Demo");
        User user = new User(tenant, "demo@findoc.local", "demo@findoc.local", "hash");
        Document documentA = new Document(tenant, user, "first.txt", "text/plain");
        Document documentB = new Document(tenant, user, "second.txt", "text/plain");
        setId(documentA, UUID.randomUUID());
        setId(documentB, UUID.randomUUID());
        documentA.setStatus(Document.Status.READY);
        documentB.setStatus(Document.Status.READY);
        DocumentChunk chunkA = new DocumentChunk(documentA, tenant, 0, "Document A permits termination with notice.");
        DocumentChunk chunkB = new DocumentChunk(documentB, tenant, 0, "Document B requires a longer notice period.");
        setId(chunkA, UUID.randomUUID());
        setId(chunkB, UUID.randomUUID());
        chunkA.setEmbedding(new float[768]);
        chunkB.setEmbedding(new float[768]);
        TenantContext.set(tenantId, userId);
        when(documentRepository.findByIdAndTenantIdAndDeletedAtIsNull(documentA.getId(), tenantId)).thenReturn(java.util.Optional.of(documentA));
        when(documentRepository.findByIdAndTenantIdAndDeletedAtIsNull(documentB.getId(), tenantId)).thenReturn(java.util.Optional.of(documentB));
        when(userRepository.findByIdAndTenantIdAndDeletedAtIsNull(userId, tenantId)).thenReturn(java.util.Optional.of(user));
        when(embeddingService.embed("termination clauses")).thenReturn(new float[768]);
        when(chunkRepository.searchSimilarInDocuments(any(PGvector.class), any(UUID.class), any(List.class), any(Integer.class)))
            .thenReturn(List.of(chunkA), List.of(chunkB));
        when(sessionRepository.save(any())).thenAnswer(invocation -> {
            AgentSession session = invocation.getArgument(0);
            setId(session, UUID.randomUUID());
            return session;
        });
        when(traceRepository.save(any(QueryTrace.class))).thenAnswer(invocation -> {
            QueryTrace trace = invocation.getArgument(0);
            setId(trace, UUID.randomUUID());
            return trace;
        });
        when(generationService.compare(any(String.class), any(List.class), any(List.class)))
            .thenReturn(new OpenRouterGenerationService.ComparisonGeneration(
                "The notice periods differ.",
                List.of("Both documents require notice."),
                List.of("Document B requires longer notice.")
            ));

        DocumentComparisonResponse response = service.compare(new DocumentComparisonRequest(documentA.getId(), documentB.getId(), "termination clauses"));

        assertThat(response.queryId()).isNotNull();
        assertThat(response.summary()).isEqualTo("The notice periods differ.");
        assertThat(response.similarities()).containsExactly("Both documents require notice.");
        assertThat(response.differences()).containsExactly("Document B requires longer notice.");
        assertThat(response.documentASources()).singleElement().extracting(source -> source.documentId()).isEqualTo(documentA.getId());
        assertThat(response.documentBSources()).singleElement().extracting(source -> source.documentId()).isEqualTo(documentB.getId());
        verify(chunkRepository, org.mockito.Mockito.times(2)).searchSimilarInDocuments(any(PGvector.class), any(UUID.class), any(List.class), any(Integer.class));
    }

    private void setId(Object entity, UUID id) throws Exception {
        Field idField = entity.getClass().getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(entity, id);
    }
}
