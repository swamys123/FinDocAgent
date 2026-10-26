# FinDocAgent Frontend (React + TypeScript)

The frontend supports login, document upload and status tracking, document selection, session-aware cited queries, and recent-query trace explanations. Session-history browsing and document comparison are available through the backend API but do not have frontend screens.

For the full-stack setup, see the root [Quick Start](../../README.md#quick-start-run-everything-with-docker-compose).

## Run Locally

```bash
npm ci
npm run dev
```

The development server listens on `http://localhost:5173`. The frontend expects the backend at `http://localhost:8080` by default; set `VITE_API_BASE_URL` in a local `.env` file if the backend uses another address.

## Validate

```bash
npm test
npm run build
npm run lint
```

`npm test` runs the Vitest component tests for login and recent-query trace flows. See the [backend README](../README.md) for backend setup and the root [Quick Start](../../README.md#quick-start-run-everything-with-docker-compose) for the full-stack Docker Compose setup.
