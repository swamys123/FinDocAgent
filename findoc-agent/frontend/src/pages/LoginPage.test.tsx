import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import { AuthContext, type AuthContextValue } from '../context/auth-context';
import { LoginPage } from './LoginPage';

const DEFAULT_TENANT_ID = '00000000-0000-0000-0000-000000000001';

function renderLogin(login: AuthContextValue['login']) {
  const authValue: AuthContextValue = {
    token: null,
    tenantId: null,
    username: null,
    login,
    logout: vi.fn(),
  };

  render(
    <AuthContext.Provider value={authValue}>
      <MemoryRouter initialEntries={['/login']}>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/documents" element={<h1>Documents page</h1>} />
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

describe('LoginPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('logs in with the configured tenant and navigates to documents', async () => {
    const user = userEvent.setup();
    const login = vi.fn<AuthContextValue['login']>().mockResolvedValue();
    renderLogin(login);

    await user.clear(screen.getByLabelText('Username'));
    await user.type(screen.getByLabelText('Username'), 'user@example.com');
    await user.type(screen.getByLabelText('Password'), 'secret');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(login).toHaveBeenCalledWith(DEFAULT_TENANT_ID, 'user@example.com', 'secret');
    expect(await screen.findByRole('heading', { name: 'Documents page' })).toBeInTheDocument();
  });

  it('shows an authentication error when login is rejected', async () => {
    const user = userEvent.setup();
    const login = vi.fn<AuthContextValue['login']>().mockRejectedValue(new ApiError(401, 'Unauthorized'));
    renderLogin(login);

    await user.type(screen.getByLabelText('Password'), 'wrong-password');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByText('Invalid tenant, username, or password.')).toBeInTheDocument();
  });

  it('shows a server error when login cannot reach the server', async () => {
    const user = userEvent.setup();
    const login = vi.fn<AuthContextValue['login']>().mockRejectedValue(new Error('Network unavailable'));
    renderLogin(login);

    await user.type(screen.getByLabelText('Password'), 'secret');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByText('Unable to reach the server.')).toBeInTheDocument();
  });
});
