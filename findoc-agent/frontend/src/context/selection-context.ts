import { createContext } from 'react';

export interface SelectionContextValue {
  selectedDocumentIds: string[];
  toggleDocument: (documentId: string) => void;
}

export const SelectionContext = createContext<SelectionContextValue | undefined>(undefined);
