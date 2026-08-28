package com.findoc.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentTest {

    @Test
    void allowsOnlyTheExpectedProcessingLifecycle() {
        Tenant tenant = new Tenant("Tenant");
        User user = new User(tenant, "user", "user@findoc.local", "hash");
        Document document = new Document(tenant, user, "report.pdf", "application/pdf");

        document.markProcessing();
        document.markReady();

        assertThat(document.getStatus()).isEqualTo(Document.Status.READY);
        assertThatThrownBy(document::markProcessing)
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void recordsFailureAndIncrementsRetryCount() {
        Tenant tenant = new Tenant("Tenant");
        User user = new User(tenant, "user", "user@findoc.local", "hash");
        Document document = new Document(tenant, user, "report.pdf", "application/pdf");

        document.markFailed("Unable to extract text");

        assertThat(document.getStatus()).isEqualTo(Document.Status.FAILED);
        assertThat(document.getErrorMessage()).isEqualTo("Unable to extract text");
        assertThat(document.getRetryCount()).isEqualTo(1);
    }

    @Test
    void returnsToPendingBetweenIngestionAttempts() {
        Tenant tenant = new Tenant("Tenant");
        User user = new User(tenant, "user", "user@findoc.local", "hash");
        Document document = new Document(tenant, user, "report.pdf", "application/pdf");

        document.markProcessing();
        document.recordFailure("temporary embedding failure", false);

        assertThat(document.getStatus()).isEqualTo(Document.Status.PENDING);
        assertThat(document.getRetryCount()).isEqualTo(1);
    }
}