import { useState, useEffect, useCallback } from 'react'
import { fetchProjects, fetchCanaryStatus, promoteCanary, rollbackCanary } from '../api'
import { useAuth } from '../context/AuthContext'

/**
 * 金丝雀发布控制 —— 查询项目当前的金丝雀部署状态，执行「全量提升 / 回滚」。
 * 金丝雀由构建时选择 CANARY 策略触发（只部署 canary Deployment 承接部分流量），
 * 观察无问题后在此收尾：
 *   - 全量提升：canary 镜像覆盖稳定版，稳定后删除金丝雀
 *   - 回滚：删除金丝雀，流量自然回落稳定版
 */
export default function CanaryControl() {
  const { canManage } = useAuth()
  const [projects, setProjects] = useState([])
  const [projectId, setProjectId] = useState('')
  const [status, setStatus] = useState(null)
  const [loading, setLoading] = useState(false)
  const [busy, setBusy] = useState('')
  const [log, setLog] = useState('')
  const [error, setError] = useState(null)

  useEffect(() => {
    fetchProjects()
      .then(data => setProjects(Array.isArray(data) ? data : []))
      .catch(() => setProjects([]))
  }, [])

  const loadStatus = useCallback(async (pid) => {
    if (!pid) { setStatus(null); return }
    setLoading(true)
    setError(null)
    try {
      const s = await fetchCanaryStatus(pid)
      setStatus(s || { exists: false })
    } catch (e) {
      setError('查询失败: ' + e.message)
      setStatus(null)
    } finally {
      setLoading(false)
    }
  }, [])

  const handleSelect = (pid) => {
    setProjectId(pid)
    setLog('')
    loadStatus(pid)
  }

  const handleAction = async (kind) => {
    const label = kind === 'promote' ? '全量提升' : '回滚'
    const tip = kind === 'promote'
      ? `将把稳定版更新为金丝雀镜像（${status?.image || '未知'}），稳定后删除金丝雀。确认？`
      : '将删除金丝雀版本，流量回落到稳定版。确认？'
    if (!confirm(tip)) return
    setBusy(kind)
    setError(null)
    setLog('')
    try {
      const r = kind === 'promote' ? await promoteCanary(projectId) : await rollbackCanary(projectId)
      setLog(r?.log || `${label}完成`)
      await loadStatus(projectId)
    } catch (e) {
      setError(`${label}失败: ` + e.message)
    } finally {
      setBusy('')
    }
  }

  return (
    <div className="card" style={{ marginBottom: 16 }}>
      <h3 style={{ margin: '0 0 10px', fontSize: 15 }}>🐤 金丝雀发布控制</h3>
      <p style={{ fontSize: 12, color: '#6b7280', margin: '0 0 10px' }}>
        构建时选择「金丝雀发布 (Canary)」策略后，新版本以少量副本承接部分流量；观察无异常后在此全量提升或回滚。
      </p>

      <div style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
        <select value={projectId} onChange={e => handleSelect(e.target.value)} style={{ minWidth: 220 }}>
          <option value="">-- 选择项目 --</option>
          {projects.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
        </select>
        {projectId && (
          <button className="btn btn-outline btn-sm" onClick={() => loadStatus(projectId)} disabled={loading}>
            {loading ? '查询中...' : '🔄 刷新状态'}
          </button>
        )}
      </div>

      {error && (
        <div style={{ marginTop: 10, padding: 10, background: '#fef2f2', borderRadius: 6, color: '#991b1b', fontSize: 12 }}>
          ⚠️ {error}
        </div>
      )}

      {projectId && !loading && status && (
        <div style={{ marginTop: 12 }}>
          {status.exists ? (
            <div style={{ padding: 12, background: '#fffbeb', border: '1px solid #fde68a', borderRadius: 8 }}>
              <div style={{ fontSize: 13, marginBottom: 8 }}>
                <span style={{ fontWeight: 600 }}>⚡ 金丝雀运行中</span>
                <span style={{ marginLeft: 10, color: '#92400e' }}>
                  {status.canaryName}（就绪 {status.readyReplicas}/{status.replicas} 副本）
                </span>
              </div>
              <div style={{ fontSize: 12, color: '#6b7280', marginBottom: 10, fontFamily: 'monospace' }}>
                镜像: {status.image}　→　稳定版: {status.stableName}
              </div>
              {canManage ? (
                <div className="btn-group">
                  <button className="btn btn-success btn-sm" disabled={busy === 'promote'}
                    onClick={() => handleAction('promote')}>
                    {busy === 'promote' ? '提升中...' : '🚀 全量提升'}
                  </button>
                  <button className="btn btn-danger btn-sm" disabled={busy === 'rollback'}
                    onClick={() => handleAction('rollback')}>
                    {busy === 'rollback' ? '回滚中...' : '↩ 回滚金丝雀'}
                  </button>
                </div>
              ) : (
                <div style={{ fontSize: 11, color: '#92400e' }}>全量提升与回滚需要项目维护者及以上权限</div>
              )}
            </div>
          ) : (
            <div style={{ fontSize: 12, color: '#9ca3af', padding: '8px 0' }}>
              该项目当前没有进行中的金丝雀部署
            </div>
          )}
        </div>
      )}

      {log && (
        <pre style={{
          marginTop: 10, padding: 10, background: '#1f2937', color: '#d1fae5',
          borderRadius: 6, fontSize: 11, lineHeight: 1.6, maxHeight: 200,
          overflow: 'auto', whiteSpace: 'pre-wrap', wordBreak: 'break-all'
        }}>
          {log}
        </pre>
      )}
    </div>
  )
}
