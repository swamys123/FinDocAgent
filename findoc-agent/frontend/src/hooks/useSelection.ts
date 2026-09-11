import { useContext } from 'react';
import { SelectionContext, type SelectionContextValue } from '../context/selection-context';

export function useSelection(): SelectionContextValue {
  const context = useContext(SelectionContext);
  if (!context) {
    throw new Error('useSelection must be used within a SelectionProvider');
  }
  return context;
}
