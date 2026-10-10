import { useState, useEffect, useCallback } from 'react'
import { NavLink, Outlet, useNavigate, useLocation } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { fetchDashboardStats, fetchInstances } from '../api'
import { useResourceEvent } from '../eventBus'
import { startEventWebSocket, stopEventWebSocket } from '../eventWs'

// 侧边栏分组结构 — 按 CI/CD 流水线阶段划分
const menuGroups = [
  // 独立项：监控看板
  { type: 'item', path: '/dashboard', label: '监控看板', icon: '📊', minRole: 'VIEWER' },

  // CI 持续集成
  {
    type: 'group', path: '/ci', label: '持续集成', icon: '🔧', minRole: 'DEVELOPER',
    children: [
      { path: '/projects', label: '项目管理', icon: '📁', minRole: 'MANAGER' },
      { path: '/pipelines', label: '流水线管理', icon: '🔧', minRole: 'MANAGER' },
      { path: '/builds', label: '构建记录', icon: '📋', minRole: 'DEVELOPER' },
    ],
  },

  // CD 持续部署
  {
    type: 'group', path: '/cd', label: '持续部署', icon: '🚀', minRole: 'DEVELOPER',
    children: [
      { path: '/deployments', label: '部署管理', icon: '🚀', minRole: 'DEVELOPER' },
      { path: '/environments', label: '环境管理', icon: '🗂', minRole: 'DEVELOPER' },
      {
        path: '/instances', label: '服务实例', icon: '🖥', minRole: 'DEVELOPER',
        children: [
          { path: '/instances/docker', label: 'Docker', icon: '🐳' },
          { path: '/instances/k8s',   label: 'K8s',   icon: '☸️' },
        ],
      },
      { path: '/security', label: '安全扫描', icon: '🔒', minRole: 'DEVELOPER' },
      { path: '/metrics', label: 'APM 监控', icon: '📈', minRole: 'DEVELOPER' },
      { path: '/artifacts', label: '制品版本', icon: '📦', minRole: 'DEVELOPER' },
      { path: '/iac', label: '环境 IaC', icon: '🌐', minRole: 'DEVELOPER' },
      { path: '/webhooks', label: 'Webhook 订阅', icon: '🔔', minRole: 'DEVELOPER' },
    ],
  },

  // 系统管理
  {
    type: 'group', path: '/system', label: '系统管理', icon: '⚙️', minRole: 'MANAGER',
    children: [
      { path: '/templates', label: '模板管理', icon: '📄', minRole: 'MANAGER' },
      { path: '/audit', label: '审计日志', icon: '📝', minRole: 'ADMIN' },
      { path: '/users', label: '用户管理', icon: '👥', minRole: 'ADMIN' },
    ],
  },

  // 独立项：通知中心
  { type: 'item', path: '/notifications', label: '通知中心', icon: '🔔', minRole: 'DEVELOPER' },
]

const ROLE_LEVEL = { ADMIN: 4, MANAGER: 3, DEVELOPER: 2, VIEWER: 1 }

function filterByRole(items, userLevel) {
  return items
    .filter(item => (ROLE_LEVEL[item.minRole] || 0) <= userLevel)
    .map(item => {
      if (item.children) {
        return { ...item, children: item.children.filter(c => (ROLE_LEVEL[c.minRole] || 0) <= userLevel) }
      }
      return item
    })
    .filter(item => item.type !== 'group' || (item.children && item.children.length > 0))
}

function isPathActive(path, location) {
  // 精确匹配或前缀匹配: /security 匹配 /security /security/xxx
  if (location.pathname === path || location.pathname.startsWith(path + '/')) return true
  // 项目子页面场景: /projects/123/security 应激活 /security 菜单
  // 仅当当前路径以 /projects/<id>/ 开头时才做末段匹配
  const m = location.pathname.match(/^\/projects\/\d+\/([^/]+)$/)
  if (m) {
    const menuSeg = path.split('/').filter(Boolean).pop()
    return m[1] === menuSeg
  }
  return false
}

function isGroupActive(children, location) {
  return children.some(c => {
    if (c.children) return isGroupActive(c.children, location)
    return isPathActive(c.path, location)
  })
}

function CountBadge({ count }) {
  if (!count || count === 0) return null
  const display = count > 999 ? '999+' : String(count)
  return (
    <span style={{
      marginLeft: 'auto',
      minWidth: 18, height: 18, padding: '0 5px',
      borderRadius: 9,
      background: 'rgba(108,99,255,0.3)',
      color: '#a5a0ff',
      fontSize: 11, fontWeight: 600,
      display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
      lineHeight: 1,
    }}>{display}</span>
  )
}

