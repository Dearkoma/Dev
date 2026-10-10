import React, { useState, useEffect, useCallback } from 'react'
import { useAuth } from '../context/AuthContext'
import {
  fetchWebhooks, createWebhook, updateWebhook, deleteWebhook,
  toggleWebhook, testWebhook, fetchProjects
} from '../api'

const TYPE_LABELS = {
  FEISHU:   { label: '飞书', icon: '🪶', color: '#378ADD' },
  DINGTALK: { label: '钉钉', icon: '📌', color: '#378ADD' },
  WECOM:    { label: '企微', icon: '🏢', color: '#3B6D11' },
  CUSTOM:   { label: '自定义', icon: '🔗', color: '#5F5E5A' }
}

const EVENT_OPTIONS = [
  { value: 'build.success',   label: '构建成功', color: '#27500A' },
  { value: 'build.failed',    label: '构建失败', color: '#791F1F' },
  { value: 'deploy.success',  label: '部署成功', color: '#27500A' },
  { value: 'deploy.failed',   label: '部署失败', color: '#791F1F' },
  { value: 'security.failed', label: '安全扫描失败', color: '#854F0B' },
  { value: 'instance.down',   label: '实例宕机', color: '#791F1F' }
]

const DEFAULT_FORM = {
  name: '', type: 'FEISHU', eventType: 'build.success',
  url: '', secret: '', projectId: '', active: true
}

function StatusChip({ active }) {
  return (
    <span style={{
      background: active ? '#EAF3DE' : '#F1EFE8',
      color: active ? '#27500A' : '#5F5E5A',
      padding: '2px 8px', borderRadius: 3, fontSize: 10, fontWeight: 500
    }}>{active ? '启用' : '禁用'}</span>
  )
}

function EventChip({ event }) {
  const o = EVENT_OPTIONS.find(e => e.value === event) || { label: event, color: '#5F5E5A' }
  return <span style={{ background: o.color, color: '#FFFFFF', padding: '2px 8px', borderRadius: 3, fontSize: 10, fontWeight: 500 }}>{o.label}</span>
}

