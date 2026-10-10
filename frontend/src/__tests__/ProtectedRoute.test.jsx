import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { AuthContext } from '../context/AuthContext'
import ProtectedRoute from '../components/ProtectedRoute'

const renderWithAuth = (ui, { user, canWrite = true, canManage = true, canTrigger = true } = {}) => {
  return render(
    <MemoryRouter>
      <AuthContext.Provider value={{
        user,
        token: user ? 'test-token' : null,
        login: vi.fn(),
        logout: vi.fn(),
        canWrite,
        canManage,
        canTrigger
      }}>
        {ui}
      </AuthContext.Provider>
    </MemoryRouter>
  )
}

describe('ProtectedRoute', () => {
  it('redirects unauthenticated users to /login', () => {
    renderWithAuth(
      <ProtectedRoute><div data-testid="protected">Protected Content</div></ProtectedRoute>,
      { user: null }
    )
    // Navigate should redirect to /login
    expect(screen.queryByTestId('protected')).toBeNull()
  })

  it('renders children for authenticated users', () => {
    renderWithAuth(
      <ProtectedRoute><div data-testid="protected">Protected Content</div></ProtectedRoute>,
      { user: { id: 1, username: 'dev', role: 'DEVELOPER', email: 'dev@test.com' } }
    )
    expect(screen.getByTestId('protected')).toBeTruthy()
    expect(screen.getByText('Protected Content')).toBeTruthy()
  })

  it('redirects non-admin users from admin routes', () => {
    renderWithAuth(
      <ProtectedRoute requireAdmin><div data-testid="admin">Admin Only</div></ProtectedRoute>,
      { user: { id: 1, username: 'dev', role: 'DEVELOPER', email: 'dev@test.com' } }
    )
    expect(screen.queryByTestId('admin')).toBeNull()
  })

  it('allows admin users to access admin routes', () => {
    renderWithAuth(
      <ProtectedRoute requireAdmin><div data-testid="admin">Admin Only</div></ProtectedRoute>,
      { user: { id: 1, username: 'admin', role: 'ADMIN', email: 'admin@test.com' } }
    )
    expect(screen.getByTestId('admin')).toBeTruthy()
  })
})
