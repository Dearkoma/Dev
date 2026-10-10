/**
 * 全局事件 WebSocket 客户端
 *
 * 连接后端 /ws/events 端点 (原生 WebSocket, 无 STOMP/SockJS 依赖)。
 * 收到事件后调用 eventBus.emit 转发, 组件用 useResourceEvent 订阅。
 *
 * 事件类型:
 *   - notification.created  → 转发为 'notification:created'
 *   - deployment.updated    → 转发为 'deployment:updated'
 *   - build.updated         → 转发为 'build:updated'
 *
 * 自动重连: 指数退避, 最多 10 次。
 */

import { emit } from './eventBus'

const WS_URL = (() => {
  const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${proto}//${window.location.host}/ws/events`
})()

let ws = null
let reconnectAttempts = 0
let reconnectTimer = null
let closeTimer = null
let manualClose = false
const MAX_RECONNECT = 10

function connect() {
  if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return
  manualClose = false
  try {
    ws = new WebSocket(WS_URL)
  } catch (e) {
    scheduleReconnect()
    return
  }

  ws.onopen = () => {
    reconnectAttempts = 0
    emit('ws:connected', {})
  }

  ws.onmessage = (evt) => {
    try {
      const data = JSON.parse(evt.data)
      if (!data || !data.type) return
      const busEvent = data.type.replace('.', ':')
      emit(busEvent, data.payload || {})
    } catch (e) {
      // 忽略非 JSON 帧
    }
  }

  ws.onerror = () => { /* onclose 会处理重连 */ }

  ws.onclose = () => {
    emit('ws:disconnected', {})
    if (!manualClose) scheduleReconnect()
  }
}

function scheduleReconnect() {
  if (reconnectAttempts >= MAX_RECONNECT) return
  reconnectAttempts++
  const delay = Math.min(1000 * Math.pow(2, reconnectAttempts), 30000)
  if (reconnectTimer) clearTimeout(reconnectTimer)
  reconnectTimer = setTimeout(connect, delay)
}

export function startEventWebSocket() {
  // 取消上一轮 stop 排队的延迟关闭。
  // React 18 StrictMode 在开发模式下会把 effect 跑两遍(mount → cleanup → mount),
  // 若 stop 立即关连接, 就会出现"连上→断开→再连上"的抖动,
  // 后端日志出现成对的连接/断开、并经 Vite ws 代理抛出 write ECONNABORTED。
  if (closeTimer) {
    clearTimeout(closeTimer)
    closeTimer = null
  }
  manualClose = false
  connect()
}

export function stopEventWebSocket() {
  if (closeTimer) return
  manualClose = true
  // 延迟到当前同步任务结束再真正关闭: 若期间又 startEventWebSocket(), 上面的
  // clearTimeout 会取消本次关闭, 从而复用同一条连接。
  closeTimer = setTimeout(() => {
    closeTimer = null
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    if (ws) {
      try { ws.close() } catch {}
      ws = null
    }
  }, 0)
}

export function isEventWebSocketOpen() {
  return ws && ws.readyState === WebSocket.OPEN
}
