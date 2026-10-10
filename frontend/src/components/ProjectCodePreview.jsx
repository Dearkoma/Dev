import React, { useState } from 'react'
import { previewProjectCode, previewProjectFile } from '../api'

/**
 * 仓库代码预览（文件树 + 文件内容查看）
 *
 * 从「项目管理 → 项目详情」抽出来，独立成组件，放在 Git 集成页使用。
 * 依赖后端：
 *   - GET /api/projects/{id}/preview        返回目录树
 *   - GET /api/projects/{id}/preview/file   返回单文件内容
 *
 * 后端是「直读 Git 平台 API」，不落地、不依赖构建产物：
 * 公开仓库无需登录（无需 GitHub 凭证）即可查看；
 * 私有仓库由后端自动取用项目里已保存的凭证（见 GithubPanel 的「GitHub 凭证」），
 * 因此这里不需要用户再传 token。
 * 只读：不会写入或修改任何文件。
 */
export default function ProjectCodePreview({ projectId, token }) {
  const [tree, setTree] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)
  const [file, setFile] = useState(null) // { path, name, content, language, size }
  const [expandedDirs, setExpandedDirs] = useState(new Set())

  const loadTree = async () => {
    if (!projectId) return
    setLoading(true)
    setError(null)
    setTree(null)
    setFile(null)
    try {
      const res = await previewProjectCode(projectId, token)
      if (res.error) {
        setError(res.error)
      } else {
        setTree(res.tree)
        setExpandedDirs(new Set([''])) // 自动展开根目录
      }
    } catch (e) {
      setError('预览失败: ' + e.message)
    } finally {
      setLoading(false)
    }
  }

  const openFile = async (filePath, fileName) => {
    if (!projectId) return
    try {
      const res = await previewProjectFile(projectId, filePath, token)
      if (res.error) {
        setFile({ path: filePath, name: fileName, content: '/* ' + res.error + ' */', language: 'plaintext' })
      } else {
        setFile({ path: res.path, name: res.name, content: res.content, language: res.language, size: res.size })
      }
    } catch (e) {
      setFile({ path: filePath, name: fileName, content: '// 加载失败: ' + e.message, language: 'plaintext' })
    }
  }

  const toggleDir = (dirPath) => {
    setExpandedDirs(prev => {
      const next = new Set(prev)
      if (next.has(dirPath)) next.delete(dirPath)
      else next.add(dirPath)
      return next
    })
  }

  const renderTreeNode = (node, depth = 0) => {
    const isDir = node.type === 'directory'
    const isExpanded = expandedDirs.has(node.path)
    const paddingLeft = 8 + depth * 18

    return (
      <div key={node.path || node.name}>
        <div
          className="tree-node"
          style={{ paddingLeft, cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 4, padding: '3px 0', fontSize: 12, borderRadius: 4 }}
          onClick={() => {
            if (isDir) toggleDir(node.path)
            else if (node.type === 'file') openFile(node.path, node.name)
          }}
          onMouseEnter={e => { e.currentTarget.style.background = '#f3f4f6' }}
          onMouseLeave={e => { e.currentTarget.style.background = 'transparent' }}
        >
          <span style={{ width: 16, textAlign: 'center', flexShrink: 0 }}>
            {isDir ? (isExpanded ? '📂' : '📁') : node.type === 'binary' ? '📦' : '📄'}
          </span>
          <span style={{
            color: isDir ? '#1f2937' : '#4b5563',
            fontWeight: isDir ? 600 : 400,
            overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
            flex: 1,
          }}>
            {node.name}
          </span>
          {isDir && (
            <span style={{ color: '#9ca3af', fontSize: 10, marginLeft: 'auto', flexShrink: 0, transition: 'transform 0.15s', transform: isExpanded ? 'rotate(0deg)' : 'rotate(-90deg)' }}>
              ▼
            </span>
          )}
          {!isDir && node.size !== undefined && (
            <span style={{ color: '#9ca3af', fontSize: 10, marginLeft: 'auto', flexShrink: 0 }}>
              {node.size < 1024 ? node.size + ' B' : node.size < 1048576 ? (node.size / 1024).toFixed(1) + ' KB' : (node.size / 1048576).toFixed(1) + ' MB'}
            </span>
          )}
        </div>
        {isDir && isExpanded && node.children && (
          <div>{node.children.map(child => renderTreeNode(child, depth + 1))}</div>
        )}
      </div>
    )
  }

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
        <span style={{ fontSize: 12, color: '#5F5E5A' }}>
          只读查看仓库文件（公开仓库无需登录；私有仓库用上方「GitHub 凭证」中保存的 PAT）
        </span>
        <button
          className="btn btn-outline btn-sm"
          onClick={loadTree}
          disabled={loading}
          style={{ fontSize: 12 }}
        >
          {loading ? '⏳ 加载中...' : '👁 预览代码'}
        </button>
      </div>

      {error && (
        <div style={{ marginTop: 8, padding: '10px 14px', borderRadius: 8, background: '#fef2f2', border: '1px solid #fecaca', color: '#991b1b', fontSize: 12 }}>
          ⚠️ {error}
          {/* 私有仓库读不到是最常见的一类失败，单独给一条可执行的出路 */}
          {(error.includes('私有仓库') || error.includes('凭证')) && (
            <div style={{ marginTop: 6, color: '#92400e' }}>
              → 私有仓库需要 PAT：请项目维护者在「GitHub 凭证」中保存 Personal Access Token（勾选 <code>repo</code> 权限），
              保存后回到这里重新点「预览代码」即可。
            </div>
          )}
        </div>
      )}

      {tree && (
        <div style={{ marginTop: 8, display: 'flex', gap: 0, border: '1px solid #e5e7eb', borderRadius: 8, overflow: 'hidden', maxHeight: 460 }}>
          <div style={{ width: 240, minWidth: 180, borderRight: '1px solid #e5e7eb', overflow: 'auto', background: '#fafbfc', padding: '4px 0' }}>
            {renderTreeNode(tree)}
          </div>
          <div style={{ flex: 1, overflow: 'auto', background: '#1e1e1e' }}>
            {file ? (
              <div style={{ fontSize: 12 }}>
                <div style={{ padding: '6px 12px', background: '#2d2d2d', color: '#ccc', fontSize: 11, borderBottom: '1px solid #444', display: 'flex', justifyContent: 'space-between', position: 'sticky', top: 0 }}>
                  <span>{file.name}</span>
                  {file.size !== undefined && (
                    <span style={{ color: '#999' }}>
                      {file.size < 1024 ? file.size + ' B' : (file.size / 1024).toFixed(1) + ' KB'}
                    </span>
                  )}
                </div>
                <pre style={{ margin: 0, padding: '10px 14px', color: '#d4d4d4', fontFamily: "'Cascadia Code', 'Fira Code', 'JetBrains Mono', Consolas, monospace", lineHeight: 1.6, whiteSpace: 'pre-wrap', wordBreak: 'break-word', tabSize: 4 }}>{file.content}</pre>
              </div>
            ) : (
              <div style={{ height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#666', fontSize: 13 }}>
                ← 点击左侧文件查看内容
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
