import React, { useState, useEffect, useCallback, useRef } from 'react'
import { useParams } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { fetchPipelines, createPipeline, updatePipeline, deletePipeline, fetchProjects, triggerBuild, checkDbConflict, fetchTemplates } from '../api'
import { emit, useResourceEvent } from '../eventBus'
import { useBuildWatch } from '../useBuildWatch'
import SchedulerList from './SchedulerList'
import VariablesPanel from './VariablesPanel'
import PipelineVersionsModal from './PipelineVersionsModal'
import { hasCapability, parseCapabilities, describeCapabilities, CAPABILITY_ORDER, CAPABILITY_LABEL } from '../capabilities'
import { templateFitsLanguage } from '../templateMatch'

/** 根据语言返回合适的默认构建步骤 */
function getDefaultBuildStep(language) {
  const lang = (language || '').toLowerCase()
  if (lang.includes('node')) return { name: 'Node.js 编译', type: 'NPM_BUILD', command: 'npm run build' }
  if (lang.includes('python')) return { name: 'Python 编译', type: 'PYTHON_BUILD', command: 'python -m compileall .' }
  if (lang === 'go' || lang === 'golang') return { name: 'Go 编译', type: 'GO_BUILD', command: 'go build -o app ./...' }
  if (lang.includes('c++') || lang.includes('cpp') || lang === 'c') return { name: 'CMake 构建', type: 'CMAKE_BUILD', command: 'cmake -S . -B build -G "MinGW Makefiles" -DCMAKE_BUILD_TYPE=Release && cmake --build build' }
  if (lang === 'rust') return { name: 'Cargo 构建', type: 'CARGO_BUILD', command: 'cargo build --release' }
  return { name: 'Maven 编译', type: 'MVN_PACKAGE', command: 'mvn clean package -DskipTests -B' }
}

/** 能力 id → 流水线阶段构造器（CAPABILITY_ORDER 决定执行顺序） */
const STAGE_BUILDERS = {
  BUILD: (lang) => ({ name: '编译构建', steps: [getDefaultBuildStep(lang)] }),
  TEST: () => ({ name: '单元测试', steps: [{ name: '执行测试', type: 'TEST', command: '' }] }),
  QUALITY: () => ({ name: '质量门禁', steps: [{ name: '测试通过率+覆盖率', type: 'QUALITY_GATE', command: 'passRate=90 coverage=60', timeout: 30 }] }),
  SAST: () => ({ name: '安全扫描(代码)', parallel: true, steps: [
    { name: 'Trivy FS 扫描', type: 'SECURITY_SCAN_FS', command: 'thresholdHigh=5 thresholdCritical=0', timeout: 180 },
    { name: 'Trivy 依赖扫描', type: 'SECURITY_SCAN_DEP', command: 'thresholdHigh=10 thresholdCritical=0', timeout: 180 }
  ]}),
  DOCKER: () => ({ name: 'Docker构建', steps: [
    { name: '构建镜像', type: 'DOCKER_BUILD', command: 'Dockerfile', timeout: 300, retry: 1 },
    { name: '推送镜像', type: 'DOCKER_PUSH', command: '', retry: 2 }
  ]}),
  IMAGE_SCAN: () => ({ name: '安全扫描(镜像)', steps: [
    { name: 'Trivy 镜像扫描', type: 'SECURITY_SCAN_IMAGE', command: 'thresholdHigh=5 thresholdCritical=0', timeout: 180 }
  ]}),
  K8S: () => ({ name: 'K8s部署', steps: [{ name: '部署到集群', type: 'K8S_DEPLOY', command: 'deployment.yaml', retry: 1 }] }),
}

/** CAPABILITY_ORDER（能力执行顺序）由 ../capabilities 统一维护 */

/**
 * 生成默认流水线定义。
 * @param language     项目语言
 * @param capabilities 项目勾选的能力 id 数组；为空/未传 → 包含全部阶段（向后兼容）
 */
const DEFAULT_STAGES = (language, capabilities) => {
  const enabled = new Set(
    Array.isArray(capabilities) && capabilities.length ? capabilities : CAPABILITY_ORDER
  )
  enabled.add('BUILD')  // 编译构建始终保留
  return JSON.stringify(
    CAPABILITY_ORDER
      .filter(id => enabled.has(id) && STAGE_BUILDERS[id])
      .map(id => STAGE_BUILDERS[id](language)),
    null, 2
  )
}

