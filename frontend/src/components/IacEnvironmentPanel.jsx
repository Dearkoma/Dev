import React, { useState, useEffect, useCallback } from 'react'
import { useAuth } from '../context/AuthContext'
import {
  fetchEnvTemplates, cloneEnvFromTemplate, cloneEnvironment,
  fetchEnvNamespaceYaml, fetchEnvQuotaYaml
} from '../api'

const TYPE_COLORS = {
  dev:     '#3B6D11',
  test:    '#BA7517',
  staging: '#534AB7',
  prod:    '#A32D2D',
  custom:  '#5F5E5A'
}

const TYPE_ICONS = {
  dev: '🟢', test: '🟡', staging: '🟣', prod: '🔴', custom: '⚪'
}

function ResourceChip({ label, value }) {
  return (
    <div style={{ background: '#F8F7F2', borderRadius: 4, padding: '4px 8px' }}>
      <p style={{ fontSize: 9, color: '#888780', margin: 0 }}>{label}</p>
      <p style={{ fontSize: 11, fontWeight: 500, color: '#2C2C2A', margin: '2px 0 0' }}>{value || '—'}</p>
    </div>
  )
}

function YamlViewer({ yaml, onClose }) {
  if (!yaml) return null
  return (
    <div style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, background: 'rgba(0,0,0,0.5)', zIndex: 1000, display: 'flex', alignItems: 'center', justifyContent: 'center' }} onClick={onClose}>
      <div onClick={e => e.stopPropagation()} style={{ background: '#FFFFFF', borderRadius: 8, padding: 20, maxWidth: 700, width: '90%', maxHeight: '80vh', overflow: 'auto' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
          <p style={{ fontSize: 14, fontWeight: 500, margin: 0 }}>K8s YAML 预览</p>
          <button onClick={onClose} style={{ background: 'transparent', border: 'none', fontSize: 18, cursor: 'pointer' }}>×</button>
        </div>
        <pre style={{ background: '#2C2C2A', color: '#97C459', padding: 14, borderRadius: 4, fontSize: 12, lineHeight: 1.6, overflow: 'auto', fontFamily: 'monospace' }}>
          {yaml}
        </pre>
        <div style={{ marginTop: 8, fontSize: 11, color: '#888780' }}>
          💡 复制后可用 <code style={{ background: '#F1EFE8', padding: '0 4px' }}>kubectl apply -f -</code> 部署
        </div>
      </div>
    </div>
  )
}

