package com.devops.platform.service;

import com.devops.platform.entity.Environment;
import com.devops.platform.entity.EnvironmentTemplate;
import com.devops.platform.repository.EnvironmentRepository;
import com.devops.platform.repository.EnvironmentTemplateRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 环境管理 + IaC 服务
 * <p>
 *  - 启动时自动建 4 套内置模板 (dev/test/staging/prod)
 *  - cloneFromTemplate: 模板 → 环境
 *  - cloneEnvironment: 克隆现有环境
 *  - 模板字段替换: {projectCode} → apartment
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnvironmentService {

    private final EnvironmentRepository environmentRepository;
    private final EnvironmentTemplateRepository templateRepository;

    @PostConstruct
    @Transactional
    public void initBuiltinTemplates() {
        if (templateRepository.findByBuiltinTrueOrderById().size() >= 4) return;
        ensureBuiltinTemplate("标准开发环境", EnvironmentTemplate.TYPE_DEV,
                "{projectCode}-dev", "100m", "500m", "256Mi", "1Gi",
                1, false, "#3B6D11", "{projectCode}-dev.example.com", 1);
        ensureBuiltinTemplate("标准测试环境", EnvironmentTemplate.TYPE_TEST,
                "{projectCode}-test", "200m", "1", "512Mi", "2Gi",
                1, false, "#BA7517", "{projectCode}-test.example.com", 2);
        ensureBuiltinTemplate("标准预发布", EnvironmentTemplate.TYPE_STAGING,
                "{projectCode}-staging", "300m", "1.5", "768Mi", "3Gi",
                2, false, "#534AB7", "{projectCode}-staging.example.com", 3);
        ensureBuiltinTemplate("生产环境", EnvironmentTemplate.TYPE_PROD,
                "{projectCode}-prod", "500m", "2", "1Gi", "4Gi",
                3, true, "#A32D2D", "{projectCode}.example.com", 4);
        log.info("[EnvTemplate] 内置模板初始化完成");
    }

    // ==================== 标准 CRUD (兼容旧 API) ====================

    public List<Environment> getAllEnvironments() {
        return environmentRepository.findAll();
    }

    public Environment getEnvironment(Long id) {
        return environmentRepository.findById(id).orElse(null);
    }

    public Environment createEnvironment(Environment env) {
        return environmentRepository.save(env);
    }

    public Environment updateEnvironment(Long id, Environment env) {
        Environment existing = getEnvironment(id);
        if (existing == null) return null;
        existing.setName(env.getName());
        existing.setDisplayName(env.getDisplayName());
        existing.setDescription(env.getDescription());
        existing.setStatus(env.getStatus());
        existing.setDeployUrl(env.getDeployUrl());
        // 新增字段
        existing.setProjectId(env.getProjectId());
        existing.setK8sCluster(env.getK8sCluster());
        existing.setK8sNamespace(env.getK8sNamespace());
        existing.setCpuRequest(env.getCpuRequest());
        existing.setCpuLimit(env.getCpuLimit());
        existing.setMemoryRequest(env.getMemoryRequest());
        existing.setMemoryLimit(env.getMemoryLimit());
        existing.setReplicas(env.getReplicas());
        existing.setIngressHost(env.getIngressHost());
        existing.setTemplateId(env.getTemplateId());
        existing.setColor(env.getColor());
        existing.setSortOrder(env.getSortOrder());
        existing.setProtectedEnv(env.getProtectedEnv());
        return environmentRepository.save(existing);
    }

    public void deleteEnvironment(Long id) {
        environmentRepository.deleteById(id);
    }

    public Environment findByName(String name) {
        return environmentRepository.findByName(name);
    }

    private void ensureBuiltinTemplate(String name, String type, String nsPattern,
                                        String cpuReq, String cpuLim,
                                        String memReq, String memLim,
                                        Integer replicas, boolean protectedEnv,
                                        String color, String ingressPattern, int sortOrder) {
        if (templateRepository.findFirstByTemplateType(type).isPresent()) return;
        EnvironmentTemplate t = new EnvironmentTemplate();
        t.setName(name);
        t.setTemplateType(type);
        t.setK8sNamespacePattern(nsPattern);
        t.setCpuRequest(cpuReq);
        t.setCpuLimit(cpuLim);
        t.setMemoryRequest(memReq);
        t.setMemoryLimit(memLim);
        t.setReplicas(replicas);
        t.setProtectedEnv(protectedEnv);
        t.setColor(color);
        t.setIngressHostPattern(ingressPattern);
        t.setBuiltin(true);
        t.setSortOrder(sortOrder);
        templateRepository.save(t);
    }

    /** 列出所有模板 (内置 + 自定义) */
    public List<EnvironmentTemplate> listAllTemplates() {
        return templateRepository.findAll();
    }

    public List<EnvironmentTemplate> listBuiltinTemplates() {
        return templateRepository.findByBuiltinTrueOrderById();
    }

    /**
     * 从模板创建环境
     * @param templateId 模板 ID
     * @param projectCode 项目编码 (用于 {projectCode} 占位符替换)
     * @param projectId 项目 ID (可空, 全局环境)
     * @param nameOverride 自定义环境名 (空 = 模板名)
     */
    @Transactional
    public Environment cloneFromTemplate(Long templateId, String projectCode, Long projectId, String nameOverride) {
        EnvironmentTemplate t = templateRepository.findById(templateId)
                .orElseThrow(() -> new RuntimeException("模板不存在: " + templateId));
        Environment env = new Environment();
        env.setName(nameOverride != null && !nameOverride.isBlank()
                ? nameOverride
                : t.getName() + " - " + projectCode);
        env.setDisplayName(env.getName());
        env.setK8sNamespace(replacePlaceholder(t.getK8sNamespacePattern(), projectCode));
        env.setK8sCluster(t.getK8sCluster());
        env.setCpuRequest(t.getCpuRequest());
        env.setCpuLimit(t.getCpuLimit());
        env.setMemoryRequest(t.getMemoryRequest());
        env.setMemoryLimit(t.getMemoryLimit());
        env.setReplicas(t.getReplicas());
        env.setProtectedEnv(t.getProtectedEnv());
        env.setColor(t.getColor());
        env.setTemplateId(t.getId());
        env.setIngressHost(replacePlaceholder(t.getIngressHostPattern(), projectCode));
        env.setProjectId(projectId);
        env.setStatus("ACTIVE");
        return environmentRepository.save(env);
    }

    /**
     * 克隆现有环境 (复制所有 IaC 字段)
     */
    @Transactional
    public Environment cloneEnvironment(Long sourceEnvId, String newName) {
        Environment src = environmentRepository.findById(sourceEnvId)
                .orElseThrow(() -> new RuntimeException("源环境不存在: " + sourceEnvId));
        Environment env = new Environment();
        env.setName(newName != null && !newName.isBlank() ? newName : src.getName() + " (副本)");
        env.setDisplayName(env.getName());
        env.setDescription(src.getDescription());
        env.setK8sNamespace(src.getK8sNamespace() + "-copy");
        env.setK8sCluster(src.getK8sCluster());
        env.setCpuRequest(src.getCpuRequest());
        env.setCpuLimit(src.getCpuLimit());
        env.setMemoryRequest(src.getMemoryRequest());
        env.setMemoryLimit(src.getMemoryLimit());
        env.setReplicas(src.getReplicas());
        env.setProtectedEnv(src.getProtectedEnv());
        env.setColor(src.getColor());
        env.setTemplateId(src.getTemplateId());
        env.setIngressHost(src.getIngressHost() != null ? src.getIngressHost() + "-copy" : null);
        env.setProjectId(src.getProjectId());
        env.setStatus("ACTIVE");
        return environmentRepository.save(env);
    }

    /**
     * {projectCode} 占位符替换
     */
    private String replacePlaceholder(String pattern, String projectCode) {
        if (pattern == null) return null;
        return pattern.replace("{projectCode}", projectCode == null ? "app" : projectCode.toLowerCase());
    }

    /**
     * 生成 K8s namespace YAML 片段
     * 实际部署时 kubectl apply 用
     */
    public String generateK8sNamespaceYaml(Environment env) {
        if (env.getK8sNamespace() == null) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("apiVersion: v1\n");
        sb.append("kind: Namespace\n");
        sb.append("metadata:\n");
        sb.append("  name: ").append(env.getK8sNamespace()).append("\n");
        sb.append("  labels:\n");
        sb.append("    env: ").append(env.getName()).append("\n");
        if (Boolean.TRUE.equals(env.getProtectedEnv())) {
            sb.append("    protected: \"true\"\n");
        }
        if (env.getColor() != null) {
            sb.append("    color: \"").append(env.getColor()).append("\"\n");
        }
        return sb.toString();
    }

    /**
     * 生成 K8s ResourceQuota YAML 片段
     */
    public String generateK8sResourceQuotaYaml(Environment env) {
        if (env.getK8sNamespace() == null) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("apiVersion: v1\n");
        sb.append("kind: ResourceQuota\n");
        sb.append("metadata:\n");
        sb.append("  name: ").append(env.getK8sNamespace()).append("-quota\n");
        sb.append("  namespace: ").append(env.getK8sNamespace()).append("\n");
        sb.append("spec:\n");
        sb.append("  hard:\n");
        if (env.getCpuLimit() != null) sb.append("    requests.cpu: \"").append(env.getCpuLimit()).append("\"\n");
        if (env.getMemoryLimit() != null) sb.append("    requests.memory: \"").append(env.getMemoryLimit()).append("\"\n");
        if (env.getCpuLimit() != null) sb.append("    limits.cpu: \"").append(env.getCpuLimit()).append("\"\n");
        if (env.getMemoryLimit() != null) sb.append("    limits.memory: \"").append(env.getMemoryLimit()).append("\"\n");
        return sb.toString();
    }
}
