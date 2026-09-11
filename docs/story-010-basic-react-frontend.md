# Story 010: Basic React Frontend

## Completed work
- Added CORS configuration to `com.findoc.config.SecurityConfig` (new `CorsConfigurationSource` bean wired via `.cors(...)` into the security filter chain), configurable via `findoc.cors.allowed-origins` (defaults to `http://localhost:5173`).
- Scaffolded a Vite + React + TypeScript + Tailwind CSS v4 frontend under `findoc-agent/frontend/` (sibling to the Java `src/` tree, not mixed into it).
- Implemented a thin API client layer (`src/api/client.ts`, `auth.ts`, `documents.ts`, `agent.ts`) covering login, document upload/list/status/delete, and agent query.
- Implemented `AuthContext`/`useAuth` (token persisted in `localStorage`) and `SelectionContext`/`useSelection` (tracks selected document IDs for scoping queries), each context split into a dedicated non-component file to satisfy the `react-refresh` lint rule.
- Implemented `LoginPage` (tenantId/username/password form), `DocumentsPage` (upload control, document table, status polling every 3s for `PENDING`/`PROCESSING` documents until `READY`/`FAILED`, document selection checkboxes, delete action), and `QueryPage` (query textarea, renders answer/intent/confidence/sources/steps, carries `sessionId` across follow-up queries).
- Wired `App.tsx` with `react-router-dom` routes (`/login`, `/documents`, `/query`), a `ProtectedRoute` guard, and a shared `NavBar`.
- Added `.env.example` (`VITE_API_BASE_URL=http://localhost:8080`) and ignored `.env` in `findoc-agent/frontend/.gitignore`.

## Pending work
- No automated frontend tests (out of scope per agreed plan — manual verification only).
- No registration/user-management UI, session-history UI, document-comparison UI, or explain-trace UI (excluded from this basic-UI scope).
- Automated frontend tests remain optional follow-up work; the agreed basic UI scope is complete.

## Validation performed
- `npm run build` (`tsc -b && vite build`) succeeds with no errors.
- `npm run lint` (ESLint) passes with no errors.
- `./gradlew test --console=plain` passes after the `SecurityConfig` CORS change (no test failures).
- Local end-to-end validation completed successfully: login, document upload, ingestion status polling, and querying the uploaded document.

## Next implementation item
- Keep the frontend aligned with backend API changes; consider automated frontend tests as a future enhancement.
