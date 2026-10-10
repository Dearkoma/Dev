import React, { useState, useEffect, useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import {
  fetchTrivyAvailable, fetchSecurityReportsByProject, fetchSecurityReport,
  fetchVulnDbStatus, updateVulnDb,
  fetchSecurityVulnsByReport, updateSecurityVulnStatus, fetchSecurityStats,
  fetchProjects, fetchProject, triggerSecurityScan
} from '../api'

const SEVERITY_COLORS = {
  CRITICAL: { bg: '#FCEBEB', fg: '#791F1F' },
  HIGH:     { bg: '#FAEEDA', fg: '#854F0B' },
  MEDIUM:   { bg: '#E6F1FB', fg: '#0C447C' },
  LOW:      { bg: '#EAF3DE', fg: '#27500A' },
  UNKNOWN:  { bg: '#F1EFE8', fg: '#444441' }
}

const STATUS_LABEL = {
  PASSED:  { text: '通过', bg: '#EAF3DE', fg: '#27500A' },
  FAILED:  { text: '未通过', bg: '#FCEBEB', fg: '#791F1F' },
  ERRORED: { text: '执行错误', bg: '#FAEEDA', fg: '#854F0B' }
}

const SCAN_TYPE_LABEL = {
  FS: '代码扫描',
  DEPENDENCY: '依赖扫描',
  IMAGE: '镜像扫描'
}

const VULN_STATUS_LABEL = {
  OPEN: { text: '未处理', bg: '#FCEBEB', fg: '#791F1F' },
  FIXED: { text: '已修复', bg: '#EAF3DE', fg: '#27500A' },
  IGNORED: { text: '已忽略', bg: '#F1EFE8', fg: '#5F5E5A' },
  FALSE_POSITIVE: { text: '误报', bg: '#E6F1FB', fg: '#0C447C' }
}

function SeverityChip({ severity, count }) {
  const c = SEVERITY_COLORS[severity] || SEVERITY_COLORS.UNKNOWN
  if (!count) return null
  return (
    <span style={{
      background: c.bg, color: c.fg, padding: '2px 8px', borderRadius: 3,
      fontSize: 11, fontWeight: 500, minWidth: 36, textAlign: 'center'
    }}>
      {severity} {count}
    </span>
  )
}

function StatusChip({ status }) {
  const c = STATUS_LABEL[status] || STATUS_LABEL.ERRORED
  return (
    <span style={{
      background: c.bg, color: c.fg, padding: '2px 8px', borderRadius: 3,
      fontSize: 11, fontWeight: 500
    }}>{c.text}</span>
  )
}

export default function SecurityPanel() {
  const { projectId: paramProjectId } = useParams()
  const navigate = useNavigate()
  const { canManage } = useAuth()

  // 支持两种进入方式:
  // 1. /security          → 无 URL 参数, 恢复上次选中的项目 (sessionStorage)
  // 2. /projects/:id/security → 有 URL 参数, 自动选中对应项目 (来自项目详情页跳转)
  const [selectedId, setSelectedId] = useState(() => {
    if (paramProjectId) return Number(paramProjectId)
    // 无 URL 参数时尝试从 sessionStorage 恢复上次选中的项目
    const cached = sessionStorage.getItem('security_selected_project')
    return cached ? Number(cached) : null
  })
  const projectId = selectedId  // 统一用 selectedId 作为当前项目

  const [projects, setProjects] = useState([])
  const [projectName, setProjectName] = useState('')
  const [trivyAvailable, setTrivyAvailable] = useState(null)
  const [stats, setStats] = useState(null)
  const [reports, setReports] = useState([])
  const [selectedReport, setSelectedReport] = useState(null)
  const [vulns, setVulns] = useState([])
  const [loading, setLoading] = useState(false)
  const [filter, setFilter] = useState('ALL')
  const [scanning, setScanning] = useState(false)
  const [scanMsg, setScanMsg] = useState(null)
  const [scanElapsed, setScanElapsed] = useState(0)
  const [dbStatus, setDbStatus] = useState(null)
  const [dbUpdating, setDbUpdating] = useState(false)
  const [dbUpdateMsg, setDbUpdateMsg] = useState(null)
  const [dbElapsed, setDbElapsed] = useState(0)

  // 漏洞库状态: 首次加载 + 更新完成后刷新
  const refreshDbStatus = useCallback(() => {
    fetchVulnDbStatus().then(setDbStatus).catch(console.error)
  }, [])
  useEffect(() => { refreshDbStatus() }, [refreshDbStatus])

  // 加载项目列表 + 如果有 URL 参数则加载项目名称
  useEffect(() => {
    fetchProjects().then(list => setProjects(list || [])).catch(console.error)
    if (paramProjectId) {
      fetchProject(Number(paramProjectId)).then(p => {
        if (p) setProjectName(p.name || '')
      }).catch(console.error)
    }
  }, [paramProjectId])

  // 选中项目变化时加载其安全数据
  const load = useCallback(async () => {
    if (!projectId) {
      setTrivyAvailable(null)
      setStats(null)
      setReports([])
      setSelectedReport(null)
      setVulns([])
      return
    }
    setLoading(true)
    try {
      const [t, s, r] = await Promise.all([
        fetchTrivyAvailable(),
        fetchSecurityStats(projectId),
        fetchSecurityReportsByProject(projectId)
      ])
      setTrivyAvailable(t)
      setStats(s)
      setReports(r || [])
      if (r && r.length > 0) {
        setSelectedReport(prev => prev && r.find(x => x.id === prev.id) ? prev : r[0])
      } else {
        setSelectedReport(null)
      }
    } catch (e) { console.error(e) }
    finally { setLoading(false) }
  }, [projectId])

  useEffect(() => { load() }, [load])

  // 选中项目时更新项目名
  useEffect(() => {
    if (projectId) {
      const p = projects.find(x => x.id === projectId)
      if (p) setProjectName(p.name || '')
    }
  }, [projectId, projects])

  // 加载漏洞明细
  useEffect(() => {
    if (selectedReport) {
      fetchSecurityVulnsByReport(selectedReport.id).then(setVulns).catch(console.error)
    } else {
      setVulns([])
    }
  }, [selectedReport])

  const handleSelectProject = (p) => {
    setSelectedId(p.id)
    setProjectName(p.name || '')
    setSelectedReport(null)
    setScanMsg(null)
    // 记住选择: 侧边栏切走再回来时自动恢复
    sessionStorage.setItem('security_selected_project', String(p.id))
    // 同步更新 URL (不跳转, 仅替换历史记录)
    navigate(`/projects/${p.id}/security`, { replace: true })
  }

  const handleResolve = async (vuln, status) => {
    const note = status === 'IGNORED' ? prompt('忽略原因:') : null
    if (status === 'IGNORED' && !note) return
    try {
      await updateSecurityVulnStatus(vuln.id, status, note || '')
      setVulns(prev => prev.map(v => v.id === vuln.id
        ? { ...v, status, resolveNote: note || '', resolvedAt: new Date().toISOString() }
        : v))
      load()
    } catch (e) { alert('操作失败: ' + e.message) }
  }

  const handleScan = async (scanType) => {
    if (!projectId) return
    setScanning(true)
    setScanMsg(null)
    setScanElapsed(0)
    const timer = setInterval(() => setScanElapsed(e => e + 1), 1000)
    try {
      const result = await triggerSecurityScan(projectId, scanType, { thresholdHigh: 5, thresholdCritical: 0 })
      setScanMsg(result)
      await load()
    } catch (e) {
      setScanMsg({ success: false, message: '请求失败: ' + e.message })
    } finally {
      clearInterval(timer)
      setScanning(false)
    }
  }

  // 手动更新漏洞库 (默认跳过在线更新, 用此按钮刷新本地库)
  const handleDbUpdate = async (includeJavaDb = true) => {
    if (dbUpdating || scanning) return
    setDbUpdating(true)
    setDbUpdateMsg(null)
    setDbElapsed(0)
    const timer = setInterval(() => setDbElapsed(e => e + 1), 1000)
    try {
      const result = await updateVulnDb(includeJavaDb)
      setDbUpdateMsg(result)
      refreshDbStatus()
    } catch (e) {
      setDbUpdateMsg({ success: false, message: '更新失败: ' + e.message })
    } finally {
      clearInterval(timer)
      setDbUpdating(false)
    }
  }

  // ================== 渲染 ==================

  const selectedProject = projects.find(p => p.id === projectId)
  const filteredVulns = filter === 'ALL' ? vulns : vulns.filter(v => v.severity === filter)
  const latestReport = stats?.latestReport
  const totalVulns = vulns.length

  return (
    <div style={{ padding: 24 }}>
      <h2 style={{ fontSize: 18, fontWeight: 500, margin: '0 0 4px' }}>🔒 安全扫描</h2>
      <p style={{ fontSize: 13, color: '#888780', margin: '0 0 16px' }}>
        集成 Trivy — 代码 / 依赖 / 容器镜像三层安全扫描 · 阈值超限自动阻断部署
      </p>

      <div style={{ display: 'grid', gridTemplateColumns: '220px 1fr', gap: 16, minHeight: 500 }}>
        {/* ====== 左侧: 项目列表 ====== */}
        <div style={{
          background: '#FFFFFF', border: '0.5px solid rgba(17,24,28,0.15)',
          borderRadius: 8, overflow: 'hidden', display: 'flex', flexDirection: 'column'
        }}>
          <div style={{
            padding: '10px 12px', borderBottom: '0.5px solid rgba(17,24,28,0.15)',
            background: '#F8F7F2', fontSize: 12, fontWeight: 500, color: '#444441'
          }}>
            项目列表 ({projects.length})
          </div>
          <div style={{ flex: 1, overflowY: 'auto', maxHeight: 'calc(100vh - 260px)' }}>
            {projects.length === 0 ? (
              <div style={{ padding: 16, fontSize: 12, color: '#888780', textAlign: 'center' }}>
                暂无项目，请先在「项目管理」中创建
              </div>
            ) : (
              projects.map(p => (
                <div
                  key={p.id}
                  onClick={() => handleSelectProject(p)}
                  style={{
                    padding: '10px 12px', cursor: 'pointer',
                    borderBottom: '0.5px solid rgba(17,24,28,0.05)',
                    background: p.id === projectId ? '#EEEDFE' : 'transparent',
                    borderLeft: p.id === projectId ? '3px solid #534AB7' : '3px solid transparent'
                  }}>
                  <div style={{ fontWeight: 500, fontSize: 12, color: '#2C2C2A', marginBottom: 2 }}>
                    📁 {p.name}
                  </div>
                  <div style={{ fontSize: 10, color: '#9ca3af' }}>{p.code || p.gitUrl || '—'}</div>
                </div>
              ))
            )}
          </div>
        </div>

        {/* ====== 右侧: 扫描内容 ====== */}
        <div>
          {!projectId ? (
            <div style={{
              background: '#FFFFFF', border: '0.5px solid rgba(17,24,28,0.15)',
              borderRadius: 8, padding: 40, textAlign: 'center', color: '#888780'
            }}>
              <p style={{ fontSize: 24, margin: '0 0 8px' }}>👈</p>
              <p style={{ fontSize: 13 }}>从左侧选择一个项目，查看其安全扫描报告</p>
            </div>
          ) : loading && !stats ? (
            <div style={{ textAlign: 'center', padding: 40 }}><div className="spinner" /></div>
          ) : (
            <>
              {/* 当前项目标签 + Trivy 状态 */}
              <div style={{ marginBottom: 12, display: 'flex', alignItems: 'center', gap: 10 }}>
                <span style={{
                  background: '#EEEDFE', color: '#534AB7', padding: '3px 12px',
                  borderRadius: 12, fontSize: 12, fontWeight: 500
                }}>
                  📁 {projectName || (selectedProject?.name) || `#${projectId}`}
                </span>
                {trivyAvailable && (
                  <span style={{
                    background: trivyAvailable.available ? '#EAF3DE' : '#FAEEDA',
                    color: trivyAvailable.available ? '#27500A' : '#854F0B',
                    padding: '3px 10px', borderRadius: 12, fontSize: 11
                  }}>
                    {trivyAvailable.available ? 'Trivy 可用' : 'Trivy 未安装'}
                  </span>
                )}
              </div>

              {/* 扫描按钮 */}
              {trivyAvailable?.available && (
                <div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
                  <button onClick={() => handleScan('fs')} disabled={scanning} style={{
                    padding: '5px 14px', fontSize: 12, fontWeight: 500,
                    background: scanning ? '#C8C6BC' : '#27500A', color: '#FFFFFF',
                    border: 'none', borderRadius: 4, cursor: scanning ? 'not-allowed' : 'pointer'
                  }}>
                    {scanning ? `⏳ 扫描中 ${scanElapsed}s...` : '🔍 代码扫描'}
                  </button>
                  <button onClick={() => handleScan('dep')} disabled={scanning} style={{
                    padding: '5px 14px', fontSize: 12, fontWeight: 500,
                    background: scanning ? '#C8C6BC' : '#27500A', color: '#FFFFFF',
                    border: 'none', borderRadius: 4, cursor: scanning ? 'not-allowed' : 'pointer'
                  }}>
                    {scanning ? `⏳ 扫描中 ${scanElapsed}s...` : '📦 依赖扫描'}
                  </button>
                  {/* 更新漏洞库: 默认扫描用本地缓存库, 此按钮手动在线更新 */}
                  <button onClick={() => handleDbUpdate(true)} disabled={dbUpdating || scanning} style={{
                    padding: '5px 14px', fontSize: 12, fontWeight: 500, marginLeft: 'auto',
                    background: dbUpdating ? '#C8C6BC' : '#534AB7', color: '#FFFFFF',
                    border: 'none', borderRadius: 4,
                    cursor: (dbUpdating || scanning) ? 'not-allowed' : 'pointer'
                  }}>
                    {dbUpdating ? `⏳ 更新中 ${dbElapsed}s...` : '⬇️ 更新漏洞库'}
                  </button>
                </div>
              )}

              {/* 漏洞库状态行 */}
              <div style={{ fontSize: 11, color: '#888780', marginBottom: 10, display: 'flex', gap: 14, flexWrap: 'wrap' }}>
                <span>📚 漏洞库: {
                  dbStatus?.dbDownloadedAt
                    ? `下载于 ${dbStatus.dbDownloadedAt}`
                    : (dbStatus?.error || '加载中...')
                }</span>
                {dbStatus?.dbNextUpdate && <span>过期于 {dbStatus.dbNextUpdate}</span>}
                <span>☕ 依赖库(Java): {
                  dbStatus?.javaDbDownloadedAt
                    ? `下载于 ${dbStatus.javaDbDownloadedAt}`
                    : '未下载 — 点「更新漏洞库」拉取（约 900MB）'
                }</span>
              </div>

              {/* 漏洞库更新结果提示 */}
              {dbUpdateMsg && (
                <div style={{
                  background: dbUpdateMsg.success ? '#EAF3DE' : '#FAEEDA',
                  border: `1px solid ${dbUpdateMsg.success ? '#27500A' : '#854F0B'}`,
                  color: dbUpdateMsg.success ? '#27500A' : '#854F0B',
                  padding: '10px 14px', borderRadius: 6, marginBottom: 12, fontSize: 12
                }}>
                  {dbUpdateMsg.success ? '✅' : '❌'} {dbUpdateMsg.message}
                  {dbUpdateMsg.costSeconds != null && ` (耗时 ${dbUpdateMsg.costSeconds}s)`}
                  {dbUpdateMsg.outputTail && (
                    <p style={{ margin: '4px 0 0', fontSize: 10, fontFamily: 'monospace', wordBreak: 'break-all' }}>
                      {dbUpdateMsg.outputTail}
                    </p>
                  )}
                </div>
              )}

              {/* 进度条 */}
              {scanning && (
                <div style={{
                  background: '#E6F1FB', border: '1px solid #0C447C',
                  color: '#0C447C', padding: '10px 14px', borderRadius: 6, marginBottom: 12, fontSize: 12
                }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
                    <span>⏳ Trivy 正在扫描，请耐心等待...</span>
                    <span>{scanElapsed}s</span>
                  </div>
                  <div style={{ background: '#C8D9E8', borderRadius: 3, height: 6, overflow: 'hidden' }}>
                    <div style={{
                      background: '#185FA5', height: '100%', borderRadius: 3,
                      width: `${Math.min(scanElapsed / 3, 95)}%`, transition: 'width 0.3s ease'
                    }} />
                  </div>
                  <p style={{ margin: '6px 0 0', fontSize: 10, color: '#5F5E5A' }}>
                    首次扫描需下载 Trivy 漏洞数据库 (~150MB)，可能需要 2-5 分钟
                  </p>
                </div>
              )}

              {/* 扫描结果 */}
              {scanMsg && (
                <div style={{
                  background: scanMsg.success ? '#EAF3DE' : '#FCEBEB',
                  color: scanMsg.success ? '#27500A' : '#791F1F',
                  padding: '8px 12px', borderRadius: 6, marginBottom: 12, fontSize: 12
                }}>
                  {scanMsg.success ? '✅ ' : '❌ '}
                  {scanMsg.summary || scanMsg.message}
                  {scanMsg.success && scanMsg.totalCount != null && (
                    <span> — 发现 {scanMsg.totalCount} 个漏洞 (严重: {scanMsg.criticalCount}, 高危: {scanMsg.highCount}) · 耗时 {scanMsg.costMs}ms</span>
                  )}
                </div>
              )}

              {/* 统计概览 */}
              {stats && (
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 10, marginBottom: 14 }}>
                  {[
                    { label: '总扫描次数', value: stats.totalScans || 0, color: '#185FA5' },
                    { label: '失败次数', value: stats.failedScans || 0, color: '#791F1F' },
                    { label: '未处理漏洞', value: stats.openVulnerabilities || 0, color: '#A32D2D' },
                    { label: '最新扫描', value: latestReport ? SCAN_TYPE_LABEL[latestReport.scanType] || latestReport.scanType : '—', color: '#444441', chip: latestReport ? <StatusChip status={latestReport.status} /> : null }
                  ].map((item, i) => (
                    <div key={i} style={{ background: '#F8F7F2', borderRadius: 6, padding: '10px 14px' }}>
                      <p style={{ fontSize: 10, color: '#888780', margin: 0 }}>{item.label}</p>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 2 }}>
                        <span style={{ fontSize: 20, fontWeight: 500, color: item.color }}>{item.value}</span>
                        {item.chip}
                      </div>
                    </div>
                  ))}
                </div>
              )}

              {/* 报告列表 + 漏洞明细 */}
              <div style={{ display: 'grid', gridTemplateColumns: '280px 1fr', gap: 14 }}>
                {/* 扫描历史 */}
                <div style={{
                  background: '#FFFFFF', border: '0.5px solid rgba(17,24,28,0.15)',
                  borderRadius: 8, overflow: 'hidden'
                }}>
                  <div style={{
                    padding: '8px 12px', borderBottom: '0.5px solid rgba(17,24,28,0.15)',
                    background: '#F8F7F2', fontSize: 11, fontWeight: 500, color: '#444441'
                  }}>
                    扫描历史 ({reports.length})
                  </div>
                  <div style={{ maxHeight: 400, overflowY: 'auto' }}>
                    {reports.length === 0 ? (
                      <div style={{ padding: 20, textAlign: 'center', color: '#888780', fontSize: 11 }}>
                        暂无扫描记录，点击上方按钮开始扫描
                      </div>
                    ) : (
                      reports.map(r => (
                        <div
                          key={r.id}
                          onClick={() => setSelectedReport(r)}
                          style={{
                            padding: '8px 12px', cursor: 'pointer',
                            borderBottom: '0.5px solid rgba(17,24,28,0.05)',
                            background: selectedReport?.id === r.id ? '#EEEDFE' : 'transparent'
                          }}>
                          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 3 }}>
                            <span style={{ fontSize: 11, color: '#2C2C2A', fontWeight: 500 }}>
                              {SCAN_TYPE_LABEL[r.scanType] || r.scanType}
                            </span>
                            <StatusChip status={r.status} />
                          </div>
                          <div style={{ display: 'flex', gap: 3, marginBottom: 2 }}>
                            <SeverityChip severity="CRITICAL" count={r.criticalCount} />
                            <SeverityChip severity="HIGH" count={r.highCount} />
                            <SeverityChip severity="MEDIUM" count={r.mediumCount} />
                          </div>
                          <p style={{ fontSize: 9, color: '#888780', margin: 0 }}>
                            {new Date(r.scannedAt).toLocaleString()} · {r.costMs}ms
                          </p>
                        </div>
                      ))
                    )}
                  </div>
                </div>

                {/* 漏洞明细 */}
                <div style={{
                  background: '#FFFFFF', border: '0.5px solid rgba(17,24,28,0.15)',
                  borderRadius: 8, overflow: 'hidden'
                }}>
                  <div style={{
                    padding: '8px 12px', borderBottom: '0.5px solid rgba(17,24,28,0.15)',
                    background: '#F8F7F2', display: 'flex', justifyContent: 'space-between', alignItems: 'center'
                  }}>
                    <span style={{ fontSize: 11, fontWeight: 500, color: '#444441' }}>
                      {selectedReport ? `漏洞明细 (${totalVulns})` : '请选择左侧报告'}
                    </span>
                    {selectedReport && (
                      <div style={{ display: 'flex', gap: 3 }}>
                        {['ALL', 'CRITICAL', 'HIGH', 'MEDIUM', 'LOW'].map(f => (
                          <button key={f} onClick={() => setFilter(f)} style={{
                            padding: '1px 6px', fontSize: 9, border: '0.5px solid rgba(17,24,28,0.3)',
                            borderRadius: 3, background: filter === f ? '#534AB7' : 'transparent',
                            color: filter === f ? '#FFFFFF' : '#5F5E5A', cursor: 'pointer'
                          }}>{f}</button>
                        ))}
                      </div>
                    )}
                  </div>
                  <div style={{ maxHeight: 400, overflowY: 'auto' }}>
                    {!selectedReport ? (
                      <div style={{ padding: 30, textAlign: 'center', color: '#888780', fontSize: 11 }}>
                        点击左侧扫描历史中的报告查看漏洞明细
                      </div>
                    ) : filteredVulns.length === 0 ? (
                      <div style={{ padding: 20, textAlign: 'center', color: '#639922', fontSize: 12 }}>
                        {totalVulns === 0 ? '✅ 扫描干净，无漏洞' : `当前过滤器 (${filter}) 下无漏洞`}
                      </div>
                    ) : (
                      filteredVulns.map(v => {
                        const sev = SEVERITY_COLORS[v.severity] || SEVERITY_COLORS.UNKNOWN
                        const st = VULN_STATUS_LABEL[v.status] || VULN_STATUS_LABEL.OPEN
                        return (
                          <div key={v.id} style={{
                            padding: '8px 12px', borderBottom: '0.5px solid rgba(17,24,28,0.05)',
                            background: v.status !== 'OPEN' ? '#F8F7F2' : 'transparent'
                          }}>
                            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 4 }}>
                              <div style={{ flex: 1 }}>
                                <div style={{ display: 'flex', gap: 4, alignItems: 'center', marginBottom: 3 }}>
                                  <span style={{
                                    background: sev.bg, color: sev.fg, padding: '1px 5px', borderRadius: 3,
                                    fontSize: 9, fontWeight: 500
                                  }}>{v.severity}</span>
                                  <span style={{ fontSize: 11, color: '#2C2C2A', fontWeight: 500 }}>
                                    {v.cveId || v.title}
                                  </span>
                                  {v.cvssScore && <span style={{ fontSize: 9, color: '#888780' }}>CVSS {v.cvssScore.toFixed(1)}</span>}
                                  {v.status !== 'OPEN' && (
                                    <span style={{
                                      background: st.bg, color: st.fg, padding: '1px 5px', borderRadius: 3,
                                      fontSize: 9, fontWeight: 500
                                    }}>{st.text}</span>
                                  )}
                                </div>
                                <p style={{ fontSize: 10, color: '#5F5E5A', margin: '0 0 3px', lineHeight: 1.4 }}>
                                  {v.title && v.title !== (v.cveId || '') ? v.title : (v.description || '').substring(0, 200)}
                                </p>
                                <p style={{ fontSize: 9, color: '#888780', margin: 0 }}>
                                  {v.packageName && <span>📦 {v.packageName}@{v.installedVersion}</span>}
                                  {v.fixedVersion && <span> → 升级到 <code style={{ background: '#F1EFE8', padding: '0 3px' }}>{v.fixedVersion}</code></span>}
                                </p>
                              </div>
                              {canManage && v.status === 'OPEN' && (
                                <div style={{ display: 'flex', flexDirection: 'column', gap: 3, marginLeft: 8 }}>
                                  <button onClick={() => handleResolve(v, 'FIXED')} style={{
                                    padding: '1px 6px', fontSize: 9, background: '#EAF3DE', color: '#27500A',
                                    border: 'none', borderRadius: 3, cursor: 'pointer'
                                  }}>已修复</button>
                                  <button onClick={() => handleResolve(v, 'IGNORED')} style={{
                                    padding: '1px 6px', fontSize: 9, background: 'transparent', color: '#5F5E5A',
                                    border: '0.5px solid rgba(17,24,28,0.3)', borderRadius: 3, cursor: 'pointer'
                                  }}>忽略</button>
                                </div>
                              )}
                            </div>
                            {v.filePath && (
                              <p style={{ fontSize: 9, color: '#888780', margin: '2px 0 0', fontFamily: 'monospace' }}>
                                📁 {v.filePath}
                              </p>
                            )}
                          </div>
                        )
                      })
                    )}
                  </div>
                </div>
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  )
}
