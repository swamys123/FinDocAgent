import type { AgentTraceResponse, QueryTraceSummary } from '../types/api';
import { apiRequest } from './client';

export function listRecentTraces(token: string): Promise<QueryTraceSummary[]> {
  return apiRequest<QueryTraceSummary[]>('/api/v1/agent/traces/recent', { token });
}

export function explainTrace(token: string, queryId: string): Promise<AgentTraceResponse> {
  return apiRequest<AgentTraceResponse>(`/api/v1/agent/explain/${queryId}`, { token });
}
