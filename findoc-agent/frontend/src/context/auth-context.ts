import { createContext } from 'react';

export interface AuthState {
  token: string | null;
  tenantId: string | null;
  username: string | null;
}

export interface AuthContextValue extends AuthState {
  login: (tenantId: string, username: string, password: string) => Promise<void>;
  logout: () => void;
}

export const AuthContext = createContext<AuthContextValue | undefined>(undefined);
