import { useState, useEffect } from 'react'
import {
  fetchTestReportsByBuild,
  fetchTestReportsByProject,
  fetchTestReport,
  fetchProjectAvgCoverage
} from '../api'

const STATUS_COLORS = {
  JUNIT: '#6db33f',
  JEST: '#c21325',
  PYTEST: '#3776ab',
  GO_TEST: '#00add8',
  CARGO_TEST: '#dea584',
  CTEST: '#8b0000',
  UNKNOWN: '#999'
}

export default function TestReportPanel({ projectId, buildId }) {
  const [reports, setReports] = useState([])
  const [avgCov, setAvgCov] = useState(null)
  const [loading, setLoading] = useState(false)
  const [expandedId, setExpandedId] = useState(null)
  const [detail, setDetail] = useState(null)

  useEffect(() => {
    if (buildId) {
      loadByBuild()
    } else if (projectId) {
      loadByProject()
    }
  }, [projectId, buildId])

  const loadByBuild = async () => {
    setLoading(true)
    try {
      const data = await fetchTestReportsByBuild(buildId)
      setReports(Array.isArray(data) ? data : [])
    } catch (e) {
      console.error('Failed to load test reports', e)
    } finally {
      setLoading(false)
    }
  }

  const loadByProject = async () => {
    setLoading(true)
    try {
      const [data, covData] = await Promise.all([
        fetchTestReportsByProject(projectId).catch(() => []),
        fetchProjectAvgCoverage(projectId).catch(() => ({ avgCoverage: -1 }))
      ])
      setReports(Array.isArray(data) ? data : [])
      setAvgCov(covData?.avgCoverage)
    } catch (e) {
      console.error('Failed to load test reports', e)
    } finally {
      setLoading(false)
    }
  }

  const loadDetail = async (id) => {
    if (expandedId === id) {
      setExpandedId(null)
      setDetail(null)
      return
    }
    try {
      const d = await fetchTestReport(id)
      setDetail(d)
      setExpandedId(id)
    } catch (e) {
      console.error('Failed to load report detail', e)
    }
  }

  const passRate = (r) => {
    if (!r.totalTests || r.totalTests === 0) return 'N/A'
    return ((r.passed / r.totalTests) * 100).toFixed(1) + '%'
  }

  const coverageBar = (pct) => {
    if (pct == null || pct < 0) return null
    const clamped = Math.min(100, Math.max(0, pct))
    const color = pct >= 80 ? '#4caf50' : pct >= 60 ? '#ff9800' : '#f44336'
    return (
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <div style={{
          flex: 1, height: 8, background: '#e0e0e0', borderRadius: 4, overflow: 'hidden', minWidth: 80
        }}>
          <div style={{
            width: clamped + '%', height: '100%', background: color, borderRadius: 4,
            transition: 'width 0.5s ease'
          }} />
        </div>
        <span style={{ fontSize: 12, fontWeight: 600, minWidth: 48 }}>{pct.toFixed(1)}%</span>
      </div>
    )
  }

  const qualityBadge = (passed) => (
    <span style={{
      padding: '2px 8px', borderRadius: 10, fontSize: 11, fontWeight: 600,
      background: passed ? '#e8f5e9' : '#fce4e4', color: passed ? '#2e7d32' : '#c62828'
    }}>
      {passed ? '✓ 门禁通过' : '✗ 门禁失败'}
    </span>
  )

  if (loading) return <div className="loading-spinner">加载测试报告...</div>

  return (
    <div className="test-report-panel">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <h3 style={{ margin: 0 }}>
          🧪 测试报告
          {reports.length > 0 && <span style={{ fontSize: 14, color: '#888', marginLeft: 8 }}>({reports.length})</span>}
        </h3>
        {avgCov != null && avgCov >= 0 && (
          <span style={{ fontSize: 13, color: '#555', fontWeight: 500 }}>
            项目平均覆盖率: <strong>{avgCov.toFixed(1)}%</strong>
          </span>
        )}
      </div>

      {reports.length === 0 && (
        <div style={{ padding: 32, textAlign: 'center', color: '#999' }}>
          <div style={{ fontSize: 40, marginBottom: 8 }}>🧪</div>
          <p>暂无测试报告</p>
          <p style={{ fontSize: 12 }}>在流水线中添加 TEST 步骤以自动生成测试报告</p>
        </div>
      )}

      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        {reports.map((r) => (
          <div key={r.id} className="card" style={{ padding: 16 }}>
            <div
              style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', cursor: 'pointer' }}
              onClick={() => loadDetail(r.id)}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <span style={{
                  display: 'inline-block', width: 10, height: 10, borderRadius: '50%',
                  background: STATUS_COLORS[r.testFramework] || '#999'
                }} />
                <strong>{r.testFramework}</strong>
                <span style={{ fontSize: 12, color: '#888' }}>
                  {r.passed}/{r.totalTests} 通过
                  {r.failed > 0 && <span style={{ color: '#e53935', marginLeft: 4 }}>{r.failed} 失败</span>}
                  {r.skipped > 0 && <span style={{ color: '#fb8c00', marginLeft: 4 }}>{r.skipped} 跳过</span>}
                </span>
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
                {coverageBar(r.coveragePercent)}
                {qualityBadge(r.qualityGatePassed)}
                <span style={{ fontSize: 11, color: '#aaa' }}>
                  {new Date(r.createdAt).toLocaleString()}
                </span>
                <span style={{ color: '#aaa', fontSize: 12 }}>{expandedId === r.id ? '▲' : '▼'}</span>
              </div>
            </div>

            {/* 展开详情 */}
            {expandedId === r.id && detail && (
              <div style={{ marginTop: 12, padding: 12, background: '#f9f9f9', borderRadius: 8, fontSize: 13 }}>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr 1fr', gap: 12, marginBottom: 12 }}>
                  <StatBox label="总测试数" value={r.totalTests} />
                  <StatBox label="通过" value={r.passed} color="#4caf50" />
                  <StatBox label="失败" value={r.failed} color="#f44336" />
                  <StatBox label="跳过" value={r.skipped} color="#ff9800" />
                </div>
                {r.lineCoverage != null && r.lineCoverage >= 0 && (
                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 12, marginBottom: 12 }}>
                    <StatBox label="行覆盖率" value={r.lineCoverage.toFixed(1) + '%'} />
                    <StatBox label="分支覆盖率" value={(r.branchCoverage >= 0 ? r.branchCoverage.toFixed(1) : 'N/A') + (r.branchCoverage >= 0 ? '%' : '')} />
                    <StatBox label="耗时" value={(r.durationMs / 1000).toFixed(1) + 's'} />
                  </div>
                )}
                {r.qualityGateMessage && (
                  <div style={{
                    padding: '8px 12px', borderRadius: 6, fontSize: 12,
                    background: r.qualityGatePassed ? '#e8f5e9' : '#ffebee',
                    color: r.qualityGatePassed ? '#2e7d32' : '#c62828',
                    marginBottom: 12
                  }}>
                    {r.qualityGatePassed ? '✅' : '❌'} {r.qualityGateMessage}
                  </div>
                )}
                {r.summary && <p style={{ color: '#666', marginBottom: 12 }}>{r.summary}</p>}
                {r.rawReport && (
                  <details>
                    <summary style={{ cursor: 'pointer', color: '#1976d2', fontSize: 12 }}>
                      查看原始报告 ({r.rawReport.length.toLocaleString()} 字符)
                    </summary>
                    <pre style={{
                      maxHeight: 300, overflow: 'auto', fontSize: 11,
                      background: '#263238', color: '#aed581', padding: 12, borderRadius: 6,
                      marginTop: 8, whiteSpace: 'pre-wrap', wordBreak: 'break-all'
                    }}>
                      {r.rawReport.substring(0, 20000)}
                      {r.rawReport.length > 20000 && '\n\n... (截断，完整报告见数据库)'}
                    </pre>
                  </details>
                )}
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  )
}

function StatBox({ label, value, color }) {
  return (
    <div style={{ textAlign: 'center', padding: '8px 4px', background: '#fff', borderRadius: 6 }}>
      <div style={{ fontSize: 20, fontWeight: 700, color: color || '#333' }}>{value ?? '-'}</div>
      <div style={{ fontSize: 11, color: '#999' }}>{label}</div>
    </div>
  )
}
