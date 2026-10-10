import React, { useState, useMemo } from 'react'
import { previewProjectCode, previewProjectFile } from '../api'

// ==================== 语法高亮（VS Code Dark+ 配色，自实现 tokenizer） ====================

const HL_COLORS = {
  keyword: '#569cd6',
  string: '#ce9178',
  comment: '#6a9955',
  number: '#b5cea8',
  type: '#4ec9b0',
  plain: '#d4d4d4',
}

const COMMON_KEYWORDS = new Set([
  'public', 'private', 'protected', 'class', 'interface', 'extends', 'implements', 'static', 'final',
  'abstract', 'void', 'return', 'if', 'else', 'for', 'while', 'do', 'switch', 'case', 'default', 'break',
  'continue', 'new', 'this', 'super', 'import', 'package', 'try', 'catch', 'finally', 'throw', 'throws',
  'function', 'const', 'let', 'var', 'async', 'await', 'export', 'from', 'typeof', 'instanceof', 'in', 'of',
  'def', 'elif', 'except', 'lambda', 'pass', 'raise', 'with', 'as', 'global', 'nonlocal', 'yield', 'assert',
  'func', 'go', 'struct', 'chan', 'select', 'map', 'range', 'defer', 'fallthrough', 'goto',
  'fn', 'impl', 'pub', 'use', 'mod', 'match', 'mut', 'ref', 'where', 'trait', 'enum', 'move', 'loop',
  'int', 'long', 'double', 'float', 'boolean', 'char', 'byte', 'short', 'unsigned', 'signed', 'sizeof',
  'using', 'namespace', 'template', 'typename', 'virtual', 'override', 'constexpr', 'nullptr',
  'val', 'object', 'companion', 'data', 'sealed', 'suspend', 'fun', 'when', 'init',
  'select', 'from', 'where', 'insert', 'update', 'delete', 'create', 'table', 'join', 'left', 'right',
  'inner', 'outer', 'on', 'group', 'by', 'order', 'limit', 'offset', 'and', 'or', 'not', 'union', 'set',
  'String', 'Integer', 'Long', 'Boolean', 'List', 'Map', 'Set', 'Optional',
])

const LITERALS = new Set(['true', 'false', 'null', 'nil', 'None', 'True', 'False', 'undefined', 'NaN', 'self'])

const HASH_COMMENT_LANGS = new Set(['python', 'bash', 'yaml', 'properties', 'dockerfile', 'makefile'])
const DASH_COMMENT_LANGS = new Set(['sql'])

function tokenizeLine(line, lang, state) {
  const tokens = []
  const hashComment = HASH_COMMENT_LANGS.has(lang)
  let i = 0, key = 0

  // Markdown：行首标题整行按关键字色
  if (lang === 'markdown' && /^#{1,6}\s/.test(line)) {
    return [{ t: 'keyword', v: line, k: key++ }]
  }

  while (i < line.length) {
    // 跨行块注释内部
    if (state.inBlockComment) {
      const end = line.indexOf('*/', i)
      if (end === -1) { tokens.push({ t: 'comment', v: line.slice(i), k: key++ }); i = line.length }
      else { tokens.push({ t: 'comment', v: line.slice(i, end + 2), k: key++ }); i = end + 2; state.inBlockComment = false }
      continue
    }
    // 块注释开始
    if (!hashComment && !DASH_COMMENT_LANGS.has(lang) && line.startsWith('/*', i)) {
      const end = line.indexOf('*/', i + 2)
      if (end === -1) { tokens.push({ t: 'comment', v: line.slice(i), k: key++ }); state.inBlockComment = true; i = line.length }
      else { tokens.push({ t: 'comment', v: line.slice(i, end + 2), k: key++ }); i = end + 2 }
      continue
    }
    // 行注释
    if (line.startsWith('//', i) && !hashComment && !DASH_COMMENT_LANGS.has(lang)) {
      tokens.push({ t: 'comment', v: line.slice(i), k: key++ })
      break
    }
    if (hashComment && line[i] === '#') {
      tokens.push({ t: 'comment', v: line.slice(i), k: key++ })
      break
    }
    if (DASH_COMMENT_LANGS.has(lang) && line.startsWith('--', i)) {
      tokens.push({ t: 'comment', v: line.slice(i), k: key++ })
      break
    }
    // 字符串
    const q = line[i]
    if (q === '"' || q === "'" || q === '`') {
      let j = i + 1
      while (j < line.length && line[j] !== q) { if (line[j] === '\\') j++; j++ }
      const end = Math.min(j + 1, line.length)
      tokens.push({ t: 'string', v: line.slice(i, end), k: key++ })
      i = end
      continue
    }
    // 数字
    if (/\d/.test(line[i]) && (i === 0 || !/[\w$]/.test(line[i - 1]))) {
      let j = i
      while (j < line.length && /[\w.]/.test(line[j])) j++
      tokens.push({ t: 'number', v: line.slice(i, j), k: key++ })
      i = j
      continue
    }
    // 标识符 → 关键字 / 字面量 / 类型（大写开头）/ 普通
    if (/[A-Za-z_$]/.test(line[i])) {
      let j = i
      while (j < line.length && /[\w$]/.test(line[j])) j++
      const w = line.slice(i, j)
      const t = COMMON_KEYWORDS.has(w) ? 'keyword'
        : LITERALS.has(w) ? 'number'
        : /^[A-Z]/.test(w) ? 'type' : 'plain'
      tokens.push({ t, v: w, k: key++ })
      i = j
      continue
    }
    tokens.push({ t: 'plain', v: line[i], k: key++ })
    i++
  }
  return tokens
}

