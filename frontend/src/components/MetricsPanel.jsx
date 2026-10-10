import React, { useState, useEffect, useCallback } from 'react'
import {
  fetchMetricsRealtime, fetchMetricsTimeseries,
  fetchMetricsSlowPaths, fetchMetricsErrorPaths
} from '../api'

const RANGE_OPTIONS = [
  { label: '最近 15 分钟', value: 15 },
  { label: '最近 1 小时', value: 60 },
  { label: '最近 6 小时', value: 360 },
  { label: '最近 24 小时', value: 1440 }
]

function MetricCard({ label, value, suffix, color, hint }) {
  return (
    <div style={{ background: '#F8F7F2', borderRadius: 6, padding: '12px 16px' }}>
      <p style={{ fontSize: 11, color: '#888780', margin: 0 }}>{label}</p>
      <p style={{ fontSize: 22, fontWeight: 500, color: color || '#2C2C2A', margin: '4px 0 0' }}>
        {value}{suffix && <span style={{ fontSize: 13, color: '#888780', marginLeft: 4 }}>{suffix}</span>}
      </p>
      {hint && <p style={{ fontSize: 10, color: '#888780', margin: '2px 0 0' }}>{hint}</p>}
    </div>
  )
}

function TimeSeriesChart({ points, minutes }) {
  if (!points || points.length === 0) {
    return <div style={{ padding: 40, textAlign: 'center', color: '#888780', fontSize: 12 }}>暂无数据 (需等请求流量产生指标)</div>
  }
  const W = 720, H = 200, P = 36
  const maxCount = Math.max(1, ...points.map(p => p.count))
  const maxP95 = Math.max(1, ...points.map(p => p.p95))

  // 双 Y 轴: 左 = QPS, 右 = P95
  const xStep = (W - P * 2) / Math.max(1, points.length - 1)

  // QPS 多边形 (左轴)
  const qpsPath = points.map((p, i) => {
    const x = P + i * xStep
    const y = H - P - (p.count / maxCount) * (H - P * 2)
    return `${i === 0 ? 'M' : 'L'} ${x} ${y}`
  }).join(' ')

  // P95 多边形 (右轴, 蓝色)
  const p95Path = points.map((p, i) => {
    const x = P + i * xStep
    const y = H - P - (p.p95 / maxP95) * (H - P * 2)
    return `${i === 0 ? 'M' : 'L'} ${x} ${y}`
  }).join(' ')

  // 错误点
  const errPoints = points.filter(p => p.errors > 0).map((p, i) => {
    const idx = points.indexOf(p)
    return { x: P + idx * xStep, y: H - P - (p.count / maxCount) * (H - P * 2), err: p.errors }
  })

  // 时间标签 (左中右)
  const labelTimes = [0, Math.floor(points.length / 2), points.length - 1]

  return (
    <svg viewBox={`0 0 ${W} ${H}`} style={{ width: '100%', height: 200 }}>
      {/* 网格线 */}
      <line x1={P} y1={P} x2={W - P} y2={P} stroke="#D3D1C7" strokeWidth="0.5" strokeDasharray="2 2"/>
      <line x1={P} y1={H / 2} x2={W - P} y2={H / 2} stroke="#D3D1C7" strokeWidth="0.5" strokeDasharray="2 2"/>
      <line x1={P} y1={H - P} x2={W - P} y2={H - P} stroke="#5F5E5A" strokeWidth="0.5"/>

      {/* QPS 折线 */}
      <path d={qpsPath} fill="none" stroke="#378ADD" strokeWidth="1.5"/>
      {/* P95 折线 */}
      <path d={p95Path} fill="none" stroke="#D85A30" strokeWidth="1.5" strokeDasharray="3 2"/>

      {/* 错误点 */}
      {errPoints.map((e, i) => (
        <circle key={i} cx={e.x} cy={e.y} r="3" fill="#A32D2D"/>
      ))}

      {/* 时间标签 */}
      {labelTimes.map(i => {
        if (i < 0 || i >= points.length) return null
        const t = new Date(points[i].time)
        const label = t.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
        return (
          <text key={i} x={P + i * xStep} y={H - 12} textAnchor="middle"
            fontSize="10" fill="#888780">{label}</text>
        )
      })}

      {/* 图例 */}
      <g transform={`translate(${P}, 18)`}>
        <rect x="0" y="0" width="10" height="3" fill="#378ADD"/>
        <text x="14" y="3" fontSize="10" fill="#5F5E5A">QPS (左轴)</text>
        <rect x="100" y="0" width="10" height="3" fill="#D85A30"/>
        <text x="114" y="3" fontSize="10" fill="#5F5E5A">P95 ms (右轴)</text>
        <circle cx="210" cy="1.5" r="3" fill="#A32D2D"/>
        <text x="218" y="3" fontSize="10" fill="#5F5E5A">错误</text>
      </g>
    </svg>
  )
}

