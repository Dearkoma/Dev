import React, { useState, useEffect, useCallback, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { fetchProjects, createProject, updateProject, deleteProject, fetchPipelines, fetchEnvironments, triggerBuild, fetchBuilds, checkWorkspace, checkDbConflict, testProjectDbConnection, fetchToolchainMeta, fetchTemplates, fetchProjectUpdateStatus } from '../api'
import { useBuildWatch } from '../useBuildWatch'
import { emit, useResourceEvent } from '../eventBus'
import ProjectMemberPanel from './ProjectMemberPanel'
import ToolchainPanel from './ToolchainPanel'
import GithubPanel from './GithubPanel'
import {
  parseCapabilities, pruneCapabilities, missingRequires, capabilityLabel, describeCapabilities,
  hasCapability, CAPABILITY_ORDER, CAPABILITY_LABEL,
} from '../capabilities'
import { templateFitsLanguage } from '../templateMatch'

// 兜底常量：/toolchain/meta 加载失败时使用（正常情况由后端元数据驱动）
const LANGUAGES = ['Java', 'Node.js', 'Python', 'Go', 'Rust', 'Other']
const FRAMEWORKS = ['Spring Boot', 'Express', 'Django', 'Gin', 'CMake', 'Other']

/**
 * 不在语言下拉中展示的语言。
 * 后端 /toolchain/meta 仍然返回 C++ 的完整元数据（框架/能力/工具检测都保留），
 * 这里只是不在新建/编辑项目的下拉里提供该选项。
 * 想恢复：把 'C++' 从这里移除即可。
 */
const HIDDEN_LANGUAGES = ['C++']

/** 兜底：语言 → 默认框架 + 构建命令（后端元数据不可用时的降级） */
const LANGUAGE_DEFAULTS = {
  'Java':     { framework: 'Spring Boot', buildCommand: 'mvn clean package -DskipTests' },
  'Node.js':  { framework: 'Express',     buildCommand: 'npm run build' },
  'Python':   { framework: 'Django',      buildCommand: 'python -m compileall .' },
  'Go':       { framework: 'Gin',         buildCommand: 'go build -o app ./...' },
  'Rust':     { framework: 'Other',       buildCommand: 'cargo build --release' },
  'C++':      { framework: 'CMake',       buildCommand: 'cmake -S . -B build -G "MinGW Makefiles" -DCMAKE_BUILD_TYPE=Release && cmake --build build' },
  'Other':    { framework: 'Other',       buildCommand: '' },
}

const DEFAULT_FORM = {
  name: '', code: '', gitUrl: '', gitBranch: '',
  language: 'Java', framework: 'Spring Boot', buildCommand: 'mvn clean package -DskipTests',
  description: '',
  capabilities: ['BUILD', 'TEST', 'QUALITY', 'SAST', 'DOCKER', 'IMAGE_SCAN', 'K8S'],
  dbType: 'H2', dbHost: '', dbPort: 3306, dbUsername: '', dbPassword: '',
  dockerfilePath: 'Dockerfile', dockerfileContent: '',
}

export default function ProjectList() {
  const navigate = useNavigate()
  const { canManage, canTrigger } = useAuth()
  const [projects, setProjects] = useState([])
  const [loading, setLoading] = useState(true)
  const [showModal, setShowModal] = useState(false)
  const [editing, setEditing] = useState(null)
  const [form, setForm] = useState(DEFAULT_FORM)
  const [search, setSearch] = useState('')

  // Member panel
  const [showMembers, setShowMembers] = useState(null)  // project id or null
  const [memberProjectName, setMemberProjectName] = useState('')

  // Detail modal
  const [detailProject, setDetailProject] = useState(null)
  const [detailPipelines, setDetailPipelines] = useState([])
  const [detailBuilds, setDetailBuilds] = useState([])
  const [detailEnvs, setDetailEnvs] = useState([])
  const [wsCheckResult, setWsCheckResult] = useState(null)
  const [wsChecking, setWsChecking] = useState(false)

  // MySQL 连接测试
  const [dbTesting, setDbTesting] = useState(false)
  const [dbTestResult, setDbTestResult] = useState(null)

  // Build trigger modal
  const [showBuildModal, setShowBuildModal] = useState(false)
  const [buildPendingPipeline, setBuildPendingPipeline] = useState(null)
  const [buildSkipDocker, setBuildSkipDocker] = useState(false)
  const [buildSkipK8s, setBuildSkipK8s] = useState(false)
  const [buildDbName, setBuildDbName] = useState('')
  const [toast, setToast] = useState(null) // { text, type: 'success'|'error'|'info' }
  const toastTimerRef = useRef(null)       // toast 自动关闭定时器（统一管理，避免互相覆盖）

  // 远端更新提示：projectId → { deployed, hasUpdate, deployedCommit, remoteCommit, remoteMessage, ... }
  const [updateMap, setUpdateMap] = useState({})
  const [updateChecking, setUpdateChecking] = useState(false)

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

  // 工具链元数据（语言 → 框架 / 可用能力 / 默认构建命令），由后端 /toolchain/meta 驱动
  const [tcMeta, setTcMeta] = useState(null)
  useEffect(() => {
    fetchToolchainMeta().then(setTcMeta).catch(() => setTcMeta(null))
  }, [])

  // Dockerfile 模板库（模板管理里 type=DOCKERFILE 的条目），用于「从模板导入」
  const [dockerTemplates, setDockerTemplates] = useState([])
  useEffect(() => {
    fetchTemplates('DOCKERFILE').then(list => setDockerTemplates(Array.isArray(list) ? list : []))
      .catch(() => setDockerTemplates([]))
  }, [])

  // ---- 语言元数据取值（后端驱动；未加载时降级到内置常量）----
  const langMetaOf = (id) => (tcMeta?.languages || []).find(l => l.id === id)
  const langOptions = (tcMeta?.languages?.length
    ? tcMeta.languages.map(l => ({ value: l.id, label: l.label }))
    : LANGUAGES.map(l => ({ value: l, label: l }))
  ).filter(o => !HIDDEN_LANGUAGES.includes(o.value))
  const frameworksOf = (id) => {
    const list = langMetaOf(id)?.frameworks
    return list?.length ? list : FRAMEWORKS
  }
  const capabilitiesOf = (id) => langMetaOf(id)?.capabilities || []
  const defaultCapabilities = (id) =>
    pruneCapabilities(capabilitiesOf(id),
      (langMetaOf(id)?.capabilities || []).filter(c => c.defaultOn).map(c => c.id))
  const defaultFramework = (id) =>
    langMetaOf(id)?.defaultFramework || LANGUAGE_DEFAULTS[id]?.framework || 'Other'
  const defaultBuildCommand = (id) =>
    langMetaOf(id)?.buildCommand ?? LANGUAGE_DEFAULTS[id]?.buildCommand ?? ''

  /** 切换语言 → 框架、构建命令、能力勾选全部跟着变 */
  const handleLanguageChange = (lang) => {
    setForm(prev => ({
      ...prev,
      language: lang,
      framework: defaultFramework(lang),
      buildCommand: defaultBuildCommand(lang),
      capabilities: defaultCapabilities(lang),
    }))
  }

  /** 取消某能力时，需要一并取消的「依赖它的能力」（如取消镜像构建 → 镜像扫描/K8s 部署不再成立） */
  const capDependentsOf = (lang, capId) =>
    capabilitiesOf(lang).filter(c => (c.requires || []).includes(capId)).map(c => c.id)

  const toggleCapability = (capId) => {
    setForm(prev => {
      const cur = prev.capabilities || []
      const turningOn = !cur.includes(capId)
      let next = turningOn ? [...cur, capId] : cur.filter(c => c !== capId)
      if (!turningOn) {
        const dependents = capDependentsOf(prev.language, capId)
        next = next.filter(id => !dependents.includes(id))
      }
      return { ...prev, capabilities: next }
    })
  }

  /** 当前表单勾选的能力 → 决定哪些区块/选项该出现（未使用 K8s 就不填 K8s 相关） */
  const formCaps = parseCapabilities(form.capabilities)
  const dockerOn = formCaps.includes('DOCKER')
  const k8sOn = formCaps.includes('K8S')
  const formCapDefs = capabilitiesOf(form.language)

  // ---- Dockerfile 模板导入 ----
  const dockerTplFitting = dockerTemplates.filter(t => templateFitsLanguage(t, form.language))
  const dockerTplOthers = dockerTemplates.filter(t => !templateFitsLanguage(t, form.language))

  /** 选中模板 → 覆盖 Dockerfile 内容（已有内容时先确认） */
  const applyDockerfileTemplate = (id) => {
    const t = dockerTemplates.find(x => String(x.id) === String(id))
    if (!t) return
    if (form.dockerfileContent?.trim() && !window.confirm(`当前已有 Dockerfile 内容，确定用模板「${t.name}」覆盖吗？`)) return
    setForm(prev => ({
      ...prev,
      dockerfileContent: t.content || '',
      dockerfilePath: prev.dockerfilePath?.trim() ? prev.dockerfilePath : 'Dockerfile',
    }))
    showToast(`已导入模板「${t.name}」`, 'success', 2500)
  }

  /** 工具链面板只展示「语言必需 + 已勾选能力所需」的工具 */
  const relevantToolIds = (lang, caps) => {
    const meta = langMetaOf(lang)
    if (!meta?.capabilities) return null   // 元数据不可用 → 不筛选，展示全部
    const ids = new Set()
    meta.capabilities.forEach(c => {
      if (caps.includes(c.id)) (c.tools || []).forEach(t => ids.add(t))
    })
    return [...ids]
  }

  /** 详情项目（触发构建弹窗）是否启用了容器化相关能力 */
  const buildDetailDockerOn = !detailProject || hasCapability(detailProject.capabilities, 'DOCKER')
  const buildDetailK8sOn = !detailProject || hasCapability(detailProject.capabilities, 'K8S')
  /** 弹窗文案用的有效能力集合：历史项目未配置过能力 → 视为全量（与展示逻辑一致） */
  const buildDetailCaps = (() => {
    const caps = parseCapabilities(detailProject?.capabilities)
    return caps.length ? caps : CAPABILITY_ORDER
  })()

  // 数据库名净化（与后端 DatabaseProvisioningService.sanitizeDbName 一致）
  const sanitizeDbName = (raw) => {
    if (!raw || !raw.trim()) return ''
    let clean = raw.trim().replace(/[^a-zA-Z0-9_\-]+/g, '_')
    clean = clean.replace(/^-+/, '').replace(/^_+/, '')
    return clean || 'app'
  }
  // 实时预览有效数据库名
  const isH2 = !detailProject?.dbType || detailProject.dbType === 'H2'
  const effectiveDbPreview = buildDbName
    ? sanitizeDbName(buildDbName)
    : (detailProject?.code ? `devops_${sanitizeDbName(detailProject.code)}` : 'devops_app')

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
    try { setProjects(await fetchProjects()) } catch (e) { console.error(e) }
    finally { setLoading(false) }
  }, [])

  useEffect(() => { load() }, [load])

  /**
   * 检查「已部署的项目在远端仓库是否有新提交」。
   * 后端按项目缓存 5 分钟，点「检查更新」传 refresh=true 强制重查。
   * 失败不打扰用户 —— 更新提示是锦上添花，不该影响项目列表本身。
   */
  const checkUpdates = useCallback(async (refresh = false) => {
    setUpdateChecking(true)
    try {
      const list = await fetchProjectUpdateStatus(refresh)
      const m = {}
      for (const s of (Array.isArray(list) ? list : [])) m[s.projectId] = s
      setUpdateMap(m)
    } catch (e) {
      console.error('检查项目更新失败', e)
    } finally {
      setUpdateChecking(false)
    }
  }, [])

  useEffect(() => { checkUpdates(false) }, [checkUpdates])

  /** 徽标悬浮说明：让用户不点也知道「从哪个提交更新到哪个提交」 */
  const updateTooltip = (st) => {
    if (!st) return ''
    return [
      st.deployedCommit
        ? `已部署：${st.deployedCommit.slice(0, 7)}${st.deployedBuildNumber ? '（' + st.deployedBuildNumber + '）' : ''}`
        : `已部署：${st.deployedBuildNumber || '未知版本'}（该版本未记录 commit）`,
      st.remoteCommit ? `远端最新：${st.remoteCommit.slice(0, 7)}` : '',
      st.remoteMessage ? `提交说明：${st.remoteMessage}` : '',
      st.remoteDate ? `提交时间：${new Date(st.remoteDate).toLocaleString('zh-CN', { hour12: false })}` : '',
      st.basis === 'TIME' ? '判定依据：提交时间（该版本未记录 commit）' : '判定依据：commit 比对',
    ].filter(Boolean).join('\n')
  }

  /**
   * 一键更新：沿用「上次部署所用的流水线 + 数据库名」重新构建一次，构建成功后照常走到部署阶段。
   * 之所以复用上次的 pipelineId / dbName，是要让更新等价于「原地升级」——
   * 尤其是 dbName 必须沿用，换成新库等于把线上数据丢掉。
   */
  const handleUpdate = async (p) => {
    const st = updateMap[p.id]
    if (!st?.hasUpdate) return
    const from = st.deployedCommit ? st.deployedCommit.slice(0, 7) : (st.deployedBuildNumber || '未知')
    const to = st.remoteCommit ? st.remoteCommit.slice(0, 7) : 'unknown'
    const deployedWhen = st.deployedAt
      ? '（' + new Date(st.deployedAt).toLocaleString('zh-CN', { hour12: false }) + '）'
      : ''
    if (!window.confirm(
      `项目「${p.name}」的 GitHub 仓库有新提交：\n\n` +
      `当前部署：${from}${deployedWhen}\n` +
      `远端最新：${to}${st.remoteMessage ? '  ' + st.remoteMessage : ''}\n\n` +
      `将触发一次构建，构建成功后自动部署。确认更新？`)) return

    try {
      let pipelineId = st.pipelineId
      if (!pipelineId) {
        // 上次用的流水线可能已被删除，退回项目下第一条
        const pls = await fetchPipelines(p.id)
        pipelineId = Array.isArray(pls) && pls.length ? pls[0].id : null
      }
      if (!pipelineId) {
        showToast('该项目没有可用流水线，无法自动更新，请先创建流水线', 'error', 8000)
        return
      }
      const skipDocker = !hasCapability(p.capabilities, 'DOCKER')
      const skipK8s = !hasCapability(p.capabilities, 'K8S')
      const dbName = st.dbName || (p.code ? `devops_${p.code.replace(/[^a-zA-Z0-9_-]/g, '_')}` : null)

      showToast(`正在触发「${p.name}」的更新构建…`, 'info', 4000)
      const build = await triggerBuild(p.id, pipelineId, null, null, skipDocker, skipK8s, dbName || null)
      if (build?.id) watchBuildResult(build.id)
      // 已经触发更新了，先把标抹掉：新构建要等部署完成才算真的「已更新」
      setUpdateMap(prev => ({ ...prev, [p.id]: { ...prev[p.id], hasUpdate: false } }))
    } catch (e) {
      showToast('更新失败: ' + e.message, 'error', 8000)
    }
  }

  const openNew = () => {
    setEditing(null)
    setForm(DEFAULT_FORM)
    setShowModal(true)
  }

  const openEdit = (p) => {
    setEditing(p)
    setForm({
      name: p.name || '', code: p.code || '', gitUrl: p.gitUrl || '', gitBranch: p.gitBranch || '',
      language: p.language || 'Java', framework: p.framework || 'Spring Boot',
      buildCommand: p.buildCommand || '', description: p.description || '',
      // 旧数据未设置过能力 → 回落到该语言的默认勾选，避免编辑时显示成"全部未勾选"
      capabilities: (() => {
        const caps = parseCapabilities(p.capabilities)
        return caps.length ? caps : defaultCapabilities(p.language || 'Java')
      })(),
      dbType: p.dbType || 'H2', dbHost: p.dbHost || '', dbPort: p.dbPort || 3306,
      dbUsername: p.dbUsername || '', dbPassword: p.dbPassword || '',
      dockerfilePath: p.dockerfilePath || 'Dockerfile',
      dockerfileContent: p.dockerfileContent || '',
    })
    setDbTestResult(null)
    setShowModal(true)
  }

  const handleSave = async () => {
    if (!form.name?.trim()) { alert('项目名称不能为空'); return }
    if (!form.code?.trim()) { alert('项目编码不能为空'); return }
    try {
      // capabilities 以数组维护，提交时转成逗号分隔字符串
      const payload = { ...form, capabilities: (form.capabilities || []).join(',') }
      if (editing) {
        const updated = await updateProject(editing.id, payload)
        emit('project:updated', { project: updated })
      } else {
        const created = await createProject(payload)
        emit('project:created', { project: created })
      }
      setShowModal(false)
      load()
    } catch (e) { alert('保存失败: ' + e.message) }
  }

  const handleDelete = async (p) => {
    if (!confirm(`确定要删除项目「${p.name}」吗？`)) return
    try {
      await deleteProject(p.id)
      // 派发全局删除事件,其他页面 (服务实例/构建记录/部署/流水线) 自动过滤
      emit('project:deleted', { id: p.id })
      load()
    } catch (e) { alert('删除失败: ' + e.message) }
  }

  // 监听其他地方的删除事件,自动从本地列表移除
  const removeLocal = useCallback((detail) => {
    if (detail?.id != null) {
      setProjects(prev => prev.filter(x => x.id !== detail.id))
    }
  }, [])
  useResourceEvent('project:deleted', removeLocal)

  // 监听其他地方的创建/更新事件,本地列表同步
  const upsertLocal = useCallback((detail) => {
    const p = detail?.project
    if (!p || p.id == null) return
    setProjects(prev => {
      const idx = prev.findIndex(x => x.id === p.id)
      if (idx >= 0) {
        const next = prev.slice(); next[idx] = { ...next[idx], ...p }; return next
      }
      return [...prev, p]
    })
  }, [])
  useResourceEvent('project:created', upsertLocal)
  useResourceEvent('project:updated', upsertLocal)

  const openDetail = async (p) => {
    setDetailProject(p)
    setWsCheckResult(null)
    try {
      const [pls, bs, es] = await Promise.all([
        fetchPipelines(p.id),
        fetchBuilds(p.id),
        fetchEnvironments(),
      ])
      setDetailPipelines(pls)
      setDetailBuilds(bs)
      setDetailEnvs(es)
    } catch (e) { console.error(e) }
  }

  const handleCheckWorkspace = async () => {
    if (!detailProject) return
    setWsChecking(true)
    setWsCheckResult(null)
    try {
      const res = await checkWorkspace(detailProject.id)
      setWsCheckResult(res)
    } catch (e) {
      setWsCheckResult({ error: '检查失败: ' + e.message, ok: false })
    } finally {
      setWsChecking(false)
    }
  }

  const handleTriggerBuild = async (pipeline) => {
    if (!detailProject) return
    setBuildPendingPipeline(pipeline)
    // 项目未启用镜像构建 / K8s 部署时，直接跳过对应阶段（界面上也不出现这两个开关）
    setBuildSkipDocker(!hasCapability(detailProject.capabilities, 'DOCKER'))
    setBuildSkipK8s(!hasCapability(detailProject.capabilities, 'K8S'))
    // 默认数据库名: devops_<projectCode>
    const defaultDb = detailProject.code ? `devops_${detailProject.code.replace(/[^a-zA-Z0-9_-]/g, '_')}` : ''
    setBuildDbName(defaultDb)
    setShowBuildModal(true)
  }

  const handleConfirmBuild = async () => {
    if (!detailProject || !buildPendingPipeline) return
    setShowBuildModal(false)
    const projectId = detailProject.id
    try {
      showToast('构建触发中…', 'info', 3000)
      const build = await triggerBuild(projectId, buildPendingPipeline.id, null, null,
        buildSkipDocker, buildSkipK8s,
        buildDbName || null)
      showToast(build?.buildNumber
        ? `构建 ${build.buildNumber} 已触发，正在执行…`
        : '构建已触发，正在执行…', 'info', 4000)
      // 异步构建：轮询到终态再给结论（成功绿 / 失败红），不再只有「已触发」一句话
      if (build?.id) watchBuildResult(build.id)
    } catch (e) {
      showToast('触发失败: ' + e.message, 'error', 6000)
    }
  }

  const filtered = projects.filter(p =>
    !search || p.name?.toLowerCase().includes(search.toLowerCase())
  )

  if (loading) return <div className="empty-state"><div className="spinner" /></div>

  return (
    <>
      <div className="page-header">
        <h2>📁 项目管理</h2>
        <div className="btn-group">
          <input
            placeholder="搜索项目..."
            value={search}
            onChange={e => setSearch(e.target.value)}
            style={{ padding: '8px 12px', border: '1px solid #d1d5db', borderRadius: 8, fontSize: 13 }}
          />
          <button className="btn btn-outline"
            title="重新比对每个已部署项目的远端最新提交（默认走 5 分钟缓存）"
            onClick={() => checkUpdates(true)} disabled={updateChecking}>
            {updateChecking ? '检查中…' : '🔍 检查更新'}
          </button>
          {canManage && <button className="btn btn-primary" onClick={openNew}>+ 新建项目</button>}
        </div>
      </div>

      <div className="card" style={{ padding: 0 }}>
        <div className="table-container">
          <table>
            <thead>
              <tr>
                <th>ID</th>
                <th>项目名称</th>
                <th>编码</th>
                <th>语言</th>
                <th>框架</th>
                <th>Git 分支</th>
                <th>创建时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {filtered.length === 0 ? (
                <tr><td colSpan={8} style={{ textAlign: 'center', padding: 32, color: '#9ca3af' }}>暂无项目数据</td></tr>
              ) : (
                filtered.map(p => (
                  <tr key={p.id}>
                    <td style={{ color: '#9ca3af' }}>#{p.id}</td>
                    <td>
                      {p.gitUrl ? (
                        <a href={p.gitUrl} target="_blank" rel="noreferrer" style={{ color: '#6c63ff', fontWeight: 600, cursor: 'pointer' }}>
                          {p.name}
                        </a>
                      ) : p.name}
                      {/* 已部署 + 远端有新提交 → 小提示；悬浮可见「从哪个提交到哪个提交」 */}
                      {updateMap[p.id]?.hasUpdate && (
                        <span
                          title={updateTooltip(updateMap[p.id])}
                          style={{
                            marginLeft: 6, padding: '1px 6px', borderRadius: 10, fontSize: 11,
                            background: '#fef3c7', color: '#92400e', border: '1px solid #fcd34d',
                            cursor: 'help', whiteSpace: 'nowrap',
                          }}>
                          ⬆ 有更新
                        </span>
                      )}
                    </td>
                    <td><code style={{ fontSize: 12, background: '#f3f4f6', padding: '2px 6px', borderRadius: 4 }}>{p.code}</code></td>
                    <td>{p.language}</td>
                    <td>{p.framework}</td>
                    <td>{p.gitBranch || '跟随远端默认'}</td>
                    <td style={{ color: '#9ca3af', fontSize: 12 }}>{p.createdAt ? new Date(p.createdAt).toLocaleDateString() : '-'}</td>
                    <td>
                      <div className="btn-group">
                        {updateMap[p.id]?.hasUpdate && canTrigger && (
                          <button className="btn btn-primary btn-sm"
                            title="沿用上次部署的流水线与数据库名，重新构建并自动部署"
                            onClick={() => handleUpdate(p)}>⬆ 更新</button>
                        )}
                        <button className="btn btn-outline btn-sm" onClick={() => openDetail(p)}>📋 详情</button>
                        {p.gitUrl && (
                          <button className="btn btn-outline btn-sm"
                            title="Git 集成：凭证、分支、提交、Merge Request、GitHub Actions、代码预览"
                            onClick={() => navigate(`/projects/${p.id}/git`)}>🔀 Git</button>
                        )}
                        <button className="btn btn-outline btn-sm"
                          onClick={() => { setShowMembers(p.id); setMemberProjectName(p.name) }}>👥 成员</button>
                        {canManage && <button className="btn btn-outline btn-sm" onClick={() => openEdit(p)}>✏️</button>}
                        {canManage && <button className="btn btn-danger btn-sm" onClick={() => handleDelete(p)}>🗑</button>}
                      </div>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Create/Edit Modal */}
      {showModal && (
        <div className="modal-overlay" onClick={() => setShowModal(false)}>
          <div className="modal" onClick={e => e.stopPropagation()}>
            <h3>{editing ? '编辑项目' : '新建项目'}</h3>
            <div className="form-group">
              <label>项目名称 *</label>
              <input value={form.name} onChange={e => setForm({...form, name: e.target.value})} placeholder="如: 用户管理系统" />
            </div>
            <div className="form-group">
              <label>项目编码 *</label>
              <input value={form.code} onChange={e => setForm({...form, code: e.target.value})} placeholder="如: user-service (用作工作目录名)" />
            </div>
            <div className="form-group">
              <label>Git 仓库地址</label>
              <input value={form.gitUrl} onChange={e => setForm({...form, gitUrl: e.target.value})} placeholder="https://github.com/user/repo.git" />
            </div>
            <div className="form-group">
              <label>Git 分支</label>
              <input value={form.gitBranch} onChange={e => setForm({...form, gitBranch: e.target.value})}
                placeholder="留空 = 跟随远端默认分支（main / master 自动识别）" />
            </div>
            <div className="detail-grid">
              <div className="form-group">
                <label>语言</label>
                <select value={form.language} onChange={e => handleLanguageChange(e.target.value)}>
                  {/* 项目原本用的语言已被隐藏时补一个占位项，避免 select 静默跳到第一项、把语言改掉 */}
                  {form.language && !langOptions.some(o => o.value === form.language) && (
                    <option value={form.language}>{form.language}（已隐藏）</option>
                  )}
                  {langOptions.map(l => <option key={l.value} value={l.value}>{l.label}</option>)}
                </select>
              </div>
              <div className="form-group">
                <label>框架</label>
                <select value={form.framework} onChange={e => setForm({...form, framework: e.target.value})}>
                  {frameworksOf(form.language).map(f => <option key={f} value={f}>{f}</option>)}
                </select>
              </div>
            </div>
            <div className="form-group">
              <label>构建命令</label>
              <input value={form.buildCommand} onChange={e => setForm({...form, buildCommand: e.target.value})} />
            </div>

            {/* 构建能力 — 随语言联动，决定默认流水线包含哪些阶段 */}
            {capabilitiesOf(form.language).length > 0 && (
              <div className="form-group">
                <label>
                  构建能力
                  <span style={{ fontWeight: 400, color: '#9ca3af', fontSize: 12, marginLeft: 6 }}>
                    （随语言联动，决定流水线包含哪些阶段）
                  </span>
                </label>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginTop: 4 }}>
                  {formCapDefs.map(c => {
                    const checked = formCaps.includes(c.id)
                    const missing = missingRequires(c, c.id, formCaps)
                    const blocked = missing.length > 0
                    const disabled = !!c.required || blocked
                    const names = missing.map(m => capabilityLabel(formCapDefs.find(d => d.id === m), m)).join('、')
                    return (
                      <label key={c.id}
                        title={blocked ? `${c.desc}｜需先勾选「${names}」` : c.desc}
                        style={{
                          display: 'inline-flex', alignItems: 'center', gap: 5,
                          fontSize: 12.5, padding: '4px 10px', borderRadius: 6,
                          cursor: disabled ? 'not-allowed' : 'pointer',
                          opacity: blocked ? 0.55 : 1,
                          background: checked ? 'rgba(108,99,255,0.10)' : '#f3f4f6',
                          color: checked ? '#4338ca' : '#6b7280',
                          border: `1px solid ${checked ? 'rgba(108,99,255,0.4)' : '#e5e7eb'}`,
                        }}>
                        <input type="checkbox" checked={checked} disabled={disabled}
                          onChange={() => toggleCapability(c.id)} style={{ margin: 0 }} />
                        {c.label}
                        {c.required && <span style={{ fontSize: 10.5, opacity: 0.65 }}>必选</span>}
                        {blocked && <span style={{ fontSize: 10.5, opacity: 0.75 }}>需「{names}」</span>}
                      </label>
                    )
                  })}
                </div>
                {!dockerOn && (
                  <div style={{ fontSize: 11.5, color: '#9ca3af', marginTop: 6 }}>
                    未勾选「镜像构建」→ 镜像安全扫描 / K8s 部署不可用，相关配置项已隐藏。
                  </div>
                )}
              </div>
            )}

            {/* 工具链检测 — 只检测本语言必需 与 已勾选能力所需 的工具 */}
            <ToolchainPanel language={form.language}
              toolFilter={relevantToolIds(form.language, formCaps)} />

            {/* 数据库类型选择 */}
            <div className="detail-grid">
              <div className="form-group">
                <label>数据库</label>
                <select value={form.dbType} onChange={e => {
                  setForm({...form, dbType: e.target.value, dbHost: '', dbPort: 3306, dbUsername: '', dbPassword: ''})
                }}>
                  <option value="H2">H2 内嵌 (推荐 — 零依赖)</option>
                  <option value="MYSQL">MySQL</option>
                </select>
              </div>
            </div>

            {/* MySQL 连接信息 */}
            {form.dbType === 'MYSQL' && (
              <>
                <div className="detail-grid">
                  <div className="form-group">
                    <label>MySQL 主机</label>
                    <input value={form.dbHost} onChange={e => setForm({...form, dbHost: e.target.value})}
                      placeholder="host.docker.internal 或 IP" />
                  </div>
                  <div className="form-group">
                    <label>端口</label>
                    <input type="number" value={form.dbPort} onChange={e => setForm({...form, dbPort: parseInt(e.target.value) || 3306})} />
                  </div>
                </div>
                <div className="detail-grid">
                  <div className="form-group">
                    <label>用户名</label>
                    <input value={form.dbUsername} onChange={e => setForm({...form, dbUsername: e.target.value})} placeholder="root" />
                  </div>
                  <div className="form-group">
                    <label>密码</label>
                    <input type="password" value={form.dbPassword} onChange={e => setForm({...form, dbPassword: e.target.value})} />
                  </div>
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginTop: 4 }}>
                  <button type="button" className="btn btn-outline" disabled={dbTesting}
                    onClick={async () => {
                      if (!form.dbHost) { alert('请先填写 MySQL 主机'); return }
                      setDbTesting(true)
                      setDbTestResult(null)
                      try {
                        const r = await testProjectDbConnection({
                          dbType: 'MYSQL', dbHost: form.dbHost, dbPort: form.dbPort || 3306,
                          dbUsername: form.dbUsername, dbPassword: form.dbPassword,
                        })
                        setDbTestResult(r)
                      } catch (e) {
                        setDbTestResult({ success: false, message: '❌ 请求失败: ' + e.message })
                      } finally { setDbTesting(false) }
                    }}>
                    {dbTesting ? '⏳ 测试中…' : '🔌 测试 MySQL 连接'}
                  </button>
                  {dbTestResult && (
                    <span style={{
                      fontSize: 12, padding: '4px 10px', borderRadius: 4,
                      background: dbTestResult.success ? '#dcfce7' : '#fee2e2',
                      color: dbTestResult.success ? '#166534' : '#991b1b',
                    }}>
                      {dbTestResult.message}
                      {dbTestResult.costMs != null && <span style={{ marginLeft: 6, opacity: 0.7 }}>({dbTestResult.costMs}ms)</span>}
                    </span>
                  )}
                </div>
                {dbTestResult?.databases?.length > 0 && (
                  <div style={{ fontSize: 11, color: '#6b7280', marginTop: 4 }}>
                    可见数据库: {dbTestResult.databases.slice(0, 8).join(', ')}
                    {dbTestResult.databases.length > 8 && ' ...'}
                  </div>
                )}
              </>
            )}

            <div className="form-group">
              <label>描述</label>
              <textarea value={form.description} onChange={e => setForm({...form, description: e.target.value})} rows={2} />
            </div>

            {/* Dockerfile 配置：仅在勾选「镜像构建」时需要；未使用容器化项目不展示 */}
            {dockerOn && (
              <div className="form-section" style={{ marginTop: 12, padding: 12, background: '#f8fafc', borderRadius: 6 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, margin: '0 0 8px', flexWrap: 'wrap' }}>
                  <h4 style={{ margin: 0, fontSize: 13, color: '#475569' }}>🐳 Dockerfile 配置</h4>
                  {dockerTemplates.length > 0 && (
                    <select
                      value=""
                      title="从「系统管理 → 模板管理」里的 Dockerfile 模板导入内容"
                      onChange={e => applyDockerfileTemplate(e.target.value)}
                      style={{ maxWidth: 320, padding: '4px 8px', fontSize: 12, border: '1px solid #cbd5e1', borderRadius: 6, background: '#fff' }}
                    >
                      <option value="">📥 从模板导入…</option>
                      {dockerTplFitting.length > 0 && (
                        <optgroup label={`适配 ${form.language}`}>
                          {dockerTplFitting.map(t => <option key={t.id} value={t.id}>{t.name}</option>)}
                        </optgroup>
                      )}
                      {dockerTplOthers.length > 0 && (
                        <optgroup label="其他语言">
                          {dockerTplOthers.map(t => <option key={t.id} value={t.id}>{t.name}{t.category ? ` · ${t.category}` : ''}</option>)}
                        </optgroup>
                      )}
                    </select>
                  )}
                </div>
                <div className="detail-grid">
                  <div className="form-group" style={{ gridColumn: '1 / -1' }}>
                    <label>Dockerfile 路径（留空使用默认 Dockerfile）</label>
                    <input value={form.dockerfilePath} onChange={e => setForm({...form, dockerfilePath: e.target.value})} placeholder="Dockerfile" />
                  </div>
                </div>
                <div className="form-group">
                  <label>Dockerfile 内容（O 项目仓库无 Dockerfile 时使用此处内容，留空则 O 项目必须自带）</label>
                  <textarea
                    value={form.dockerfileContent}
                    onChange={e => setForm({...form, dockerfileContent: e.target.value})}
                    rows={8}
                    placeholder={'# 示例: 简单 Spring Boot Dockerfile\nFROM eclipse-temurin:21-jre-alpine\nWORKDIR /app\nCOPY target/*.jar app.jar\nEXPOSE 8080\nENTRYPOINT ["java","-jar","/app/app.jar"]'}
                    style={{ fontFamily: 'monospace', fontSize: 12 }}
                  />
                </div>
              </div>
            )}
            {!dockerOn && form.dockerfileContent && (
              <div style={{ marginTop: 12, fontSize: 11.5, color: '#9ca3af' }}>
                🐳 已保留原有 Dockerfile 内容（当前未启用镜像构建，如需修改请先勾选「镜像构建」）。
              </div>
            )}

            <div className="btn-group" style={{ justifyContent: 'flex-end', marginTop: 8 }}>
              <button className="btn btn-outline" onClick={() => setShowModal(false)}>取消</button>
              <button className="btn btn-primary" onClick={handleSave}>{editing ? '保存' : '创建'}</button>
            </div>
          </div>
        </div>
      )}

      {/* Detail Modal */}
      {detailProject && (
        <div className="modal-overlay" onClick={() => setDetailProject(null)}>
          <div className="modal" style={{ maxWidth: 700 }} onClick={e => e.stopPropagation()}>
            <h3>📁 {detailProject.name}</h3>

            <div className="detail-section">
              <h4>基本信息</h4>
              <div className="detail-grid">
                <div className="detail-item"><div className="label">项目编码</div><div className="value"><code>{detailProject.code}</code></div></div>
                <div className="detail-item"><div className="label">语言</div><div className="value">{detailProject.language} / {detailProject.buildTool}</div></div>
                <div className="detail-item"><div className="label">Git 地址</div><div className="value">
                  {detailProject.gitUrl ? <a href={detailProject.gitUrl} target="_blank" rel="noreferrer">{detailProject.gitUrl}</a> : '未配置'}
                </div></div>
                <div className="detail-item"><div className="label">分支</div><div className="value">{detailProject.gitBranch || '跟随远端默认'}</div></div>
                <div className="detail-item" style={{ gridColumn: '1 / -1' }}><div className="label">构建命令</div><div className="value"><code>{detailProject.buildCommand || '-'}</code></div></div>
                <div className="detail-item" style={{ gridColumn: '1 / -1' }}>
                  <div className="label">构建能力</div>
                  <div className="value">{describeCapabilities(detailProject.capabilities) || CAPABILITY_LABEL.BUILD}</div>
                </div>
                {hasCapability(detailProject.capabilities, 'DOCKER') && (
                  <>
                    <div className="detail-item" style={{ gridColumn: '1 / -1' }}><div className="label">Dockerfile 路径</div><div className="value"><code>{detailProject.dockerfilePath || 'Dockerfile'}</code></div></div>
                    <div className="detail-item" style={{ gridColumn: '1 / -1' }}><div className="label">Dockerfile 来源</div><div className="value">{detailProject.dockerfileContent ? 'D 平台托管（项目配置）' : 'O 项目仓库自带'}</div></div>
                  </>
                )}
                <div className="detail-item" style={{ gridColumn: '1 / -1' }}><div className="label">描述</div><div className="value">{detailProject.description || '-'}</div></div>
              </div>

              {/* --- 工作目录检查 --- */}
              <div style={{ marginTop: 12, borderTop: '1px solid #e5e7eb', paddingTop: 12 }}>
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                  <span style={{ fontWeight: 600, fontSize: 14 }}>📁 工作目录</span>
                  <button
                    className="btn btn-outline btn-sm"
                    onClick={handleCheckWorkspace}
                    disabled={wsChecking}
                    style={{ fontSize: 12 }}
                  >
                    {wsChecking ? '⏳ 检查中...' : '🔍 检查工作目录'}
                  </button>
                </div>

                {wsCheckResult && (
                  <div style={{
                    marginTop: 8, padding: '10px 14px', borderRadius: 8, fontSize: 12,
                    background: wsCheckResult.error ? '#fef2f2' : wsCheckResult.ok ? '#f0fdf4' : '#fffbeb',
                    border: `1px solid ${wsCheckResult.error ? '#fecaca' : wsCheckResult.ok ? '#bbf7d0' : '#fcd34d'}`,
                    color: '#333', lineHeight: 1.7, maxHeight: 260, overflow: 'auto'
                  }}>
                    <div style={{ fontWeight: 600, marginBottom: 4 }}>
                      {wsCheckResult.workspaceExists ? '✅ 目录已就绪' : '⚠️ 目录尚未创建'}
                    </div>
                    <div>路径: <code style={{ fontSize: 11 }}>{wsCheckResult.workspacePath}</code></div>
                    <div>Git 仓库: {wsCheckResult.isGitRepo ? '✅ 是' : '❌ 否（首次构建时会自动克隆）'}</div>

                    {wsCheckResult.hasPomXml !== undefined && (
                      <div>pom.xml: {wsCheckResult.hasPomXml
                        ? `✅ ${wsCheckResult.pomXmlPath || ''}` : '❌ 未找到'}</div>
                    )}
                    {wsCheckResult.hasPackageJson !== undefined && (
                      <div>package.json: {wsCheckResult.hasPackageJson
                        ? `✅ ${wsCheckResult.packageJsonPath || ''}` : '❌ 未找到'}</div>
                    )}
                    {wsCheckResult.hasMvnw !== undefined && (
                      <div>Maven Wrapper: {wsCheckResult.hasMvnw ? '✅' : '❌'}</div>
                    )}

                    {wsCheckResult.rootFiles && wsCheckResult.rootFiles.length > 0 && (
                      <div style={{ marginTop: 4 }}>
                        <div style={{ fontWeight: 600 }}>根目录文件:</div>
                        <div style={{ wordBreak: 'break-all', color: '#666' }}>
                          {wsCheckResult.rootFiles.join(', ')}
                        </div>
                      </div>
                    )}

                    {wsCheckResult.error && !wsCheckResult.workspaceExists && (
                      <div style={{ color: '#991b1b', marginTop: 4 }}>
                        ⚠️ {wsCheckResult.error}
                      </div>
                    )}
                  </div>
                )}
              </div>
            </div>

            <div className="detail-section">
              <h4>流水线 ({detailPipelines.length})</h4>
              {detailPipelines.length === 0 ? (
                <div style={{ padding: '14px 16px', background: '#fffbeb', border: '1px solid #fcd34d', borderRadius: 8, fontSize: 13 }}>
                  <div style={{ color: '#92400e', fontWeight: 600, marginBottom: 6 }}>
                    ⚠️ 暂无流水线
                  </div>
                  <div style={{ color: '#a16207', marginBottom: 10 }}>
                    构建和部署必须通过流水线触发，请先创建至少一条流水线。
                  </div>
                  <button
                    className="btn btn-primary btn-sm"
                    onClick={() => { setDetailProject(null); navigate('/pipelines') }}
                    style={{ fontSize: 12 }}
                  >
                    🔧 创建流水线
                  </button>
                </div>
              ) : (
                <div className="table-container">
                  <table>
                    <thead><tr><th>名称</th><th>触发方式</th><th>操作</th></tr></thead>
                    <tbody>
                      {detailPipelines.map(pl => (
                        <tr key={pl.id}>
                          <td>{pl.name}</td>
                          <td>{pl.trigger || 'MANUAL'}</td>
                          <td>
                            <button className="btn btn-success btn-sm" onClick={() => handleTriggerBuild(pl)}>▶ 触发构建</button>
                            {' '}
                            <button className="btn btn-outline btn-sm" onClick={() => { setDetailProject(null); navigate(`/projects/${detailProject.id}/pipelines`) }}>🔧</button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>

            <div className="detail-section">
              <h4>最近构建 ({detailBuilds.length})</h4>
              {detailBuilds.length === 0 ? <p style={{color:'#9ca3af',fontSize:13}}>暂无构建记录</p> : (
                <div className="table-container">
                  <table>
                    <thead><tr><th>编号</th><th>状态</th><th>时间</th></tr></thead>
                    <tbody>
                      {detailBuilds.slice(0, 5).map(b => (
                        <tr key={b.id}>
                          <td>#{b.buildNumber}</td>
                          <td><span className={`badge badge-${b.status?.toLowerCase()}`}>{b.status}</span></td>
                          <td>{b.startTime ? new Date(b.startTime).toLocaleString() : '-'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>

            <div className="detail-section">
              <h4>环境 ({detailEnvs.length})</h4>
              {detailEnvs.length === 0 ? <p style={{color:'#9ca3af',fontSize:13}}>暂无环境</p> : (
                detailEnvs.map(env => (
                  <span key={env.id} className={`badge badge-${env.protectedEnv ? 'failed' : 'developer'}`} style={{ marginRight: 6 }}>
                    {env.name}
                  </span>
                ))
              )}
            </div>

            {/* GitHub 云端构建与发布（凭证加密保存在项目上，配置一次即可） */}
            <div className="detail-section">
              <h4>GitHub 云端构建</h4>
              {detailProject.gitUrl
                ? <GithubPanel projectId={detailProject.id} />
                : <p style={{color:'#9ca3af',fontSize:13}}>未配置 Git 仓库地址</p>}
            </div>

            <div style={{ textAlign: 'right', marginTop: 12, display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
              <button className="btn btn-outline" onClick={() => { setDetailProject(null); navigate(`/projects/${detailProject.id}/git`) }}>🔀 Git 集成</button>
              <button className="btn btn-outline" onClick={() => { setDetailProject(null); navigate(`/projects/${detailProject.id}/artifacts`) }}>📦 制品</button>
              <button className="btn btn-outline" onClick={() => { setDetailProject(null); navigate(`/projects/${detailProject.id}/security`) }}>🔒 安全扫描</button>
              <button className="btn btn-outline" onClick={() => setDetailProject(null)}>关闭</button>
            </div>
          </div>
        </div>
      )}

      {/* ===== 构建触发确认弹窗 ===== */}
      {showBuildModal && (
        <div style={{
          position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.45)', zIndex: 9999,
          display: 'flex', alignItems: 'center', justifyContent: 'center'
        }} onClick={() => setShowBuildModal(false)}>
          <div style={{
            background: '#fff', borderRadius: 12, padding: '24px 28px', width: 420, maxWidth: '92vw',
            boxShadow: '0 8px 30px rgba(0,0,0,0.18)'
          }} onClick={e => e.stopPropagation()}>
            <h3 style={{ margin: '0 0 4px', fontSize: 18, color: '#1f2937' }}>⚡ 触发构建</h3>
            <p style={{ margin: '0 0 16px', fontSize: 13, color: '#6b7280' }}>
              流水线: <strong>{buildPendingPipeline?.name}</strong>
            </p>

            <div style={{ background: '#f9fafb', borderRadius: 8, padding: '14px 16px' }}>
              {!buildDetailDockerOn && !buildDetailK8sOn && (
                <div style={{ fontSize: 12, color: '#6b7280', padding: '2px 0' }}>
                  本项目未启用镜像构建与 K8s 部署，无需选择跳过项。
                </div>
              )}
              {buildDetailDockerOn && (
              <label style={{
                display: 'flex', alignItems: 'center', gap: 10, padding: '8px 0',
                cursor: 'pointer', borderBottom: buildDetailK8sOn ? '1px solid #e5e7eb' : 'none'
              }}>
                <input
                  type="checkbox"
                  checked={buildSkipDocker}
                  onChange={e => setBuildSkipDocker(e.target.checked)}
                  style={{ width: 18, height: 18, cursor: 'pointer', accentColor: '#6366f1' }}
                />
                <div style={{ flex: 1 }}>
                  <div style={{ fontWeight: 600, fontSize: 14, color: '#374151' }}>🐳 跳过 Docker</div>
                  <div style={{ fontSize: 11, color: '#9ca3af' }}>不构建和推送 Docker 镜像</div>
                </div>
              </label>
              )}
              {buildDetailK8sOn && (
              <label style={{
                display: 'flex', alignItems: 'center', gap: 10, padding: '8px 0',
                cursor: 'pointer'
              }}>
                <input
                  type="checkbox"
                  checked={buildSkipK8s}
                  onChange={e => setBuildSkipK8s(e.target.checked)}
                  style={{ width: 18, height: 18, cursor: 'pointer', accentColor: '#6366f1' }}
                />
                <div style={{ flex: 1 }}>
                  <div style={{ fontWeight: 600, fontSize: 14, color: '#374151' }}>☸️ 跳过 Kubectl</div>
                  <div style={{ fontSize: 11, color: '#9ca3af' }}>不执行 Kubernetes 部署</div>
                </div>
              </label>
              )}
            </div>

            {/* 数据库隔离配置 — H2 模式无需配置 */}
            {isH2 ? (
              <div style={{ marginTop: 12, background: '#f0fdf4', borderRadius: 8, padding: '10px 12px' }}>
                <div style={{ fontSize: 12, fontWeight: 600, color: '#065f46', marginBottom: 4 }}>
                  🗄 数据库：H2 内嵌模式
                </div>
                <span style={{ fontSize: 11, color: '#047857' }}>
                  O 项目使用 Spring Boot 自带 H2 文件数据库，零外部依赖，Pod 内自包含运行。
                </span>
              </div>
            ) : (
            <div style={{ marginTop: 12, background: '#f0f9ff', borderRadius: 8, padding: '10px 12px' }}>
              <div style={{ fontSize: 12, fontWeight: 600, color: '#0369a1', marginBottom: 8 }}>
                🗄 数据库隔离配置（部署时自动创建独立库）
              </div>
              <label style={{ display: 'block', marginBottom: 8 }}>
                <span style={{ fontSize: 11, color: '#6b7280' }}>数据库名</span>
                <input
                  type="text"
                  value={buildDbName}
                  onChange={e => setBuildDbName(e.target.value)}
                  placeholder={`devops_${detailProject?.code || 'app'}`}
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

            <p style={{
              margin: '12px 0 0', fontSize: 11, color: '#9ca3af', textAlign: 'center'
            }}>
              将执行: {describeCapabilities(
                buildDetailCaps
                  .filter(id => id !== 'DOCKER' || !buildSkipDocker)
                  .filter(id => id !== 'K8S' || !buildSkipK8s),
                capabilitiesOf(detailProject?.language)
              ) || CAPABILITY_LABEL.BUILD}
            </p>

            <div style={{ display: 'flex', gap: 10, marginTop: 16, justifyContent: 'flex-end' }}>
              <button
                className="btn btn-outline"
                onClick={() => setShowBuildModal(false)}
                style={{ fontSize: 13 }}
              >
                取消
              </button>
              <button
                className="btn btn-success"
                onClick={handleConfirmBuild}
                style={{ fontSize: 13 }}
              >
                ▶ 确认构建
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Toast notification */}
      {toast && (
        <div className={`toast toast-${toast.type}`}>
          {toast.text}
        </div>
      )}

      {/* Project Member Panel */}
      {showMembers && (
        <ProjectMemberPanel
          projectId={showMembers}
          projectName={memberProjectName}
          onClose={() => { setShowMembers(null); setMemberProjectName('') }}
        />
      )}

    </>
  )
}
