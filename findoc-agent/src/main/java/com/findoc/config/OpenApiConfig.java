package com.findoc.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI findocOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("FinDoc Agent API")
                .version("1.0.0")
                .description("Tenant-aware document intelligence and agent query API."))
            .addServersItem(new Server().url("http://localhost:8080").description("Local development"))
            .components(new Components()
                .addSecuritySchemes("bearer-jwt", new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("JWT issued by POST /api/v1/auth/token.")));
    }
}
