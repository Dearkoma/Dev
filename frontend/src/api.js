const BASE = '/api'

/**
 * 读取 CSRF Token（Spring Security CookieCsrfTokenRepository 写入 XSRF-TOKEN cookie）
 */
function getCsrfToken() {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/)
  return match ? decodeURIComponent(match[1]) : ''
}

async function request(url, options = {}) {
  const headers = { ...options.headers }
  // 仅在请求有 body 且未手动设置 Content-Type 时使用 JSON
  if (options.body && !headers['Content-Type']) {
    headers['Content-Type'] = 'application/json'
  }

  // CSRF Token (Spring Security 期望 X-XSRF-TOKEN)
  const csrf = getCsrfToken()
  if (csrf) {
    headers['X-XSRF-TOKEN'] = csrf
  }

  // 请求超时（默认 30 秒，可通过 options.timeout 覆盖）
  const controller = new AbortController()
  const timeoutMs = options.timeout || 30000
  const timeoutId = setTimeout(() => controller.abort(), timeoutMs)
  const signal = options.signal || controller.signal

  let res
  try {
    res = await fetch(BASE + url, {
      ...options,
      headers,
      signal,
      credentials: 'same-origin'  // 自动发送 HttpOnly Cookie
    })
    clearTimeout(timeoutId)
  } catch (err) {
    clearTimeout(timeoutId)
    if (err.name === 'AbortError') {
      throw new Error('请求超时，后端服务响应过慢')
    }
    // 网络错误（ECONNREFUSED 等）
    if (err.message.includes('Failed to fetch') || err.name === 'TypeError') {
      throw new Error('无法连接后端服务，请确认 Spring Boot 应用已在端口 8080 启动')
    }
    throw err
  }

  if (!res.ok) {
    const text = await res.text()
    let message
    try {
      const parsed = JSON.parse(text)
      message = parsed.error || parsed.message
    } catch {
      message = text
    }

    // 401 → 清除 token 并通过自定义事件通知 AuthContext 登出
    if (res.status === 401) {
      localStorage.removeItem('devops_token')
      localStorage.removeItem('devops_user')
      // 非登录接口才通知登出
      if (!url.startsWith('/auth/')) {
        window.dispatchEvent(new CustomEvent('auth:logout'))
      }
    }

    const err = new Error(message || `HTTP ${res.status}`)
    err.status = res.status
    throw err
  }

  const ct = res.headers.get('content-type')
  if (ct && ct.includes('application/json')) return res.json()
  return res.text()
}

// ==================== Auth ====================
export const loginApi = (credentials) =>
  request('/auth/login', { method: 'POST', body: JSON.stringify(credentials) })

export const registerApi = (data) =>
  request('/auth/register', { method: 'POST', body: JSON.stringify(data) })

export const fetchMe = () => request('/auth/me')

// ==================== Dashboard ====================
export const fetchDashboardStats = () => request('/dashboard/stats')
export const fetchDashboardTrend = () => request('/dashboard/trends')
export const fetchRecentBuilds = (limit = 10) => request(`/dashboard/recent-builds?limit=${limit}`)

// ==================== Projects ====================
export const fetchProjects = () => request('/projects')
export const fetchProject = (id) => request(`/projects/${id}`)
export const createProject = (data) => request('/projects', { method: 'POST', body: JSON.stringify(data) })
export const updateProject = (id, data) => request(`/projects/${id}`, { method: 'PUT', body: JSON.stringify(data) })
export const deleteProject = (id) => request(`/projects/${id}`, { method: 'DELETE' })
// token 可选：不传则后端用项目里已保存的 GitHub 凭证；传了则以请求头为准（页面上的「临时 Token」）
export const previewProjectCode = (id, token) =>
  request(`/projects/${id}/preview`, { headers: token ? { 'X-Git-Token': token } : {} })
export const previewProjectFile = (id, filePath, token) =>
  request(`/projects/${id}/preview/file?path=${encodeURIComponent(filePath)}`,
    { headers: token ? { 'X-Git-Token': token } : {} })
