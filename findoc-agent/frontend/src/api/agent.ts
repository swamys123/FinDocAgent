import type { AgentResponse } from '../types/api';
import { apiRequestJson } from './client';

export interface AgentQueryRequest {
  query: string;
  documentIds?: string[];
  sessionId?: string;
}

export function queryAgent(token: string, request: AgentQueryRequest): Promise<AgentResponse> {
  return apiRequestJson<AgentResponse>('/api/v1/agent/query', token, request);
}
