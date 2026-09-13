import { NavLink } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';

export function NavBar() {
  const { username, logout } = useAuth();

  const linkClass = ({ isActive }: { isActive: boolean }) =>
    `rounded px-3 py-2 text-sm font-medium ${isActive ? 'bg-indigo-100 text-indigo-700' : 'text-gray-600 hover:bg-gray-100'}`;

  return (
    <nav className="flex items-center justify-between border-b border-gray-200 bg-white px-6 py-3">
      <div className="flex items-center gap-2">
        <span className="mr-4 text-sm font-semibold text-gray-900">FinDoc Agent</span>
        <NavLink to="/documents" className={linkClass}>
          Documents
        </NavLink>
        <NavLink to="/query" className={linkClass}>
          Query
        </NavLink>
      </div>
      <div className="flex items-center gap-3 text-sm text-gray-600">
        <span>{username}</span>
        <button onClick={logout} className="rounded px-3 py-1 text-red-600 hover:bg-red-50">
          Logout
        </button>
      </div>
    </nav>
  );
}
