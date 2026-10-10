package com.devops.platform.controller;
import com.devops.platform.entity.Pipeline;
import com.devops.platform.service.PipelineService;
import com.devops.platform.service.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/pipelines")
@RequiredArgsConstructor
public class PipelineController {
    private final PipelineService pipelineService;
    private final ProjectAccessService accessService;
    @GetMapping
    public List<Pipeline> getAll(@RequestParam(required = false) Long projectId) {
        if (projectId != null) {
            accessService.assertCanAccessProject(projectId);
            return pipelineService.getPipelinesByProject(projectId);
        }
        return accessService.filterByAccessibleProjects(pipelineService.getAllPipelines(), Pipeline::getProjectId);
    }
    @GetMapping("/{id}")
    public ResponseEntity<Pipeline> getOne(@PathVariable Long id) {
        accessService.assertCanAccessPipeline(id);
        Pipeline p = pipelineService.getPipeline(id);
        return p == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(p);
    }
    @PostMapping
    public ResponseEntity<?> create(@RequestBody Pipeline pipeline) {
        if (pipeline.getProjectId() != null) {
            accessService.assertCanAccessProject(pipeline.getProjectId());
        }
        try {
            Pipeline created = pipelineService.createPipeline(pipeline);
            return ResponseEntity.ok(created);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
    @PutMapping("/{id}")
    public ResponseEntity<Pipeline> update(@PathVariable Long id, @RequestBody Pipeline pipeline) {
        accessService.assertCanAccessPipeline(id);
        Pipeline p = pipelineService.updatePipeline(id, pipeline);
        return p == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(p);
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        accessService.assertCanAccessPipeline(id);
        pipelineService.deletePipeline(id);
        return ResponseEntity.ok().build();
    }

    /** 获取流水线版本历史 */
    @GetMapping("/{id}/versions")
    public ResponseEntity<?> getVersions(@PathVariable Long id) {
        accessService.assertCanAccessPipeline(id);
        var versions = pipelineService.getVersionHistory(id);
        return ResponseEntity.ok(versions);
    }

    /** 回滚流水线到指定历史版本（回滚前会自动把当前定义存为新快照） */
    @PostMapping("/{id}/rollback-version")
    public ResponseEntity<?> rollbackVersion(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        accessService.assertCanAccessPipeline(id);
        Object vid = body.get("versionId");
        if (vid == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少 versionId"));
        }
        try {
            Pipeline p = pipelineService.rollbackToVersion(id, Long.valueOf(vid.toString()));
            return p == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(p);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