const DEFAULT_FORM = {
  name: '', projectId: '',
  definition: DEFAULT_STAGES('Java'),
  description: '',
  branchPattern: '',
  cronExpression: '',
  cronEnabled: false,
}

/** 步骤类型 → 所需构建能力（导入模板时按项目已启用能力裁剪阶段用） */
const STEP_TYPE_CAPABILITY = {
  MVN_PACKAGE: 'BUILD', NPM_INSTALL: 'BUILD', NPM_BUILD: 'BUILD', PIP_INSTALL: 'BUILD',
  PYTHON_BUILD: 'BUILD', GO_BUILD: 'BUILD', CMAKE_BUILD: 'BUILD', CARGO_BUILD: 'BUILD',
  GRADLE_BUILD: 'BUILD',
  TEST: 'TEST',
  QUALITY_GATE: 'QUALITY',
  SECURITY_SCAN_FS: 'SAST', SECURITY_SCAN_DEP: 'SAST',
  DOCKER_BUILD: 'DOCKER', DOCKER_PUSH: 'DOCKER',
  SECURITY_SCAN_IMAGE: 'IMAGE_SCAN',
  K8S_DEPLOY: 'K8S',
}

/**
 * 按项目已启用能力裁剪模板阶段：
 * 剔除项目未启用能力对应的步骤；某阶段被剔空则整段删除。
 * 无能力声明的步骤（如审批门）始终保留；提示文案只记阶段名，避免与子步骤重复。
 * capabilities 为空（历史项目未配置）→ 视为全量启用，原样返回。
 */
function pruneStagesByCapabilities(stages, capabilities) {
  const caps = Array.isArray(capabilities) ? capabilities.filter(Boolean) : []
  if (!caps.length) return { stages, removed: [] }
  const enabled = new Set(caps)
  const removed = []
  const kept = []
  ;(stages || []).forEach(stage => {
    const dropped = []
    const steps = (stage.steps || []).filter(step => {
      const need = STEP_TYPE_CAPABILITY[step?.type]
      if (!need || enabled.has(need)) return true
      dropped.push(step.name || step.type)
      return false
    })
    if (steps.length) {
      kept.push({ ...stage, steps })
      removed.push(...dropped)                 // 阶段保留 → 只记被剔掉的步骤
    } else {
      removed.push(stage.name || '未命名阶段')  // 阶段整体删除 → 只记阶段名
    }
  })
  return { stages: kept, removed }
}