// 已部署项目是否有远端新提交（refresh=true 跳过后端 5 分钟缓存）
export const fetchProjectUpdateStatus = (refresh = false) =>
  request(`/projects/update-status${refresh ? '?refresh=true' : ''}`, { timeout: 60000 })
export const testProjectDbConnection = (project) =>
  request('/projects/test-db-connection', { method: 'POST', body: JSON.stringify(project) })

// ==================== Pipelines ====================
export const fetchPipelines = (projectId) => {
  const qs = projectId ? `?projectId=${projectId}` : ''
  return request(`/pipelines${qs}`)
}
export const createPipeline = (data) => request('/pipelines', { method: 'POST', body: JSON.stringify(data) })
export const updatePipeline = (id, data) => request(`/pipelines/${id}`, { method: 'PUT', body: JSON.stringify(data) })
export const deletePipeline = (id) => request(`/pipelines/${id}`, { method: 'DELETE' })

// ==================== Builds ====================
export const fetchBuilds = (projectId, status) => {
  const params = new URLSearchParams()
  if (projectId) params.set('projectId', projectId)
  if (status) params.set('status', status)
  const qs = params.toString()
  return request(`/builds${qs ? '?' + qs : ''}`)
}
export const fetchBuild = (id) => request(`/builds/${id}`)
export const fetchBuildLog = (id) => request(`/builds/${id}/log`)
export const checkDbConflict = (dbName, projectId) =>
  request(`/builds/check-db-conflict?dbName=${encodeURIComponent(dbName)}&projectId=${projectId}`)
export const triggerBuild = (projectId, pipelineId, buildParams = null, branch = null, skipDocker = false, skipK8s = false, dbName = null, deployStrategy = 'ROLLING') =>
  request(`/builds/trigger?projectId=${projectId}&pipelineId=${pipelineId}`, {
    method: 'POST',
    body: JSON.stringify({ buildParams, branch, skipDocker, skipK8s, dbName, deployStrategy })
  })
export const cancelBuild = (id) => request(`/builds/${id}/cancel`, { method: 'DELETE' })
export const deleteBuild = (id) => request(`/builds/${id}`, { method: 'DELETE' })
export const retryBuild = (id) => request(`/builds/${id}/retry`, { method: 'POST' })
export const approveStep = (id) => request(`/builds/${id}/approve-step`, { method: 'POST' })
export const rejectStep = (id, reason) => request(`/builds/${id}/reject-step`, { method: 'POST', body: JSON.stringify({ reason }) })
export const fetchPipelineVersions = (id) => request(`/pipelines/${id}/versions`)
export const fetchVariables = (projectId, pipelineId) => {
  const params = new URLSearchParams()
  if (projectId) params.set('projectId', projectId)
  if (pipelineId) params.set('pipelineId', pipelineId)
  return request(`/variables?${params}`)
}
export const createVariable = (data) => request('/variables', { method: 'POST', body: JSON.stringify(data) })
export const updateVariable = (id, data) => request(`/variables/${id}`, { method: 'PUT', body: JSON.stringify(data) })
export const deleteVariable = (id) => request(`/variables/${id}`, { method: 'DELETE' })
export const checkWorkspace = (projectId) => request(`/builds/workspace-check?projectId=${projectId}`)

// ==================== Environments ====================
export const fetchEnvironments = () => request('/environments')
export const createEnvironment = (data) => request('/environments', { method: 'POST', body: JSON.stringify(data) })
export const updateEnvironment = (id, data) => request(`/environments/${id}`, { method: 'PUT', body: JSON.stringify(data) })
export const deleteEnvironment = (id) => request(`/environments/${id}`, { method: 'DELETE' })

// ==================== Users ====================
export const fetchUsers = () => request('/users')
export const createUser = (data) => request('/users', { method: 'POST', body: JSON.stringify(data) })
export const updateUser = (id, data) => request(`/users/${id}`, { method: 'PUT', body: JSON.stringify(data) })
export const deleteUser = (id) => request(`/users/${id}`, { method: 'DELETE' })