function highlightLines(code, lang) {
  const state = { inBlockComment: false }
  return code.split('\n').map(line => tokenizeLine(line, lang, state))
}

// ==================== 文件树按扩展名着色 ====================

const EXT_COLORS = {
  js: '#e8d44d', mjs: '#e8d44d', jsx: '#61dafb', ts: '#5396d4', tsx: '#61dafb',
  java: '#e76f00', kt: '#a97bff', kts: '#a97bff', gradle: '#619cbc', groovy: '#619cbc',
  py: '#5a9fd4', go: '#6ad7e5', rs: '#dea584',
  c: '#9ab3d1', h: '#9ab3d1', cpp: '#f34b7d', cc: '#f34b7d', hpp: '#f34b7d',
  json: '#cbcb41', yaml: '#cb7e7e', yml: '#cb7e7e', xml: '#e37933',
  md: '#7ec4e8', css: '#a074c4', html: '#e3644f', htm: '#e3644f', sql: '#e8a25c',
  sh: '#89e051', properties: '#b8b8b8',
}

function extColor(name) {
  if (name === 'Dockerfile' || name === 'Makefile') return '#7db3c7'
  const ext = name.includes('.') ? name.split('.').pop().toLowerCase() : ''
  return EXT_COLORS[ext] || '#cccccc'
}

const LINE_HEIGHT = 19
const lineStyle = { height: LINE_HEIGHT, lineHeight: LINE_HEIGHT + 'px' }

/**
 * 仓库代码预览（文件树 + 文件内容查看，IDE 深色风格）
 *
 * 依赖后端：
 *   - GET /api/projects/{id}/preview        返回目录树
 *   - GET /api/projects/{id}/preview/file   返回单文件内容
 *
 * 凭证统一由后端取用（项目里「Git 凭证」保存的 PAT 自动解密使用）；
 * 公开仓库无需任何凭证。只读，不会写入或修改任何文件。
 */
