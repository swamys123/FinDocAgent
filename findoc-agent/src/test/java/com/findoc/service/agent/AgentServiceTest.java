package com.findoc.service.agent;

import com.findoc.dto.request.AgentQueryRequest;
import com.findoc.dto.response.AgentResponse;
import com.findoc.entity.DocumentChunk;
import com.findoc.entity.Tenant;
import com.findoc.entity.User;
import com.findoc.repository.AgentSessionRepository;
import com.findoc.repository.DocumentChunkRepository;
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
    private final EmbeddingService embeddingService = mock(EmbeddingService.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AgentSessionRepository sessionRepository = mock(AgentSessionRepository.class);
    private final SessionMessageRepository messageRepository = mock(SessionMessageRepository.class);
    private final QueryTraceRepository traceRepository = mock(QueryTraceRepository.class);
    private final OpenRouterGenerationService generationService = mock(OpenRouterGenerationService.class);

    private final AgentService service = new AgentService(
        chunkRepository,
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
    void generatesAnswerAndPersistsSessionTrace() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Tenant tenant = new Tenant("Demo");
        User user = new User(tenant, "demo@findoc.local", "demo@findoc.local", "hash");
        TenantContext.set(tenantId, userId);

        DocumentChunk chunk = mock(DocumentChunk.class);
        when(chunk.getContent()).thenReturn("The merger creates quarterly compliance obligations.");
        when(embeddingService.embed(any(String.class))).thenReturn(new float[768]);
        when(chunkRepository.searchSimilar(any(PGvector.class), any(UUID.class), any(Integer.class))).thenReturn(List.of(chunk));
        when(userRepository.findByIdAndTenantIdAndDeletedAtIsNull(userId, tenantId)).thenReturn(java.util.Optional.of(user));
        when(sessionRepository.save(any())).thenAnswer(invocation -> {
            Object value = invocation.getArgument(0);
            Field idField = value.getClass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(value, UUID.randomUUID());
            return value;
        });
        when(generationService.generate(any(String.class), any(String.class), any(List.class))).thenReturn("This document requires quarterly compliance review.");

        AgentResponse response = service.query(new AgentQueryRequest("Summarise the compliance obligations", List.of(), null));

        assertThat(response.answer()).isEqualTo("This document requires quarterly compliance review.");
        assertThat(response.sessionId()).isNotNull();
        assertThat(response.intent()).isEqualTo("SUMMARISE");
        verify(messageRepository, org.mockito.Mockito.times(2)).save(any());
        verify(traceRepository).save(any());
    }
}
