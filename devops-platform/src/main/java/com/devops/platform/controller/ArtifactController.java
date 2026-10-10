package com.devops.platform.controller;

import com.devops.platform.entity.Artifact;
import com.devops.platform.entity.ArtifactVersion;
import com.devops.platform.service.ArtifactService;
import com.devops.platform.service.ArtifactVersionService;
import com.devops.platform.service.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/artifacts")
@RequiredArgsConstructor
public class ArtifactController {

    private final ArtifactService artifactService;
    private final ArtifactVersionService versionService;
    private final ProjectAccessService accessService;

    @GetMapping
    public List<Artifact> getByBuild(@RequestParam Long buildId) {
        accessService.assertCanAccessBuild(buildId);
        return artifactService.getArtifactsByBuild(buildId);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Artifact> getOne(@PathVariable Long id) {
        accessService.assertCanAccessArtifact(id);
        Artifact a = artifactService.getArtifact(id);
        return a == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(a);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        accessService.assertCanAccessArtifact(id);
        artifactService.deleteArtifact(id);
        return ResponseEntity.ok().build();
    }

    // ==================== 版本管理 ====================

    /** 项目下所有版本 (跨 artifact) */
    @GetMapping("/versions/project/{projectId}")
    public List<ArtifactVersion> versionsByProject(@PathVariable Long projectId) {
        accessService.assertCanAccessProject(projectId);
        return versionService.versionsByProject(projectId);
    }

    /** 单个 artifact 的版本列表 */
    @GetMapping("/{id}/versions")
    public List<ArtifactVersion> versionsByArtifact(@PathVariable Long id) {
        accessService.assertCanAccessArtifact(id);
        return versionService.versionsByArtifact(id);
    }

    /** 校验和验证 - 重新算 SHA256 对比 */
    @GetMapping("/versions/{versionId}/verify")
    public ResponseEntity<Map<String, Object>> verify(@PathVariable Long versionId) {
        accessService.assertCanAccessArtifactVersion(versionId);
        boolean ok = versionService.verifyIntegrity(versionId);
        return ResponseEntity.ok(Map.of(
                "versionId", versionId,
                "integrityValid", ok,
                "message", ok ? "✅ 校验通过" : "❌ 校验失败或文件缺失"
        ));
    }

    /** 部署 - 计数自增 */
    @PostMapping("/versions/{versionId}/deployed")
    public ResponseEntity<ArtifactVersion> markDeployed(@PathVariable Long versionId) {
        accessService.assertCanAccessArtifactVersion(versionId);
        versionService.onDeployed(versionId);
        return versionService.findById(versionId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** 软删版本 */
    @DeleteMapping("/versions/{versionId}")
    public ResponseEntity<Map<String, Object>> deleteVersion(@PathVariable Long versionId) {
        accessService.assertCanAccessArtifactVersion(versionId);
        boolean ok = versionService.deleteVersion(versionId);
        return ResponseEntity.ok(Map.of(
                "versionId", versionId,
                "deleted", ok
        ));
    }
}

