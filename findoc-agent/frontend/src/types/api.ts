export interface AuthResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  tenantId: string;
}

export type DocumentStatus = 'PENDING' | 'PROCESSING' | 'READY' | 'FAILED';

export interface DocumentResponse {
  documentId: string;
  filename: string;
  fileType: string;
  status: DocumentStatus;
  chunkCount: number;
  createdAt: string;
}

export interface AgentSourceResponse {
  chunkId: string;
  documentId: string;
  filename: string;
  content: string;
  similarityScore: number;
  pageNumber: number | null;
}

export interface AgentResponse {
  queryId: string;
  sessionId: string;
  answer: string;
  intent: string;
  sources: AgentSourceResponse[];
  stepsTaken: string[];
  confidence: number;
}
