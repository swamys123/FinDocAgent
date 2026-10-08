# Story 015: README operational source of truth

## Status

Complete

## Completed Work

- Established `findoc-agent/README.md` as the single source of truth for operational setup, configuration, endpoint security, API documentation, and validation instructions.
- Updated the root README to focus on product positioning and link to the developer runbook instead of duplicating operational details.
- Surfaced generated OpenAPI/Swagger support and the five-source retrieval default in the root feature summary.
- Aligned the documented public routes and the `test`, `localIntegrationTest`, and `integrationTest` tasks with Spring Security and Gradle configuration.

## Pending Work

- Keep the developer runbook aligned with future runtime, configuration, and validation changes.

## Validation Performed

- Checked the public route matchers against `SecurityConfig` and Gradle task names/configuration against `build.gradle`.
- Confirmed the root README links directly to the module README and removed its duplicated setup, configuration, endpoint, test-command, and demo-account sections.
- No application tests were run; this change only modifies documentation.

## Next Implementation Item

Add operational circuit-breaker metrics if required by deployment consumers; continue multi-tenant load validation.