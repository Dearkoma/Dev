package com.devops.platform.controller;
import com.devops.platform.entity.Environment;
import com.devops.platform.entity.EnvironmentTemplate;
import com.devops.platform.service.EnvironmentService;
import com.devops.platform.service.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import com.devops.platform.aspect.AuditAspect;
@RestController
@RequestMapping("/api/environments")
@RequiredArgsConstructor
public class EnvironmentController {
    private final EnvironmentService environmentService;
    private final ProjectAccessService accessService;
    @GetMapping
    public List<Environment> getAll() {
        return accessService.filterByAccessibleProjects(environmentService.getAllEnvironments(), Environment::getProjectId);
    }
    @GetMapping("/{id}")
    public ResponseEntity<Environment> getOne(@PathVariable Long id) {
        accessService.assertCanAccessEnvironment(id);
        Environment e = environmentService.getEnvironment(id);
        return e == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(e);
    }
    @AuditAspect.Audit(action = "CREATE", resource = "ENVIRONMENT")
    @PostMapping
    public Environment create(@RequestBody Environment env) {
        if (env.getProjectId() != null) {
            accessService.assertCanAccessProject(env.getProjectId());
        }
        return environmentService.createEnvironment(env);
    }
    @AuditAspect.Audit(action = "UPDATE", resource = "ENVIRONMENT")
    @PutMapping("/{id}")
    public ResponseEntity<Environment> update(@PathVariable Long id, @RequestBody Environment env) {
        accessService.assertCanAccessEnvironment(id);
        Environment e = environmentService.updateEnvironment(id, env);
        return e == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(e);
    }
    @AuditAspect.Audit(action = "DELETE", resource = "ENVIRONMENT")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        accessService.assertCanAccessEnvironment(id);
        environmentService.deleteEnvironment(id);
        return ResponseEntity.ok().build();
    }

    // ==================== IaC 端点 ====================

    @GetMapping("/templates")
    public List<EnvironmentTemplate> listTemplates() { return environmentService.listAllTemplates(); }

    @GetMapping("/templates/builtin")
    public List<EnvironmentTemplate> listBuiltinTemplates() { return environmentService.listBuiltinTemplates(); }

    /** 从模板创建环境 */
    @PostMapping("/clone-from-template")
    public Environment cloneFromTemplate(@RequestBody Map<String, Object> body) {
        Long templateId = ((Number) body.get("templateId")).longValue();
        String projectCode = (String) body.get("projectCode");
        Long projectId = body.get("projectId") != null ? ((Number) body.get("projectId")).longValue() : null;
        String nameOverride = (String) body.get("nameOverride");
        if (projectId != null) {
            accessService.assertCanAccessProject(projectId);
        }
        return environmentService.cloneFromTemplate(templateId, projectCode, projectId, nameOverride);
    }

    /** 克隆现有环境 */
    @PostMapping("/{id}/clone")
    public Environment cloneEnvironment(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        accessService.assertCanAccessEnvironment(id);
        String newName = body != null ? (String) body.get("name") : null;
        return environmentService.cloneEnvironment(id, newName);
    }

    /** 生成 K8s namespace YAML */
    @GetMapping("/{id}/yaml/namespace")
    public ResponseEntity<Map<String, String>> genNamespaceYaml(@PathVariable Long id) {
        accessService.assertCanAccessEnvironment(id);
        Environment env = environmentService.getEnvironment(id);
        if (env == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("yaml", environmentService.generateK8sNamespaceYaml(env)));
    }

    /** 生成 K8s ResourceQuota YAML */
    @GetMapping("/{id}/yaml/quota")
    public ResponseEntity<Map<String, String>> genQuotaYaml(@PathVariable Long id) {
        accessService.assertCanAccessEnvironment(id);
        Environment env = environmentService.getEnvironment(id);
        if (env == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("yaml", environmentService.generateK8sResourceQuotaYaml(env)));
    }
}