export default function MetricsPanel() {
  const [realtime, setRealtime] = useState(null)
  const [series, setSeries] = useState(null)
  const [slowPaths, setSlowPaths] = useState([])
  const [errorPaths, setErrorPaths] = useState([])
  const [minutes, setMinutes] = useState(60)
  const [loading, setLoading] = useState(true)

  const load = useCallback(async () => {
    try {
      const [rt, ts, slow, errs] = await Promise.all([
        fetchMetricsRealtime(),
        fetchMetricsTimeseries(minutes),
        fetchMetricsSlowPaths(10, minutes),
        fetchMetricsErrorPaths(10, minutes)
      ])
      setRealtime(rt)
      setSeries(ts)
      setSlowPaths(slow || [])
      setErrorPaths(errs || [])
    } catch (e) { console.error(e) }
    finally { setLoading(false) }
  }, [minutes])

  useEffect(() => { load() }, [load])
  useEffect(() => {
    const t = setInterval(() => { fetchMetricsRealtime().then(setRealtime) }, 5000)
    return () => clearInterval(t)
  }, [])

  if (loading && !realtime) {
    return <div style={{ padding: 24 }}><div className="spinner" /></div>
  }

  const errColor = (realtime?.errorRate || 0) > 5 ? '#A32D2D' : (realtime?.errorRate || 0) > 1 ? '#BA7517' : '#27500A'

  return (
    <div style={{ padding: 24 }}>
      <h2 style={{ fontSize: 18, fontWeight: 500, margin: '0 0 8px' }}>APM 性能监控</h2>
      <p style={{ fontSize: 13, color: '#888780', margin: '0 0 16px' }}>
        HTTP 请求时序分析 · QPS / 错误率 / P50·P95·P99 响应时间 · 慢路径 Top N
      </p>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 12, marginBottom: 20 }}>
        <MetricCard label="总请求数" value={realtime?.totalRequests?.toLocaleString() || 0} hint="服务启动累计" color="#185FA5" />
        <MetricCard label="当前 QPS" value={realtime?.qps || 0} hint="最近 60s 平均" color="#534AB7" />
        <MetricCard label="错误率" value={realtime?.errorRate || 0} suffix="%" hint={`错误 ${realtime?.totalErrors || 0} 次`} color={errColor} />
        <MetricCard label="活跃指标" value={realtime?.activeRecentCount || 0} hint="最近 60s 落库数" color="#5F5E5A" />
      </div>

      <div style={{ background: '#FFFFFF', border: '0.5px solid rgba(17,24,28,0.15)', borderRadius: 8, padding: 16, marginBottom: 16 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
          <p style={{ fontSize: 14, fontWeight: 500, margin: 0 }}>请求时序</p>
          <div style={{ display: 'flex', gap: 4 }}>
            {RANGE_OPTIONS.map(o => (
              <button key={o.value} onClick={() => setMinutes(o.value)} style={{
                padding: '4px 10px', fontSize: 11,
                background: minutes === o.value ? '#534AB7' : 'transparent',
                color: minutes === o.value ? '#FFFFFF' : '#5F5E5A',
                border: '0.5px solid rgba(17,24,28,0.3)', borderRadius: 3, cursor: 'pointer'
              }}>{o.label}</button>
            ))}
          </div>
        </div>
        <TimeSeriesChart points={series?.points || []} minutes={minutes} />
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
        <div style={{ background: '#FFFFFF', border: '0.5px solid rgba(17,24,28,0.15)', borderRadius: 8, overflow: 'hidden' }}>
          <div style={{ padding: '10px 12px', background: '#F8F7F2', borderBottom: '0.5px solid rgba(17,24,28,0.15)' }}>
            <p style={{ fontSize: 12, fontWeight: 500, margin: 0 }}>🐢 慢路径 Top 10 (按 P95)</p>
          </div>
          {slowPaths.length === 0 ? (
            <div style={{ padding: 20, textAlign: 'center', color: '#888780', fontSize: 12 }}>暂无数据</div>
          ) : (
            <table style={{ width: '100%', fontSize: 11, borderCollapse: 'collapse' }}>
              <thead>
                <tr style={{ background: '#FAFCF5' }}>
                  <th style={{ padding: '6px 10px', textAlign: 'left', color: '#5F5E5A' }}>路径</th>
                  <th style={{ padding: '6px 10px', textAlign: 'right', color: '#5F5E5A' }}>次数</th>
                  <th style={{ padding: '6px 10px', textAlign: 'right', color: '#5F5E5A' }}>P50</th>
                  <th style={{ padding: '6px 10px', textAlign: 'right', color: '#5F5E5A' }}>P95</th>
                  <th style={{ padding: '6px 10px', textAlign: 'right', color: '#5F5E5A' }}>P99</th>
                </tr>
              </thead>
              <tbody>
                {slowPaths.map(p => (
                  <tr key={p.path} style={{ borderTop: '0.5px solid rgba(17,24,28,0.05)' }}>
                    <td style={{ padding: '6px 10px', color: '#2C2C2A', fontFamily: 'monospace', fontSize: 10 }}>{p.path}</td>
                    <td style={{ padding: '6px 10px', textAlign: 'right', color: '#5F5E5A' }}>{p.count}</td>
                    <td style={{ padding: '6px 10px', textAlign: 'right', color: '#5F5E5A' }}>{p.p50}ms</td>
                    <td style={{ padding: '6px 10px', textAlign: 'right', color: '#A32D2D', fontWeight: 500 }}>{p.p95}ms</td>
                    <td style={{ padding: '6px 10px', textAlign: 'right', color: '#5F5E5A' }}>{p.p99}ms</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>

        <div style={{ background: '#FFFFFF', border: '0.5px solid rgba(17,24,28,0.15)', borderRadius: 8, overflow: 'hidden' }}>
          <div style={{ padding: '10px 12px', background: '#F8F7F2', borderBottom: '0.5px solid rgba(17,24,28,0.15)' }}>
            <p style={{ fontSize: 12, fontWeight: 500, margin: 0 }}>❌ 错误路径 Top 10</p>
          </div>
          {errorPaths.length === 0 ? (
            <div style={{ padding: 20, textAlign: 'center', color: '#27500A', fontSize: 12 }}>✅ 没有错误请求</div>
          ) : (
            <table style={{ width: '100%', fontSize: 11, borderCollapse: 'collapse' }}>
              <thead>
                <tr style={{ background: '#FCEBEB' }}>
                  <th style={{ padding: '6px 10px', textAlign: 'left', color: '#791F1F' }}>路径</th>
                  <th style={{ padding: '6px 10px', textAlign: 'right', color: '#791F1F' }}>错误数</th>
                </tr>
              </thead>
              <tbody>
                {errorPaths.map(p => (
                  <tr key={p.path} style={{ borderTop: '0.5px solid rgba(17,24,28,0.05)' }}>
                    <td style={{ padding: '6px 10px', color: '#2C2C2A', fontFamily: 'monospace', fontSize: 10 }}>{p.path}</td>
                    <td style={{ padding: '6px 10px', textAlign: 'right', color: '#A32D2D', fontWeight: 500 }}>{p.errorCount}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>

      <div style={{ marginTop: 16, padding: 10, background: '#F1EFE8', borderRadius: 4, fontSize: 11, color: '#5F5E5A' }}>
        💡 集成 Prometheus: 在 Prometheus 配置中添加 scrape target <code style={{ background: '#FFFFFF', padding: '0 4px' }}>http://D平台:8080/api/metrics/prometheus</code>, 数据自动可被 Grafana 拉取
      </div>
    </div>
  )
}