// ==================== Webhook ====================
export const getWebhookUrl = (projectId) => `${window.location.origin}/api/webhook/${projectId}`

// ==================== Deployments ====================
// 审批人/操作人由后端从 JWT SecurityContext 取, 前端不传
export const requestDeploy = (data) => request('/deployments/request', { method: 'POST', body: JSON.stringify(data) })
export const approveDeploy = (id) =>
  request(`/deployments/approve/${id}`, { method: 'POST' })
export const rejectDeploy = (id, reason) =>
  request(`/deployments/reject/${id}`, { method: 'POST', body: JSON.stringify({ reason }) })
export const fetchDeploymentsPending = () => request('/deployments/pending')
export const fetchDeploymentRequests = (projectId) =>
  request(`/deployments/requests${projectId ? '?projectId=' + projectId : ''}`)
export const fetchDeploymentHistory = (projectId, environmentId) => {
  const params = new URLSearchParams()
  if (projectId) params.set('projectId', projectId)
  if (environmentId) params.set('environmentId', environmentId)
  return request(`/deployments/history?${params}`)
}
export const fetchRollbackCandidates = (projectId, environmentId) =>
  request(`/deployments/rollback-candidates?projectId=${projectId}&environmentId=${environmentId}`)
export const rollbackDeploy = (historyId) =>
  request(`/deployments/rollback/${historyId}`, { method: 'POST' })
export const deleteDeploymentHistory = (id) => request(`/deployments/history/${id}`, { method: 'DELETE' })
export const deleteDeploymentRequest = (id) => request(`/deployments/request/${id}`, { method: 'DELETE' })
export const promoteCanary = (projectId, fullReplicas) =>
  request(`/instances/canary-promote?projectId=${projectId}&replicas=${fullReplicas}`, { method: 'POST', timeout: 180000 })
export const rollbackCanary = (projectId) =>
  request(`/instances/canary-rollback?projectId=${projectId}`, { method: 'POST' })

// ==================== Notifications ====================
export const fetchNotifications = () => request('/notifications')
export const fetchUnreadCount = () => request('/notifications/unread-count')
export const markNotifRead = (id) => request(`/notifications/${id}/read`, { method: 'PUT' })
export const markAllNotifRead = () => request('/notifications/read-all', { method: 'PUT' })
export const deleteNotification = (id) => request(`/notifications/${id}`, { method: 'DELETE' })
export const clearAllNotifications = () => request('/notifications/clear-all', { method: 'DELETE' })

// ==================== Artifacts ====================
export const fetchArtifacts = (buildId) => request(`/artifacts?buildId=${buildId}`)
export const deleteArtifact = (id) => request(`/artifacts/${id}`, { method: 'DELETE' })

