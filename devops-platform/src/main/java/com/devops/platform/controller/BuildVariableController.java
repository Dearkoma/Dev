package com.devops.platform.controller;

import com.devops.platform.entity.BuildVariable;
import com.devops.platform.entity.Pipeline;
import com.devops.platform.repository.PipelineRepository;
import com.devops.platform.service.BuildVariableService;
import com.devops.platform.service.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/variables")
@RequiredArgsConstructor
public class BuildVariableController {

    private final BuildVariableService variableService;
    private final ProjectAccessService accessService;
    private final PipelineRepository pipelineRepository;

    /** 获取项目的所有变量（项目级 + 流水线级）。projectId 必填，且必须是当前用户可访问的项目 */
    @GetMapping
    public List<BuildVariable> list(
            @RequestParam Long projectId,
            @RequestParam(required = false) Long pipelineId) {
        accessService.assertCanAccessProject(projectId);
        if (pipelineId != null) {
            // 项目级 + 指定流水线级（OR 查询，两参数都非空时不存在跨项目泄露）
            return variableService.getVariables(projectId, pipelineId);
        }
        return variableService.getVariablesByProject(projectId);
    }

    /** 解析后的最终变量表（key=value） */
    @GetMapping("/resolved")
    public Map<String, String> resolved(
            @RequestParam Long projectId,
            @RequestParam(required = false) Long pipelineId) {
        accessService.assertCanAccessProject(projectId);
        return variableService.resolveAll(projectId, pipelineId);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody BuildVariable variable) {
        try {
            Long pid = resolveProjectId(variable);
            if (pid == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "必须提供 projectId（或可由 pipelineId 推导）"));
            }
            // 流水线级变量强制补齐 projectId，保证 findByProjectId 可查全
            variable.setProjectId(pid);
            accessService.assertCanAccessProject(pid);
            return ResponseEntity.ok(variableService.create(variable));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody BuildVariable variable) {
        BuildVariable existing = variableService.getById(id);
        if (existing == null) return ResponseEntity.notFound().build();
        assertAccess(existing);
        try {
            return ResponseEntity.ok(variableService.update(id, variable));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        BuildVariable existing = variableService.getById(id);
        if (existing == null) return ResponseEntity.notFound().build();
        assertAccess(existing);
        variableService.delete(id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** 由变量推导其所属项目：优先 projectId，其次经 pipelineId 反查 */
    private Long resolveProjectId(BuildVariable v) {
        if (v.getProjectId() != null) return v.getProjectId();
        if (v.getPipelineId() != null) {
            return pipelineRepository.findById(v.getPipelineId())
                    .map(Pipeline::getProjectId).orElse(null);
        }
        return null;
    }

    private void assertAccess(BuildVariable v) {
        Long pid = resolveProjectId(v);
        if (pid == null) {
            throw new IllegalArgumentException("无法确定变量所属项目");
        }
        accessService.assertCanAccessProject(pid);
    }
}
