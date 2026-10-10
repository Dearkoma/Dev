import React, { useState, useEffect, useCallback, useMemo } from 'react'
import { useAuth } from '../context/AuthContext'
import {
  fetchDeploymentsPending, fetchDeploymentRequests, fetchDeploymentHistory,
  fetchRollbackCandidates, requestDeploy, approveDeploy, rejectDeploy, rollbackDeploy,
  deleteDeploymentHistory, deleteDeploymentRequest,
  fetchBuilds, fetchProjects, fetchEnvironments, fetchPipelines,
  fetchProjectMyRole
} from '../api'
import { emit, useResourceEvent } from '../eventBus'
import EnvironmentList from './EnvironmentList'
import CanaryControl from './CanaryControl'

export default function DeploymentList() {
  const { canTrigger, canManage } = useAuth()
  const [tab, setTab] = useState('requests')
  const [pending, setPending] = useState([])
  const [history, setHistory] = useState([])
  const [loading, setLoading] = useState(true)

  const [showModal, setShowModal] = useState(false)
  const [builds, setBuilds] = useState([])
  const [environments, setEnvironments] = useState([])
  const [optionsLoading, setOptionsLoading] = useState(false)
  const [selectedBuild, setSelectedBuild] = useState('')
  const [selectedEnv, setSelectedEnv] = useState('')
  const [reason, setReason] = useState('')
  const [projectPerms, setProjectPerms] = useState({})  // projectId → { canApprove }

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [p, h] = await Promise.all([
        fetchDeploymentRequests(),
        fetchDeploymentHistory()
      ])
      setPending(p.filter(r => r.status === 'PENDING'))
      setHistory(Array.isArray(h) ? h : [])
    } catch (e) { console.error(e) }
    setLoading(false)
  }, [])

  useEffect(() => { load() }, [load])

  // 跨组件同步: 项目被删 → 该项目下的构建过滤
  const onProjectDeleted = useCallback((detail) => {
    const id = detail?.id
    if (id == null) return
    setBuilds(prev => prev.filter(b => b.projectId !== id))
  }, [])
  useResourceEvent('project:deleted', onProjectDeleted)

  // 构建被删 → 部署请求里的引用过滤
  const onBuildDeleted = useCallback((detail) => {
    const id = detail?.id
    if (id == null) return
    setBuilds(prev => prev.filter(b => b.id !== id))
    setPending(prev => prev.filter(p => p.buildId !== id))
    setHistory(prev => prev.filter(h => h.buildId !== id))
  }, [])
  useResourceEvent('build:deleted', onBuildDeleted)

  // WebSocket 推送部署状态变更 → 立即刷新 (替代轮询)
  useResourceEvent('deployment:updated', load)

  // 计算每个 (项目, 环境) 组合的部署版本数, 用于回滚按钮状态
  const deployCountMap = useMemo(() => {
    const map = {}
    history.forEach(h => {
      const key = `${h.projectId}_${h.environmentId}`
      map[key] = (map[key] || 0) + 1
    })
    return map
  }, [history])

  // 加载各项目的用户权限（用于审批/回滚按钮）
  useEffect(() => {
    const projectIds = new Set()
    pending.forEach(r => projectIds.add(r.projectId))
    history.forEach(h => projectIds.add(h.projectId))
    if (projectIds.size === 0) return
    Promise.all([...projectIds].map(pid =>
      fetchProjectMyRole(pid).then(role => ({ pid, role })).catch(() => ({ pid, role: null }))
    )).then(results => {
      const perms = {}
      results.forEach(({ pid, role }) => {
        perms[pid] = { canApprove: role?.canApprove || false }
      })
      setProjectPerms(perms)
    })
  }, [pending, history])

  const openRequestModal = async () => {
    // 立即打开模态框, 避免用户等待两次 API 调用后才有反馈
    setShowModal(true)
    setOptionsLoading(true)
    setBuilds([])
    setEnvironments([])
    try {
      const [bList, eList] = await Promise.all([fetchBuilds(), fetchEnvironments()])
      setBuilds(bList.filter(b => b.status === 'SUCCESS'))
      setEnvironments(eList)
    } catch (e) {
      console.error('加载构建/环境列表失败:', e)
      alert('加载构建或环境列表失败: ' + (e.message || '未知错误'))
    } finally {
      setOptionsLoading(false)
    }
  }

  const handleRequest = async () => {
    if (!selectedBuild || !selectedEnv) return alert('请选择构建和环境')
    try {
      const result = await requestDeploy({
        buildId: Number(selectedBuild),
        environmentId: Number(selectedEnv),
        reason
      })
      setShowModal(false)
      setSelectedBuild(''); setSelectedEnv(''); setReason('')
      await load()
      // 根据部署状态给出明确反馈
      if (result.status === 'DEPLOYING') {
        alert('部署已触发，正在后台执行 K8s 部署，请在"部署历史"中查看进度')
        setTab('history')
      } else if (result.status === 'DEPLOYED') {
        alert('部署已自动执行（非保护环境），请在"部署历史"中查看')
        setTab('history')
      } else if (result.status === 'PENDING') {
        alert('部署申请已提交，等待审批')
        setTab('pending')
      } else {
        alert(`部署申请已提交，状态: ${result.status}`)
      }
    } catch (e) { alert('部署申请失败: ' + e.message) }
  }

  const handleApprove = async (id) => {
    try { await approveDeploy(id); emit('deployment:updated', { id }); load() } catch (e) { alert(e.message) }
  }

  const handleReject = async (id) => {
    const r = prompt('拒绝原因:')
    if (!r) return
    try { await rejectDeploy(id, r); emit('deployment:updated', { id }); load() } catch (e) { alert(e.message) }
  }

  const handleRollback = async (projectId, envId) => {
    const verCount = deployCountMap[`${projectId}_${envId}`] || 0
    if (verCount <= 1) {
      alert('仅此一版，没有可回滚的历史版本')
      return
    }
    try {
      const candidates = await fetchRollbackCandidates(projectId, envId)
      if (!candidates || candidates.length === 0) {
        alert('没有可回滚的部署点（可能旧记录未标记为回滚点）')
        return
      }
      const target = candidates[0]
      if (window.confirm(`确定回滚到构建 #${target.buildNumber}？（将执行 kubectl rollout undo）`)) {
        await rollbackDeploy(target.id)
        alert('回滚已触发，请在部署历史中查看结果')
        load()
      }
    } catch (e) { alert('回滚失败: ' + e.message) }
  }

  const handleDeleteHistory = async (id) => {
    if (!window.confirm('确认删除该部署历史？')) return
    try { await deleteDeploymentHistory(id); emit('deployment:updated', { id }); load() } catch (e) { alert('删除失败: ' + e.message) }
  }

  const handleDeleteRequest = async (id) => {
    if (!window.confirm('确认删除该部署申请？')) return
    try { await deleteDeploymentRequest(id); emit('deployment:updated', { id }); load() } catch (e) { alert('删除失败: ' + e.message) }
  }

  if (loading) return <div className="empty-state"><div className="spinner" /></div>

  return (
    <>
      <div className="page-header">
        <h2>🚀 部署管理</h2>
        <div style={{ display: 'flex', gap: 8 }}>
          <div className="filter-bar">
            <button className={`btn ${tab === 'requests' ? 'btn-primary' : 'btn-outline'} btn-sm`}
              onClick={() => setTab('requests')}>部署申请</button>
            <button className={`btn ${tab === 'pending' ? 'btn-primary' : 'btn-outline'} btn-sm`}
              onClick={() => setTab('pending')}>
              待审批 {pending.length > 0 && <span style={{ color: '#ef4444' }}>({pending.length})</span>}
            </button>
            <button className={`btn ${tab === 'history' ? 'btn-primary' : 'btn-outline'} btn-sm`}
              onClick={() => setTab('history')}>部署历史</button>
            <button className={`btn ${tab === 'environments' ? 'btn-primary' : 'btn-outline'} btn-sm`}
              onClick={() => setTab('environments')}>🌐 环境管理</button>
          </div>
          {canTrigger && tab !== 'environments' && <button className="btn btn-primary" onClick={openRequestModal}>+ 申请部署</button>}
        </div>
      </div>

      {tab === 'environments' ? (
        <EnvironmentList />
      ) : (
      <>
      {/* 金丝雀发布控制：CANARY 策略部署后的全量提升 / 回滚收尾 */}
      <CanaryControl />

      {tab === 'requests' && (
        <div className="card" style={{ padding: 0 }}>
          <div className="table-container">
            <table>
              <thead>
                <tr>
                  <th>ID</th><th>项目</th><th>环境</th><th>构建</th><th>申请者</th>
                  <th>状态</th><th>时间</th><th>操作</th>
                </tr>
              </thead>
              <tbody>
                {pending.length === 0 ? (
                  <tr><td colSpan={8} style={{ textAlign: 'center', padding: 32, color: '#9ca3af' }}>暂无部署申请</td></tr>
                ) : (
                  pending.map(r => (
                    <tr key={r.id}>
                      <td>#{r.id}</td>
                      <td>{r.projectName}</td>
                      <td>{r.environmentName}</td>
                      <td>#{r.buildId}</td>
                      <td>{r.requestedBy}</td>
                      <td><span className="badge badge-running">待审批</span></td>
                      <td style={{ fontSize: 12 }}>{new Date(r.createdAt).toLocaleString()}</td>
                      <td>
                        <div className="btn-group">
                          {(canManage || projectPerms[r.projectId]?.canApprove) && <button className="btn btn-primary btn-sm" onClick={() => handleApprove(r.id)}>✓ 通过</button>}
                          {(canManage || projectPerms[r.projectId]?.canApprove) && <button className="btn btn-danger btn-sm" onClick={() => handleReject(r.id)}>✗ 拒绝</button>}
                        </div>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {tab === 'history' && (
        <div className="card" style={{ padding: 0 }}>
          <div className="table-container">
            <table>
              <thead>
                <tr>
                  <th>ID</th><th>项目</th><th>环境</th><th>构建</th><th>版本</th>
                  <th>部署者</th><th>状态</th><th>时间</th><th>操作</th>
                </tr>
              </thead>
              <tbody>
                {history.length === 0 ? (
                  <tr><td colSpan={9} style={{ textAlign: 'center', padding: 32, color: '#9ca3af' }}>暂无部署历史</td></tr>
                ) : (
                  history.map(h => {
                    const verCount = deployCountMap[`${h.projectId}_${h.environmentId}`] || 0
                    const isOnlyVersion = verCount <= 1 && h.isRollbackPoint !== false
                    const canApproveThis = canManage || projectPerms[h.projectId]?.canApprove
                    let statusLabel, statusClass
                    switch (h.status) {
                      case 'DEPLOYED': statusLabel = '已部署'; statusClass = 'success'; break
                      case 'DEPLOYING': statusLabel = '部署中'; statusClass = 'running'; break
                      case 'FAILED': statusLabel = '部署失败'; statusClass = 'failed'; break
                      case 'ROLLED_BACK': statusLabel = '已回滚'; statusClass = 'warning'; break
                      default: statusLabel = h.status; statusClass = 'info'; break
                    }
                    return (
                    <tr key={h.id}>
                      <td>#{h.id}</td>
                      <td>{h.projectName}</td>
                      <td>{h.environmentName}</td>
                      <td>{h.buildNumber}</td>
                      <td>{h.version || '-'}</td>
                      <td>{h.deployedBy || '-'}</td>
                      <td>
                        <span className={`badge badge-${statusClass}`}>{statusLabel}</span>
                      </td>
                      <td style={{ fontSize: 12 }}>{new Date(h.deployedAt).toLocaleString()}</td>
                      <td>
                        {canApproveThis && (
                          isOnlyVersion ? (
                            <span style={{ fontSize: 12, color: '#9ca3af', cursor: 'help' }}
                              title="仅此一版，无可回滚版本">↩ 仅此一版</span>
                          ) : (
                            <button className="btn btn-outline btn-sm"
                              onClick={() => handleRollback(h.projectId, h.environmentId)}>↩ 回滚</button>
                          )
                        )}
                        {canApproveThis && <button className="btn btn-outline btn-sm" style={{ marginLeft: 4, color: '#ef4444' }}
                          onClick={() => handleDeleteHistory(h.id)}>🗑</button>}
                      </td>
                    </tr>
                  )})
                )}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {tab === 'pending' && (
        <div className="card" style={{ padding: 0 }}>
          <div className="table-container">
            <table>
              <thead>
                <tr>
                  <th>ID</th><th>项目</th><th>环境</th><th>构建</th><th>申请者</th>
                  <th>原因</th><th>时间</th><th>操作</th>
                </tr>
              </thead>
              <tbody>
                {pending.length === 0 ? (
                  <tr><td colSpan={8} style={{ textAlign: 'center', padding: 32, color: '#9ca3af' }}>暂无待审批</td></tr>
                ) : (
                  pending.map(r => (
                    <tr key={r.id}>
                      <td>#{r.id}</td>
                      <td>{r.projectName}</td>
                      <td>{r.environmentName}</td>
                      <td>#{r.buildId}</td>
                      <td>{r.requestedBy}</td>
                      <td style={{ color: '#6b7280', fontSize: 12 }}>{r.reason || '-'}</td>
                      <td style={{ fontSize: 12 }}>{new Date(r.createdAt).toLocaleString()}</td>
                      <td>
                        <div className="btn-group">
                          {(canManage || projectPerms[r.projectId]?.canApprove) && <button className="btn btn-primary btn-sm" onClick={() => handleApprove(r.id)}>✓</button>}
                          {(canManage || projectPerms[r.projectId]?.canApprove) && <button className="btn btn-danger btn-sm" onClick={() => handleReject(r.id)}>✗</button>}
                          {(canManage || projectPerms[r.projectId]?.canApprove) && <button className="btn btn-outline btn-sm" style={{ color: '#ef4444' }} title="删除申请" onClick={() => handleDeleteRequest(r.id)}>🗑</button>}
                        </div>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {showModal && (
        <div className="modal-overlay" onClick={() => setShowModal(false)}>
          <div className="modal" onClick={e => e.stopPropagation()}>
            <h3>申请部署</h3>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12, marginTop: 16 }}>
              <label>选择构建:
                <select value={selectedBuild} onChange={e => setSelectedBuild(e.target.value)} style={{ width: '100%', marginTop: 4 }} disabled={optionsLoading}>
                  <option value="">{optionsLoading ? '加载中...' : '-- 选择成功的构建 --'}</option>
                  {builds.map(b => <option key={b.id} value={b.id}>#{b.buildNumber} - 项目#{b.projectId}</option>)}
                </select>
              </label>
              <label>目标环境:
                <select value={selectedEnv} onChange={e => setSelectedEnv(e.target.value)} style={{ width: '100%', marginTop: 4 }} disabled={optionsLoading}>
                  <option value="">{optionsLoading ? '加载中...' : '-- 选择环境 --'}</option>
                  {environments.map(e => <option key={e.id} value={e.id}>{e.displayName || e.name} {e.protectedEnv ? '(需审批)' : ''}</option>)}
                </select>
              </label>
              <label>申请原因:
                <textarea value={reason} onChange={e => setReason(e.target.value)}
                  style={{ width: '100%', marginTop: 4, minHeight: 60 }} placeholder="可选填" />
              </label>
              <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 8 }}>
                <button className="btn btn-outline" onClick={() => setShowModal(false)}>取消</button>
                <button className="btn btn-primary" onClick={handleRequest}>提交申请</button>
              </div>
            </div>
          </div>
        </div>
      )}
      </>
      )}
    </>
  )
}
