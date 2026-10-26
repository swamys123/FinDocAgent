package com.findoc.repository;

import com.findoc.entity.Document;
import com.findoc.entity.Tenant;
import com.findoc.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class DocumentRepositoryTest {

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void onlyReturnsActiveDocumentsForRequestedTenant() {
        Tenant tenantA = tenantRepository.save(new Tenant("Tenant A"));
        Tenant tenantB = tenantRepository.save(new Tenant("Tenant B"));
        User userA = userRepository.save(new User(tenantA, "user-a", "a@findoc.local", "hash"));
        User userB = userRepository.save(new User(tenantB, "user-b", "b@findoc.local", "hash"));

        Document active = documentRepository.save(new Document(tenantA, userA, "active.pdf", "application/pdf"));
        documentRepository.save(new Document(tenantB, userB, "other.pdf", "application/pdf"));
        Document deleted = new Document(tenantA, userA, "deleted.pdf", "application/pdf");
        deleted.setDeletedAt(Instant.now());
        documentRepository.save(deleted);

        assertThat(documentRepository.findByTenantIdAndDeletedAtIsNull(tenantA.getId(), org.springframework.data.domain.Pageable.unpaged()))
            .extracting(Document::getId)
            .containsExactly(active.getId());
        assertThat(documentRepository.findByIdAndTenantIdAndDeletedAtIsNull(active.getId(), tenantB.getId()))
            .isEmpty();
        assertThat(documentRepository.findByIdAndTenantIdAndDeletedAtIsNull(deleted.getId(), tenantA.getId()))
            .isEmpty();
    }

    @Test
    void replayTransitionIsTenantScopedAndPreservesRetryCount() {
        Tenant tenant = tenantRepository.save(new Tenant("Replay tenant"));
        Tenant otherTenant = tenantRepository.save(new Tenant("Other replay tenant"));
        User owner = userRepository.save(new User(tenant, "replay-owner", "replay@example.test", "hash"));
        Document failed = documentRepository.saveAndFlush(new Document(tenant, owner, "failed.txt", "text/plain"));
        jdbcTemplate.update(
            "update documents set status = 'FAILED', error_msg = 'old failure', retry_count = 3 where id = ?",
            failed.getId());

        assertThat(documentRepository.queueFailedDocumentForReplay(failed.getId(), tenant.getId())).isEqualTo(1);
        assertThat(documentRepository.queueFailedDocumentForReplay(failed.getId(), tenant.getId())).isZero();
        assertThat(documentRepository.queueFailedDocumentForReplay(failed.getId(), otherTenant.getId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "select status from documents where id = ?", String.class, failed.getId())).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
            "select error_msg from documents where id = ?", String.class, failed.getId())).isNull();
        assertThat(jdbcTemplate.queryForObject(
            "select retry_count from documents where id = ?", Integer.class, failed.getId())).isEqualTo(3);
    }
}