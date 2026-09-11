import { useState, type FormEvent } from 'react';
import type { AgentResponse } from '../types/api';
import { queryAgent } from '../api/agent';
import { useAuth } from '../hooks/useAuth';
import { useSelection } from '../hooks/useSelection';

export function QueryPage() {
  const { token } = useAuth();
  const { selectedDocumentIds } = useSelection();
  const [query, setQuery] = useState('');
  const [sessionId, setSessionId] = useState<string | undefined>(undefined);
  const [response, setResponse] = useState<AgentResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (!token || !query.trim()) return;
    setLoading(true);
    setError(null);
    try {
      const result = await queryAgent(token, {
        query,
        documentIds: selectedDocumentIds.length > 0 ? selectedDocumentIds : undefined,
        sessionId,
      });
      setResponse(result);
      setSessionId(result.sessionId);
    } catch {
      setError('Query failed. Please try again.');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="mx-auto max-w-3xl p-6">
      <h1 className="mb-4 text-xl font-semibold text-gray-900">Query</h1>

      {selectedDocumentIds.length === 0 && (
        <p className="mb-4 text-sm text-gray-500">
          No documents selected — the query will search across all your ready documents.
        </p>
      )}
      {selectedDocumentIds.length > 0 && (
        <p className="mb-4 text-sm text-gray-500">Searching {selectedDocumentIds.length} selected document(s).</p>
      )}

      <form onSubmit={handleSubmit} className="mb-6 flex gap-2">
        <textarea
          className="flex-1 rounded border border-gray-300 px-3 py-2 text-sm"
          rows={3}
          placeholder="Ask a question about your documents..."
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          required
        />
        <button
          type="submit"
          disabled={loading}
          className="self-start rounded bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700 disabled:opacity-50"
        >
          {loading ? 'Asking...' : 'Ask'}
        </button>
      </form>

      {error && <p className="mb-4 text-sm text-red-600">{error}</p>}

      {response && (
        <div className="space-y-4">
          <div className="rounded-lg bg-white p-4 shadow">
            <div className="mb-2 flex items-center justify-between text-xs text-gray-500">
              <span>Intent: {response.intent}</span>
              <span>Confidence: {(response.confidence * 100).toFixed(0)}%</span>
            </div>
            <p className="whitespace-pre-wrap text-gray-900">{response.answer}</p>
          </div>

          {response.sources.length > 0 && (
            <div className="rounded-lg bg-white p-4 shadow">
              <h2 className="mb-2 text-sm font-semibold text-gray-700">Sources</h2>
              <ul className="space-y-2">
                {response.sources.map((source) => (
                  <li key={source.chunkId} className="rounded border border-gray-100 p-2 text-sm">
                    <div className="mb-1 flex items-center justify-between text-xs text-gray-500">
                      <span>
                        {source.filename}
                        {source.pageNumber != null ? ` (page ${source.pageNumber})` : ''}
                      </span>
                      <span>{(source.similarityScore * 100).toFixed(0)}% match</span>
                    </div>
                    <p className="text-gray-700">{source.content}</p>
                  </li>
                ))}
              </ul>
            </div>
          )}

          {response.stepsTaken.length > 0 && (
            <div className="rounded-lg bg-white p-4 shadow">
              <h2 className="mb-2 text-sm font-semibold text-gray-700">Steps Taken</h2>
              <ol className="list-decimal space-y-1 pl-5 text-sm text-gray-700">
                {response.stepsTaken.map((step, index) => (
                  <li key={index}>{step}</li>
                ))}
              </ol>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