export default function IacEnvironmentPanel() {
  const { canManage } = useAuth()
  const [templates, setTemplates] = useState([])
  const [loading, setLoading] = useState(true)
  const [cloneModal, setCloneModal] = useState(null)  // template 选中
  const [yamlModal, setYamlModal] = useState(null)    // {title, content}
  const [cloneForm, setCloneForm] = useState({ projectCode: '', projectId: '', nameOverride: '' })

  const load = useCallback(async () => {
    setLoading(true)
    try { setTemplates(await fetchEnvTemplates()) }
    catch (e) { console.error(e) }
    finally { setLoading(false) }
  }, [])

  useEffect(() => { load() }, [load])

  const handleClone = async () => {
    if (!cloneForm.projectCode) { alert('请填项目编码'); return }
    try {
      await cloneEnvFromTemplate(
        cloneModal.id,
        cloneForm.projectCode,
        cloneForm.projectId ? Number(cloneForm.projectId) : null,
        cloneForm.nameOverride
      )
      setCloneModal(null)
      setCloneForm({ projectCode: '', projectId: '', nameOverride: '' })
      alert('✅ 环境已创建, 请到「部署管理」查看')
    } catch (e) { alert('创建失败: ' + e.message) }
  }

  const handleViewYaml = async (env, type) => {
    try {
      const data = type === 'namespace' ? await fetchEnvNamespaceYaml(env.id) : await fetchEnvQuotaYaml(env.id)
      setYamlModal({ title: `${env.name} - ${type === 'namespace' ? 'Namespace' : 'ResourceQuota'}`, content: data.yaml })
    } catch (e) { alert('生成失败: ' + e.message) }
  }

  if (loading) return <div style={{ padding: 24 }}><div className="spinner" /></div>

  const builtin = templates.filter(t => t.builtin)
  const custom = templates.filter(t => !t.builtin)

  return (
    <div style={{ padding: 24 }}>
      <h2 style={{ fontSize: 18, fontWeight: 500, margin: '0 0 8px' }}>环境管理 · IaC 模板</h2>
      <p style={{ fontSize: 13, color: '#888780', margin: '0 0 16px' }}>
        预置 dev/test/staging/prod 4 套模板 · 一键克隆为环境 · 自动生成 K8s Namespace + ResourceQuota YAML
      </p>

      <div style={{ marginBottom: 24 }}>
        <p style={{ fontSize: 13, fontWeight: 500, color: '#5F5E5A', margin: '0 0 12px' }}>📚 内置模板 ({builtin.length})</p>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))', gap: 12 }}>
          {builtin.map(t => (
            <div key={t.id} style={{
              background: '#FFFFFF', border: '0.5px solid rgba(17,24,28,0.15)',
              borderLeft: `4px solid ${t.color || TYPE_COLORS[t.templateType]}`,
              borderRadius: 6, padding: 14
            }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
                <p style={{ fontSize: 13, fontWeight: 500, color: '#2C2C2A', margin: 0 }}>
                  {TYPE_ICONS[t.templateType]} {t.name}
                </p>
                <span style={{ background: t.color, color: '#FFFFFF', padding: '1px 8px', borderRadius: 3, fontSize: 10, fontWeight: 500 }}>
                  {t.templateType.toUpperCase()}
                </span>
              </div>
              <p style={{ fontSize: 11, color: '#5F5E5A', margin: '0 0 10px' }}>{t.description}</p>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 4, marginBottom: 10 }}>
                <ResourceChip label="副本" value={t.replicas} />
                <ResourceChip label="CPU" value={t.cpuRequest + ' / ' + t.cpuLimit} />
                <ResourceChip label="内存" value={t.memoryRequest + ' / ' + t.memoryLimit} />
              </div>
              <div style={{ background: '#F8F7F2', borderRadius: 4, padding: '6px 8px', fontSize: 10, color: '#5F5E5A', marginBottom: 10 }}>
                <p style={{ margin: 0 }}>📦 <code style={{ background: '#FFFFFF', padding: '0 4px' }}>{t.k8sNamespacePattern}</code></p>
                <p style={{ margin: '4px 0 0' }}>🌐 <code style={{ background: '#FFFFFF', padding: '0 4px' }}>{t.ingressHostPattern}</code></p>
              </div>
              {canManage && (
                <button onClick={() => { setCloneModal(t); setCloneForm({ projectCode: '', projectId: '', nameOverride: t.name }) }}
                  style={{ width: '100%', padding: '6px 0', fontSize: 12, background: '#534AB7', color: '#FFFFFF', border: 'none', borderRadius: 4, cursor: 'pointer' }}>
                  📋 克隆为环境
                </button>
              )}
            </div>
          ))}
        </div>
      </div>

      {custom.length > 0 && (
        <div>
          <p style={{ fontSize: 13, fontWeight: 500, color: '#5F5E5A', margin: '0 0 12px' }}>⚙️ 自定义模板 ({custom.length})</p>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))', gap: 12 }}>
            {custom.map(t => (
              <div key={t.id} style={{ background: '#FFFFFF', border: '0.5px solid rgba(17,24,28,0.15)', borderRadius: 6, padding: 14 }}>
                <p style={{ fontSize: 13, fontWeight: 500, color: '#2C2C2A', margin: '0 0 8px' }}>{t.name}</p>
                <p style={{ fontSize: 11, color: '#5F5E5A', margin: '0 0 8px' }}>{t.description}</p>
                <button onClick={() => { setCloneModal(t); setCloneForm({ projectCode: '', projectId: '', nameOverride: t.name }) }}
                  style={{ width: '100%', padding: '6px 0', fontSize: 12, background: 'transparent', color: '#534AB7', border: '0.5px solid #534AB7', borderRadius: 4, cursor: 'pointer' }}>
                  克隆
                </button>
              </div>
            ))}
          </div>
        </div>
      )}

      <div style={{ marginTop: 24, padding: 12, background: '#F1EFE8', borderRadius: 6, fontSize: 11, color: '#5F5E5A' }}>
        💡 模板即 K8s 资源声明的"工厂预设" — 一次配置, 多次复用; 真正落地只需点"克隆"再点"生成 YAML" 即可拿到可 apply 的 K8s manifest
      </div>

      {cloneModal && (
        <div style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, background: 'rgba(0,0,0,0.5)', zIndex: 1000, display: 'flex', alignItems: 'center', justifyContent: 'center' }} onClick={() => setCloneModal(null)}>
          <div onClick={e => e.stopPropagation()} style={{ background: '#FFFFFF', borderRadius: 8, padding: 20, maxWidth: 460, width: '90%' }}>
            <p style={{ fontSize: 14, fontWeight: 500, margin: '0 0 12px' }}>从模板「{cloneModal.name}」克隆环境</p>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              <div>
                <label style={{ fontSize: 11, color: '#5F5E5A' }}>项目编码 (用于替换 {`{projectCode}`})</label>
                <input value={cloneForm.projectCode} onChange={e => setCloneForm({ ...cloneForm, projectCode: e.target.value })}
                  placeholder="apartment" style={{ width: '100%', padding: '6px 8px', fontSize: 12, marginTop: 2 }}/>
              </div>
              <div>
                <label style={{ fontSize: 11, color: '#5F5E5A' }}>项目 ID (可选, 关联项目)</label>
                <input type="number" value={cloneForm.projectId} onChange={e => setCloneForm({ ...cloneForm, projectId: e.target.value })}
                  style={{ width: '100%', padding: '6px 8px', fontSize: 12, marginTop: 2 }}/>
              </div>
              <div>
                <label style={{ fontSize: 11, color: '#5F5E5A' }}>环境名 (可选, 留空用默认)</label>
                <input value={cloneForm.nameOverride} onChange={e => setCloneForm({ ...cloneForm, nameOverride: e.target.value })}
                  style={{ width: '100%', padding: '6px 8px', fontSize: 12, marginTop: 2 }}/>
              </div>
              <div style={{ background: '#EEEDFE', borderRadius: 4, padding: 8, fontSize: 11, color: '#3C3489', marginTop: 4 }}>
                <strong>预览:</strong> namespace = <code style={{ background: '#FFFFFF', padding: '0 4px' }}>{cloneModal.k8sNamespacePattern.replace('{projectCode}', cloneForm.projectCode || 'XXX')}</code>
              </div>
            </div>
            <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 16 }}>
              <button onClick={() => setCloneModal(null)} style={{ padding: '6px 14px', fontSize: 12, background: 'transparent', border: '0.5px solid rgba(17,24,28,0.3)', borderRadius: 4, cursor: 'pointer' }}>取消</button>
              <button onClick={handleClone} style={{ padding: '6px 14px', fontSize: 12, background: '#534AB7', color: '#FFFFFF', border: 'none', borderRadius: 4, cursor: 'pointer' }}>创建环境</button>
            </div>
          </div>
        </div>
      )}

      {yamlModal && <YamlViewer yaml={yamlModal.content} onClose={() => setYamlModal(null)} />}
    </div>
  )
}
