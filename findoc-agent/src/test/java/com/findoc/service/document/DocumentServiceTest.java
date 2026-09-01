package com.findoc.service.document;

import com.findoc.entity.Document;
import com.findoc.entity.DocumentSource;
import com.findoc.entity.Tenant;
import com.findoc.entity.User;
import com.findoc.messaging.IngestionProducer;
import com.findoc.repository.DocumentChunkRepository;
import com.findoc.repository.DocumentRepository;
import com.findoc.repository.DocumentSourceRepository;
import com.findoc.repository.UserRepository;
import com.findoc.util.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;
q

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentServiceTest {

    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final DocumentChunkRepository documentChunkRepository = mock(DocumentChunkRepository.class);
    private final DocumentSourceRepository documentSourceRepository = mock(DocumentSourceRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final IngestionProducer ingestionProducer = mock(IngestionProducer.class);
    private final DocumentService service = new DocumentService(
        documentRepository,
        documentChunkRepository,
        documentSourceRepository,
        userRepository,
        ingestionProducer
    );

    @Test
    void returnsOriginalBytesForTenantDocument() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        Tenant tenant = mock(Tenant.class);
        User user = mock(User.class);
        Document document = new Document(tenant, user, "report.pdf", "application/pdf");
        byte[] content = new byte[] {1, 2, 3, 4};
        ReflectionTestUtils.setField(document, "id", documentId);

        TenantContext.set(tenantId, userId);
        when(documentRepository.findByIdAndTenantIdAndDeletedAtIsNull(documentId, tenantId)).thenReturn(Optional.of(document));
        when(documentSourceRepository.findByDocumentIdAndTenantId(documentId, tenantId)).thenReturn(Optional.of(new DocumentSource(document, tenant, content)));

        assertThat(service.download(documentId)).isEqualTo(content);
    }

    @Test
    void rejectsMissingSourceForTenantDocument() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        Tenant tenant = mock(Tenant.class);
        User user = mock(User.class);
        Document document = new Document(tenant, user, "report.pdf", "application/pdf");
        ReflectionTestUtils.setField(document, "id", documentId);

        TenantContext.set(tenantId, userId);
        when(documentRepository.findByIdAndTenantIdAndDeletedAtIsNull(documentId, tenantId)).thenReturn(Optional.of(document));
        when(documentSourceRepository.findByDocumentIdAndTenantId(documentId, tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.download(documentId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Document source not found");
    }
}