export default function WebhookPanel() {
  const { canManage } = useAuth()
  const [subs, setSubs] = useState([])
  const [projects, setProjects] = useState([])
  const [loading, setLoading] = useState(true)
  const [showModal, setShowModal] = useState(false)
  const [editing, setEditing] = useState(null)
  const [form, setForm] = useState(DEFAULT_FORM)
  const [filter, setFilter] = useState('ALL')

  const load = useCallback(async () => {
    setLoading(true)
    try { setSubs(await fetchWebhooks()) }
    catch (e) { console.error(e) }
    finally { setLoading(false) }
  }, [])

  useEffect(() => { load() }, [load])
  useEffect(() => { fetchProjects().then(p => setProjects(p || [])).catch(() => setProjects([])) }, [])

  /** 项目 ID → 名称，用于展示订阅范围 */
  const projectLabel = (id) => {
    if (id === null || id === undefined || id === '') return '全局（所有项目）'
    const p = projects.find(x => String(x.id) === String(id))
    return p ? `项目 ${p.name}` : `项目 #${id}`
  }

  const openNew = () => { setEditing(null); setForm(DEFAULT_FORM); setShowModal(true) }
  const openEdit = (sub) => {
    setEditing(sub)
    setForm({
      name: sub.name || '', type: sub.type, eventType: sub.eventType,
      url: sub.url || '', secret: sub.secret || '',
      projectId: sub.projectId != null ? String(sub.projectId) : '', active: sub.active
    })
    setShowModal(true)
  }

  const handleSave = async () => {
    if (!form.name || !form.url) { alert('名称和 URL 必填'); return }
    try {
      const payload = {
        ...form,
        projectId: form.projectId ? Number(form.projectId) : null
      }
      if (editing) {
        await updateWebhook(editing.id, payload)
      } else {
        await createWebhook(payload)
      }
      setShowModal(false)
      load()
    } catch (e) { alert('保存失败: ' + e.message) }
  }

  const handleDelete = async (sub) => {
    if (!confirm(`删除订阅「${sub.name}」?`)) return
    try { await deleteWebhook(sub.id); load() } catch (e) { alert(e.message) }
  }

  const handleToggle = async (sub) => {
    try { await toggleWebhook(sub.id); load() } catch (e) { alert(e.message) }
  }

  const handleTest = async (sub) => {
    try {
      const r = await testWebhook(sub.id)
      alert(`✅ ${r.message}\n事件类型: ${r.eventType}`)
    } catch (e) { alert('测试失败: ' + e.message) }
  }

  if (loading && subs.length === 0) return <div style={{ padding: 24 }}><div className="spinner" /></div>

  const filtered = filter === 'ALL' ? subs : subs.filter(s => s.type === filter)

  return (
    <div style={{ padding: 24 }}>
      <h2 style={{ fontSize: 18, fontWeight: 500, margin: '0 0 8px' }}>Webhook 订阅</h2>
      <p style={{ fontSize: 13, color: '#888780', margin: '0 0 16px' }}>
        把平台事件 (构建/部署/安全/实例) 推送到飞书 / 钉钉 / 企微 / 自定义 Webhook
      </p>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 12, marginBottom: 20 }}>
        <div style={{ background: '#F8F7F2', borderRadius: 6, padding: '12px 16px' }}>
          <p style={{ fontSize: 11, color: '#888780', margin: 0 }}>总订阅数</p>
          <p style={{ fontSize: 22, fontWeight: 500, color: '#185FA5', margin: '4px 0 0' }}>{subs.length}</p>
        </div>
        <div style={{ background: '#F8F7F2', borderRadius: 6, padding: '12px 16px' }}>
          <p style={{ fontSize: 11, color: '#888780', margin: 0 }}>启用中</p>
          <p style={{ fontSize: 22, fontWeight: 500, color: '#27500A', margin: '4px 0 0' }}>{subs.filter(s => s.active).length}</p>
        </div>
        <div style={{ background: '#F8F7F2', borderRadius: 6, padding: '12px 16px' }}>
          <p style={{ fontSize: 11, color: '#888780', margin: 0 }}>已禁用</p>
          <p style={{ fontSize: 22, fontWeight: 500, color: '#791F1F', margin: '4px 0 0' }}>{subs.filter(s => !s.active).length}</p>
        </div>
        <div style={{ background: '#F8F7F2', borderRadius: 6, padding: '12px 16px' }}>
          <p style={{ fontSize: 11, color: '#888780', margin: 0 }}>连续失败</p>
          <p style={{ fontSize: 22, fontWeight: 500, color: '#BA7517', margin: '4px 0 0' }}>{subs.filter(s => s.failCount >= 3).length}</p>
        </div>
      </div>

      <div style={{ background: '#FFFFFF', border: '0.5px solid rgba(17,24,28,0.15)', borderRadius: 8, overflow: 'hidden' }}>
        <div style={{ padding: '10px 12px', background: '#F8F7F2', borderBottom: '0.5px solid rgba(17,24,28,0.15)', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <p style={{ fontSize: 12, fontWeight: 500, margin: 0 }}>订阅列表</p>
          <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
            <select value={filter} onChange={e => setFilter(e.target.value)} style={{ padding: '2px 6px', fontSize: 11 }}>
              <option value="ALL">全部</option>
              <option value="FEISHU">飞书</option>
              <option value="DINGTALK">钉钉</option>
              <option value="WECOM">企微</option>
              <option value="CUSTOM">自定义</option>
            </select>
            {canManage && (
              <button onClick={openNew} style={{
                padding: '4px 12px', fontSize: 12, background: '#534AB7', color: '#FFFFFF',
                border: 'none', borderRadius: 4, cursor: 'pointer'
              }}>+ 新建订阅</button>
            )}
          </div>
        </div>

        {filtered.length === 0 ? (
          <div style={{ padding: 30, textAlign: 'center', color: '#888780', fontSize: 13 }}>
            暂无订阅 — 点击"新建订阅"添加飞书/钉钉/企微机器人
          </div>
        ) : (
          <table style={{ width: '100%', fontSize: 11, borderCollapse: 'collapse' }}>
            <thead>
              <tr style={{ background: '#FAFCF5' }}>
                <th style={{ padding: '8px 10px', textAlign: 'left', color: '#5F5E5A' }}>名称</th>
                <th style={{ padding: '8px 10px', textAlign: 'left', color: '#5F5E5A' }}>类型</th>
                <th style={{ padding: '8px 10px', textAlign: 'left', color: '#5F5E5A' }}>事件</th>
                <th style={{ padding: '8px 10px', textAlign: 'left', color: '#5F5E5A' }}>范围</th>
                <th style={{ padding: '8px 10px', textAlign: 'left', color: '#5F5E5A' }}>URL</th>
                <th style={{ padding: '8px 10px', textAlign: 'center', color: '#5F5E5A' }}>状态</th>
                <th style={{ padding: '8px 10px', textAlign: 'right', color: '#5F5E5A' }}>失败数</th>
                <th style={{ padding: '8px 10px', textAlign: 'left', color: '#5F5E5A' }}>最后触发</th>
                {canManage && <th style={{ padding: '8px 10px', textAlign: 'right', color: '#5F5E5A' }}>操作</th>}
              </tr>
            </thead>
            <tbody>
              {filtered.map(s => {
                const t = TYPE_LABELS[s.type] || TYPE_LABELS.CUSTOM
                return (
                  <tr key={s.id} style={{ borderTop: '0.5px solid rgba(17,24,28,0.05)' }}>
                    <td style={{ padding: '8px 10px', color: '#2C2C2A', fontWeight: 500 }}>{s.name}</td>
                    <td style={{ padding: '8px 10px' }}>
                      <span style={{ color: t.color, fontWeight: 500 }}>{t.icon} {t.label}</span>
                    </td>
                    <td style={{ padding: '8px 10px' }}><EventChip event={s.eventType} /></td>
                    <td style={{ padding: '8px 10px', color: s.projectId ? '#185FA5' : '#888780', fontSize: 10 }}>
                      {projectLabel(s.projectId)}
                    </td>
                    <td style={{ padding: '8px 10px', fontFamily: 'monospace', fontSize: 10, color: '#5F5E5A', maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis' }}>
                      {s.url}
                    </td>
                    <td style={{ padding: '8px 10px', textAlign: 'center' }}>
                      <StatusChip active={s.active} />
                    </td>
                    <td style={{ padding: '8px 10px', textAlign: 'right', color: s.failCount > 0 ? '#BA7517' : '#888780', fontWeight: s.failCount > 0 ? 500 : 400 }}>
                      {s.failCount || 0}
                    </td>
                    <td style={{ padding: '8px 10px', color: '#888780', fontSize: 10 }}>
                      {s.lastTriggeredAt ? new Date(s.lastTriggeredAt).toLocaleString('zh-CN') : '—'}
                      {s.lastTriggerStatus && (
                        <span style={{ color: s.lastTriggerStatus.startsWith('2') ? '#27500A' : '#791F1F', marginLeft: 4 }}>
                          [{s.lastTriggerStatus}]
                        </span>
                      )}
                    </td>
                    {canManage && (
                      <td style={{ padding: '8px 10px', textAlign: 'right' }}>
                        <div style={{ display: 'flex', gap: 4, justifyContent: 'flex-end' }}>
                          <button onClick={() => handleTest(s)} style={{
                            padding: '2px 8px', fontSize: 10, background: 'transparent',
                            color: '#378ADD', border: '0.5px solid #378ADD', borderRadius: 3, cursor: 'pointer'
                          }}>🧪 测试</button>
                          <button onClick={() => handleToggle(s)} style={{
                            padding: '2px 8px', fontSize: 10, background: 'transparent',
                            color: '#5F5E5A', border: '0.5px solid rgba(17,24,28,0.3)', borderRadius: 3, cursor: 'pointer'
                          }}>{s.active ? '禁用' : '启用'}</button>
                          <button onClick={() => openEdit(s)} style={{
                            padding: '2px 8px', fontSize: 10, background: 'transparent',
                            color: '#5F5E5A', border: '0.5px solid rgba(17,24,28,0.3)', borderRadius: 3, cursor: 'pointer'
                          }}>编辑</button>
                          <button onClick={() => handleDelete(s)} style={{
                            padding: '2px 8px', fontSize: 10, background: 'transparent',
                            color: '#791F1F', border: '0.5px solid rgba(121,31,31,0.3)', borderRadius: 3, cursor: 'pointer'
                          }}>删除</button>
                        </div>
                      </td>
                    )}
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
      </div>

      <div style={{ marginTop: 16, padding: 10, background: '#F1EFE8', borderRadius: 4, fontSize: 11, color: '#5F5E5A' }}>
        💡 飞书/钉钉/企微: 复制机器人的 Webhook URL 填入即可 · 自定义: 支持 HMAC-SHA256 签名
        (X-DevOps-Signature 头), 接收端用同一密钥验证来源
        <br/>📌 一条订阅只对应一个事件；想同时收「构建成功」和「构建失败」需要建两条订阅
        <br/>📌 订阅范围留空 = 全局订阅，任何项目的事件都会推送；指定项目则只推该项目的事件
      </div>

      {showModal && (
        <div style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, background: 'rgba(0,0,0,0.5)', zIndex: 1000, display: 'flex', alignItems: 'center', justifyContent: 'center' }} onClick={() => setShowModal(false)}>
          <div onClick={e => e.stopPropagation()} style={{ background: '#FFFFFF', borderRadius: 8, padding: 20, maxWidth: 500, width: '90%' }}>
            <p style={{ fontSize: 14, fontWeight: 500, margin: '0 0 12px' }}>{editing ? '编辑订阅' : '新建订阅'}</p>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
              <div>
                <label style={{ fontSize: 11, color: '#5F5E5A' }}>名称</label>
                <input value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} style={{ width: '100%', padding: '6px 8px', fontSize: 12, marginTop: 2 }} placeholder="apartment 飞书通知"/>
              </div>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
                <div>
                  <label style={{ fontSize: 11, color: '#5F5E5A' }}>类型</label>
                  <select value={form.type} onChange={e => setForm({ ...form, type: e.target.value })} style={{ width: '100%', padding: '6px 8px', fontSize: 12, marginTop: 2 }}>
                    <option value="FEISHU">飞书</option>
                    <option value="DINGTALK">钉钉</option>
                    <option value="WECOM">企微</option>
                    <option value="CUSTOM">自定义</option>
                  </select>
                </div>
                <div>
                  <label style={{ fontSize: 11, color: '#5F5E5A' }}>事件</label>
                  <select value={form.eventType} onChange={e => setForm({ ...form, eventType: e.target.value })} style={{ width: '100%', padding: '6px 8px', fontSize: 12, marginTop: 2 }}>
                    {EVENT_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
                  </select>
                </div>
              </div>
              <div>
                <label style={{ fontSize: 11, color: '#5F5E5A' }}>Webhook URL</label>
                <input value={form.url} onChange={e => setForm({ ...form, url: e.target.value })} style={{ width: '100%', padding: '6px 8px', fontSize: 11, marginTop: 2, fontFamily: 'monospace' }}
                  placeholder="https://open.feishu.cn/..."/>
              </div>
              <div>
                <label style={{ fontSize: 11, color: '#5F5E5A' }}>Secret (HMAC 签名密钥, 可选)</label>
                <input value={form.secret} onChange={e => setForm({ ...form, secret: e.target.value })} style={{ width: '100%', padding: '6px 8px', fontSize: 11, marginTop: 2, fontFamily: 'monospace' }} placeholder="用于 X-DevOps-Signature 头, 接收端用同一密钥验证"/>
              </div>
              <div>
                <label style={{ fontSize: 11, color: '#5F5E5A' }}>订阅范围</label>
                <select value={form.projectId} onChange={e => setForm({ ...form, projectId: e.target.value })} style={{ width: '100%', padding: '6px 8px', fontSize: 12, marginTop: 2 }}>
                  <option value="">全局 — 接收所有项目的事件</option>
                  {projects.map(p => <option key={p.id} value={String(p.id)}>{p.name}</option>)}
                </select>
                <p style={{ fontSize: 10, color: '#888780', margin: '4px 0 0' }}>
                  选定项目后，只有该项目的事件会推到这里；留空则所有项目共用这一个订阅
                </p>
              </div>
            </div>
            <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 16 }}>
              <button onClick={() => setShowModal(false)} style={{ padding: '6px 14px', fontSize: 12, background: 'transparent', border: '0.5px solid rgba(17,24,28,0.3)', borderRadius: 4, cursor: 'pointer' }}>取消</button>
              <button onClick={handleSave} style={{ padding: '6px 14px', fontSize: 12, background: '#534AB7', color: '#FFFFFF', border: 'none', borderRadius: 4, cursor: 'pointer' }}>保存</button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
