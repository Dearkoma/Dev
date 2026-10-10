import { createContext, useContext, useState, useEffect, useCallback } from 'react'

const AuthContext = createContext(null)
const USER_KEY = 'devops_user'

export function AuthProvider({ children }) {
  const [user, setUser] = useState(() => {
    try {
      const saved = localStorage.getItem(USER_KEY)
      return saved ? JSON.parse(saved) : null
    } catch { return null }
  })
  const [token, setToken] = useState(null)  // 来自 /api/auth/me 返回或登录响应
  const [loading, setLoading] = useState(true)

  // 启动时验证 token 有效性（Cookie 自动发送）
  useEffect(() => {
    fetch('/api/auth/me', { credentials: 'same-origin' })
      .then(res => {
        if (res.ok) return res.json()
        throw new Error('token_invalid')
      })
      .then(data => {
        setUser(data)
        if (data.token) setToken(data.token)
        localStorage.setItem(USER_KEY, JSON.stringify(data))
      })
      .catch(() => {
        setToken(null)
        setUser(null)
        localStorage.removeItem(USER_KEY)
      })
      .finally(() => setLoading(false))
  }, [])

  // 监听 api.js 发起的 auth:logout 事件，用于 401 退出登录
  useEffect(() => {
    const handleAuthLogout = () => {
      setToken(null)
      setUser(null)
      localStorage.removeItem(USER_KEY)
    }
    window.addEventListener('auth:logout', handleAuthLogout)
    return () => window.removeEventListener('auth:logout', handleAuthLogout)
  }, [])

  const login = useCallback((authResponse) => {
    if (authResponse.token) setToken(authResponse.token)
    setUser(authResponse)
    localStorage.setItem(USER_KEY, JSON.stringify(authResponse))
  }, [])

  const logout = useCallback(async () => {
    try {
      await fetch('/api/auth/logout', { method: 'POST', credentials: 'same-origin' })
    } catch (e) { /* ignore */ }
    setToken(null)
    setUser(null)
    localStorage.removeItem(USER_KEY)
  }, [])

  const role = user?.role || ''

  const isAdmin = role === 'ADMIN'
  const isManager = role === 'MANAGER'
  const isDeveloper = role === 'DEVELOPER'
  const isViewer = role === 'VIEWER'
  const canWrite = !isViewer
  const canManage = isAdmin || isManager
  const canTrigger = isAdmin || isManager || isDeveloper

  const value = { user, token, loading, login, logout, isAuthenticated: !!user,
    isAdmin, isManager, isDeveloper, isViewer, canWrite, canManage, canTrigger }

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
