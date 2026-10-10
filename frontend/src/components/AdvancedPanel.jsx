import { useState, useEffect } from 'react'
import {
  fetchAdvancedOverview, fetchAdvancedList, createAdvancedItem, deleteAdvancedItem,
  fetchSastSemgrepAvailable, triggerSastScan, fetchSastFindings
} from '../api'

const SECTIONS = [
  {
    key: 'clusters', title: '🌐 多集群管理', icon: '🖥️',
    desc: '管理多个 Kubernetes 集群（EKS/AKS/GKE/K3S），kubeconfig 加密存储',
    fields: [
      { key: 'name', label: '集群名称', type: 'text', required: true },
      { key: 'provider', label: '提供商', type: 'select', options: ['K8S', 'EKS', 'AKS', 'GKE', 'K3S'] },
      { key: 'endpoint', label: 'API Server', type: 'text' },
      { key: 'region', label: '区域', type: 'text' },
      { key: 'nodeCount', label: '节点数', type: 'number' },
    ]
  },
  {
    key: 'gitops', title: '🔄 GitOps (ArgoCD)', icon: '📦',
    desc: '声明式 GitOps，Git 仓库即部署真相源，自动同步 K8s 集群状态',
    fields: [
      { key: 'name', label: '应用名称', type: 'text', required: true },
      { key: 'gitRepoUrl', label: 'Git 仓库 URL', type: 'text', required: true },
      { key: 'gitPath', label: 'K8s YAML 路径', type: 'text', placeholder: '.' },
      { key: 'gitBranch', label: '分支', type: 'text' },
      { key: 'targetNamespace', label: '目标命名空间', type: 'text' },
      { key: 'syncPolicy', label: '同步策略', type: 'select', options: ['MANUAL', 'AUTO_SYNC', 'AUTO_PRUNE'] },
    ]
  },
  {
    key: 'feature-flags', title: '🚩 Feature Flags', icon: '🏴',
    desc: '特性开关/灰度发布，按百分比或用户定向开启功能',
    fields: [
      { key: 'key', label: '开关标识', type: 'text', required: true },
      { key: 'name', label: '显示名称', type: 'text', required: true },
      { key: 'description', label: '描述', type: 'text' },
      { key: 'flagType', label: '类型', type: 'select', options: ['BOOLEAN', 'PERCENTAGE', 'TARGETED'] },
      { key: 'rolloutPercent', label: '灰度百分比', type: 'number' },
      { key: 'environment', label: '环境', type: 'select', options: ['dev', 'staging', 'prod'] },
    ]
  },
  {
    key: 'chaos', title: '💥 混沌工程', icon: '🔥',
    desc: '主动注入故障（Pod Kill / 网络延迟 / CPU 压力），验证系统韧性',
    fields: [
      { key: 'name', label: '实验名称', type: 'text', required: true },
      { key: 'experimentType', label: '实验类型', type: 'select', options: ['POD_KILL', 'NETWORK_DELAY', 'CPU_STRESS', 'MEMORY_STRESS'] },
      { key: 'targetSelector', label: '目标选择器', type: 'text', placeholder: 'app=my-app' },
      { key: 'durationSeconds', label: '持续时间(秒)', type: 'number' },
    ]
  },
  {
    key: 'policies', title: '📜 OPA 策略即代码', icon: '🛡️',
    desc: 'Rego 策略语言定义部署合规规则（镜像来源/资源限制/安全上下文）',
    fields: [
      { key: 'name', label: '策略名称', type: 'text', required: true },
      { key: 'category', label: '分类', type: 'select', options: ['SECURITY', 'COMPLIANCE', 'RESOURCE', 'CUSTOM'] },
      { key: 'severity', label: '级别', type: 'select', options: ['DENY', 'WARN', 'AUDIT'] },
      { key: 'targetStage', label: '目标阶段', type: 'select', options: ['BUILD', 'DEPLOY', 'OPERATE'] },
    ]
  },
  {
    key: 'costs', title: '💰 成本管理', icon: '📊',
    desc: '按项目/环境/资源类型追踪云资源成本，支持月度账单',
    fields: [
      { key: 'resourceName', label: '资源名称', type: 'text', required: true },
      { key: 'resourceType', label: '资源类型', type: 'select', options: ['COMPUTE', 'STORAGE', 'NETWORK', 'DATABASE', 'OTHER'] },
      { key: 'costAmount', label: '金额 (CNY)', type: 'number' },
      { key: 'billingPeriod', label: '计费周期', type: 'select', options: ['HOURLY', 'DAILY', 'MONTHLY'] },
      { key: 'environment', label: '环境', type: 'select', options: ['dev', 'staging', 'prod'] },
    ]
  },
  {
    key: 'backups', title: '🗄️ 数据库备份', icon: '💾',
    desc: '定时自动备份 MySQL/PostgreSQL，支持保留策略和按需恢复',
    fields: [
      { key: 'name', label: '备份名称', type: 'text', required: true },
      { key: 'sourceDbName', label: '源数据库名', type: 'text', required: true },
      { key: 'cronExpression', label: 'Cron 表达式', type: 'text', placeholder: '0 2 * * *' },
      { key: 'retentionDays', label: '保留天数', type: 'number' },
      { key: 'backupType', label: '备份类型', type: 'select', options: ['MYSQL', 'POSTGRES', 'VOLUME', 'CONFIG'] },
    ]
  },
  {
    key: 'chatops', title: '🤖 ChatOps', icon: '💬',
    desc: 'IM 机器人交互式命令（飞书/钉钉/企微），@机器人 触发部署/查询/回滚',
    fields: [
      { key: 'command', label: '命令名', type: 'text', required: true },
      { key: 'description', label: '描述', type: 'text' },
      { key: 'usage_', label: '用法示例', type: 'text' },
      { key: 'permission', label: '所需角色', type: 'select', options: ['VIEWER', 'DEVELOPER', 'MANAGER', 'ADMIN'] },
    ]
  },
  {
    key: 'sast', title: '🔍 SAST 静态分析', icon: '🔎',
    desc: 'Semgrep/SonarQube 静态代码安全扫描，集成到 CI 流水线',
    fields: []
  },
  {
    key: 'hpa', title: '📈 HPA 弹性伸缩', icon: '⚡',
    desc: 'Horizontal Pod Autoscaler 配置，基于 CPU/内存/自定义指标自动扩缩容',
    fields: [
      { key: 'name', label: '配置名称', type: 'text', required: true },
      { key: 'targetDeployment', label: '目标 Deployment', type: 'text', required: true },
      { key: 'minReplicas', label: '最小副本数', type: 'number' },
      { key: 'maxReplicas', label: '最大副本数', type: 'number' },
      { key: 'targetCpuPercent', label: '目标 CPU %', type: 'number' },
      { key: 'targetMemoryPercent', label: '目标内存 %', type: 'number' },
    ]
  },
  {
    key: 'slsa', title: '🔐 SLSA 供应链安全', icon: '🔒',
    desc: 'SLSA L1-L3 构建来源证明（Provenance），in-toto 格式，制品签名验证',
    fields: []
  },
]

