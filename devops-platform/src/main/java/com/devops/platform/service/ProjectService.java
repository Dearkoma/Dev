package com.devops.platform.service;
import com.devops.platform.entity.Project;
import com.devops.platform.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
@Service
@RequiredArgsConstructor
public class ProjectService {
    private final ProjectRepository projectRepository;
    public List<Project> getAllProjects() { return projectRepository.findAll(); }
    public Project getProject(Long id) { return projectRepository.findById(id).orElse(null); }
    public Project createProject(Project project) {
        if (project.getCode() == null || project.getCode().isEmpty()) {
            project.setCode("proj-" + System.currentTimeMillis());
        }
        // code 列有唯一约束，重复时 save 会裸抛 DataIntegrityViolationException → 前端只见 500，
        // 这里前置校验给出可读信息（GlobalExceptionHandler 统一转 400）
        if (projectRepository.existsByCode(project.getCode())) {
            throw new IllegalArgumentException("项目编码「" + project.getCode() + "」已存在，请换一个编码");
        }
        project.setCapabilities(ToolchainService.normalizeCapabilities(project.getCapabilities()));
        return projectRepository.save(project);
    }
    public Project updateProject(Long id, Project project) {
        Project existing = getProject(id);
        if (existing == null) return null;
        // 编辑同样可能把 code 改成与别的项目重复（排除自身）
        if (project.getCode() != null && !project.getCode().isEmpty()
                && !project.getCode().equals(existing.getCode())
                && projectRepository.existsByCodeAndIdNot(project.getCode(), id)) {
            throw new IllegalArgumentException("项目编码「" + project.getCode() + "」已被其他项目使用，请换一个编码");
        }
        existing.setName(project.getName());
        existing.setCode(project.getCode());
        existing.setDescription(project.getDescription());
        existing.setGitUrl(project.getGitUrl());
        existing.setGitBranch(project.getGitBranch());
        existing.setBuildCommand(project.getBuildCommand());
        existing.setStartCommand(project.getStartCommand());
        existing.setLanguage(project.getLanguage());
        existing.setFramework(project.getFramework());
        // 构建能力:决定默认流水线包含哪些阶段 —— 少同步这一行会导致前端改了能力保存后不生效
        existing.setCapabilities(ToolchainService.normalizeCapabilities(project.getCapabilities()));
        // 数据库配置：必须显式同步,否则前端改了 MYSQL 字段后保存不生效
        existing.setDbType(project.getDbType());
        existing.setDbHost(project.getDbHost());
        existing.setDbPort(project.getDbPort());
        existing.setDbUsername(project.getDbUsername());
        existing.setDbPassword(project.getDbPassword());
        // Dockerfile 配置:同样可能漏
        existing.setDockerfilePath(project.getDockerfilePath());
        existing.setDockerfileContent(project.getDockerfileContent());
        existing.setEnabled(project.getEnabled());
        return projectRepository.save(existing);
    }

    /**
     * 测试 MySQL 连接
     * 用于前端填完连接信息后,点击"测试连接"立即验证
     * 不会修改任何项目数据,只返回连接结果
     */
    public Map<String, Object> testDbConnection(Project project) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (project == null) {
            result.put("success", false);
            result.put("message", "项目为空");
            return result;
        }
        if (!"MYSQL".equalsIgnoreCase(project.getDbType())) {
            result.put("success", false);
            result.put("message", "项目数据库类型不是 MYSQL，当前是: " + project.getDbType());
            return result;
        }
        String host = project.getDbHost();
        Integer port = project.getDbPort();
        String user = project.getDbUsername();
        String pass = project.getDbPassword();
        if (host == null || host.isBlank() || port == null || port <= 0) {
            result.put("success", false);
            result.put("message", "host 或 port 缺失");
            return result;
        }
        result.put("host", host);
        result.put("port", port);
        result.put("username", user != null ? user : "");

        long start = System.currentTimeMillis();
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(
                "jdbc:mysql://" + host + ":" + port
                        + "/?useUnicode=true&characterEncoding=utf-8&useSSL=false"
                        + "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&connectTimeout=5000",
                user, pass)) {
            boolean valid = conn.isValid(3);
            long cost = System.currentTimeMillis() - start;
            // 顺手列出可见的数据库,帮用户确认
            java.util.List<String> dbs = new java.util.ArrayList<>();
            try (java.sql.Statement st = conn.createStatement();
                 java.sql.ResultSet rs = st.executeQuery("SHOW DATABASES")) {
                while (rs.next()) dbs.add(rs.getString(1));
            }
            result.put("success", valid);
            result.put("message", valid ? "✅ 连接成功" : "连接返回 invalid");
            result.put("costMs", cost);
            result.put("databases", dbs);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "❌ 连接失败: " + e.getMessage());
            result.put("costMs", System.currentTimeMillis() - start);
            Throwable cause = e.getCause();
            if (cause != null) result.put("cause", cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
        return result;
    }
    public void deleteProject(Long id) { projectRepository.deleteById(id); }
    public List<Project> findByStatus(String status) { return projectRepository.findByStatus(status); }
}