// ==================== Instances ====================
export const fetchInstances = () => request('/instances')
export const fetchInstanceStats = () => request('/instances/stats')
export const fetchProjectInstances = (projectId) => request(`/instances/project/${projectId}`)
export const updateInstanceHealth = (id, data) => request(`/instances/${id}/health`, { method: 'PUT', body: JSON.stringify(data) })
export const fetchK8sStatus = () => request('/instances/k8s-status')
export const reconnectK8s = () => request('/instances/k8s-reconnect', { method: 'POST' })
export const fetchK8sNamespaces = () => request('/instances/k8s-namespaces')
export const restartInstance = (id) => request(`/instances/${id}/restart`, { method: 'POST' })
export const stopInstance = (id) => request(`/instances/${id}/stop`, { method: 'POST' })
export const startInstance = (id) => request(`/instances/${id}/start`, { method: 'POST' })
export const deleteInstance = (id) => request(`/instances/${id}`, { method: 'DELETE' })
export const getAccessInfo = (id) => request(`/instances/${id}/access-info`)
export const exposeToExternal = (id) => request(`/instances/${id}/expose`, { method: 'POST' })
export const stopForward = (id) => request(`/instances/${id}/stop-forward`, { method: 'POST' })
export const getInstanceLogs = (id, tail = 200, container = null) => {
  let url = `/instances/${id}/logs?tail=${tail}`
  if (container) url += `&container=${encodeURIComponent(container)}`
  return request(url)
}
export const getInstanceContainers = (id) => request(`/instances/${id}/containers`)
export const fetchAvailability = () => request('/instances/availability')
export const fetchStatsByType = () => request('/instances/stats-by-type')
export const fetchDockerContainers = () => request('/instances/docker-containers')
export const fetchK8sDeployments = (namespace = 'devops') => request(`/instances/k8s/deployments?namespace=${namespace}`)
export const getK8sDeployment = (name, namespace = 'devops') => request(`/instances/k8s/deployments/${name}?namespace=${namespace}`)
export const deleteK8sDeployment = (name, namespace = 'devops') => request(`/instances/k8s/deployments/${name}?namespace=${namespace}`, { method: 'DELETE' })

// ==================== Templates ====================
export const fetchTemplates = (type) => request(`/templates${type ? '?type=' + type : ''}`)
export const fetchBuiltinTemplates = () => request('/templates/builtin')
export const searchTemplates = (keyword) => request(`/templates/search?keyword=${encodeURIComponent(keyword)}`)
export const createTemplate = (data) => request('/templates', { method: 'POST', body: JSON.stringify(data) })
export const updateTemplate = (id, data) => request(`/templates/${id}`, { method: 'PUT', body: JSON.stringify(data) })
export const deleteTemplate = (id) => request(`/templates/${id}`, { method: 'DELETE' })

// ==================== Audit ====================
export const fetchAuditLogs = (page = 0, size = 50) => request(`/audit?page=${page}&size=${size}`)
export const fetchAuditByUser = (username) => request(`/audit/user/${username}`)
export const fetchAuditByAction = (action) => request(`/audit/action/${action}`)
export const fetchAuditByResource = (resource, resourceId) => request(`/audit/resource/${resource}/${resourceId}`)

// ==================== Scheduler ====================
export const fetchSchedules = () => request('/scheduler')
export const updatePipelineCron = (pipelineId, cronExpression, cronEnabled) =>
  request(`/scheduler/pipeline/${pipelineId}`, {
    method: 'PUT',
    body: JSON.stringify({ cronExpression, cronEnabled })
  })

// ==================== Security (DevSecOps) ====================
export const fetchTrivyAvailable = () => request('/security/trivy-available')
export const fetchVulnDbStatus = () => request('/security/db-status')
export const updateVulnDb = (includeJavaDb = true) =>
  request('/security/db-update', {
    method: 'POST',
    body: JSON.stringify({ includeJavaDb }),
    timeout: 900000  // 漏洞库下载量大, 最长等 15 分钟
  })
export const fetchSecurityReportsByProject = (projectId) => request(`/security/reports/project/${projectId}`)
export const fetchSecurityReportsByBuild = (buildId) => request(`/security/reports/build/${buildId}`)
export const fetchSecurityReport = (id) => request(`/security/reports/${id}`)
export const fetchSecurityVulnsByReport = (reportId) => request(`/security/vulnerabilities/${reportId}`)
export const fetchSecurityVulnsByProject = (projectId) => request(`/security/vulnerabilities/project/${projectId}`)
export const updateSecurityVulnStatus = (id, status, note = '') =>
  request(`/security/vulnerabilities/${id}/status`, { method: 'PUT', body: JSON.stringify({ status, note }) })
export const fetchSecurityStats = (projectId) => request(`/security/stats/project/${projectId}`)
export const triggerSecurityScan = (projectId, scanType, thresholds = {}) =>
  request(`/security/scan/${scanType}/${projectId}`, {
    method: 'POST',
    body: JSON.stringify(thresholds),
    timeout: 600000  // Trivy 首次扫描需下载 DB，最长等 10 分钟
  })

