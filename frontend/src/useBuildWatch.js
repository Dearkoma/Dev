import { useCallback, useEffect, useRef } from 'react'
import { fetchBuild } from './api'

/** 构建终态：到了这些状态就不会再变化 */
export const TERMINAL_BUILD_STATUSES = ['SUCCESS', 'FAILED', 'CANCELLED']

/**
 * 轮询跟踪一次构建直到结束，然后回调给调用方。
 *
 * 为什么需要它：后端构建是异步的（触发接口立刻返回），
 * 失败可能在几秒内发生（例如 git clone -b 分支不存在直接 128）。
 * 只提示「已触发」会让用户以为一切正常，所以必须轮询到终态才能给出成功/失败结论。
 *
 * @param onFinish   结束回调 (build) => void
 * @param intervalMs 轮询间隔，默认 3000ms
 * @param timeoutMs  最长跟踪时长，默认 10 分钟（兜底，防止无限轮询）
 * @returns (buildId) => void 开始跟踪；重复调用会先停掉上一次跟踪
 */
export function useBuildWatch(onFinish, intervalMs = 3000, timeoutMs = 10 * 60 * 1000) {
  const timerRef = useRef(null)
  const finishRef = useRef(onFinish)

  useEffect(() => { finishRef.current = onFinish }, [onFinish])

  const stop = useCallback(() => {
    if (timerRef.current) {
      clearInterval(timerRef.current)
      timerRef.current = null
    }
  }, [])

  // 组件卸载时停止轮询，避免内存泄漏与在已卸载组件上 setState
  useEffect(() => stop, [stop])

  return useCallback((buildId) => {
    stop()
    if (!buildId) return
    const startedAt = Date.now()
    const tick = async () => {
      try {
        const b = await fetchBuild(buildId)
        if (b && TERMINAL_BUILD_STATUSES.includes(b.status)) {
          stop()
          finishRef.current?.(b)
        }
      } catch { /* 单次轮询失败忽略，下一次再试 */ }
      if (Date.now() - startedAt > timeoutMs) stop()
    }
    timerRef.current = setInterval(tick, intervalMs)
    tick()
  }, [stop, intervalMs, timeoutMs])
}
