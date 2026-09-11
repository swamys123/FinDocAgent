import { useMemo, useState, type ReactNode } from 'react';
import { SelectionContext } from './selection-context';

export function SelectionProvider({ children }: { children: ReactNode }) {
  const [selectedDocumentIds, setSelectedDocumentIds] = useState<string[]>([]);

  const value = useMemo(
    () => ({
      selectedDocumentIds,
      toggleDocument: (documentId: string) => {
        setSelectedDocumentIds((prev) =>
          prev.includes(documentId) ? prev.filter((id) => id !== documentId) : [...prev, documentId],
        );
      },
    }),
    [selectedDocumentIds],
  );

  return <SelectionContext.Provider value={value}>{children}</SelectionContext.Provider>;
}