export default function ProjectCodePreview({ projectId }) {
  const [tree, setTree] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)
  const [hint, setHint] = useState(null)
  const [file, setFile] = useState(null) // { path, name, content, language, size }
  const [expandedDirs, setExpandedDirs] = useState(new Set())

  // 高亮结果缓存：仅在打开的文件变化时重算，避免滚动/展开树时重复 tokenize
  const highlighted = useMemo(
    () => (file?.content ? highlightLines(file.content, file.language || 'plaintext') : []),
    [file]
  )

  const loadTree = async () => {
    if (!projectId) return
    setLoading(true)
    setError(null)
    setHint(null)
    setTree(null)
    setFile(null)
    try {
      const res = await previewProjectCode(projectId)
      if (res.error) {
        setError(res.error)
        setHint(res.hint || null)
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
      const res = await previewProjectFile(projectId, filePath)
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
    const paddingLeft = 8 + depth * 16

    return (
      <div key={node.path || node.name}>
        <div
          // ⚠️ 这里必须逐个写 padding-*，不能用 padding 简写：
          // 简写与 paddingLeft 同时出现时，后挂载的简写会把缩进整个重置掉，
          // 整棵树会摊平成一层（表现就是「层次不对」）。
          style={{
            paddingTop: 2, paddingBottom: 2, paddingRight: 8, paddingLeft,
            cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 5,
            fontSize: 12, whiteSpace: 'nowrap',
          }}
          onClick={() => {
            if (isDir) toggleDir(node.path)
            else if (node.type === 'file') openFile(node.path, node.name)
          }}
          onMouseEnter={e => { e.currentTarget.style.background = '#2a2d2e' }}
          onMouseLeave={e => { e.currentTarget.style.background = 'transparent' }}
        >
          <span style={{ width: 15, textAlign: 'center', flexShrink: 0, fontSize: 11 }}>
            {isDir ? (isExpanded ? '📂' : '📁') : node.type === 'binary' ? '📦' : '📄'}
          </span>
          <span style={{
            color: isDir ? '#cccccc' : extColor(node.name),
            fontWeight: isDir ? 500 : 400,
            overflow: 'hidden', textOverflow: 'ellipsis',
            flex: 1,
          }}>
            {node.name}
          </span>
          {isDir && (
            <span style={{ color: '#858585', fontSize: 9, marginLeft: 'auto', flexShrink: 0, transition: 'transform 0.15s', transform: isExpanded ? 'rotate(0deg)' : 'rotate(-90deg)' }}>
              ▼
            </span>
          )}
          {!isDir && node.size !== undefined && (
            <span style={{ color: '#6e6e6e', fontSize: 10, marginLeft: 'auto', flexShrink: 0 }}>
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
          只读查看仓库文件（公开仓库无需登录；私有仓库用上方「Git 凭证」中保存的 PAT）
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
          {/* 行动建议由后端随错误一起下发（限流 / 凭证 / 不存在各有各的说法），
              这里只做兜底，避免把「限流」误导成「私有仓库要配 PAT」 */}
          {(hint || error.includes('私有仓库')) && (
            <div style={{ marginTop: 6, color: '#92400e' }}>
              → {hint || '私有仓库需要 PAT：请项目维护者在上方「Git 凭证」中保存 Personal Access Token（GitHub 勾选 repo 权限），保存后回到这里重新点「预览代码」即可。'}
            </div>
          )}
        </div>
      )}

      {tree && (
        <div style={{ marginTop: 8, display: 'flex', gap: 0, border: '1px solid #3c3c3c', borderRadius: 8, overflow: 'hidden', maxHeight: 480, background: '#1e1e1e' }}>
          {/* 侧边栏：文件树 */}
          <div style={{ width: 240, minWidth: 180, borderRight: '1px solid #3c3c3c', overflow: 'auto', background: '#252526', padding: '6px 0' }}>
            {renderTreeNode(tree)}
          </div>

          {/* 编辑器区 */}
          <div style={{ flex: 1, overflow: 'auto', background: '#1e1e1e' }}>
            {file ? (
              <div style={{ minWidth: 'max-content' }}>
                {/* 标签栏 */}
                <div style={{
                  padding: '6px 14px', background: '#2d2d2d', color: '#cccccc', fontSize: 11,
                  borderBottom: '1px solid #3c3c3c', display: 'flex', gap: 10, alignItems: 'center',
                  position: 'sticky', top: 0, zIndex: 1,
                }}>
                  <span style={{ color: extColor(file.name) }}>{file.path || file.name}</span>
                  {file.language && file.language !== 'plaintext' && (
                    <span style={{ padding: '0 6px', borderRadius: 8, background: '#3c3c3c', color: '#9cdcfe', fontSize: 10 }}>
                      {file.language}
                    </span>
                  )}
                  {file.size !== undefined && (
                    <span style={{ marginLeft: 'auto', color: '#858585' }}>
                      {file.size < 1024 ? file.size + ' B' : (file.size / 1024).toFixed(1) + ' KB'}
                    </span>
                  )}
                </div>

                {/* 行号 + 代码 */}
                <div style={{ display: 'flex', fontFamily: "'Cascadia Code', 'Fira Code', 'JetBrains Mono', Consolas, monospace", fontSize: 12.5 }}>
                  <div style={{
                    flexShrink: 0, textAlign: 'right', padding: '8px 10px 8px 14px',
                    borderRight: '1px solid #404040', color: '#858585', userSelect: 'none',
                    background: '#1e1e1e',
                  }}>
                    {highlighted.map((_, i) => (
                      <div key={i} style={lineStyle}>{i + 1}</div>
                    ))}
                  </div>
                  <pre style={{ margin: 0, padding: '8px 16px', whiteSpace: 'pre', tabSize: 4, flex: 1 }}>
                    {highlighted.map((tokens, i) => (
                      <div key={i} style={lineStyle}>
                        {tokens.length > 0
                          ? tokens.map(tk => <span key={tk.k} style={{ color: HL_COLORS[tk.t] }}>{tk.v}</span>)
                          : ' '}
                      </div>
                    ))}
                  </pre>
                </div>
              </div>
            ) : (
              <div style={{ height: '100%', minHeight: 300, display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#6e6e6e', fontSize: 13 }}>
                ← 点击左侧文件查看内容
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
