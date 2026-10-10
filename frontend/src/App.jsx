import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { AuthProvider } from './context/AuthContext'
import ProtectedRoute from './components/ProtectedRoute'
import AppLayout from './components/AppLayout'
import Login from './components/Login'
import Register from './components/Register'
import Dashboard from './components/Dashboard'
import ProjectList from './components/ProjectList'
import PipelineList from './components/PipelineList'
import BuildList from './components/BuildList'
import DeploymentList from './components/DeploymentList'
import EnvironmentList from './components/EnvironmentList'
import InstanceList from './components/InstanceList'
import TemplateList from './components/TemplateList'
import AuditLogList from './components/AuditLogList'
import NotificationCenter from './components/NotificationCenter'
import UserManagement from './components/UserManagement'
import SecurityPanel from './components/SecurityPanel'
import MetricsPanel from './components/MetricsPanel'
import ArtifactVersionPanel from './components/ArtifactVersionPanel'
import IacEnvironmentPanel from './components/IacEnvironmentPanel'
import WebhookPanel from './components/WebhookPanel'
import TestReportPanel from './components/TestReportPanel'
import GitPanel from './components/GitPanel'
import AdvancedPanel from './components/AdvancedPanel'
import './App.css'

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route path="/register" element={<Register />} />

          <Route path="/" element={<ProtectedRoute><AppLayout /></ProtectedRoute>}>
            <Route index element={<Navigate to="/dashboard" replace />} />
            <Route path="dashboard" element={<Dashboard />} />
            <Route path="projects" element={<ProjectList />} />
            <Route path="projects/:projectId/pipelines" element={<PipelineList />} />
            <Route path="projects/:projectId/security" element={<SecurityPanel />} />
            <Route path="projects/:projectId/artifacts" element={<ArtifactVersionPanel />} />
            <Route path="projects/:projectId/tests" element={<TestReportPanel />} />
            <Route path="projects/:projectId/git" element={<GitPanel />} />
            <Route path="advanced" element={<AdvancedPanel />} />
            <Route path="security" element={<SecurityPanel />} />
            <Route path="artifacts" element={<ArtifactVersionPanel />} />
            <Route path="iac" element={<IacEnvironmentPanel />} />
            <Route path="webhooks" element={<WebhookPanel />} />
            <Route path="metrics" element={<MetricsPanel />} />
            <Route path="pipelines" element={<PipelineList />} />
            <Route path="builds" element={<BuildList />} />
            <Route path="deployments" element={<DeploymentList />} />
            <Route path="environments" element={<EnvironmentList />} />
            <Route path="instances" element={<InstanceList />} />
            <Route path="instances/docker" element={<InstanceList />} />
            <Route path="instances/k8s" element={<InstanceList />} />
            <Route path="templates" element={<TemplateList />} />
            <Route path="audit" element={<ProtectedRoute requireAdmin><AuditLogList /></ProtectedRoute>} />
            <Route path="notifications" element={<NotificationCenter />} />
            <Route path="users" element={<ProtectedRoute requireAdmin><UserManagement /></ProtectedRoute>} />
          </Route>

          <Route path="*" element={<Navigate to="/dashboard" replace />} />
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  )
}
