import type { DocumentResponse } from '../types/api';
import { apiRequest } from './client';

export function listDocuments(token: string): Promise<DocumentResponse[]> {
  return apiRequest<DocumentResponse[]>('/api/v1/documents', { token });
}

export function getDocumentStatus(token: string, documentId: string): Promise<DocumentResponse> {
  return apiRequest<DocumentResponse>(`/api/v1/documents/${documentId}/status`, { token });
}

export function uploadDocument(token: string, file: File): Promise<DocumentResponse> {
  const formData = new FormData();
  formData.append('file', file);
  return apiRequest<DocumentResponse>('/api/v1/documents/upload', {
    method: 'POST',
    token,
    body: formData,
  });
}

export function deleteDocument(token: string, documentId: string): Promise<void> {
  return apiRequest<void>(`/api/v1/documents/${documentId}`, { method: 'DELETE', token });
}
