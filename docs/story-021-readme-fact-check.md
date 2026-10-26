# Story 021: README factual consistency review

## Completed work

- Audited numeric and behavioral claims in the root README and developer guide against source, configuration, tests, Compose settings, and the checked-in service-agreement PDF.
- Corrected documentation to describe chunks as up to 512 whitespace-delimited terms with 50-term overlap (not model-tokenized) and clarified that PDF page counts are stored but per-chunk page numbers are not currently assigned.
- Updated the frontend description for the recent-query and expandable trace screens, and documented API-only session history/comparison plus the tenant-admin DLQ replay endpoint.
- Documented optional tenant-scoped idempotency keys for upload, query, and comparison, with their 255-character maximum, 24-hour expiry, 422 fingerprint conflict, and replay header.
- Aligned implementation-status priorities with pending Audit 3/Audit 4 work and marked validation results as historical rather than freshly run.
- Corrected or qualified sample response values and operational claims, including the Compose/local port mappings and which test tasks require Docker.

## Pending work

- Audit 3 DLQ publish safety, recovery, provisioning, failure-reason visibility, and observability.
- Audit 4 content-hash deduplication and frontend idempotency-key/retry handling.
- Full correctness-suite execution in a Docker-enabled environment.

## Validation performed

- Checked implementation values against `application.yml`, `.env.example`, Compose, controllers, services, repositories, tests, and frontend routes.
- Verified the seeded demo password against its stored BCrypt hash.
- Confirmed the SLA values described in the README against `sample-service-agreement.pdf`.
- Reviewed documentation changes with `git diff --check`; application tests were not rerun for this documentation-only change.

## Next implementation item

Continue with the pending Audit 3 DLQ delivery-safety and observability findings.
