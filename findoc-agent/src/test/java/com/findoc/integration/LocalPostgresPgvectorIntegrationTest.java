package com.findoc.integration;

import com.findoc.entity.Document;
import com.findoc.entity.DocumentChunk;
import com.findoc.entity.Tenant;
import com.findoc.entity.User;
import com.findoc.repository.DocumentChunkRepository;
import com.findoc.repository.DocumentRepository;
import com.findoc.repository.TenantRepository;
import com.findoc.repository.UserRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("local-integration")
@ActiveProfiles("local-pg")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class LocalPostgresPgvectorIntegrationTest {
    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentChunkRepository chunkRepository;

    @Test
    @Transactional
    void persistsAndRetrievesPgvectorDataWithinTenantScope() {
        Tenant firstTenant = tenantRepository.saveAndFlush(new Tenant("integration-" + UUID.randomUUID()));
        Tenant secondTenant = tenantRepository.saveAndFlush(new Tenant("integration-" + UUID.randomUUID()));
        User firstUser = userRepository.saveAndFlush(new User(firstTenant, "user-" + UUID.randomUUID(),
            "first@example.test", "test-password-hash"));
        User secondUser = userRepository.saveAndFlush(new User(secondTenant, "user-" + UUID.randomUUID(),
            "second@example.test", "test-password-hash"));

        Document firstDocument = documentRepository.saveAndFlush(
            new Document(firstTenant, firstUser, "first.txt", "text/plain"));
        Document secondDocument = documentRepository.saveAndFlush(
            new Document(secondTenant, secondUser, "second.txt", "text/plain"));

        float[] firstEmbedding = embeddingWithValue(1.0f);
        DocumentChunk firstChunk = new DocumentChunk(firstDocument, firstTenant, 0, "first tenant content");
        firstChunk.setEmbedding(firstEmbedding);
        chunkRepository.saveAndFlush(firstChunk);

        DocumentChunk secondChunk = new DocumentChunk(secondDocument, secondTenant, 0, "second tenant content");
        secondChunk.setEmbedding(embeddingWithValue(0.0f));
        chunkRepository.saveAndFlush(secondChunk);

        assertThat(chunkRepository.searchSimilar(firstEmbedding, firstTenant.getId(), 10))
            .extracting(DocumentChunk::getContent)
            .containsExactly("first tenant content");

        firstChunk.markDeleted();
        chunkRepository.saveAndFlush(firstChunk);

        assertThat(chunkRepository.searchSimilar(firstEmbedding, firstTenant.getId(), 10)).isEmpty();
    }

    private static float[] embeddingWithValue(float value) {
        float[] embedding = new float[768];
        embedding[0] = value;
        return embedding;
    }
}