/**
 * SAST 静态代码分析专属视图 — 真实扫描 + 漏洞明细
 */
function SastView({ projectId }) {
  const [semgrep, setSemgrep] = useState(null)
  const [scanning, setScanning] = useState(false)
  const [scanResult, setScanResult] = useState(null)
  const [findings, setFindings] = useState([])
  const [expandedReport, setExpandedReport] = useState(null)

  useEffect(() => {
    fetchSastSemgrepAvailable().then(r => setSemgrep(r?.available)).catch(() => setSemgrep(false))
  }, [])

  const runScan = async () => {
    if (!projectId) { alert('请先在上方输入项目 ID'); return }
    setScanning(true)
    setScanResult(null)
    try {
      const r = await triggerSastScan({ projectId: parseInt(projectId), thresholdHigh: 0, thresholdCritical: 0 })
      setScanResult(r)
      if (r?.id) {
        const f = await fetchSastFindings(r.id)
        setFindings(Array.isArray(f) ? f : [])
        setExpandedReport(r.id)
      }
    } catch (e) {
      alert('SAST 扫描失败: ' + e.message)
    } finally {
      setScanning(false)
    }
  }

  const sevColor = (s) => ({
    CRITICAL: '#791F1F', HIGH: '#854F0B', MEDIUM: '#0C447C', LOW: '#27500A'
  }[s] || '#444')

  return (
    <div>
      <div style={{
        display: 'flex', alignItems: 'center', gap: 12, padding: 12, background: '#f8f9fa',
        borderRadius: 8, marginBottom: 12
      }}>
        <div style={{ flex: 1, fontSize: 12 }}>
          <div><strong>扫描引擎:</strong> {semgrep === null ? '检测中...' : semgrep ? '✅ Semgrep (真实规则集)' : '⚙️ 内置模式扫描器 (零依赖降级)'}</div>
          <div style={{ color: '#888', marginTop: 2 }}>
            扫描项目工作区源码，检测硬编码密钥 / SQL注入 / XSS / 命令注入 / 弱加密等 19 类漏洞模式
          </div>
        </div>
        <button onClick={runScan} disabled={scanning || !projectId}
          className="btn btn-primary" style={{ fontSize: 12 }}>
          {scanning ? '⏳ 扫描中...' : '🔍 触发 SAST 扫描'}
        </button>
      </div>

      {!projectId && (
        <div style={{ color: '#e65100', fontSize: 12, marginBottom: 12 }}>
          ⚠ 请先在上方"项目 ID"输入框填写要扫描的项目 ID
        </div>
      )}

      {scanResult && (
        <div style={{
          padding: 12, borderRadius: 8, marginBottom: 12, fontSize: 13,
          background: scanResult.status === 'FAILED' ? '#fce4e4' : '#e8f5e9',
          border: `1px solid ${scanResult.status === 'FAILED' ? '#ef9a9a' : '#a5d6a7'}`
        }}>
          <strong>{scanResult.scanner}</strong> 扫描完成 — {scanResult.summary}
        </div>
      )}

      {/* 漏洞明细 */}
      {findings.length > 0 && (
        <div style={{ maxHeight: 400, overflow: 'auto' }}>
          <h4 style={{ fontSize: 13, margin: '0 0 8px' }}>发现 {findings.length} 处代码安全问题</h4>
          {findings.map((f, i) => (
            <div key={f.id || i} style={{
              padding: '8px 10px', borderLeft: `3px solid ${sevColor(f.severity)}`,
              marginBottom: 6, background: '#fff', borderRadius: 4, fontSize: 12
            }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
                <span style={{
                  background: sevColor(f.severity), color: '#fff', padding: '1px 6px',
                  borderRadius: 3, fontSize: 10, fontWeight: 600
                }}>{f.severity}</span>
                <strong>{f.title || f.ruleId}</strong>
                {f.cweId && <span style={{ color: '#888', fontSize: 10 }}>{f.cweId}</span>}
              </div>
              <div style={{ color: '#666', marginBottom: 3 }}>{f.message}</div>
              <code style={{
                display: 'block', fontSize: 11, background: '#f5f5f5', padding: '4px 8px',
                borderRadius: 3, color: '#c62828', wordBreak: 'break-all'
              }}>
                {f.filePath}:{f.lineNumber}
              </code>
              {f.codeSnippet && (
                <pre style={{
                  margin: '4px 0 0', fontSize: 11, background: '#263238', color: '#aed581',
                  padding: '6px 8px', borderRadius: 3, overflow: 'auto', maxHeight: 80
                }}>{f.codeSnippet}</pre>
              )}
            </div>
          ))}
        </div>
      )}

      {scanResult && findings.length === 0 && scanResult.status === 'COMPLETED' && (
        <div style={{ textAlign: 'center', color: '#999', fontSize: 13, padding: 24 }}>
          ✅ 未发现代码安全问题
        </div>
      )}
    </div>
  )
}

export default function AdvancedPanel() {
  const [activeSection, setActiveSection] = useState('clusters')
  const [overview, setOverview] = useState(null)
  const [items, setItems] = useState([])
  const [loading, setLoading] = useState(false)
  const [projectId, setProjectId] = useState('')
  const [showAdd, setShowAdd] = useState(false)
  const [formData, setFormData] = useState({})

  useEffect(() => {
    loadOverview()
  }, [])

  useEffect(() => {
    if (activeSection) loadItems()
  }, [activeSection, projectId])

  const loadOverview = async () => {
    try {
      const data = await fetchAdvancedOverview()
      setOverview(data)
    } catch (e) { console.error('Failed to load advanced overview', e) }
  }

  const loadItems = async () => {
    setLoading(true)
    try {
      const params = projectId ? `?projectId=${projectId}` : ''
      const data = await fetchAdvancedList(activeSection, params)
      setItems(Array.isArray(data) ? data : [])
    } catch (e) { console.error('Failed to load items', e) }
    finally { setLoading(false) }
  }

  const section = SECTIONS.find(s => s.key === activeSection) || SECTIONS[0]

  const handleAdd = async () => {
    try {
      const payload = { ...formData }
      if (projectId) payload.projectId = parseInt(projectId)
      await createAdvancedItem(activeSection, payload)
      setShowAdd(false)
      setFormData({})
      loadItems()
      loadOverview()
    } catch (e) { alert('创建失败: ' + e.message) }
  }

  const handleDelete = async (id) => {
    if (!confirm('确认删除？')) return
    try {
      await deleteAdvancedItem(activeSection, id)
      loadItems()
      loadOverview()
    } catch (e) { alert('删除失败: ' + e.message) }
  }

  const handleAction = async (action, id) => {
    try {
      const { fetchApi } = await import('../api')
      await fetchApi(`/advanced/${activeSection}/${id}/${action}`, { method: 'POST' })
      loadItems()
    } catch (e) { alert('操作失败: ' + e.message) }
  }

  return (
    <div className="advanced-panel">
      <h2>🚀 高级能力（论文展望章节）</h2>
      <p style={{ color: '#888', fontSize: 13, marginBottom: 16 }}>
        以下能力代表 DevOps 平台的未来演进方向，涵盖了从 GitOps 到供应链安全的完整 DevOps 能力地图。
      </p>

      {/* 能力概览 */}
      {overview && (
        <div style={{
          display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(140px, 1fr))',
          gap: 8, marginBottom: 20
        }}>
          {Object.entries(overview).map(([key, val]) => (
            <div key={key} style={{
              padding: 10, background: '#f8f9fa', borderRadius: 8, textAlign: 'center', fontSize: 12
            }}>
              <div style={{ fontWeight: 700, fontSize: 18, color: '#1976d2' }}>{val.count || 0}</div>
              <div style={{ color: '#888', marginTop: 4 }}>{toLabel(key)}</div>
            </div>
          ))}
        </div>
      )}

      {/* 功能标签 */}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginBottom: 20 }}>
        {SECTIONS.map(s => (
          <button key={s.key}
            onClick={() => { setActiveSection(s.key); setShowAdd(false) }}
            style={{
              padding: '6px 12px', borderRadius: 16, border: '1px solid #ddd', cursor: 'pointer',
              fontSize: 12, background: activeSection === s.key ? '#1976d2' : '#fff',
              color: activeSection === s.key ? '#fff' : '#333',
              fontWeight: activeSection === s.key ? 600 : 400
            }}>
            {s.icon} {toLabel(s.key)}
          </button>
        ))}
      </div>

      {/* 当前功能详情 */}
      <div className="card" style={{ padding: 16 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
          <div>
            <h3 style={{ margin: 0 }}>{section.title}</h3>
            <p style={{ color: '#888', fontSize: 12, margin: '4px 0 0' }}>{section.desc}</p>
          </div>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <input type="number" value={projectId}
              onChange={e => setProjectId(e.target.value)}
              placeholder="项目 ID"
              style={{ width: 80, padding: '4px 8px', fontSize: 12 }} />
            {section.fields.length > 0 && (
              <button onClick={() => setShowAdd(!showAdd)} className="btn btn-primary" style={{ fontSize: 12 }}>
                {showAdd ? '取消' : '+ 新增'}
              </button>
            )}
          </div>
        </div>

        {/* 新增表单 */}
        {showAdd && (
          <div style={{ padding: 12, background: '#f5f5f5', borderRadius: 8, marginBottom: 12 }}>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
              {section.fields.map(f => (
                <div key={f.key}>
                  <label style={{ fontSize: 11, color: '#888' }}>{f.label}</label>
                  {f.type === 'select' ? (
                    <select value={formData[f.key] || ''}
                      onChange={e => setFormData({ ...formData, [f.key]: e.target.value })}
                      style={{ width: '100%', padding: '4px 6px', fontSize: 12, marginTop: 2 }}>
                      <option value="">选择...</option>
                      {f.options.map(o => <option key={o} value={o}>{o}</option>)}
                    </select>
                  ) : (
                    <input type={f.type} value={formData[f.key] || ''}
                      onChange={e => setFormData({ ...formData, [f.key]: f.type === 'number' ? Number(e.target.value) : e.target.value })}
                      placeholder={f.placeholder || f.label}
                      style={{ width: '100%', padding: '4px 6px', fontSize: 12, marginTop: 2 }} />
                  )}
                </div>
              ))}
            </div>
            <button onClick={handleAdd} className="btn btn-success" style={{ marginTop: 8, fontSize: 12 }}>
              ✅ 创建
            </button>
          </div>
        )}

        {/* SAST 专属视图 */}
        {activeSection === 'sast' && <SastView projectId={projectId} />}

        {/* 列表 */}
        {loading ? <div className="loading-spinner">加载中...</div> : (
          <div style={{ maxHeight: 400, overflow: 'auto' }}>
            {items.length === 0 && (
              <div style={{ padding: 24, textAlign: 'center', color: '#999', fontSize: 13 }}>
                暂无数据 — {section.desc}
              </div>
            )}
            {items.map((item, i) => (
              <div key={item.id || i} style={{
                padding: '8px 10px', borderBottom: '1px solid #f0f0f0', fontSize: 12,
                display: 'flex', justifyContent: 'space-between', alignItems: 'center'
              }}>
                <div style={{ flex: 1 }}>
                  <strong>{item.name || item.key || item.command || `#${item.id}`}</strong>
                  {item.status && (
                    <span style={{
                      marginLeft: 8, padding: '1px 6px', borderRadius: 4, fontSize: 10,
                      background: item.status === 'ACTIVE' || item.status === 'COMPLETED' || item.status === 'SYNCED' ? '#e8f5e9' : '#fff3e0',
                      color: item.status === 'ACTIVE' || item.status === 'COMPLETED' || item.status === 'SYNCED' ? '#2e7d32' : '#e65100'
                    }}>{item.status}</span>
                  )}
                  {item.enabled !== undefined && (
                    <span style={{
                      marginLeft: 4, fontSize: 10, color: item.enabled ? '#4caf50' : '#999'
                    }}>{item.enabled ? '● 启用' : '○ 禁用'}</span>
                  )}
                  <div style={{ color: '#aaa', fontSize: 11, marginTop: 2 }}>
                    {item.description || item.gitRepoUrl || item.targetDeployment || item.sourceDbName || item.summary || ''}
                  </div>
                </div>
                <div style={{ display: 'flex', gap: 4 }}>
                  {['sync', 'run', 'toggle'].includes(getAction(activeSection)) && (
                    <button onClick={() => handleAction(getAction(activeSection), item.id)}
                      className="btn btn-sm" style={{ fontSize: 11, padding: '2px 8px' }}>
                      {getActionLabel(activeSection)}
                    </button>
                  )}
                  <button onClick={() => handleDelete(item.id)}
                    style={{
                      padding: '2px 8px', fontSize: 11, border: 'none', background: '#ffebee',
                      color: '#c62828', borderRadius: 4, cursor: 'pointer'
                    }}>删除</button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}

function toLabel(key) {
  const map = {
    clusters: '多集群', gitops: 'GitOps', 'feature-flags': 'Feature Flags',
    chaos: '混沌工程', policies: 'OPA策略', costs: '成本管理',
    backups: '数据库备份', chatops: 'ChatOps', sast: 'SAST',
    hpa: 'HPA弹性伸缩', slsa: 'SLSA供应链',
    multiCluster: '多集群', gitOps: 'GitOps', featureFlags: 'Feature Flags',
    chaosEngineering: '混沌工程', policyAsCode: 'OPA策略', costManagement: '成本管理',
    backup: '备份', chatOps: 'ChatOps', slsaSupplyChain: 'SLSA'
  }
  return map[key] || key
}

function getAction(section) {
  const map = { gitops: 'sync', chaos: 'run', backups: 'run', hpa: 'toggle' }
  return map[section] || null
}

function getActionLabel(section) {
  const map = { gitops: '🔄 同步', chaos: '▶ 运行', backups: '▶ 备份', hpa: '🔀 切换' }
  return map[section] || '执行'
}