export default function AppLayout() {
  const { user, logout, canWrite } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [expanded, setExpanded] = useState({})
  const [badgeCounts, setBadgeCounts] = useState({})

  const fetchBadgeCounts = useCallback(async () => {
    try {
      const [stats, instances] = await Promise.all([
        fetchDashboardStats(),
        fetchInstances().catch(() => []),
      ])
      const dockerCount = (instances || []).filter(i => i.deployType === 'DOCKER').length
      const k8sCount = (instances || []).filter(i => i.deployType === 'K8S').length
      setBadgeCounts({
        '/projects': stats.totalProjects ?? 0,
        '/pipelines': stats.activePipelines ?? 0,
        '/builds': stats.totalBuilds ?? 0,
        '/deployments': stats.pendingDeployments ?? 0,
        '/notifications': stats.unreadNotifications ?? 0,
        '/instances': (instances || []).length,
        '/instances/docker': dockerCount,
        '/instances/k8s': k8sCount,
        '/templates': stats.totalTemplates ?? 0,
        '/audit': stats.totalAuditLogs ?? 0,
        '/users': stats.totalUsers ?? 0,
      })
    } catch (e) {
      // 静默失败，不影响导航
    }
  }, [])

  useEffect(() => {
    fetchBadgeCounts()
    const timer = setInterval(fetchBadgeCounts, 15000)
    return () => clearInterval(timer)
  }, [fetchBadgeCounts])

  // 启动全局事件 WebSocket, 收到 notification.created / deployment.updated 立即刷新
  useEffect(() => {
    startEventWebSocket()
    return () => stopEventWebSocket()
  }, [])

  // 路由切换时立即刷新角标
  useEffect(() => {
    fetchBadgeCounts()
  }, [location.pathname, fetchBadgeCounts])

  // 任何地方删除项目/实例/构建 → 立即刷新角标
  const refreshOnChange = useCallback(() => { fetchBadgeCounts() }, [fetchBadgeCounts])
  useResourceEvent('project:deleted', refreshOnChange)
  useResourceEvent('project:created', refreshOnChange)
  useResourceEvent('project:updated', refreshOnChange)
  useResourceEvent('pipeline:deleted', refreshOnChange)
  useResourceEvent('build:deleted', refreshOnChange)
  useResourceEvent('instance:deleted', refreshOnChange)
  useResourceEvent('deployment:updated', refreshOnChange)
  useResourceEvent('notification:created', refreshOnChange)

  const handleLogout = () => {
    logout()
    navigate('/login', { replace: true })
  }

  const userLevel = ROLE_LEVEL[user?.role] || 0
  const visibleGroups = filterByRole(menuGroups, userLevel)

  const toggleExpand = (path) => {
    setExpanded(prev => ({ ...prev, [path]: !prev[path] }))
  }

  const renderNavItem = (item) => {
    const hasChildren = item.children && item.children.length > 0
    const isGroup = item.type === 'group'

    if (isGroup) {
      const groupActive = isGroupActive(item.children, location)
      const isExpanded = expanded[item.path] !== false // default expanded
      return (
        <div key={item.path} style={{ marginBottom: 0 }}>
          <div
            className={`sidebar-item sidebar-parent ${groupActive ? 'active' : ''}`}
            onClick={() => toggleExpand(item.path)}
            style={{ cursor: 'pointer' }}
          >
            <span className="icon">{item.icon}</span>
            {item.label}
            <span style={{ fontSize: 10, transition: 'transform 0.2s', transform: isExpanded ? 'rotate(90deg)' : 'rotate(0deg)' }}>▶</span>
          </div>
          {isExpanded && (
            <div className="sidebar-submenu">
              {item.children.map(child => renderNavItem(child))}
            </div>
          )}
        </div>
      )
    }

    // 子菜单项（如服务实例下的 Docker/K8s）
    if (hasChildren) {
      const parentActive = isPathActive(item.path, location)
      const isExpanded = expanded[item.path] !== false
      return (
        <div key={item.path} style={{ marginBottom: 0 }}>
          <div
            className={`sidebar-item sidebar-parent ${parentActive ? 'active' : ''}`}
            onClick={() => toggleExpand(item.path)}
            style={{ cursor: 'pointer' }}
          >
            <span className="icon">{item.icon}</span>
            {item.label}
            <CountBadge count={badgeCounts[item.path]} />
            <span style={{ fontSize: 10, transition: 'transform 0.2s', transform: isExpanded ? 'rotate(90deg)' : 'rotate(0deg)' }}>▶</span>
          </div>
          {isExpanded && (
            <div className="sidebar-submenu" style={{ paddingLeft: 12 }}>
              <NavLink
                to={item.path}
                end
                className={({ isActive }) => 'sidebar-subitem ' + (isActive ? 'active' : '')}
              >
                全部实例
                <CountBadge count={badgeCounts[item.path]} />
              </NavLink>
              {item.children.map(c => (
                <NavLink
                  key={c.path}
                  to={c.path}
                  className={({ isActive }) => 'sidebar-subitem ' + (isActive ? 'active' : '')}
                >
                  <span className="icon">{c.icon}</span>
                  {c.label}
                  <CountBadge count={badgeCounts[c.path]} />
                </NavLink>
              ))}
            </div>
          )}
        </div>
      )
    }

    // 普通叶子项
    const active = isPathActive(item.path, location)
    return (
      <NavLink
        key={item.path}
        to={item.path}
        className={({ isActive }) => 'sidebar-item ' + (isActive || active ? 'active' : '')}
      >
        <span className="icon">{item.icon}</span>
        {item.label}
        <CountBadge count={badgeCounts[item.path]} />
      </NavLink>
    )
  }

  return (
    <div className="app">
      <aside className="sidebar">
        <div className="sidebar-logo">
          🚀 DevOps<span>Platform</span>
        </div>
        <nav className="sidebar-nav">
          {visibleGroups.map(item => renderNavItem(item))}
        </nav>
        <div className="sidebar-user">
          <div className="sidebar-user-info">
            <div className="sidebar-user-avatar">
              {(user?.realName || user?.username || '?')[0].toUpperCase()}
            </div>
            <div>
              <div className="sidebar-user-name">{user?.realName || user?.username}</div>
              <div className="sidebar-user-role">
                {user?.role === 'ADMIN' ? '管理员' :
                 user?.role === 'MANAGER' ? '项目经理' :
                 user?.role === 'DEVELOPER' ? '开发者' :
                 user?.role === 'VIEWER' ? '观察者' : user?.role}
                {!canWrite && <span style={{ marginLeft: 4, fontSize: 10, color: '#ef4444' }}>只读</span>}
              </div>
            </div>
          </div>
          <button className="sidebar-logout" onClick={handleLogout} title="退出登录">
            ⇥
          </button>
        </div>
      </aside>
      <main className="main">
        <Outlet />
      </main>
    </div>
  )
}
