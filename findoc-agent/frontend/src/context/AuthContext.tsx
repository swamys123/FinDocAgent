import { useMemo, useState, type ReactNode } from 'react';
import { login as loginRequest } from '../api/auth';
import { AuthContext, type AuthContextValue, type AuthState } from './auth-context';

const STORAGE_KEY = 'findoc-auth';

function loadStoredAuth(): AuthState {
  const raw = localStorage.getItem(STORAGE_KEY);
  if (!raw) {
    return { token: null, tenantId: null, username: null };
  }
  try {
    return JSON.parse(raw) as AuthState;
  } catch {
    return { token: null, tenantId: null, username: null };
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>(loadStoredAuth);

  const value = useMemo<AuthContextValue>(
    () => ({
      ...state,
      login: async (tenantId, username, password) => {
        const response = await loginRequest(tenantId, username, password);
        const next: AuthState = { token: response.accessToken, tenantId: response.tenantId, username };
        localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
        setState(next);
      },
      logout: () => {
        localStorage.removeItem(STORAGE_KEY);
        setState({ token: null, tenantId: null, username: null });
      },
    }),
    [state],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
