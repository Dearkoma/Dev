import React, { useState, useEffect, useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import {
  fetchArtifactVersionsByProject, verifyArtifactVersion,
  markArtifactVersionDeployed, deleteArtifactVersion,
  fetchProjects
} from '../api'

const STATUS_COLORS = {
  ACTIVE:     { bg: '#EAF3DE', fg: '#27500A' },
  DEPRECATED: { bg: '#FAEEDA', fg: '#854F0B' },
  RETAINED:   { bg: '#F1EFE8', fg: '#5F5E5A' },
  DELETED:    { bg: '#FCEBEB', fg: '#791F1F' }
}

const STATUS_LABEL = {
  ACTIVE: '活跃',
  DEPRECATED: '已弃用',
  RETAINED: '保留',
  DELETED: '已删除'
}

function formatSize(bytes) {
  if (!bytes) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

function StatusChip({ status }) {
  const c = STATUS_COLORS[status] || STATUS_COLORS.RETAINED
  return <span style={{ background: c.bg, color: c.fg, padding: '2px 8px', borderRadius: 3, fontSize: 10, fontWeight: 500 }}>{STATUS_LABEL[status] || status}</span>
}

export default function ArtifactVersionPanel() {
  const { projectId: paramProjectId } = useParams()
  const navigate = useNavigate()
  const { canManage } = useAuth()
  const projectId = paramProjectId ? Number(paramProjectId) : null
  const [versions, setVersions] = useState([])
  const [loading, setLoading] = useState(true)
  const [verifying, setVerifying] = useState(null)  // versionId 正在校验
  const [verifyResult, setVerifyResult] = useState(null)  // { versionId, ok, msg }
  const [filter, setFilter] = useState('ALL')
  const [projects, setProjects] = useState([])

  // 从侧边栏 /artifacts 进入 (无 projectId) → 拉项目列表供选择
  useEffect(() => {
    if (!projectId) {
      fetchProjects().then(list => setProjects(list || [])).catch(console.error)
    }
  }, [projectId])

  const load = useCallback(async () => {
    if (!projectId) return
    setLoading(true)
    try {
      const list = await fetchArtifactVersionsByProject(projectId)
      setVersions(list || [])
    } catch (e) { console.error(e) }
    finally { setLoading(false) }
  }, [projectId])

  useEffect(() => { load() }, [load])

  const handleVerify = async (v) => {
    setVerifying(v.id)
    setVerifyResult(null)
    try {
      const r = await verifyArtifactVersion(v.id)
      setVerifyResult({ versionId: v.id, ...r })
    } catch (e) {
      setVerifyResult({ versionId: v.id, integrityValid: false, message: e.message })
    }
    finally { setVerifying(null) }
  }

  const handleMarkDeployed = async (v) => {
    try {
      await markArtifactVersionDeployed(v.id)
      load()
    } catch (e) { alert('操作失败: ' + e.message) }
  }

  const handleDelete = async (v) => {
    if (!confirm(`软删版本 ${v.version}?`)) return
    try {
      await deleteArtifactVersion(v.id)
      load()
    } catch (e) { alert('删除失败: ' + e.message) }
  }

  if (!projectId) {
    return (
      <div style={{ padding: 24 }}>
        <h2 style={{ marginBottom: 16 }}>📦 制品版本</h2>
        <div className="card" style={{ padding: 16 }}>
          <div style={{ marginBottom: 12, color: '#9ca3af' }}>选择一个项目查看其制品版本：</div>
          {projects.length === 0 ? (
            <div style={{ color: '#6b7280' }}>暂无项目，请先到项目管理页创建</div>
          ) : (
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(240px, 1fr))', gap: 12 }}>
              {projects.map(p => (
                <div
                  key={p.id}
                  className="card"
                  style={{ padding: 14, cursor: 'pointer', transition: 'transform 0.15s, box-shadow 0.15s' }}
                  onMouseEnter={e => { e.currentTarget.style.transform = 'translateY(-2px)'; e.currentTarget.style.boxShadow = '0 4px 12px rgba(0,0,0,0.3)' }}
                  onMouseLeave={e => { e.currentTarget.style.transform = ''; e.currentTarget.style.boxShadow = '' }}
                  onClick={() => navigate(`/projects/${p.id}/artifacts`)}
                >
                  <div style={{ fontWeight: 600, marginBottom: 4 }}>📁 {p.name}</div>
                  <div style={{ fontSize: 12, color: '#9ca3af' }}>{p.code || ''}</div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    )
  }
  if (loading && versions.length === 0) {
    return <div style={{ padding: 24 }}><div className="spinner" /></div>
  }

  const filtered = filter === 'ALL' ? versions : versions.filter(v => v.status === filter)
  const activeCount = versions.filter(v => v.status === 'ACTIVE').length
  const totalDeploys = versions.reduce((sum, v) => sum + (v.deployCount || 0), 0)

  return (
    <div style={{ padding: 24 }}>
      <h2 style={{ fontSize: 18, fontWeight: 500, margin: '0 0 8px' }}>制品版本管理</h2>
      <p style={{ fontSize: 13, color: '#888780', margin: '0 0 16px' }}>
        多版本追溯 · SHA256 完整性校验 · 保留策略 · 部署热度
      </p>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 12, marginBottom: 20 }}>
        <div style={{ background: '#F8F7F2', borderRadius: 6, padding: '12px 16px' }}>
          <p style={{ fontSize: 11, color: '#888780', margin: 0 }}>总版本数</p>
          <p style={{ fontSize: 22, fontWeight: 500, color: '#185FA5', margin: '4px 0 0' }}>{versions.length}</p>
        </div>
        <div style={{ background: '#F8F7F2', borderRadius: 6, padding: '12px 16px' }}>
          <p style={{ fontSize: 11, color: '#888780', margin: 0 }}>活跃版本</p>
          <p style={{ fontSize: 22, fontWeight: 500, color: '#27500A', margin: '4px 0 0' }}>{activeCount}</p>
        </div>
        <div style={{ background: '#F8F7F2', borderRadius: 6, padding: '12px 16px' }}>
          <p style={{ fontSize: 11, color: '#888780', margin: 0 }}>累计部署</p>
          <p style={{ fontSize: 22, fontWeight: 500, color: '#534AB7', margin: '4px 0 0' }}>{totalDeploys}</p>
        </div>
        <div style={{ background: '#F8F7F2', borderRadius: 6, padding: '12px 16px' }}>
          <p style={{ fontSize: 11, color: '#888780', margin: 0 }}>保留策略</p>
          <p style={{ fontSize: 12, color: '#5F5E5A', margin: '6px 0 0' }}>最多 5 个 ACTIVE, 其余自动 RETAINED</p>
        </div>
      </div>

      <div style={{ background: '#FFFFFF', border: '0.5px solid rgba(17,24,28,0.15)', borderRadius: 8, overflow: 'hidden' }}>
        <div style={{ padding: '10px 12px', background: '#F8F7F2', borderBottom: '0.5px solid rgba(17,24,28,0.15)', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <p style={{ fontSize: 12, fontWeight: 500, margin: 0 }}>版本列表 ({versions.length})</p>
          <div style={{ display: 'flex', gap: 4 }}>
            {['ALL', 'ACTIVE', 'RETAINED', 'DEPRECATED', 'DELETED'].map(f => (
              <button key={f} onClick={() => setFilter(f)} style={{
                padding: '2px 8px', fontSize: 10,
                background: filter === f ? '#534AB7' : 'transparent',
                color: filter === f ? '#FFFFFF' : '#5F5E5A',
                border: '0.5px solid rgba(17,24,28,0.3)', borderRadius: 3, cursor: 'pointer'
              }}>{f}</button>
            ))}
          </div>
        </div>

        {filtered.length === 0 ? (
          <div style={{ padding: 30, textAlign: 'center', color: '#888780', fontSize: 13 }}>
            暂无版本记录
          </div>
        ) : (
          <table style={{ width: '100%', fontSize: 11, borderCollapse: 'collapse' }}>
            <thead>
              <tr style={{ background: '#FAFCF5' }}>
                <th style={{ padding: '8px 10px', textAlign: 'left', color: '#5F5E5A' }}>版本</th>
                <th style={{ padding: '8px 10px', textAlign: 'left', color: '#5F5E5A' }}>SHA256</th>
                <th style={{ padding: '8px 10px', textAlign: 'right', color: '#5F5E5A' }}>大小</th>
                <th style={{ padding: '8px 10px', textAlign: 'left', color: '#5F5E5A' }}>构建</th>
                <th style={{ padding: '8px 10px', textAlign: 'right', color: '#5F5E5A' }}>部署</th>
                <th style={{ padding: '8px 10px', textAlign: 'left', color: '#5F5E5A' }}>创建时间</th>
                <th style={{ padding: '8px 10px', textAlign: 'center', color: '#5F5E5A' }}>状态</th>
                {canManage && <th style={{ padding: '8px 10px', textAlign: 'right', color: '#5F5E5A' }}>操作</th>}
              </tr>
            </thead>
            <tbody>
              {filtered.map(v => (
                <tr key={v.id} style={{ borderTop: '0.5px solid rgba(17,24,28,0.05)' }}>
                  <td style={{ padding: '8px 10px', color: '#2C2C2A', fontWeight: 500 }}>{v.version}</td>
                  <td style={{ padding: '8px 10px', fontFamily: 'monospace', fontSize: 10, color: '#5F5E5A' }}>
                    {v.sha256 ? v.sha256.substring(0, 16) + '...' : '—'}
                  </td>
                  <td style={{ padding: '8px 10px', textAlign: 'right', color: '#5F5E5A' }}>{formatSize(v.size)}</td>
                  <td style={{ padding: '8px 10px', color: '#185FA5' }}>#{v.buildId}</td>
                  <td style={{ padding: '8px 10px', textAlign: 'right', color: '#534AB7', fontWeight: 500 }}>{v.deployCount || 0}</td>
                  <td style={{ padding: '8px 10px', color: '#888780', fontSize: 10 }}>
                    {new Date(v.createdAt).toLocaleString('zh-CN')}
                  </td>
                  <td style={{ padding: '8px 10px', textAlign: 'center' }}>
                    <StatusChip status={v.status} />
                  </td>
                  {canManage && (
                    <td style={{ padding: '8px 10px', textAlign: 'right' }}>
                      <div style={{ display: 'flex', gap: 4, justifyContent: 'flex-end' }}>
                        <button
                          onClick={() => handleVerify(v)}
                          disabled={verifying === v.id || !v.sha256}
                          style={{
                            padding: '2px 8px', fontSize: 10,
                            background: verifying === v.id ? '#F1EFE8' : 'transparent',
                            color: '#5F5E5A', border: '0.5px solid rgba(17,24,28,0.3)',
                            borderRadius: 3, cursor: verifying === v.id ? 'wait' : 'pointer'
                          }}>
                          {verifying === v.id ? '校验中...' : '🔒 校验'}
                        </button>
                        <button onClick={() => handleMarkDeployed(v)} style={{
                          padding: '2px 8px', fontSize: 10,
                          background: '#EAF3DE', color: '#27500A', border: 'none',
                          borderRadius: 3, cursor: 'pointer'
                        }}>📤 部署</button>
                        {v.status !== 'DELETED' && (
                          <button onClick={() => handleDelete(v)} style={{
                            padding: '2px 8px', fontSize: 10,
                            background: 'transparent', color: '#791F1F', border: '0.5px solid rgba(121,31,31,0.3)',
                            borderRadius: 3, cursor: 'pointer'
                          }}>🗑 软删</button>
                        )}
                      </div>
                      {verifyResult && verifyResult.versionId === v.id && (
                        <p style={{ fontSize: 10, color: verifyResult.integrityValid ? '#27500A' : '#791F1F', margin: '4px 0 0' }}>
                          {verifyResult.message}
                        </p>
                      )}
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div style={{ marginTop: 16, padding: 10, background: '#F1EFE8', borderRadius: 4, fontSize: 11, color: '#5F5E5A' }}>
        💡 保留策略: 同一个 artifact 最多保留 5 个 ACTIVE 版本, 超过自动标记为 RETAINED (不删除, 可回滚)
      </div>
    </div>
  )
}