// ==================== APM 性能指标 ====================
export const fetchMetricsRealtime = () => request('/metrics/realtime')
export const fetchMetricsTimeseries = (minutes = 60) => request(`/metrics/timeseries?minutes=${minutes}`)
export const fetchMetricsSlowPaths = (limit = 10, minutes = 60) =>
  request(`/metrics/slow-paths?limit=${limit}&minutes=${minutes}`)
export const fetchMetricsErrorPaths = (limit = 10, minutes = 60) =>
  request(`/metrics/error-paths?limit=${limit}&minutes=${minutes}`)

// ==================== Artifact Version ====================
export const fetchArtifactVersionsByProject = (projectId) => request(`/artifacts/versions/project/${projectId}`)
export const fetchArtifactVersionsByArtifact = (artifactId) => request(`/artifacts/${artifactId}/versions`)
export const verifyArtifactVersion = (versionId) => request(`/artifacts/versions/${versionId}/verify`)
export const markArtifactVersionDeployed = (versionId) =>
  request(`/artifacts/versions/${versionId}/deployed`, { method: 'POST' })
export const deleteArtifactVersion = (versionId) => request(`/artifacts/versions/${versionId}`, { method: 'DELETE' })

// ==================== Environment Templates (IaC) ====================
export const fetchEnvTemplates = () => request('/environments/templates')
export const fetchBuiltinEnvTemplates = () => request('/environments/templates/builtin')
export const cloneEnvFromTemplate = (templateId, projectCode, projectId, nameOverride) =>
  request('/environments/clone-from-template', {
    method: 'POST',
    body: JSON.stringify({ templateId, projectCode, projectId, nameOverride })
  })
export const cloneEnvironment = (id, name) =>
  request(`/environments/${id}/clone`, { method: 'POST', body: JSON.stringify({ name }) })
export const fetchEnvNamespaceYaml = (id) => request(`/environments/${id}/yaml/namespace`)
export const fetchEnvQuotaYaml = (id) => request(`/environments/${id}/yaml/quota`)

// ==================== Webhook Subscriptions ====================
export const fetchWebhooks = () => request('/webhooks/subscriptions')
export const fetchActiveWebhooks = () => request('/webhooks/subscriptions/active')
export const createWebhook = (sub) => request('/webhooks/subscriptions', { method: 'POST', body: JSON.stringify(sub) })
export const updateWebhook = (id, sub) => request(`/webhooks/subscriptions/${id}`, { method: 'PUT', body: JSON.stringify(sub) })
export const deleteWebhook = (id) => request(`/webhooks/subscriptions/${id}`, { method: 'DELETE' })
export const toggleWebhook = (id) => request(`/webhooks/subscriptions/${id}/toggle`, { method: 'POST' })

// ==================== Project Members ====================
export const fetchProjectMembers = (projectId) => request(`/projects/${projectId}/members`)
export const addProjectMember = (projectId, username, role) =>
  request(`/projects/${projectId}/members`, { method: 'POST', body: JSON.stringify({ username, role }) })
export const updateProjectMember = (projectId, memberId, role) =>
  request(`/projects/${projectId}/members/${memberId}`, { method: 'PUT', body: JSON.stringify({ role }) })
export const removeProjectMember = (projectId, memberId) =>
  request(`/projects/${projectId}/members/${memberId}`, { method: 'DELETE' })
export const fetchProjectMyRole = (projectId) => request(`/projects/${projectId}/members/my-role`)
export const testWebhook = (id) => request(`/webhooks/subscriptions/${id}/test`, { method: 'POST' })

// ==================== Test Reports ====================
export const fetchTestReportsByBuild = (buildId) => request(`/test-reports/build/${buildId}`)
export const fetchTestReportsByProject = (projectId) => request(`/test-reports/project/${projectId}`)
export const fetchTestReport = (id) => request(`/test-reports/${id}`)
export const fetchProjectAvgCoverage = (projectId) => request(`/test-reports/project/${projectId}/avg-coverage`)

