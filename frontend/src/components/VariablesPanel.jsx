import { useState, useEffect, useCallback } from 'react'
import { fetchVariables, createVariable, updateVariable, deleteVariable } from '../api'
import { useAuth } from '../context/AuthContext'

/**
 * 构建变量管理（弹窗内容）—— 项目级 + 流水线级环境变量
 * 变量在构建时由 BuildService 注入，用于流水线步骤的 when 条件（var.xxx / param.xxx）。
 * secret 变量由后端 AES-GCM 加密存储，列表只显示密文占位、不回显明文。
 *
 * @param pipeline  所属流水线 { id, name, projectId }
 * @param onClose   关闭回调
 */
export default function VariablesPanel({ pipeline, onClose }) {
  const { canManage } = useAuth()
  const [vars, setVars] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [saving, setSaving] = useState(false)
  const [editingId, setEditingId] = useState(null)
  const emptyForm = { varKey: '', value: '', description: '', secret: false, scope: 'pipeline' }
  const [form, setForm] = useState(emptyForm)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const data = await fetchVariables(pipeline.projectId, null)
      setVars(Array.isArray(data) ? data : [])
    } catch (e) {
      setError('加载失败: ' + e.message)
    } finally {
      setLoading(false)
    }
  }, [pipeline.projectId])

  useEffect(() => { load() }, [load])

  const handleSubmit = async () => {
    if (!form.varKey.trim()) { alert('变量名不能为空'); return }
    setSaving(true)
    try {
      const body = {
        varKey: form.varKey.trim(),
        value: form.value,
        description: form.description,
        secret: form.secret,
        projectId: pipeline.projectId,
        pipelineId: form.scope === 'pipeline' ? pipeline.id : null,
      }
      if (editingId) {
        await updateVariable(editingId, body)
      } else {
        await createVariable(body)
      }
      setForm(emptyForm)
      setEditingId(null)
      await load()
    } catch (e) {
      alert('保存失败: ' + e.message)
    } finally {
      setSaving(false)
    }
  }

  const handleEdit = (v) => {
    setEditingId(v.id)
    setForm({
      varKey: v.varKey,
      // secret 变量不回显密文，留空表示不修改
      value: v.secret ? '' : (v.value || ''),
      description: v.description || '',
      secret: !!v.secret,
      scope: v.pipelineId ? 'pipeline' : 'project',
    })
  }

  const handleDelete = async (v) => {
    if (!confirm(`确定删除变量「${v.varKey}」？`)) return
    try {
      await deleteVariable(v.id)
      await load()
    } catch (e) {
      alert('删除失败: ' + e.message)
    }
  }

  // 排序：项目级在前，流水线级在后
  const sorted = [...vars].sort((a, b) => (a.pipelineId ? 1 : 0) - (b.pipelineId ? 1 : 0))

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" style={{ maxWidth: 680 }} onClick={e => e.stopPropagation()}>
        <h3>🔐 构建变量 — {pipeline.name}</h3>
        <p style={{ fontSize: 12, color: '#6b7280', margin: '4px 0 12px' }}>
          变量在构建时注入，可用于流水线步骤的 when 条件（如 <code>var.DEPLOY_ENV=prod</code>）。
          项目级变量对该项目的所有流水线生效；流水线级仅当前流水线生效，且同名时优先。
        </p>

        {error && (
          <div style={{ padding: 10, background: '#fef2f2', borderRadius: 6, color: '#991b1b', fontSize: 12, marginBottom: 10 }}>
            ⚠️ {error}
          </div>
        )}

        {/* 变量列表 */}
        <div style={{ maxHeight: 300, overflow: 'auto', marginBottom: 14, border: '1px solid #e5e7eb', borderRadius: 6 }}>
          {loading ? (
            <div style={{ padding: 20, textAlign: 'center', color: '#9ca3af', fontSize: 13 }}>加载中...</div>
          ) : sorted.length === 0 ? (
            <div style={{ padding: 20, textAlign: 'center', color: '#9ca3af', fontSize: 13 }}>
              暂无变量，在下方新增第一条
            </div>
          ) : (
            sorted.map(v => (
              <div key={v.id} style={{
                display: 'flex', alignItems: 'center', gap: 10, padding: '8px 12px',
                borderBottom: '1px solid #f3f4f6', fontSize: 13
              }}>
                <span style={{
                  padding: '1px 6px', borderRadius: 8, fontSize: 11, flex: '0 0 auto',
                  background: v.pipelineId ? '#eef2ff' : '#ecfdf5',
                  color: v.pipelineId ? '#3730a3' : '#166534'
                }}>
                  {v.pipelineId ? '本流水线' : '项目级'}
                </span>
                <code style={{ fontWeight: 600 }}>{v.varKey}</code>
                <span style={{ flex: 1, color: '#6b7280', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {v.secret ? '🔒 已加密存储（不回显）' : `= ${v.value ?? ''}`}
                  {v.description && <span style={{ marginLeft: 8, color: '#9ca3af' }}>· {v.description}</span>}
                </span>
                {canManage && (
                  <span style={{ display: 'flex', gap: 6, flex: '0 0 auto' }}>
                    <button className="btn btn-outline btn-sm" onClick={() => handleEdit(v)}>✏️</button>
                    <button className="btn btn-danger btn-sm" onClick={() => handleDelete(v)}>🗑</button>
                  </span>
                )}
              </div>
            ))
          )}
        </div>

        {/* 新增 / 编辑表单 */}
        {canManage && (
          <div style={{ padding: 12, background: '#f8fafc', borderRadius: 8 }}>
            <div style={{ fontSize: 12, fontWeight: 600, marginBottom: 8 }}>
              {editingId ? '编辑变量（secret 留空 = 不修改值）' : '新增变量'}
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1.4fr', gap: 8, marginBottom: 8 }}>
              <input value={form.varKey} onChange={e => setForm({ ...form, varKey: e.target.value })}
                placeholder="变量名，如 DEPLOY_ENV" />
              <input value={form.value} onChange={e => setForm({ ...form, value: e.target.value })}
                type={form.secret ? 'password' : 'text'}
                placeholder={form.secret ? '值（加密存储）' : '值'} />
            </div>
            <div style={{ display: 'flex', gap: 12, alignItems: 'center', marginBottom: 10, fontSize: 12 }}>
              <input value={form.description} onChange={e => setForm({ ...form, description: e.target.value })}
                placeholder="描述（可选）" style={{ flex: 1 }} />
              <label style={{ display: 'flex', gap: 4, alignItems: 'center', flex: '0 0 auto' }}>
                <input type="checkbox" checked={form.secret}
                  onChange={e => setForm({ ...form, secret: e.target.checked })} /> 密钥
              </label>
              <select value={form.scope} onChange={e => setForm({ ...form, scope: e.target.value })}
                style={{ flex: '0 0 auto' }} disabled={!!editingId}>
                <option value="pipeline">本流水线</option>
                <option value="project">项目级</option>
              </select>
            </div>
            <div style={{ display: 'flex', gap: 8 }}>
              <button className="btn btn-success btn-sm" onClick={handleSubmit} disabled={saving}>
                {saving ? '保存中...' : editingId ? '保存修改' : '＋ 添加变量'}
              </button>
              {editingId && (
                <button className="btn btn-outline btn-sm" onClick={() => { setEditingId(null); setForm(emptyForm) }}>
                  取消编辑
                </button>
              )}
            </div>
          </div>
        )}

        <div className="btn-group" style={{ justifyContent: 'flex-end', marginTop: 14 }}>
          <button className="btn btn-outline" onClick={onClose}>关闭</button>
        </div>
      </div>
    </div>
  )
}
