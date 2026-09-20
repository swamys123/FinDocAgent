import { useEffect, useState } from 'react';
import { explainTrace, listRecentTraces } from '../api/traces';
import { useAuth } from '../hooks/useAuth';
import type { AgentTraceResponse, QueryTraceSummary } from '../types/api';

export function TracesPage() {
  const { token } = useAuth();
  const [traces, setTraces] = useState<QueryTraceSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [expandedQueryId, setExpandedQueryId] = useState<string | null>(null);
  const [traceDetails, setTraceDetails] = useState<Record<string, AgentTraceResponse>>({});
  const [detailLoadingId, setDetailLoadingId] = useState<string | null>(null);
  const [detailError, setDetailError] = useState<string | null>(null);

  useEffect(() => {
    if (!token) return;
    setLoading(true);
    setError(null);
    listRecentTraces(token)
      .then(setTraces)
      .catch(() => setError('Failed to load recent queries. Please try again.'))
      .finally(() => setLoading(false));
  }, [token]);

  async function toggleExpand(queryId: string) {
    if (expandedQueryId === queryId) {
      setExpandedQueryId(null);
      return;
    }
    setExpandedQueryId(queryId);
    if (!token || traceDetails[queryId]) return;

    setDetailError(null);
    setDetailLoadingId(queryId);
    try {
      const detail = await explainTrace(token, queryId);
      setTraceDetails((prev) => ({ ...prev, [queryId]: detail }));
    } catch {
      setDetailError('Failed to load trace details. Please try again.');
    } finally {
      setDetailLoadingId(null);
    }
  }

  return (
    <div className="mx-auto max-w-3xl p-6">
      <h1 className="mb-4 text-xl font-semibold text-gray-900">Recent Queries</h1>

      {loading && <p className="text-sm text-gray-500">Loading recent queries...</p>}
      {error && <p className="mb-4 text-sm text-red-600">{error}</p>}
      {!loading && !error && traces.length === 0 && (
        <p className="text-sm text-gray-500">No queries yet — ask a question on the Query page to see it here.</p>
      )}

      <div className="space-y-3">
        {traces.map((trace) => {
          const isExpanded = expandedQueryId === trace.queryId;
          const detail = traceDetails[trace.queryId];
          return (
            <div key={trace.queryId} className="rounded-lg bg-white shadow">
              <button
                type="button"
                onClick={() => toggleExpand(trace.queryId)}
                className="flex w-full items-start justify-between gap-4 p-4 text-left"
              >
                <div>
                  <p className="text-sm font-medium text-gray-900">{trace.query}</p>
                  <p className="mt-1 text-xs text-gray-500">
                    {trace.intent} &middot; {trace.durationMs}ms &middot; {new Date(trace.createdAt).toLocaleString()}
                  </p>
                </div>
                <span className="text-xs text-indigo-600">{isExpanded ? 'Hide' : 'Explain'}</span>
              </button>

              {isExpanded && (
                <div className="border-t border-gray-100 p-4">
                  {detailLoadingId === trace.queryId && <p className="text-sm text-gray-500">Loading trace...</p>}
                  {detailError && detailLoadingId !== trace.queryId && !detail && (
                    <p className="text-sm text-red-600">{detailError}</p>
                  )}
                  {detail && (
                    <ol className="space-y-3">
                      {detail.fullTrace.map((step) => (
                        <li key={step.step} className="rounded border border-gray-100 p-3 text-sm">
                          <div className="mb-2 flex items-center justify-between text-xs text-gray-500">
                            <span className="font-medium text-gray-700">
                              Step {step.step}: {step.tool}
                            </span>
                            <span>{step.durationMs}ms</span>
                          </div>
                          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
                            <div>
                              <p className="mb-1 text-xs font-medium text-gray-500">Input</p>
                              <pre className="overflow-x-auto rounded bg-gray-50 p-2 text-xs text-gray-700">
                                {JSON.stringify(step.input, null, 2)}
                              </pre>
                            </div>
                            <div>
                              <p className="mb-1 text-xs font-medium text-gray-500">Output</p>
                              <pre className="overflow-x-auto rounded bg-gray-50 p-2 text-xs text-gray-700">
                                {JSON.stringify(step.output, null, 2)}
                              </pre>
                            </div>
                          </div>
                        </li>
                      ))}
                    </ol>
                  )}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
