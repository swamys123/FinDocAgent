package com.findoc.service.idempotency;

import com.findoc.dto.request.AgentQueryRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequestFingerprintTest {
    private final RequestFingerprint fingerprint = new RequestFingerprint(new ObjectMapper());

    @Test
    void jsonFingerprintIncludesEndpointAndRequestFields() {
        AgentQueryRequest request = new AgentQueryRequest("revenue", List.of(), null);

        assertThat(fingerprint.json("POST /api/v1/agent/query", request))
            .isEqualTo(fingerprint.json("POST /api/v1/agent/query", request));
        assertThat(fingerprint.json("POST /api/v1/agent/query", request))
            .isNotEqualTo(fingerprint.json("POST /api/v1/agent/compare", request));
        assertThat(fingerprint.json("POST /api/v1/agent/query", request))
            .isNotEqualTo(fingerprint.json("POST /api/v1/agent/query",
                new AgentQueryRequest("profit", List.of(), null)));
    }

    @Test
    void uploadFingerprintIncludesFileMetadataAndContents() {
        String endpoint = "POST /api/v1/documents/upload";

        String original = fingerprint.upload(endpoint, "report.txt", "text/plain", "content".getBytes());

        assertThat(fingerprint.upload(endpoint, "report.txt", "text/plain", "content".getBytes())).isEqualTo(original);
        assertThat(fingerprint.upload(endpoint, "other.txt", "text/plain", "content".getBytes())).isNotEqualTo(original);
        assertThat(fingerprint.upload(endpoint, "report.txt", "text/plain", "changed".getBytes())).isNotEqualTo(original);
        assertThat(fingerprint.upload(endpoint, "report.txt", "application/pdf", "content".getBytes())).isNotEqualTo(original);
    }
}
