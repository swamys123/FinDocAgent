# Story 014: Recent Traces Listing UI

## Status

Complete

## Completed Work

- Added `QueryTraceRepository.findTop5BySession_User_IdAndTenant_IdOrderByCreatedAtDesc` to fetch a user's 5 most recent query traces, scoped by tenant and user.
- Added `QueryTraceSummaryResponse` DTO (`queryId`, `query`, `intent`, `durationMs`, `createdAt`).
- Added `AgentService.recentTraces()` reading tenant/user from `TenantContext`.
- Added `GET /api/v1/agent/traces/recent` endpoint on `AgentController`, returning the caller's recent trace summaries (JWT-scoped, no path params).
- Added service and controller test coverage: summaries mapping, tenant/user-scoped ordering, empty list, and anonymous-request rejection (403).
- Added frontend types (`QueryTraceSummary`, `AgentTraceStep`, `AgentTraceResponse`) mirroring the backend DTOs.
- Added `src/api/traces.ts` with `listRecentTraces()` and `explainTrace()` using the existing `apiRequest` client pattern.
- Added `TracesPage.tsx`: lists the last 5 queries as cards (query text, intent, duration, timestamp); clicking a row lazily fetches and expands an inline panel rendering each `fullTrace` step (tool, duration, pretty-printed input/output JSON), with per-row loading/error state and result caching.
- Wired the `/traces` route (protected, `AppLayout`) into `App.tsx` and added a "Traces" link to `NavBar.tsx`.
- Added recruiter-facing README documentation and screenshots for the second query, recent-query listing, and backend execution trace.

## Pending Work

- No configurable limit/pagination beyond the fixed last-5 window (explicitly out of scope for this story).
- No automated frontend tests were added — the frontend has no existing test runner configured; introducing one was out of scope for this change.

## Validation Performed

- `./gradlew test --tests com.findoc.repository.* --tests com.findoc.service.agent.AgentServiceTest --tests com.findoc.controller.AgentControllerTest --console=plain` passed.
- `npm run build` (tsc + vite build) in `findoc-agent/frontend` passed with no type errors.

## Next Implementation Item

Run the full unit suite and manually validate `/traces` end-to-end against the live PostgreSQL/Kafka stack (generate several queries via `/api/v1/agent/query`, confirm listing order/limit/tenant isolation, and confirm the expandable explain panel renders correctly in the browser).
