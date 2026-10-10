import React, { useState, useEffect, useCallback } from 'react'
import { useAuth } from '../context/AuthContext'
import {
  fetchGitConfig, saveGitConfig, fetchGitCommits, fetchGitRuns,
  fetchGitRunJobs, dispatchGitWorkflow, createGitTag,
} from '../api'

/** 运行状态（GitHub Actions 的 status 字段） */
const RUN_STATUS = {
  queued:      { text: '排队中', color: '#BA7517' },
  in_progress: { text: '进行中', color: '#185FA5' },
  completed:   { text: '已完成', color: '#5F5E5A' },
  waiting:     { text: '等待中', color: '#BA7517' },
}
/** 运行结论（GitHub Actions 的 conclusion 字段） */
const CONCLUSION = {
  success:   { text: '成功', color: '#3B6D11' },
  failure:   { text: '失败', color: '#A32D2D' },
  cancelled: { text: '已取消', color: '#5F5E5A' },
  skipped:   { text: '已跳过', color: '#5F5E5A' },
}

const fmtTime = (iso) => {
  if (!iso) return '-'
  try {
    return new Date(iso).toLocaleString('zh-CN', { hour12: false })
  } catch { return iso }
}
const shortSha = (s) => (s ? String(s).slice(0, 7) : '')

const cardStyle = { border: '1px solid #e5e7eb', borderRadius: 8, padding: 14, marginBottom: 14, background: '#fff' }
const labelStyle = { display: 'block', fontSize: 12, color: '#5F5E5A', marginBottom: 4 }
const inputStyle = { width: '100%', padding: '6px 8px', fontSize: 13, border: '1px solid #d1d5db', borderRadius: 6, boxSizing: 'border-box' }
const btnPrimary = { padding: '6px 12px', fontSize: 13, borderRadius: 6, border: 'none', color: '#fff', background: '#2563eb', cursor: 'pointer' }
const btnGhost = { padding: '6px 12px', fontSize: 13, borderRadius: 6, border: '1px solid #d1d5db', background: '#fff', color: '#374151', cursor: 'pointer' }
const mono = { fontFamily: "'Courier New', monospace", fontSize: 12 }

/**
 * GitHub 面板（Git 集成页 / 项目详情共用）
 * - 仓库概览
 * - 凭证配置（PAT 加密保存，接口不回传明文）
 * - 代码预览（由调用方通过 codePreview 注入，插在凭证与 Actions 之间）
 * - GitHub Actions：触发构建、运行列表、job/步骤明细
 * - 发布版本：创建 tag，由仓库里「tag → build → Release」的 workflow 完成发布
 * - 提交历史（GitHub / GitLab / Gitee 均可）
 *
 * 查看（仓库概览 / 代码 / 运行记录 / 提交历史）对公开仓库无需凭证；
 * 私有仓库的「查看」会复用下面保存的凭证（后端解密后直连 Git 平台 API）。
 * 只有「触发构建」「打 tag 发布」这两个写操作才要求已配置凭证且有项目维护权限。
 *
 * @param codePreview 可选。渲染在「凭证配置」与「GitHub Actions」之间的节点。
 */
