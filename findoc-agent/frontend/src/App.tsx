import type { ReactNode } from 'react';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider } from './context/AuthContext';
import { SelectionProvider } from './context/SelectionContext';
import { ProtectedRoute } from './components/ProtectedRoute';
import { NavBar } from './components/NavBar';
import { LoginPage } from './pages/LoginPage';
import { DocumentsPage } from './pages/DocumentsPage';
import { QueryPage } from './pages/QueryPage';

function AppLayout({ children }: { children: ReactNode }) {
  return (
    <div className="min-h-screen bg-gray-50">
      <NavBar />
      {children}
    </div>
  );
}

function App() {
  return (
    <AuthProvider>
      <SelectionProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route
              path="/documents"
              element={
                <ProtectedRoute>
                  <AppLayout>
                    <DocumentsPage />
                  </AppLayout>
                </ProtectedRoute>
              }
            />
            <Route
              path="/query"
              element={
                <ProtectedRoute>
                  <AppLayout>
                    <QueryPage />
                  </AppLayout>
                </ProtectedRoute>
              }
            />
            <Route path="*" element={<Navigate to="/documents" replace />} />
          </Routes>
        </BrowserRouter>
      </SelectionProvider>
    </AuthProvider>
  );
}

export default App;
