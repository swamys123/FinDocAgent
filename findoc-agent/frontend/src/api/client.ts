const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

interface RequestOptions {
  method?: string;
  body?: BodyInit;
  headers?: Record<string, string>;
  token?: string | null;
}

// Thin fetch wrapper: builds the URL, attaches the bearer token, and normalizes error responses.
export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { ...options.headers };
  if (options.token) {
    headers.Authorization = `Bearer ${options.token}`;
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    method: options.method ?? 'GET',
    body: options.body,
    headers,
  });

  if (!response.ok) {
    const text = await response.text().catch(() => '');
    throw new ApiError(response.status, text || `Request failed with status ${response.status}`);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return (await response.json()) as T;
}

export function apiRequestJson<T>(path: string, token: string | null, body: unknown, method = 'POST'): Promise<T> {
  return apiRequest<T>(path, {
    method,
    token,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
}
