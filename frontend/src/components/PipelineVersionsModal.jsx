import { useState, useEffect, useCallback } from 'react'
import { fetchPipelineVersions, rollbackPipelineVersion } from '../api'
import { useAuth } from '../context/AuthContext'

/**
 * 流水线版本历史（弹窗）—— 每次修改流水线定义都会自动保存一个版本快照。
 * 支持查看历史版本、回滚到指定版本（回滚前的当前定义会自动再存一个快照，历史不丢）。
 *
 * @param pipeline   所属流水线 { id, name }
 * @param onClose    关闭回调
 * @param onRollback 回滚成功后回调（用于父组件刷新流水线列表）
 */
export default function PipelineVersionsModal({ pipeline, onClose, onRollback }) {
  const { canManage } = useAuth()
  const [versions, setVersions] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [expandedId, setExpandedId] = useState(null)
  const [rolling, setRolling] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const data = await fetchPipelineVersions(pipeline.id)
      setVersions(Array.isArray(data) ? data : [])
    } catch (e) {
      setError('加载失败: ' + e.message)
    } finally {
      setLoading(false)
    }
  }, [pipeline.id])

  useEffect(() => { load() }, [load])

  const fmtTime = (iso) => {
    if (!iso) return '-'
    try { return new Date(iso).toLocaleString('zh-CN', { hour12: false }) } catch { return iso }
  }

  const handleRollback = async (v) => {
    if (!confirm(
      `确定回滚到版本 v${v.versionNumber}（${v.changedBy || '未知'} 于 ${fmtTime(v.createdAt)} 保存）？\n\n` +
      `回滚前的当前定义会自动保存为新版本，历史不会丢失。`
    )) return
    setRolling(true)
    try {
      await rollbackPipelineVersion(pipeline.id, v.id)
      alert(`已回滚到版本 v${v.versionNumber}`)
      onClose()
      if (onRollback) onRollback()
    } catch (e) {
      alert('回滚失败: ' + e.message)
    } finally {
      setRolling(false)
    }
  }

  // 定义概要：阶段名列表
  const summarize = (definition) => {
    try {
      const def = JSON.parse(definition)
      const stages = def.stages || []
      return stages.map(s => s.name || '?').join(' → ') || '(空流水线)'
    } catch {
      return '(定义解析失败)'
    }
  }

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" style={{ maxWidth: 640 }} onClick={e => e.stopPropagation()}>
        <h3>🕘 版本历史 — {pipeline.name}</h3>
        <p style={{ fontSize: 12, color: '#6b7280', margin: '4px 0 12px' }}>
          每次修改流水线定义都会自动保存快照。最新版本在最上方。
        </p>

        {error && (
          <div style={{ padding: 10, background: '#fef2f2', borderRadius: 6, color: '#991b1b', fontSize: 12, marginBottom: 10 }}>
            ⚠️ {error}
          </div>
        )}

        <div style={{ maxHeight: 380, overflow: 'auto', border: '1px solid #e5e7eb', borderRadius: 6 }}>
          {loading ? (
            <div style={{ padding: 20, textAlign: 'center', color: '#9ca3af', fontSize: 13 }}>加载中...</div>
          ) : versions.length === 0 ? (
            <div style={{ padding: 20, textAlign: 'center', color: '#9ca3af', fontSize: 13 }}>
              暂无历史版本（修改流水线定义后自动生成快照）
            </div>
          ) : (
            versions.map((v, idx) => (
              <div key={v.id} style={{ borderBottom: '1px solid #f3f4f6' }}>
                <div style={{
                  display: 'flex', alignItems: 'center', gap: 10, padding: '8px 12px', fontSize: 13,
                  cursor: 'pointer', background: expandedId === v.id ? '#f8fafc' : 'transparent'
                }} onClick={() => setExpandedId(expandedId === v.id ? null : v.id)}>
                  <span style={{
                    padding: '1px 7px', borderRadius: 8, fontSize: 11, fontWeight: 600,
                    background: idx === 0 ? '#dbeafe' : '#f3f4f6',
                    color: idx === 0 ? '#1d4ed8' : '#374151'
                  }}>
                    v{v.versionNumber}{idx === 0 ? ' · 最近变更' : ''}
                  </span>
                  <span style={{ flex: 1, color: '#374151', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {summarize(v.definition)}
                  </span>
                  <span style={{ fontSize: 11, color: '#9ca3af', flex: '0 0 auto' }}>
                    {v.changedBy || 'system'} · {fmtTime(v.createdAt)}
                  </span>
                  {canManage && (
                    <button className="btn btn-outline btn-sm" disabled={rolling}
                      onClick={e => { e.stopPropagation(); handleRollback(v) }}>
                      ↩ 回滚
                    </button>
                  )}
                </div>
                {expandedId === v.id && (
                  <pre style={{
                    margin: 0, padding: '10px 14px', fontSize: 11, lineHeight: 1.6,
                    background: '#fafafa', borderTop: '1px solid #f3f4f6',
                    maxHeight: 200, overflow: 'auto', whiteSpace: 'pre-wrap', wordBreak: 'break-all'
                  }}>
                    {tryFormat(v.definition)}
                  </pre>
                )}
              </div>
            ))
          )}
        </div>

        <div className="btn-group" style={{ justifyContent: 'flex-end', marginTop: 14 }}>
          <button className="btn btn-outline" onClick={onClose}>关闭</button>
        </div>
      </div>
    </div>
  )
}

function tryFormat(definition) {
  try { return JSON.stringify(JSON.parse(definition), null, 2) } catch { return definition }
}
