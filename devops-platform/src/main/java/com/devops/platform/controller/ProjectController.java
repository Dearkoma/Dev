package com.devops.platform.controller;
import com.devops.platform.entity.Project;
import com.devops.platform.service.CodePreviewService;
import com.devops.platform.service.ProjectAccessService;
import com.devops.platform.service.ProjectService;
import com.devops.platform.service.ProjectUpdateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import com.devops.platform.aspect.AuditAspect;
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {
    private final ProjectService projectService;
    private final CodePreviewService codePreviewService;
    private final ProjectAccessService accessService;
    private final ProjectUpdateService projectUpdateService;
    @GetMapping
    public List<Project> getAll() {
        return accessService.filterByAccessibleProjects(projectService.getAllProjects(), Project::getId);
    }
    @GetMapping("/{id}")
    public ResponseEntity<Project> getOne(@PathVariable Long id) {
        accessService.assertCanAccessProject(id);
        Project p = projectService.getProject(id);
        return p == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(p);
    }
    @AuditAspect.Audit(action = "CREATE", resource = "PROJECT")
    @PostMapping
    public Project create(@RequestBody Project project) { return projectService.createProject(project); }
    @AuditAspect.Audit(action = "UPDATE", resource = "PROJECT")
    @PutMapping("/{id}")
    public ResponseEntity<Project> update(@PathVariable Long id, @RequestBody Project project) {
        accessService.assertCanAccessProject(id);
        Project p = projectService.updateProject(id, project);
        return p == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(p);
    }
    @AuditAspect.Audit(action = "DELETE", resource = "PROJECT")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        accessService.assertCanAccessProject(id);
        projectService.deleteProject(id);
        return ResponseEntity.ok().build();
    }
    @GetMapping("/by-status")
    public List<Project> getByStatus(@RequestParam String status) {
        return accessService.filterByAccessibleProjects(projectService.findByStatus(status), Project::getId);
    }

    /**
     * 各项目「远端仓库有更新」状态 —— 已部署的项目，其部署的 commit 与远端最新 commit 不一致时为 true。
     * 只返回当前用户有权限访问的项目；默认走 5 分钟缓存，refresh=true 强制重查（手动「检查更新」）。
     */
    @GetMapping("/update-status")
    public List<Map<String, Object>> updateStatus(@RequestParam(defaultValue = "false") boolean refresh) {
        List<Project> accessible = accessService.filterByAccessibleProjects(projectService.getAllProjects(), Project::getId);
        return projectUpdateService.checkAll(accessible, refresh);
    }

    /**
     * 仓库文件树。公开仓库无需凭证；私有仓库自动使用项目里加密保存的 GitHub 凭证
     * （请求头 X-Git-Token 可临时覆盖，与 /api/git 系列接口一致）。
     */
    @GetMapping("/{id}/preview")
    public ResponseEntity<Map<String, Object>> previewCode(@PathVariable Long id,
                                                           @RequestHeader(value = "X-Git-Token", required = false) String headerToken) {
        accessService.assertCanAccessProject(id);
        return ResponseEntity.ok(codePreviewService.getFileTree(id, headerToken));
    }

    /** 单个文件内容（contents API + base64 解码）；凭证规则同上 */
    @GetMapping("/{id}/preview/file")
    public ResponseEntity<Map<String, Object>> previewFile(@PathVariable Long id,
                                                           @RequestParam String path,
                                                           @RequestHeader(value = "X-Git-Token", required = false) String headerToken) {
        accessService.assertCanAccessProject(id);
        return ResponseEntity.ok(codePreviewService.getFileContent(id, path, headerToken));
    }

    /**
     * 测试 MySQL 连接 — 前端填完数据库信息后点"测试连接"调用
     * 不写库,只返回连接结果
     */
    @PostMapping("/test-db-connection")
    public ResponseEntity<Map<String, Object>> testDbConnection(@RequestBody Project project) {
        return ResponseEntity.ok(projectService.testDbConnection(project));
    }
}
