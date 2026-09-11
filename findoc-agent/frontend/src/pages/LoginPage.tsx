import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import { ApiError } from '../api/client';

export function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [tenantId, setTenantId] = useState('00000000-0000-0000-0000-000000000001');
  const [username, setUsername] = useState('demo@findoc.local');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await login(tenantId, username, password);
      navigate('/documents');
    } catch (err) {
      setError(err instanceof ApiError ? 'Invalid tenant, username, or password.' : 'Unable to reach the server.');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-gray-50">
      <form onSubmit={handleSubmit} className="w-full max-w-sm rounded-lg bg-white p-8 shadow">
        <h1 className="mb-6 text-xl font-semibold text-gray-900">FinDoc Agent Login</h1>

        <label className="mb-1 block text-sm font-medium text-gray-700">Tenant ID</label>
        <input
          className="mb-4 w-full rounded border border-gray-300 px-3 py-2 text-sm"
          value={tenantId}
          onChange={(e) => setTenantId(e.target.value)}
          required
        />

        <label className="mb-1 block text-sm font-medium text-gray-700">Username</label>
        <input
          className="mb-4 w-full rounded border border-gray-300 px-3 py-2 text-sm"
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          required
        />

        <label className="mb-1 block text-sm font-medium text-gray-700">Password</label>
        <input
          type="password"
          className="mb-4 w-full rounded border border-gray-300 px-3 py-2 text-sm"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          required
        />

        {error && <p className="mb-4 text-sm text-red-600">{error}</p>}

        <button
          type="submit"
          disabled={submitting}
          className="w-full rounded bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700 disabled:opacity-50"
        >
          {submitting ? 'Signing in...' : 'Sign in'}
        </button>
      </form>
    </div>
  );
}
