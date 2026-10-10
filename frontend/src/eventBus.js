// 简单事件总线 — 跨组件删除/更新通知
// 用法:
//   emit('project:deleted', { id: 5 })
//   useResourceEvent('project:deleted', (detail) => { ... })
//
// 派发的事件:
//   project:deleted     { id }
//   project:created     { project }
//   pipeline:deleted    { id, projectId }
//   pipeline:created    { pipeline }
//   pipeline:updated    { pipeline }
//   build:deleted       { id, projectId }
//   instance:deleted    { id, projectId }
//   deployment:deleted  { id }

const subscribers = new Map()

export function emit(eventName, detail) {
  // 用 DOM CustomEvent 兜底,确保跨组件、跨路由也能触发
  window.dispatchEvent(new CustomEvent(eventName, { detail }))
  // 同时调直接订阅者(更快)
  const subs = subscribers.get(eventName)
  if (subs) {
    subs.forEach(cb => {
      try { cb(detail) } catch (e) { console.error(`[eventBus] ${eventName} subscriber error:`, e) }
    })
  }
}

export function on(eventName, callback) {
  if (!subscribers.has(eventName)) subscribers.set(eventName, new Set())
  subscribers.get(eventName).add(callback)
  return () => off(eventName, callback)
}

export function off(eventName, callback) {
  const subs = subscribers.get(eventName)
  if (subs) subs.delete(callback)
}

// React hook: 组件挂载时订阅,卸载时取消
import { useEffect } from 'react'

export function useResourceEvent(eventName, callback) {
  useEffect(() => {
    if (!callback) return
    const unsub = on(eventName, callback)
    return unsub
  }, [eventName, callback])
}

// 同时监听 DOM CustomEvent 和直接订阅(emit 已经同时触发,这里提供手动监听入口)
export function onDomEvent(eventName, callback) {
  window.addEventListener(eventName, callback)
  return () => window.removeEventListener(eventName, callback)
}
