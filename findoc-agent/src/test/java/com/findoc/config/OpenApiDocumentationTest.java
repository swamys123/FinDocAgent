package com.findoc.config;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiDocumentationTest {
    @Test
    void openApiMetadataDefinesLocalServerAndBearerAuthentication() {
        OpenAPI openAPI = new OpenApiConfig().findocOpenAPI();

        assertThat(openAPI.getInfo().getTitle()).isEqualTo("FinDoc Agent API");
        assertThat(openAPI.getInfo().getVersion()).isEqualTo("1.0.0");
        assertThat(openAPI.getServers()).extracting("url").contains("http://localhost:8080");
        assertThat(openAPI.getComponents().getSecuritySchemes()).containsKey("bearer-jwt");
        assertThat(openAPI.getComponents().getSecuritySchemes().get("bearer-jwt").getScheme()).isEqualTo("bearer");
    }
}