export default function PipelineList() {
  const { projectId } = useParams()
  const { canManage, canTrigger } = useAuth()
  const pid = projectId ? Number(projectId) : undefined
  const [pipelines, setPipelines] = useState([])
  const [projects, setProjects] = useState([])
  const [loading, setLoading] = useState(true)
  const [showModal, setShowModal] = useState(false)
  const [editing, setEditing] = useState(null)
  const [varsPipeline, setVarsPipeline] = useState(null)       // 构建变量弹窗：当前流水线
  const [versionsPipeline, setVersionsPipeline] = useState(null) // 版本历史弹窗：当前流水线
  const [form, setForm] = useState(DEFAULT_FORM)
  const [search, setSearch] = useState('')
  const [toast, setToast] = useState(null) // { text, type: 'info'|'error' }
  const toastTimerRef = useRef(null)       // toast 自动关闭定时器（统一管理，避免互相覆盖）

  /** 统一入口：显示 toast，并保证「新的 toast 不会被旧定时器提前关掉」 */
  const showToast = useCallback((text, type = 'info', ms = 3000) => {
    if (toastTimerRef.current) clearTimeout(toastTimerRef.current)
    setToast({ text, type })
    toastTimerRef.current = setTimeout(() => setToast(null), ms)
  }, [])

  /** 触发构建后轮询到终态，给出明确结论（详见 useBuildWatch 注释） */
  const watchBuildResult = useBuildWatch(useCallback((b) => {
    if (b.status === 'SUCCESS') {
      showToast(`构建 ${b.buildNumber} 成功 ✅ — 可到「构建记录」查看日志`, 'success', 5000)
    } else if (b.status === 'CANCELLED') {
      showToast(`构建 ${b.buildNumber} 已取消`, 'info', 5000)
    } else {
      showToast(`构建 ${b.buildNumber} 失败 ❌ — 请到「构建记录」查看日志排错`, 'error', 12000)
    }
  }, [showToast]))

  useEffect(() => () => {
    if (toastTimerRef.current) clearTimeout(toastTimerRef.current)
  }, [])
  const [showBuildModal, setShowBuildModal] = useState(false)
  const [buildPendingPipeline, setBuildPendingPipeline] = useState(null)
  const [buildSkipDocker, setBuildSkipDocker] = useState(false)
  const [buildSkipK8s, setBuildSkipK8s] = useState(false)
  const [buildDbName, setBuildDbName] = useState('')
  const [buildStrategy, setBuildStrategy] = useState('ROLLING')
  const [tab, setTab] = useState('pipelines')

  // 流水线模板库（模板管理里 type=PIPELINE 的条目），用于「从模板创建」
  const [pipelineTemplates, setPipelineTemplates] = useState([])
  useEffect(() => {
    fetchTemplates('PIPELINE').then(list => setPipelineTemplates(Array.isArray(list) ? list : []))
      .catch(() => setPipelineTemplates([]))
  }, [])

  // 数据库名净化（与后端 DatabaseProvisioningService.sanitizeDbName 一致）
  const sanitizeDbName = (raw) => {
    if (!raw || !raw.trim()) return ''
    let clean = raw.trim().replace(/[^a-zA-Z0-9_\-]+/g, '_')
    clean = clean.replace(/^-+/, '').replace(/^_+/, '')
    return clean || 'app'
  }
  // 实时预览有效数据库名
  const getProjectForBuild = () => {
    if (!buildPendingPipeline) return null
    return projects.find(p => p.id === buildPendingPipeline.projectId)
  }
  const isH2 = !getProjectForBuild()?.dbType || getProjectForBuild()?.dbType === 'H2'
  // 所属项目是否启用容器化能力 —— 未启用则触发构建弹窗不出现对应开关
  const buildProjDockerOn = hasCapability(getProjectForBuild()?.capabilities, 'DOCKER')
  const buildProjK8sOn = hasCapability(getProjectForBuild()?.capabilities, 'K8S')
  /** 弹窗文案用的有效能力集合：历史项目未配置过能力 → 视为全量（与展示逻辑一致） */
  const buildProjCaps = (() => {
    const caps = parseCapabilities(getProjectForBuild()?.capabilities)
    return caps.length ? caps : CAPABILITY_ORDER
  })()
  const effectiveDbPreview = buildDbName
    ? sanitizeDbName(buildDbName)
    : (() => {
        const proj = getProjectForBuild()
        return proj?.code ? `devops_${sanitizeDbName(proj.code)}` : 'devops_app'
      })()

  // 数据库名冲突检测（实时防抖 — H2 模式跳过）
  const [dbConflict, setDbConflict] = useState(null)
  useEffect(() => {
    if (!showBuildModal || isH2 || !effectiveDbPreview || !buildPendingPipeline) {
      setDbConflict(null)
      return
    }
    const timer = setTimeout(async () => {
      try {
        const r = await checkDbConflict(effectiveDbPreview, buildPendingPipeline.projectId)
        setDbConflict(r)
      } catch { setDbConflict(null) }
    }, 400)
    return () => clearTimeout(timer)
  }, [effectiveDbPreview, showBuildModal, buildPendingPipeline])

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setPipelines(await fetchPipelines(pid))
      setProjects(await fetchProjects())
    } catch (e) { console.error(e) }
    finally { setLoading(false) }
  }, [projectId])

  useEffect(() => { load() }, [load])

  // When projectId changes externally, reload
  useEffect(() => { load() }, [pid, load])

  /** 项目 → 语言 + 勾选能力，用于生成默认流水线 */
  const stagesForProject = (proj) => {
    const caps = (proj?.capabilities || '').split(',').map(s => s.trim()).filter(Boolean)
    return DEFAULT_STAGES(proj?.language || 'Java', caps)
  }

  // ---- 流水线模板导入 ----
  /**
   * 从「模板管理」导入流水线定义：
   * 解析模板 JSON → 按所选项目已启用的能力裁剪掉相关阶段 → 写回定义框（已有内容先确认覆盖）。
   */
  const applyPipelineTemplate = (id) => {
    const t = (pipelineTemplates || []).find(x => String(x.id) === String(id))
    if (!t) return
    let stages
    try {
      stages = JSON.parse(t.content || '[]')
    } catch {
      alert(`模板「${t.name}」的内容不是有效 JSON，无法导入`)
      return
    }
    if (!Array.isArray(stages) || !stages.length) {
      alert(`模板「${t.name}」没有可用的阶段定义`)
      return
    }
    if (form.definition?.trim() && !window.confirm(`当前已有流水线定义，确定用模板「${t.name}」覆盖吗？`)) return

    const proj = (projects || []).find(p => String(p.id) === String(form.projectId))
    const caps = parseCapabilities(proj?.capabilities)
    const { stages: pruned, removed } = pruneStagesByCapabilities(stages, caps)

    setForm(prev => ({ ...prev, definition: JSON.stringify(pruned, null, 2) }))

    showToast(
      removed.length
        ? `已导入「${t.name}」，并跳过项目未启用的 ${removed.length} 项：${removed.join('、')}`
        : `已导入「${t.name}」（${pruned.length} 个阶段）`,
      removed.length ? 'info' : 'success',
      4000,
    )
  }

  /** 切换所属项目 → 按该项目的语言与能力重新生成默认定义 */
  const handleProjectChange = (pidValue) => {
    const proj = (projects || []).find(p => String(p.id) === String(pidValue))
    setForm(prev => ({ ...prev, projectId: pidValue, definition: stagesForProject(proj) }))
  }

  const openNew = () => {
    setEditing(null)
    const proj = pid ? (projects || []).find(p => p.id === pid) : null
    setForm({ ...DEFAULT_FORM, projectId: pid || '', definition: stagesForProject(proj) })
    setShowModal(true)
  }

  const openEdit = (p) => {
    setEditing(p)
    setForm({
      name: p.name || '',
      projectId: p.projectId || '',
      description: p.description || '',
      definition: typeof p.definition === 'string' ? p.definition : JSON.stringify(p.definition, null, 2),
      branchPattern: p.branchPattern || '',
      cronExpression: p.cronExpression || '',
      cronEnabled: p.cronEnabled || false,
    })
    setShowModal(true)
  }

  const handleSave = async () => {
    // 基本字段校验
    if (!form.name.trim()) {
      alert('请输入流水线名称')
      return
    }
    if (!form.projectId) {
      alert('请选择所属项目（请先在项目管理中创建项目）')
      return
    }
    try {
      // 验证 JSON 格式
      JSON.parse(form.definition)
      const data = {
        ...form,
        name: form.name.trim(),
        projectId: Number(form.projectId),
        cronEnabled: form.cronEnabled || false,
      }
      if (editing) {
        await updatePipeline(editing.id, data)
      } else {
        await createPipeline(data)
      }
      setShowModal(false)
      load()
    } catch (e) {
      if (e instanceof SyntaxError) {
        alert('流水线定义不是有效的 JSON，请检查格式\n\n提示：确保所有括号和引号正确配对')
      } else {
        alert('保存失败: ' + (e.message || '未知错误'))
      }
    }
  }

  const handleDelete = async (p) => {
    if (!confirm(`确定要删除流水线「${p.name}」吗？`)) return
    try {
      await deletePipeline(p.id)
      emit('pipeline:deleted', { id: p.id, projectId: p.projectId })
      load()
    } catch (e) { alert('删除失败: ' + e.message) }
  }

  // 跨组件同步
  const onPipelineDeleted = useCallback((detail) => {
    if (detail?.id != null) setPipelines(prev => prev.filter(x => x.id !== detail.id))
  }, [])
  useResourceEvent('pipeline:deleted', onPipelineDeleted)

  const onProjectDeleted = useCallback((detail) => {
    const id = detail?.id
    if (id == null) return
    setProjects(prev => prev.filter(x => x.id !== id))
    setPipelines(prev => prev.filter(p => p.projectId !== id))
  }, [])
  useResourceEvent('project:deleted', onProjectDeleted)

  const onProjectUpsert = useCallback((detail) => {
    const p = detail?.project
    if (!p || p.id == null) return
    setProjects(prev => {
      const idx = prev.findIndex(x => x.id === p.id)
      if (idx >= 0) { const n = prev.slice(); n[idx] = { ...n[idx], ...p }; return n }
      return [...prev, p]
    })
  }, [])
  useResourceEvent('project:created', onProjectUpsert)
  useResourceEvent('project:updated', onProjectUpsert)

  const handleTrigger = async (p, pid) => {
    setBuildPendingPipeline({ pipeline: p, projectId: pid })
    const proj = projects.find(pr => pr.id === pid)
    // 项目未启用镜像构建 / K8s 部署时，直接跳过对应阶段（界面上也不出现这两个开关）
    setBuildSkipDocker(!hasCapability(proj?.capabilities, 'DOCKER'))
    setBuildSkipK8s(!hasCapability(proj?.capabilities, 'K8S'))
    // 默认数据库名
    const defaultDb = proj?.code ? `devops_${proj.code.replace(/[^a-zA-Z0-9_-]/g, '_')}` : ''
    setBuildDbName(defaultDb)
    setShowBuildModal(true)
  }

  const handleConfirmBuild = async () => {
    if (!buildPendingPipeline) return
    const { pipeline, projectId } = buildPendingPipeline
    setShowBuildModal(false)
    showToast('构建触发中…', 'info', 3000)
    try {
      const build = await triggerBuild(projectId, pipeline.id, null, null,
        buildSkipDocker, buildSkipK8s,
        buildDbName || null, buildStrategy)
      showToast(build?.buildNumber
        ? `构建 ${build.buildNumber} 已触发，正在执行…`
        : '构建已触发，正在执行…', 'info', 4000)
      // 异步构建：轮询到终态再给结论，避免「已触发」之后没有下文
      if (build?.id) watchBuildResult(build.id)
    } catch (e) {
      showToast('触发失败: ' + e.message, 'error', 6000)
    }
  }

  const getProjectName = (pid) => {
    const p = projects.find(pr => pr.id === pid)
    return p ? p.name : `#${pid}`
  }

  const filtered = pipelines.filter(p =>
    !search || p.name?.toLowerCase().includes(search.toLowerCase())
  )

  // 弹窗内「从模板创建」下拉的分组（按弹窗所选项目的语言区分推荐/其他）
  const modalProj = (projects || []).find(p => String(p.id) === String(form.projectId))
  const modalLang = modalProj?.language || ''
  const pipelineTplFitting = pipelineTemplates.filter(t => templateFitsLanguage(t, modalLang))
  const pipelineTplOthers = pipelineTemplates.filter(t => !templateFitsLanguage(t, modalLang))

  if (loading) return <div className="empty-state"><div className="spinner" /></div>

  return (
    <>
      <div className="page-header">
        <h2>🔧 流水线管理 {pid && <span style={{fontSize:14,color:'#9ca3af'}}>(筛选项目)</span>}</h2>
      </div>

      {/* Tab 切换 */}
      <div className="btn-group" style={{ marginBottom: 12 }}>
        <button className={`btn ${tab === 'pipelines' ? 'btn-primary' : 'btn-outline'} btn-sm`} onClick={() => setTab('pipelines')}>📋 流水线列表</button>
        <button className={`btn ${tab === 'schedules' ? 'btn-primary' : 'btn-outline'} btn-sm`} onClick={() => setTab('schedules')}>⏰ 定时任务</button>
      </div>

      {tab === 'schedules' ? (
        <SchedulerList />
      ) : (
      <>
      <div className="page-header" style={{ marginTop: 0 }}>
        <div className="btn-group">
          <input
            placeholder="搜索流水线..."
            value={search}
            onChange={e => setSearch(e.target.value)}
            style={{ padding: '8px 12px', border: '1px solid #d1d5db', borderRadius: 8, fontSize: 13 }}
          />
          {canManage && <button className="btn btn-primary" onClick={openNew}>+ 新建流水线</button>}
        </div>
      </div>

      <div className="card" style={{ padding: 0 }}>
        <div className="table-container">
          <table>
            <thead>
              <tr>
                <th>名称</th>
                <th>所属项目</th>
                <th>分支模式</th>
                <th>定时构建</th>
                <th>阶段数</th>
                <th>创建时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {filtered.length === 0 ? (
                <tr><td colSpan={7} style={{ textAlign: 'center', padding: 32, color: '#9ca3af' }}>暂无流水线数据</td></tr>
              ) : (
                filtered.map(p => {
                  let stageCount = 0
                  try { stageCount = JSON.parse(typeof p.definition === 'string' ? p.definition : '[]').length } catch {}
                  return (
                    <tr key={p.id}>
                      <td style={{ fontWeight: 600 }}>{p.name}</td>
                      <td>{getProjectName(p.projectId)}</td>
                      <td style={{ fontSize: 12 }}>
                        {p.branchPattern ? (
                          <span className="badge badge-running" style={{ fontFamily: 'monospace', fontSize: 11 }}>
                            {p.branchPattern}
                          </span>
                        ) : (
                          <span style={{ color: '#6b7280' }}>全部</span>
                        )}
                      </td>
                      <td>
                        {p.cronExpression ? (
                          <span className="badge badge-success" style={{ fontSize: 11 }}>
                            ⏰ {p.cronEnabled ? '启用' : '停用'}
                          </span>
                        ) : (
                          <span style={{ color: '#6b7280' }}>—</span>
                        )}
                      </td>
                      <td>{stageCount} 阶段</td>
                      <td style={{ color: '#9ca3af', fontSize: 12 }}>{p.createdAt ? new Date(p.createdAt).toLocaleDateString() : '-'}</td>
                      <td>
                        <div className="btn-group">
                          {canTrigger && <button className="btn btn-success btn-sm" onClick={() => handleTrigger(p, p.projectId)}>▶ 执行</button>}
                          {canManage && <button className="btn btn-outline btn-sm" title="构建变量" onClick={() => setVarsPipeline(p)}>🔐</button>}
                          <button className="btn btn-outline btn-sm" title="版本历史" onClick={() => setVersionsPipeline(p)}>🕘</button>
                          {canManage && <button className="btn btn-outline btn-sm" onClick={() => openEdit(p)}>✏️</button>}
                          {canManage && <button className="btn btn-danger btn-sm" onClick={() => handleDelete(p)}>🗑</button>}
                        </div>
                      </td>
                    </tr>
                  )
                })
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Create/Edit Modal */}
      {showModal && (
        <div className="modal-overlay" onClick={() => setShowModal(false)}>
          <div className="modal" style={{ maxWidth: 700 }} onClick={e => e.stopPropagation()}>
            <h3>{editing ? '编辑流水线' : '新建流水线'}</h3>
            <div className="form-group">
              <label>流水线名称 *</label>
              <input value={form.name} onChange={e => setForm({...form, name: e.target.value})} placeholder="如: CI/CD 发布流程" />
            </div>
            <div className="form-group">
              <label>所属项目 *</label>
              <select value={form.projectId} onChange={e => handleProjectChange(e.target.value)}>
                <option value="">-- 选择项目 --</option>
                {projects.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
              </select>
            </div>
            <div className="form-group">
              <label>描述</label>
              <input value={form.description || ''} onChange={e => setForm({...form, description: e.target.value})} placeholder="流水线用途说明" />
            </div>
            <div className="form-group">
              <label>分支模式 <span style={{ color: '#9ca3af', fontSize: 11, fontWeight: 400 }}>— Webhook 触发时匹配的分支，如 feature/*, main, develop</span></label>
              <input
                value={form.branchPattern}
                onChange={e => setForm({...form, branchPattern: e.target.value})}
                placeholder="留空表示匹配所有分支。示例: feature/*, hotfix/* 或 main,develop"
              />
            </div>
            <div style={{ display: 'flex', gap: 12 }}>
              <div className="form-group" style={{ flex: 2 }}>
                <label>Cron 定时表达式 <span style={{ color: '#9ca3af', fontSize: 11, fontWeight: 400 }}>— 如 0 9 * * 1-5 (工作日早上9点)</span></label>
                <input
                  value={form.cronExpression}
                  onChange={e => setForm({...form, cronExpression: e.target.value})}
                  placeholder="留空表示不启用定时。示例: 0 */6 * * * (每6小时)"
                />
              </div>
              <div className="form-group" style={{ flex: 0, minWidth: 90, display: 'flex', flexDirection: 'column', justifyContent: 'flex-end', paddingBottom: 2 }}>
                <label>&nbsp;</label>
                <label style={{ display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer', fontSize: 13, padding: '8px 0' }}>
                  <input
                    type="checkbox"
                    checked={form.cronEnabled}
                    onChange={e => setForm({...form, cronEnabled: e.target.checked})}
                    disabled={!form.cronExpression}
                  />
                  启用
                </label>
              </div>
            </div>
            <div className="form-group">
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', marginBottom: 4 }}>
                <label style={{ margin: 0 }}>流水线定义 (JSON)</label>
                {pipelineTemplates.length > 0 && (
                  <select
                    value=""
                    title="从「系统管理 → 模板管理」里的流水线模板导入，并按项目能力自动裁剪"
                    onChange={e => applyPipelineTemplate(e.target.value)}
                    style={{ maxWidth: 320, padding: '4px 8px', fontSize: 12, border: '1px solid #d1d5db', borderRadius: 6 }}
                  >
                    <option value="">📥 从模板创建…</option>
                    {pipelineTplFitting.length > 0 && (
                      <optgroup label={modalLang ? `适配 ${modalLang}` : '全部模板'}>
                        {pipelineTplFitting.map(t => <option key={t.id} value={t.id}>{t.name}</option>)}
                      </optgroup>
                    )}
                    {pipelineTplOthers.length > 0 && (
                      <optgroup label="其他语言">
                        {pipelineTplOthers.map(t => <option key={t.id} value={t.id}>{t.name}{t.category ? ` · ${t.category}` : ''}</option>)}
                      </optgroup>
                    )}
                  </select>
                )}
                {form.projectId && (
                  <span style={{ fontSize: 11, color: '#9ca3af' }}>
                    导入后自动跳过项目未启用的能力阶段
                  </span>
                )}
              </div>
              <textarea
                value={form.definition}
                onChange={e => setForm({...form, definition: e.target.value})}
                rows={14}
                style={{ fontFamily: "'Courier New', monospace", fontSize: 12 }}
              />
            </div>
            <div className="btn-group" style={{ justifyContent: 'flex-end', marginTop: 8 }}>
              <button className="btn btn-outline" onClick={() => setShowModal(false)}>取消</button>
              <button className="btn btn-primary" onClick={handleSave}>{editing ? '保存' : '创建'}</button>
            </div>
          </div>
        </div>
      )}

      {/* Build trigger confirmation modal */}
      {showBuildModal && (
        <div className="modal-overlay" onClick={() => setShowBuildModal(false)}>
          <div className="modal" style={{ maxWidth: 440 }} onClick={e => e.stopPropagation()}>
            <h3>⚡ 触发构建</h3>
            {buildPendingPipeline && (
              <p style={{ color: '#6b7280', marginBottom: 16, fontSize: 14 }}>
                流水线: <strong>{buildPendingPipeline.pipeline.name}</strong>
              </p>
            )}
            <div style={{
              background: '#f9fafb', borderRadius: 10, padding: 16,
              border: '1px solid #e5e7eb', marginBottom: 16
            }}>
              {!buildProjDockerOn && !buildProjK8sOn && (
                <div style={{ fontSize: 12, color: '#6b7280' }}>
                  所属项目未启用镜像构建与 K8s 部署，无需选择跳过项。
                </div>
              )}
              {buildProjDockerOn && (
              <label style={{
                display: 'flex', alignItems: 'flex-start', gap: 10,
                padding: '8px 0', cursor: 'pointer', fontSize: 14,
                borderBottom: buildProjK8sOn ? '1px solid #e5e7eb' : 'none'
              }}>
                <input
                  type="checkbox"
                  checked={buildSkipDocker}
                  onChange={e => setBuildSkipDocker(e.target.checked)}
                  style={{ marginTop: 2 }}
                />
                <div>
                  <div style={{ fontWeight: 600 }}>🐳 跳过 Docker</div>
                  <div style={{ color: '#9ca3af', fontSize: 12 }}>不构建和推送 Docker 镜像</div>
                </div>
              </label>
              )}
              {buildProjK8sOn && (
              <label style={{
                display: 'flex', alignItems: 'flex-start', gap: 10,
                padding: '8px 0', cursor: 'pointer', fontSize: 14
              }}>
                <input
                  type="checkbox"
                  checked={buildSkipK8s}
                  onChange={e => setBuildSkipK8s(e.target.checked)}
                  style={{ marginTop: 2 }}
                />
                <div>
                  <div style={{ fontWeight: 600 }}>☸️ 跳过 Kubectl</div>
                  <div style={{ color: '#9ca3af', fontSize: 12 }}>不执行 K8s 部署</div>
                </div>
              </label>
              )}
            </div>

            {/* 数据库隔离配置 — H2 模式无需配置 */}
            {isH2 ? (
              <div style={{
                background: '#f0fdf4', borderRadius: 8, padding: '10px 12px',
                marginBottom: 16, border: '1px solid #bbf7d0'
              }}>
                <div style={{ fontSize: 12, fontWeight: 600, color: '#065f46', marginBottom: 4 }}>
                  🗄 数据库：H2 内嵌模式
                </div>
                <span style={{ fontSize: 11, color: '#047857' }}>
                  O 项目使用 Spring Boot 自带 H2 文件数据库，零外部依赖，Pod 内自包含运行。
                </span>
              </div>
            ) : (
            <div style={{
              background: '#f0f9ff', borderRadius: 8, padding: '10px 12px',
              marginBottom: 16, border: '1px solid #bae6fd'
            }}>
              <div style={{ fontSize: 12, fontWeight: 600, color: '#0369a1', marginBottom: 8 }}>
                🗄 数据库隔离配置（部署时自动创建独立库）
              </div>
              <label style={{ display: 'block', marginBottom: 8 }}>
                <span style={{ fontSize: 11, color: '#6b7280' }}>数据库名</span>
                <input
                  type="text"
                  value={buildDbName}
                  onChange={e => setBuildDbName(e.target.value)}
                  placeholder={`devops_${getProjectForBuild()?.code || 'app'}`}
                  style={{
                    width: '100%', padding: '6px 10px', marginTop: 2,
                    border: '1px solid #d1d5db', borderRadius: 6, fontSize: 13,
                    boxSizing: 'border-box'
                  }}
                />
                <span style={{ fontSize: 11, color: '#047857' }}>
                  📌 将创建数据库: <strong>{effectiveDbPreview}</strong>
                  {dbConflict?.reusedByCurrent && (
                    <span style={{ color: '#d97706', marginLeft: 8 }}>⚠️ 已存在（同项目重建）</span>
                  )}
                </span>
                {dbConflict?.conflict && (
                  <span style={{ display: 'block', fontSize: 11, color: '#dc2626', marginTop: 4, padding: '6px 10px', background: '#fef2f2', borderRadius: 6, border: '1px solid #fecaca' }}>
                    ⛔ 数据库已存在且被 <strong>{dbConflict.conflictProject}</strong> 占用！请更换数据库名
                  </span>
                )}
              </label>
            </div>
            )}
            <div style={{
              fontSize: 12, color: '#6b7280', marginBottom: 16, padding: '8px 12px',
              background: '#eff6ff', borderRadius: 8, border: '1px solid #bfdbfe'
            }}>
              将执行: {describeCapabilities(
                buildProjCaps
                  .filter(id => id !== 'DOCKER' || !buildSkipDocker)
                  .filter(id => id !== 'K8S' || !buildSkipK8s),
                null
              ) || CAPABILITY_LABEL.BUILD}
            </div>
            {!buildSkipK8s && (
            <div style={{ marginBottom: 16 }}>
              <label style={{ fontSize: 13, fontWeight: 600, color: '#374151', display: 'block', marginBottom: 8 }}>
                🚀 部署策略：
                <select value={buildStrategy} onChange={e => setBuildStrategy(e.target.value)}
                  style={{ marginLeft: 8, padding: '6px 10px', border: '1px solid #d1d5db', borderRadius: 6, fontSize: 13 }}>
                  <option value="ROLLING">滚动更新 (Rolling)</option>
                  <option value="BLUE_GREEN">蓝绿部署 (Blue-Green)</option>
                  <option value="CANARY">金丝雀发布 (Canary)</option>
                </select>
              </label>
              <div style={{ fontSize: 11, color: '#9ca3af', marginLeft: 4 }}>
                {buildStrategy === 'ROLLING' && '逐步替换旧 Pod，服务不中断（默认）'}
                {buildStrategy === 'BLUE_GREEN' && '部署新版本到独立槽位，验证后切换流量，可秒级回滚'}
                {buildStrategy === 'CANARY' && '小比例流量验证新版本，观察后可全量提升'}
              </div>
            </div>
            )}
            <div className="btn-group" style={{ justifyContent: 'flex-end' }}>
              <button className="btn btn-outline" onClick={() => setShowBuildModal(false)}>取消</button>
              <button className="btn btn-primary" onClick={handleConfirmBuild}>▶ 确认构建</button>
            </div>
          </div>
        </div>
      )}

      {/* 构建变量弹窗 */}
      {varsPipeline && (
        <VariablesPanel pipeline={varsPipeline} onClose={() => setVarsPipeline(null)} />
      )}

      {/* 版本历史弹窗 */}
      {versionsPipeline && (
        <PipelineVersionsModal
          pipeline={versionsPipeline}
          onClose={() => setVersionsPipeline(null)}
          onRollback={load}
        />
      )}

      {/* Toast notification */}
      {toast && (
        <div className={`toast toast-${toast.type}`}>
          {toast.text}
        </div>
      )}
      </>
      )}
    </>
  )
}
