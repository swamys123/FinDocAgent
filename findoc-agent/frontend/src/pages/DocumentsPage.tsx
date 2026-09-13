import { useCallback, useEffect, useRef, useState, type ChangeEvent } from 'react';
import type { DocumentResponse } from '../types/api';
import { deleteDocument, getDocumentStatus, listDocuments, uploadDocument } from '../api/documents';
import { useAuth } from '../hooks/useAuth';
import { useSelection } from '../hooks/useSelection';

const POLL_INTERVAL_MS = 3000;
const ACTIVE_STATUSES = new Set(['PENDING', 'PROCESSING']);

export function DocumentsPage() {
  const { token } = useAuth();
  const { selectedDocumentIds, toggleDocument } = useSelection();
  const [documents, setDocuments] = useState<DocumentResponse[]>([]);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const refreshDocuments = useCallback(async () => {
    if (!token) return;
    const docs = await listDocuments(token);
    setDocuments(docs);
  }, [token]);

  useEffect(() => {
    let cancelled = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- standard fetch-on-mount; state is set only in the async callback
    refreshDocuments()
      .catch(() => {
        if (!cancelled) setError('Failed to load documents.');
      });
    return () => {
      cancelled = true;
    };
  }, [refreshDocuments]);

  // Poll only documents still ingesting until they reach a terminal status.
  useEffect(() => {
    if (!token) return;
    const pending = documents.filter((doc) => ACTIVE_STATUSES.has(doc.status));
    if (pending.length === 0) return;

    const interval = setInterval(async () => {
      const updates = await Promise.all(pending.map((doc) => getDocumentStatus(token, doc.documentId)));
      setDocuments((prev) =>
        prev.map((doc) => updates.find((update) => update.documentId === doc.documentId) ?? doc),
      );
    }, POLL_INTERVAL_MS);

    return () => clearInterval(interval);
  }, [documents, token]);

  async function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (!file || !token) return;
    setUploading(true);
    setError(null);
    try {
      await uploadDocument(token, file);
      await refreshDocuments();
    } catch {
      setError('Upload failed. Please try again.');
    } finally {
      setUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  }

  async function handleDelete(documentId: string) {
    if (!token) return;
    await deleteDocument(token, documentId);
    await refreshDocuments();
  }

  return (
    <div className="mx-auto max-w-4xl p-6">
      <h1 className="mb-4 text-xl font-semibold text-gray-900">Documents</h1>

      <div className="mb-6 flex items-center gap-3">
        <input ref={fileInputRef} type="file" onChange={handleFileChange} disabled={uploading} className="text-sm" />
        {uploading && <span className="text-sm text-gray-500">Uploading...</span>}
      </div>

      {error && <p className="mb-4 text-sm text-red-600">{error}</p>}

      <table className="w-full border-collapse overflow-hidden rounded-lg bg-white text-sm shadow">
        <thead className="bg-gray-100 text-left text-gray-600">
          <tr>
            <th className="px-4 py-2">Select</th>
            <th className="px-4 py-2">Filename</th>
            <th className="px-4 py-2">Status</th>
            <th className="px-4 py-2">Chunks</th>
            <th className="px-4 py-2">Uploaded</th>
            <th className="px-4 py-2"></th>
          </tr>
        </thead>
        <tbody>
          {documents.map((doc) => (
            <tr key={doc.documentId} className="border-t border-gray-100">
              <td className="px-4 py-2">
                <input
                  type="checkbox"
                  checked={selectedDocumentIds.includes(doc.documentId)}
                  onChange={() => toggleDocument(doc.documentId)}
                  disabled={doc.status !== 'READY'}
                />
              </td>
              <td className="px-4 py-2">{doc.filename}</td>
              <td className="px-4 py-2">
                <StatusBadge status={doc.status} />
              </td>
              <td className="px-4 py-2">{doc.chunkCount}</td>
              <td className="px-4 py-2">{new Date(doc.createdAt).toLocaleString()}</td>
              <td className="px-4 py-2">
                <button onClick={() => handleDelete(doc.documentId)} className="text-red-600 hover:underline">
                  Delete
                </button>
              </td>
            </tr>
          ))}
          {documents.length === 0 && (
            <tr>
              <td colSpan={6} className="px-4 py-6 text-center text-gray-500">
                No documents uploaded yet.
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}

function StatusBadge({ status }: { status: DocumentResponse['status'] }) {
  const colors: Record<DocumentResponse['status'], string> = {
    PENDING: 'bg-gray-200 text-gray-700',
    PROCESSING: 'bg-yellow-100 text-yellow-800',
    READY: 'bg-green-100 text-green-800',
    FAILED: 'bg-red-100 text-red-800',
  };
  return <span className={`rounded px-2 py-1 text-xs font-medium ${colors[status]}`}>{status}</span>;
}
