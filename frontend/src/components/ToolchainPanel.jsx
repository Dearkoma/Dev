import React, { useState, useEffect, useCallback, useRef } from 'react'
import { fetchToolchainStatus, startToolchainDownload, fetchToolchainTask } from '../api'

/** 从 "cmake version 3.30.3" 之类的输出里提取版本号 */
function shortVersion(v) {
  if (!v) return ''
  const m = String(v).match(/\d+\.\d+(?:\.\d+)?/)
  return m ? 'v' + m[0] : ''
}

const BTN = {
  marginLeft: 4, padding: '1px 7px', fontSize: 11, cursor: 'pointer',
  border: '1px solid #93c5fd', borderRadius: 5, background: '#fff', color: '#1d4ed8',
}

/**
 * 工具链状态面板 — 展示某语言所需工具的本机可用性，缺失时可一键下载并自动接入。
 * 下载由后端异步执行，前端轮询进度；完成后自动重新检测并给出提示。
 *
 * @param toolFilter 只展示这些工具 id（外加该语言必需的工具）；null/未传 → 展示全部。
 *                   由上层按「项目已勾选的能力」计算，未使用容器化的项目就不会出现 Docker/Kubectl。
 */
export default function ToolchainPanel({ language, toolFilter, onChange }) {
  const [status, setStatus] = useState(null)
  const [loading, setLoading] = useState(false)
  const [tasks, setTasks] = useState({})     // toolId -> task
  const [notice, setNotice] = useState(null)
  const timers = useRef({})

  const load = useCallback(async (lang) => {
    if (!lang) { setStatus(null); return }
    setLoading(true)
    try {
      const s = await fetchToolchainStatus(lang)
      setStatus(s)
      if (onChange) onChange(s)
    } catch {
      setStatus(null)
    } finally {
      setLoading(false)
    }
  }, [onChange])

  useEffect(() => {
    setNotice(null)
    setTasks({})
    load(language)
  }, [language, load])

  // 卸载时清理所有轮询
  useEffect(() => () => {
    Object.values(timers.current).forEach(t => clearInterval(t))
    timers.current = {}
  }, [])

  const poll = (taskId, toolId) => {
    if (timers.current[toolId]) clearInterval(timers.current[toolId])
    timers.current[toolId] = setInterval(async () => {
      try {
        const task = await fetchToolchainTask(taskId)
        setTasks(prev => ({ ...prev, [toolId]: task }))
        if (task.state === 'DONE' || task.state === 'FAILED') {
          clearInterval(timers.current[toolId])
          delete timers.current[toolId]
          if (task.state === 'DONE') {
            setNotice({ type: 'success', text: task.message })
            await load(language)
            setTimeout(() => {
              setTasks(prev => {
                const next = { ...prev }
                delete next[toolId]
                return next
              })
            }, 4000)
          } else {
            setNotice({ type: 'error', text: task.message })
          }
        }
      } catch {
        // 轮询失败保持静默，下一轮重试
      }
    }, 1000)
  }

  const handleDownload = async (tool) => {
    setNotice({ type: 'info', text: `正在启动「${tool.label}」下载…` })
    try {
      const task = await startToolchainDownload(tool.id)
      setTasks(prev => ({ ...prev, [tool.id]: task }))
      poll(task.id, tool.id)
    } catch (e) {
      setNotice({ type: 'error', text: '启动下载失败: ' + e.message })
    }
  }

  if (!language) return null

  const ready = status?.ready
  const busyTasks = Object.entries(tasks).filter(([, t]) =>
    t.state === 'DOWNLOADING' || t.state === 'EXTRACTING' || t.state === 'PENDING')

  // 语言必需的工具始终展示；能力相关的工具只在对应能力被勾选时展示
  const visibleTools = (status?.tools || []).filter(t =>
    !toolFilter || t.required || toolFilter.includes(t.id))

  return (
    <div style={{
      marginTop: 2, marginBottom: 14, padding: '10px 12px', borderRadius: 8,
      background: ready ? '#f0fdf4' : '#fffbeb',
      border: `1px solid ${ready ? '#bbf7d0' : '#fde68a'}`,
    }}>
      <div style={{
        display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap',
        fontSize: 12, fontWeight: 500,
        color: ready ? '#166534' : '#92400e',
      }}>
        <span>🧰 {language} 工具链</span>
        {loading && <span style={{ fontWeight: 400, opacity: 0.7 }}>检测中…</span>}
        {!loading && status && ready && <span style={{ fontWeight: 400 }}>已就绪</span>}
        {!loading && status && !ready && (
          <span style={{ fontWeight: 400 }}>
            缺少必需工具：{(status.missingRequired || []).join('、') || '未知'}
          </span>
        )}
        {!loading && status && (
          <button
            type="button"
            onClick={() => load(language)}
            style={{ marginLeft: 'auto', ...BTN, borderColor: '#d1d5db', color: '#4b5563' }}
          >
            ⟳ 重新检测
          </button>
        )}
      </div>

      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 8 }}>
        {visibleTools.map(t => {
          const task = tasks[t.id]
          const busy = task && (task.state === 'DOWNLOADING' || task.state === 'EXTRACTING' || task.state === 'PENDING')
          const bg = t.available ? '#dcfce7' : (t.required ? '#fee2e2' : '#f3f4f6')
          const fg = t.available ? '#166534' : (t.required ? '#991b1b' : '#6b7280')
          return (
            <span key={t.id} style={{
              display: 'inline-flex', alignItems: 'center', gap: 5,
              fontSize: 11.5, padding: '3px 8px', borderRadius: 6,
              background: bg, color: fg, border: '1px solid rgba(0,0,0,0.06)',
            }}>
              <span>{t.available ? '✓' : '✗'}</span>
              <span>{t.label}</span>
              {t.available && t.version && (
                <span style={{ opacity: 0.7 }}>{shortVersion(t.version)}</span>
              )}
              {!t.available && t.required && <span style={{ opacity: 0.75 }}>必需</span>}
              {!t.available && !busy && t.downloadable && (
                <button type="button" style={BTN} onClick={() => handleDownload(t)}>⬇ 下载</button>
              )}
              {!t.available && !busy && !t.downloadable && t.homeUrl && (
                <a href={t.homeUrl} target="_blank" rel="noreferrer"
                  style={{ color: '#2563eb', textDecoration: 'underline' }}>手动安装</a>
              )}
            </span>
          )
        })}
      </div>

      {busyTasks.map(([tid, task]) => (
        <div key={tid} style={{ marginTop: 10 }}>
          <div style={{ fontSize: 11, color: '#374151', marginBottom: 3 }}>{task.message}</div>
          <div style={{ height: 6, borderRadius: 3, background: '#e5e7eb', overflow: 'hidden' }}>
            <div style={{
              height: '100%', width: `${Math.max(task.progress || 0, 2)}%`,
              background: '#3b82f6', transition: 'width .3s',
            }} />
          </div>
        </div>
      ))}

      {notice && (
        <div style={{
          marginTop: 9, fontSize: 11.5, padding: '5px 9px', borderRadius: 6,
          background: notice.type === 'success' ? '#dcfce7'
            : notice.type === 'error' ? '#fee2e2' : '#e0e7ff',
          color: notice.type === 'success' ? '#166534'
            : notice.type === 'error' ? '#991b1b' : '#3730a3',
        }}>
          {notice.text}
        </div>
      )}
    </div>
  )
}
