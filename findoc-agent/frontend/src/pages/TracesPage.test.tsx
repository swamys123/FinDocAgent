import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { explainTrace, listRecentTraces } from '../api/traces';
import { AuthContext, type AuthContextValue } from '../context/auth-context';
import type { AgentTraceResponse, QueryTraceSummary } from '../types/api';
import { TracesPage } from './TracesPage';

vi.mock('../api/traces', () => ({
  explainTrace: vi.fn(),
  listRecentTraces: vi.fn(),
}));

const trace: QueryTraceSummary = {
  queryId: 'query-1',
  query: 'Compare quarterly revenue',
  intent: 'comparison',
  durationMs: 125,
  createdAt: '2026-01-01T00:00:00Z',
};

const traceDetail: AgentTraceResponse = {
  queryId: trace.queryId,
  query: trace.query,
  intent: trace.intent,
  fullTrace: [
    {
      step: 1,
      tool: 'search_documents',
      input: { query: trace.query },
      output: { results: 2 },
      durationMs: 40,
    },
  ],
  totalDurationMs: 40,
};

function renderTraces(token: string | null = 'test-token') {
  const authValue: AuthContextValue = {
    token,
    tenantId: 'tenant-1',
    username: 'user@example.com',
    login: vi.fn(),
    logout: vi.fn(),
  };

  render(
    <AuthContext.Provider value={authValue}>
      <TracesPage />
    </AuthContext.Provider>,
  );
}

describe('TracesPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows a loading state and then recent traces', async () => {
    let resolveTraces!: (traces: QueryTraceSummary[]) => void;
    vi.mocked(listRecentTraces).mockReturnValue(
      new Promise((resolve) => {
        resolveTraces = resolve;
      }),
    );

    renderTraces();
    expect(screen.getByText('Loading recent queries...')).toBeInTheDocument();

    resolveTraces([trace]);
    expect(await screen.findByText(trace.query)).toBeInTheDocument();
  });

  it('shows the empty state when there are no traces', async () => {
    vi.mocked(listRecentTraces).mockResolvedValue([]);
    renderTraces();

    expect(await screen.findByText(/No queries yet/)).toBeInTheDocument();
  });

  it('shows an error when recent traces fail to load', async () => {
    vi.mocked(listRecentTraces).mockRejectedValue(new Error('Request failed'));
    renderTraces();

    expect(await screen.findByText('Failed to load recent queries. Please try again.')).toBeInTheDocument();
  });

  it('loads and displays trace details when a query is expanded', async () => {
    vi.mocked(listRecentTraces).mockResolvedValue([trace]);
    vi.mocked(explainTrace).mockResolvedValue(traceDetail);
    renderTraces();

    await screen.findByText(trace.query);
    fireEvent.click(screen.getByRole('button', { name: /Explain/ }));

    expect(await screen.findByText('Step 1: search_documents')).toBeInTheDocument();
    expect(explainTrace).toHaveBeenCalledWith('test-token', trace.queryId);
  });

  it('shows an error when trace details fail to load', async () => {
    vi.mocked(listRecentTraces).mockResolvedValue([trace]);
    vi.mocked(explainTrace).mockRejectedValue(new Error('Request failed'));
    renderTraces();

    await screen.findByText(trace.query);
    fireEvent.click(screen.getByRole('button', { name: /Explain/ }));

    expect(await screen.findByText('Failed to load trace details. Please try again.')).toBeInTheDocument();
  });
});
