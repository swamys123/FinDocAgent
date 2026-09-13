import type { AuthResponse } from '../types/api';
import { apiRequestJson } from './client';

export function login(tenantId: string, username: string, password: string): Promise<AuthResponse> {
  return apiRequestJson<AuthResponse>('/api/v1/auth/token', null, { tenantId, username, password });
}
