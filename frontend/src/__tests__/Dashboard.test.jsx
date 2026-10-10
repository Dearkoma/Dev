import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { BrowserRouter } from 'react-router-dom'
import { AuthContext } from '../context/AuthContext'
import Dashboard from '../components/Dashboard'

// Mock the api module
vi.mock('../api', () => ({
  fetchDashboardStats: vi.fn().mockResolvedValue({
    totalProjects: 5,
    totalBuilds: 42,
    successRate: 95.5,
    avgBuildTimeMs: 120000,
    runningBuilds: 2,
    pendingDeployments: 1,
    runningInstances: 8,
    unreadNotifications: 3
  }),
  fetchRecentBuilds: vi.fn().mockResolvedValue([]),
  fetchDashboardTrends: vi.fn().mockResolvedValue([])
}))

const mockUser = {
  id: 1,
  username: 'admin',
  role: 'ADMIN',
  email: 'admin@test.com'
}

const renderWithProviders = (ui) => {
  return render(
    <BrowserRouter>
      <AuthContext.Provider value={{
        user: mockUser,
        token: 'test-token',
        login: vi.fn(),
        logout: vi.fn(),
        canWrite: true,
        canManage: true,
        canTrigger: true
      }}>
        {ui}
      </AuthContext.Provider>
    </BrowserRouter>
  )
}

describe('Dashboard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders loading state initially', () => {
    renderWithProviders(<Dashboard />)
    expect(screen.getByText(/加载中|Loading/i)).toBeTruthy()
  })

  it('renders stat cards after data loads', async () => {
    renderWithProviders(<Dashboard />)
    await waitFor(() => {
      expect(screen.getByText(/5/)).toBeTruthy()
    }, { timeout: 3000 })
  })

  it('renders dashboard title', () => {
    renderWithProviders(<Dashboard />)
    expect(screen.getByText(/监控看板|Dashboard/i)).toBeTruthy()
  })
})
