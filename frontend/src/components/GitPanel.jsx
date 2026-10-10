import { useState, useEffect } from 'react'
import { useParams } from 'react-router-dom'
import { fetchGitRepoInfo, fetchGitBranches, fetchGitCommits, fetchGitMRs, createGitMR } from '../api'
import GithubPanel from './GithubPanel'
import ProjectCodePreview from './ProjectCodePreview'

export default function GitPanel() {
  const { projectId } = useParams()
  const [repoInfo, setRepoInfo] = useState(null)
  const [branches, setBranches] = useState([])
  const [commits, setCommits] = useState([])
  const [mrs, setMrs] = useState([])
  const [selectedBranch, setSelectedBranch] = useState('main')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)

  // MR 创建表单
  const [showMRForm, setShowMRForm] = useState(false)
  const [mrTitle, setMrTitle] = useState('')
  const [mrSource, setMrSource] = useState('')
  const [mrTarget, setMrTarget] = useState('main')
  const [mrDesc, setMrDesc] = useState('')

  useEffect(() => {
    if (projectId) initLoad()
  }, [projectId])

  const initLoad = async () => {
    setLoading(true)
    setError(null)
    try {
      const info = await fetchGitRepoInfo(projectId)
      setRepoInfo(info)
      if (info.error) { setError(info.error); setLoading(false); return }

      const [branchesData, mrsData] = await Promise.all([
        fetchGitBranches(projectId).catch(() => []),
        fetchGitMRs(projectId, 'open').catch(() => [])
      ])
      setBranches(Array.isArray(branchesData) ? branchesData : [])
      setMrs(Array.isArray(mrsData) ? mrsData : [])

      if (Array.isArray(branchesData) && branchesData.length > 0) {
        const defaultBranch = branchesData.find(b => b.name === 'main' || b.name === 'master')
        if (defaultBranch) {
          setSelectedBranch(defaultBranch.name)
          loadCommits(defaultBranch.name)
        }
      }
    } catch (e) {
      setError('加载失败: ' + e.message)
    } finally {
      setLoading(false)
    }
  }

  const loadCommits = async (branch) => {
    setSelectedBranch(branch)
    try {
      const data = await fetchGitCommits(projectId, branch, 20)
      setCommits(Array.isArray(data) ? data : [])
    } catch (e) { console.error('Failed to load commits', e) }
  }

  const handleCreateMR = async () => {
    if (!mrTitle || !mrSource) return
    setLoading(true)
    try {
      await createGitMR(projectId, {
        title: mrTitle,
        sourceBranch: mrSource,
        targetBranch: mrTarget,
        description: mrDesc
      })
      setShowMRForm(false)
      setMrTitle(''); setMrSource(''); setMrDesc('')
      // 刷新 MR 列表
      const mrsData = await fetchGitMRs(projectId, 'open')
      setMrs(Array.isArray(mrsData) ? mrsData : [])
    } catch (e) {
      alert('创建 MR 失败: ' + e.message)
    } finally {
      setLoading(false)
    }
  }

  if (loading && !repoInfo) return <div className="loading-spinner">加载仓库信息...</div>

  return (
    <div className="git-panel">
      <h3>🔀 Git 集成</h3>

      {error && (
        <div style={{ padding: 16, background: '#fff3e0', borderRadius: 8, marginBottom: 16, color: '#e65100' }}>
          ⚠ {error}
        </div>
      )}

      {/* 仓库概览 → Git 凭证 → 代码预览 → Actions（提交历史由下方「分支 + 提交」栅格承担；
          凭证统一走上方「Git 凭证」保存的 PAT，公开仓库无需登录，本页不再另设 token 输入框） */}
      <GithubPanel
        projectId={projectId}
        codePreview={<ProjectCodePreview projectId={projectId} />}
        showCommits={false}
      />

      {/* 分支列表 + 提交历史（本页的提交展示，可切分支、条数更多） */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 2fr', gap: 16 }}>
        {/* 分支 */}
        <div className="card" style={{ padding: 12 }}>
          <h4 style={{ margin: '0 0 8px' }}>🌿 分支 ({branches.length})</h4>
          <div style={{ maxHeight: 400, overflow: 'auto' }}>
            {branches.map(b => (
              <div key={b.name}
                onClick={() => loadCommits(b.name)}
                style={{
                  padding: '6px 8px', cursor: 'pointer', borderRadius: 4, fontSize: 13,
                  background: selectedBranch === b.name ? '#e3f2fd' : 'transparent',
                  fontWeight: selectedBranch === b.name ? 600 : 400,
                  display: 'flex', justifyContent: 'space-between', alignItems: 'center'
                }}>
                <span>
                  {b.protected && '🔒 '} {b.name}
                </span>
                <span style={{ fontSize: 11, color: '#aaa' }}>
                  {b.lastCommitSha?.substring(0, 7)}
                </span>
              </div>
            ))}
            {branches.length === 0 && <div style={{ color: '#999', fontSize: 12, padding: 16, textAlign: 'center' }}
            >无分支数据（公开仓库无需登录；私有仓库请在上方配置凭证）</div>}
          </div>
        </div>

        {/* 提交历史 */}
        <div className="card" style={{ padding: 12 }}>
          <h4 style={{ margin: '0 0 8px' }}>📝 提交 ({selectedBranch})</h4>
          <div style={{ maxHeight: 400, overflow: 'auto' }}>
            {commits.map(c => (
              <div key={c.sha} style={{
                padding: '8px', borderBottom: '1px solid #f0f0f0', fontSize: 13
              }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
                  <code style={{ fontSize: 11, color: '#1976d2' }}>{c.sha?.substring(0, 8)}</code>
                  <span style={{ fontSize: 11, color: '#aaa' }}>{c.date?.substring(0, 10)}</span>
                </div>
                <div style={{ fontWeight: 500, marginBottom: 2 }}>{c.message?.split('\n')[0]?.substring(0, 100)}</div>
                <span style={{ fontSize: 11, color: '#888' }}>{c.author}</span>
              </div>
            ))}
            {commits.length === 0 && <div style={{ color: '#999', fontSize: 12, padding: 16, textAlign: 'center' }}
            >无提交数据（公开仓库无需登录；私有仓库请在上方配置凭证）</div>}
          </div>
        </div>
      </div>

      {/* MR/PR 列表 + 创建 */}
      <div className="card" style={{ padding: 16, marginTop: 16 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
          <h4 style={{ margin: 0 }}>🔄 Merge Requests ({mrs.length})</h4>
          <button onClick={() => setShowMRForm(!showMRForm)} className="btn btn-primary" style={{ fontSize: 12 }}>
            {showMRForm ? '取消' : '+ 创建 MR'}
          </button>
        </div>

        {/* 创建 MR 表单 */}
        {showMRForm && (
          <div style={{ padding: 12, background: '#f5f5f5', borderRadius: 8, marginBottom: 12 }}>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8, marginBottom: 8 }}>
              <div>
                <label style={{ fontSize: 12 }}>源分支 *</label>
                <select value={mrSource} onChange={e => setMrSource(e.target.value)}
                  style={{ width: '100%', padding: '6px 8px', marginTop: 2 }}>
                  <option value="">选择分支...</option>
                  {branches.map(b => <option key={b.name} value={b.name}>{b.name}</option>)}
                </select>
              </div>
              <div>
                <label style={{ fontSize: 12 }}>目标分支</label>
                <select value={mrTarget} onChange={e => setMrTarget(e.target.value)}
                  style={{ width: '100%', padding: '6px 8px', marginTop: 2 }}>
                  {branches.map(b => <option key={b.name} value={b.name}>{b.name}</option>)}
                </select>
              </div>
            </div>
            <div style={{ marginBottom: 8 }}>
              <label style={{ fontSize: 12 }}>标题 *</label>
              <input type="text" value={mrTitle} onChange={e => setMrTitle(e.target.value)}
                placeholder="Merge Request 标题"
                style={{ width: '100%', padding: '6px 8px', marginTop: 2 }} />
            </div>
            <div style={{ marginBottom: 8 }}>
              <label style={{ fontSize: 12 }}>描述</label>
              <textarea value={mrDesc} onChange={e => setMrDesc(e.target.value)} rows={3}
                placeholder="描述变更内容..."
                style={{ width: '100%', padding: '6px 8px', marginTop: 2, resize: 'vertical' }} />
            </div>
            <button onClick={handleCreateMR} disabled={!mrTitle || !mrSource}
              className="btn btn-success" style={{ fontSize: 12 }}>
              ✅ 提交 Merge Request
            </button>
          </div>
        )}

        {/* MR 列表 */}
        <div style={{ maxHeight: 300, overflow: 'auto' }}>
          {mrs.map(mr => (
            <div key={mr.id} style={{
              padding: '10px', borderBottom: '1px solid #f0f0f0', fontSize: 13,
              display: 'flex', justifyContent: 'space-between', alignItems: 'center'
            }}>
              <div style={{ flex: 1 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
                  <span style={{
                    padding: '2px 6px', borderRadius: 4, fontSize: 11, fontWeight: 600,
                    background: mr.state === 'open' || mr.state === 'opened' ? '#e8f5e9' : '#f3e5f5',
                    color: mr.state === 'open' || mr.state === 'opened' ? '#2e7d32' : '#7b1fa2'
                  }}>
                    {mr.state === 'open' || mr.state === 'opened' ? 'OPEN' : mr.state.toUpperCase()}
                  </span>
                  <span style={{ fontWeight: 500 }}>!{mr.id} {mr.title}</span>
                </div>
                <span style={{ fontSize: 11, color: '#888' }}>
                  {mr.sourceBranch} → {mr.targetBranch}
                </span>
              </div>
              {mr.url && (
                <a href={mr.url} target="_blank" rel="noreferrer"
                  style={{ fontSize: 11, color: '#1976d2', textDecoration: 'none' }}>
                  查看 →
                </a>
              )}
            </div>
          ))}
          {mrs.length === 0 && !showMRForm && (
            <div style={{ color: '#999', fontSize: 12, padding: 16, textAlign: 'center' }}>
              暂无 Merge Requests（公开仓库无需登录；私有仓库请在上方配置凭证）
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