// ==================== Git Integration ====================
export const fetchGitRepoInfo = (projectId, token) =>
  request(`/git/${projectId}/info`, { headers: token ? { 'X-Git-Token': token } : {} })
export const fetchGitBranches = (projectId, token) =>
  request(`/git/${projectId}/branches`, { headers: token ? { 'X-Git-Token': token } : {} })
export const fetchGitCommits = (projectId, branch, limit, token) =>
  request(`/git/${projectId}/commits?branch=${encodeURIComponent(branch)}&limit=${limit || 20}`,
    { headers: token ? { 'X-Git-Token': token } : {} })
export const fetchGitMRs = (projectId, state, token) =>
  request(`/git/${projectId}/merge-requests?state=${state || 'open'}`,
    { headers: token ? { 'X-Git-Token': token } : {} })
export const createGitMR = (projectId, body, token) =>
  request(`/git/${projectId}/merge-requests`, {
    method: 'POST',
    body: JSON.stringify(body),
    headers: token ? { 'X-Git-Token': token, 'Content-Type': 'application/json' } : {}
  })

// ==================== GitHub Actions / Release ====================
export const fetchGitConfig = (projectId) => request(`/git/${projectId}/config`)
export const saveGitConfig = (projectId, body) =>
  request(`/git/${projectId}/config`, { method: 'PUT', body: JSON.stringify(body) })
export const dispatchGitWorkflow = (projectId, body = {}) =>
  request(`/git/${projectId}/actions/dispatch`, { method: 'POST', body: JSON.stringify(body) })
export const fetchGitRuns = (projectId, limit = 10) =>
  request(`/git/${projectId}/actions/runs?limit=${limit}`)
export const fetchGitRunJobs = (projectId, runId) =>
  request(`/git/${projectId}/actions/runs/${runId}/jobs`)
export const createGitTag = (projectId, body) =>
  request(`/git/${projectId}/tags`, { method: 'POST', body: JSON.stringify(body) })

// ==================== Advanced Capabilities ====================
export const fetchAdvancedOverview = () => request('/advanced/overview')
export const fetchAdvancedList = (section, queryString = '') =>
  request(`/advanced/${section}${queryString}`)
export const createAdvancedItem = (section, body) =>
  request(`/advanced/${section}`, { method: 'POST', body: JSON.stringify(body) })
export const deleteAdvancedItem = (section, id) =>
  request(`/advanced/${section}/${id}`, { method: 'DELETE' })
export const fetchApi = (url, options) => request(url, options)

// ==================== SAST ====================
export const fetchSastSemgrepAvailable = () => request('/advanced/sast/semgrep-available')
export const triggerSastScan = (body) =>
  request('/advanced/sast/scan', { method: 'POST', body: JSON.stringify(body) })
export const fetchSastFindings = (reportId) => request(`/advanced/sast/${reportId}/findings`)

// ==================== 工具链（语言能力 / 本机检测 / 下载） ====================
/** 语言与工具元数据：框架、默认构建命令、可用能力、所需工具 */
export const fetchToolchainMeta = () => request('/toolchain/meta')
/** 指定语言的工具链检测状态（不传 language 返回全部语言） */
export const fetchToolchainStatus = (language) =>
  request(`/toolchain/status${language ? `?language=${encodeURIComponent(language)}` : ''}`)
/** 清空检测缓存后重新检测 */
export const resetToolchainCache = () => request('/toolchain/reset', { method: 'POST' })
/** 启动某个工具的下载安装（异步，返回任务对象） */
export const startToolchainDownload = (tool) =>
  request('/toolchain/download', { method: 'POST', body: JSON.stringify({ tool }) })
/** 查询下载任务进度 */
export const fetchToolchainTask = (taskId) =>
  request(`/toolchain/download/${taskId}`, { timeout: 10000 })