export default function GithubPanel({ projectId, codePreview }) {
  const { canManage } = useAuth()
  const [cfg, setCfg] = useState(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState('')
  const [toast, setToast] = useState(null)
  const [tokenInput, setTokenInput] = useState('')
  const [workflowInput, setWorkflowInput] = useState('')
  const [commits, setCommits] = useState([])
  const [runs, setRuns] = useState([])
  const [expandedRun, setExpandedRun] = useState(null)
  const [jobsMap, setJobsMap] = useState({})
  const [tagInput, setTagInput] = useState('')

  const showToast = useCallback((text, type = 'info', ms = 4500) => {
    setToast({ text, type })
    setTimeout(() => setToast(null), ms)
  }, [])

  const load = useCallback(async () => {
    if (!projectId) return
    setLoading(true)
    try {
      const c = await fetchGitConfig(projectId)
      setCfg(c || null)
      setWorkflowInput(c?.workflow || '')
      const isGithub = c?.platform === 'github'
      const [cm, rn] = await Promise.all([
        fetchGitCommits(projectId, c?.branch || '', 12).catch(() => []),
        isGithub ? fetchGitRuns(projectId, 8).catch(() => []) : Promise.resolve([]),
      ])
      setCommits(Array.isArray(cm) ? cm : [])
      setRuns(Array.isArray(rn) ? rn : [])
    } catch (e) {
      showToast('加载 GitHub 信息失败：' + e.message, 'error')
    } finally {
      setLoading(false)
    }
  }, [projectId, showToast])

  useEffect(() => { load() }, [load])

  const handleSaveConfig = async () => {
    setBusy('cfg')
    try {
      const body = { workflow: workflowInput }
      if (tokenInput.trim()) body.token = tokenInput.trim()
      const r = await saveGitConfig(projectId, body)
      setTokenInput('')
      showToast(r?.hasToken ? '配置已保存（PAT 加密存储）' : '配置已保存', 'success', 3000)
      await load()
    } catch (e) {
      showToast('保存失败：' + e.message, 'error', 8000)
    } finally {
      setBusy('')
    }
  }

  const handleClearToken = async () => {
    if (!window.confirm('确定清除已保存的 GitHub 凭证？清除后将无法触发 Actions 与发布。')) return
    setBusy('cfg')
    try {
      await saveGitConfig(projectId, { token: '' })
      showToast('凭证已清除', 'success', 3000)
      await load()
    } catch (e) {
      showToast('清除失败：' + e.message, 'error', 8000)
    } finally {
      setBusy('')
    }
  }

  const handleDispatch = async () => {
    setBusy('dispatch')
    try {
      const r = await dispatchGitWorkflow(projectId, {})
      showToast(r?.message || '已触发 GitHub Actions', 'success', 6000)
      // Actions 从排队到出现在列表有几秒延迟，分两次刷新
      setTimeout(() => load(), 2500)
      setTimeout(() => load(), 9000)
    } catch (e) {
      showToast(e.message || '触发失败', 'error', 12000)
    } finally {
      setBusy('')
    }
  }

  const handleRelease = async () => {
    const tag = tagInput.trim()
    if (!tag) { showToast('请输入 tag，如 v1.0.0', 'error', 3000); return }
    if (!window.confirm(`将在默认分支最新提交上创建 tag「${tag}」，并触发 Actions 构建与发布 Release。确认？`)) return
    setBusy('tag')
    try {
      const r = await createGitTag(projectId, { tag })
      showToast(r?.message || `已创建 tag ${tag}`, 'success', 8000)
      setTagInput('')
      setTimeout(() => load(), 2500)
      setTimeout(() => load(), 9000)
    } catch (e) {
      showToast(e.message || '发布失败', 'error', 12000)
    } finally {
      setBusy('')
    }
  }

  const toggleRun = async (run) => {
    if (expandedRun === run.id) { setExpandedRun(null); return }
    setExpandedRun(run.id)
    if (!jobsMap[run.id]) {
      try {
        const js = await fetchGitRunJobs(projectId, run.id)
        setJobsMap(prev => ({ ...prev, [run.id]: Array.isArray(js) ? js : [] }))
      } catch {
        setJobsMap(prev => ({ ...prev, [run.id]: [] }))
      }
    }
  }

  const isGithub = cfg?.platform === 'github'
  const repoLabel = cfg?.owner ? `${cfg.owner}/${cfg.repo}` : ''
  const statusOf = (run) => {
    if (run.status === 'completed') return CONCLUSION[run.conclusion] || { text: run.conclusion || '—', color: '#5F5E5A' }
    return RUN_STATUS[run.status] || { text: run.status || '—', color: '#5F5E5A' }
  }

  if (loading && !cfg) {
    return <div style={{ padding: 16, fontSize: 13, color: '#5F5E5A' }}>加载中...</div>
  }

  return (
    <div>
      {toast && (
        <div style={{
          position: 'sticky', top: 0, zIndex: 5, marginBottom: 10, padding: '8px 12px', borderRadius: 6, fontSize: 13,
          color: '#fff',
          background: toast.type === 'error' ? '#dc2626' : toast.type === 'success' ? '#16a34a' : '#2563eb',
        }}>{toast.text}</div>
      )}

      {/* 仓库概览 */}
      <div style={cardStyle}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 8 }}>
          <div style={{ fontSize: 13 }}>
            <span style={{ color: '#5F5E5A' }}>仓库：</span>
            <strong>{repoLabel || '未配置可解析的 Git 地址'}</strong>
            {cfg?.platform && (
              <span style={{ marginLeft: 8, padding: '1px 7px', borderRadius: 10, fontSize: 11, background: '#eef2ff', color: '#3730a3' }}>
                {cfg.platform}
              </span>
            )}
            {cfg?.hasToken
              ? <span style={{ marginLeft: 8, padding: '1px 7px', borderRadius: 10, fontSize: 11, background: '#dcfce7', color: '#166534' }}>凭证已配置</span>
              : <span style={{ marginLeft: 8, padding: '1px 7px', borderRadius: 10, fontSize: 11, background: '#fef3c7', color: '#92400e' }}>未配置凭证</span>}
          </div>
          <button style={btnGhost} onClick={load} disabled={loading}>{loading ? '刷新中...' : '刷新'}</button>
        </div>
        <div style={{ marginTop: 6, fontSize: 12, color: '#5F5E5A' }}>
          默认分支：<span style={mono}>{cfg?.branch || '（跟随远端默认）'}</span>
          {!isGithub && <span style={{ marginLeft: 10 }}>· Actions 与发布仅支持 GitHub 仓库</span>}
        </div>
      </div>

      {/* 凭证配置 */}
      {canManage && isGithub && (
        <div style={cardStyle}>
          <h4 style={{ margin: '0 0 10px', fontSize: 13, color: '#475569' }}>GitHub 凭证</h4>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            <div>
              <label style={labelStyle}>Personal Access Token（留空 = 不修改）</label>
              <input type="password" style={inputStyle} value={tokenInput}
                onChange={e => setTokenInput(e.target.value)}
                placeholder={cfg?.hasToken ? '已保存（如需更换请重新输入）' : 'ghp_...'} />
            </div>
            <div>
              <label style={labelStyle}>Workflow 文件名</label>
              <input style={inputStyle} value={workflowInput}
                onChange={e => setWorkflowInput(e.target.value)} placeholder="build.yaml" />
            </div>
          </div>
          <div style={{ marginTop: 10, display: 'flex', gap: 8 }}>
            <button style={btnPrimary} onClick={handleSaveConfig} disabled={busy === 'cfg'}>
              {busy === 'cfg' ? '保存中...' : '保存配置'}
            </button>
            {cfg?.hasToken && (
              <button style={btnGhost} onClick={handleClearToken} disabled={busy === 'cfg'}>清除凭证</button>
            )}
          </div>
          <div style={{ marginTop: 8, fontSize: 11, color: '#5F5E5A', lineHeight: 1.7 }}>
            PAT 需具备 <span style={mono}>repo</span> 与 <span style={mono}>workflow</span> 权限；<br />
            凭证经 AES-GCM 加密后存入数据库，接口不返回明文，页面上也不会回显。
          </div>
        </div>
      )}

      {/* 代码预览（调用方注入）—— 位于 GitHub 凭证与 Actions 之间。
          公开仓库无需登录；私有仓库由后端自动取用上方保存的凭证（前端不用另传 token） */}
      {codePreview && (
        <div style={cardStyle}>
          <h4 style={{ margin: '0 0 10px', fontSize: 13, color: '#475569' }}>📂 代码预览</h4>
          {codePreview}
        </div>
      )}

      {/* GitHub Actions */}
      {isGithub && (
        <div style={cardStyle}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 8 }}>
            <h4 style={{ margin: 0, fontSize: 13, color: '#475569' }}>GitHub Actions</h4>
            <div style={{ display: 'flex', gap: 8 }}>
              <input style={{ ...inputStyle, width: 150 }} value={tagInput}
                onChange={e => setTagInput(e.target.value)} placeholder="v1.0.0" />
              <button style={btnPrimary} onClick={handleRelease} disabled={!canManage || !cfg?.hasToken || busy === 'tag'}>
                {busy === 'tag' ? '发布中...' : '打 tag 发布'}
              </button>
              <button style={btnPrimary} onClick={handleDispatch} disabled={!canManage || !cfg?.hasToken || busy === 'dispatch'}>
                {busy === 'dispatch' ? '触发中...' : '触发构建'}
              </button>
            </div>
          </div>
          {!canManage ? (
            <div style={{ marginTop: 8, fontSize: 11, color: '#92400e' }}>
              触发与发布需要项目维护者（OWNER / MAINTAINER）及以上权限。查看运行记录、分支、提交与代码无需任何凭证。
            </div>
          ) : !cfg?.hasToken ? (
            <div style={{ marginTop: 8, fontSize: 11, color: '#92400e' }}>
              触发构建与发布版本需要先保存 GitHub 凭证；仅查看运行记录、分支、提交与代码则无需凭证。
            </div>
          ) : null}

          <div style={{ marginTop: 12 }}>
            {runs.length === 0 && (
              <div style={{ fontSize: 12, color: '#5F5E5A', padding: '10px 0' }}>
                暂无运行记录（查看记录无需凭证，公开仓库可直接读取）。点「触发构建」会调用 workflow_dispatch；若提示未找到 workflow，请确认文件名与仓库中的 `.github/workflows/` 一致。
              </div>
            )}
            {runs.map(run => {
              const st = statusOf(run)
              const open = expandedRun === run.id
              return (
                <div key={run.id} style={{ border: '1px solid #eef0f3', borderRadius: 6, marginBottom: 8, overflow: 'hidden' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '8px 10px', cursor: 'pointer', background: open ? '#f8fafc' : '#fff' }}
                    onClick={() => toggleRun(run)}>
                    <span style={{ width: 8, height: 8, borderRadius: '50%', background: st.color, flex: '0 0 auto' }} />
                    <span style={{ fontSize: 12.5, color: st.color, minWidth: 48 }}>{st.text}</span>
                    <span style={{ fontSize: 12.5, flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {run.displayTitle || run.name || 'workflow'} · <span style={mono}>{run.branch}</span>
                    </span>
                    <span style={{ fontSize: 11, color: '#9ca3af' }}>{run.event}</span>
                    <span style={{ fontSize: 11, color: '#5F5E5A', minWidth: 130, textAlign: 'right' }}>{fmtTime(run.createdAt)}</span>
                    {run.htmlUrl && (
                      <a href={run.htmlUrl} target="_blank" rel="noreferrer" style={{ fontSize: 11, color: '#2563eb' }}
                        onClick={e => e.stopPropagation()}>详情</a>
                    )}
                  </div>
                  {open && (
                    <div style={{ padding: '8px 12px', borderTop: '1px solid #eef0f3', background: '#fafafa' }}>
                      {(jobsMap[run.id] || []).length === 0 && (
                        <div style={{ fontSize: 12, color: '#5F5E5A' }}>暂无 job 信息</div>
                      )}
                      {(jobsMap[run.id] || []).map(job => {
                        const jc = CONCLUSION[job.conclusion] || RUN_STATUS[job.status] || { text: job.status || '—', color: '#5F5E5A' }
                        return (
                          <div key={job.id} style={{ marginBottom: 8 }}>
                            <div style={{ fontSize: 12.5, color: '#374151' }}>
                              <span style={{ color: jc.color }}>{jc.text}</span> · {job.name}
                              <span style={{ marginLeft: 8, color: '#9ca3af' }}>
                                {fmtTime(job.startedAt)} → {fmtTime(job.completedAt)}
                              </span>
                            </div>
                            <div style={{ marginTop: 4, paddingLeft: 12 }}>
                              {(job.steps || []).map(s => {
                                const sc = CONCLUSION[s.conclusion] || RUN_STATUS[s.status] || { text: s.status || '—', color: '#9ca3af' }
                                return (
                                  <div key={s.number} style={{ fontSize: 11.5, color: '#5F5E5A', lineHeight: 1.8 }}>
                                    <span style={{ color: sc.color, display: 'inline-block', minWidth: 46 }}>{sc.text}</span>
                                    {s.number}. {s.name}
                                  </div>
                                )
                              })}
                            </div>
                          </div>
                        )
                      })}
                    </div>
                  )}
                </div>
              )
            })}
          </div>
        </div>
      )}

      {/* 提交历史 */}
      <div style={cardStyle}>
        <h4 style={{ margin: '0 0 10px', fontSize: 13, color: '#475569' }}>提交历史</h4>
        {commits.length === 0 && (
          <div style={{ fontSize: 12, color: '#5F5E5A' }}>
            没有取到提交记录。私有仓库需要在上面配置 PAT；GitHub 之外的平台请确认地址可解析。
          </div>
        )}
        {commits.map((c, i) => (
          <div key={c.sha || i} style={{ display: 'flex', gap: 10, padding: '7px 0', borderBottom: i === commits.length - 1 ? 'none' : '1px solid #f3f4f6' }}>
            <span style={{ ...mono, color: '#2563eb', flex: '0 0 auto' }}>{shortSha(c.sha)}</span>
            <span style={{ fontSize: 12.5, flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
              title={c.message}>{c.message}</span>
            <span style={{ fontSize: 11, color: '#5F5E5A', flex: '0 0 auto' }}>{c.author}</span>
            <span style={{ fontSize: 11, color: '#9ca3af', flex: '0 0 auto', minWidth: 130, textAlign: 'right' }}>{fmtTime(c.date)}</span>
          </div>
        ))}
      </div>
    </div>
  )
}
