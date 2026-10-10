package com.devops.platform.service;

import com.devops.platform.entity.*;
import com.devops.platform.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 对象级授权服务 — 防止横向越权 (IDOR / 失效的访问控制)
 *
 * 核心规则:
 *  - 全局 ADMIN / MANAGER: 可访问所有项目
 *  - 其他角色 (DEVELOPER / VIEWER): 必须为该项目的成员 (ProjectMember)
 *
 * 所有按 ID 访问具体资源的接口，在业务逻辑前调用对应的 assertXxx 方法。
 * 越权时抛出 AccessDeniedException，由 GlobalExceptionHandler 统一转 403。
 * 列表接口用 filterByAccessibleProjects 过滤，只返回当前用户有权限的项目数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectAccessService {

    private final ProjectMemberRepository memberRepository;
    private final BuildRepository buildRepository;
    private final PipelineRepository pipelineRepository;
    private final ServiceInstanceRepository instanceRepository;
    private final EnvironmentRepository environmentRepository;
    private final ArtifactRepository artifactRepository;
    private final ArtifactVersionRepository artifactVersionRepository;
    private final TestReportRepository testReportRepository;
    private final SecurityReportRepository securityReportRepository;
    private final DeploymentRequestRepository deploymentRequestRepository;

    // ==================== 当前用户 ====================

    public String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return null;
        }
        return auth.getName();
    }

    public String currentGlobalRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) return null;
        return auth.getAuthorities().stream()
                .map(Object::toString)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .findFirst()
                .orElse(null);
    }

    public boolean isGlobalAdminOrManager() {
        String role = currentGlobalRole();
        return "ADMIN".equals(role) || "MANAGER".equals(role);
    }

    // ==================== 核心项目访问检查 ====================

    /**
     * 判断当前用户能否访问指定项目。
     * 全局 ADMIN/MANAGER 始终可访问；其他角色必须是项目成员。
     */
    public boolean canAccessProject(Long projectId) {
        if (projectId == null) return true;
        if (isGlobalAdminOrManager()) return true;
        String username = currentUsername();
        if (username == null) return false;
        return memberRepository.findByProjectIdAndUsername(projectId, username).isPresent();
    }

    /**
     * 断言可访问项目，否则抛 AccessDeniedException → 403
     */
    public void assertCanAccessProject(Long projectId) {
        if (!canAccessProject(projectId)) {
            log.warn("越权访问项目被拒: user={}, projectId={}", currentUsername(), projectId);
            throw new AccessDeniedException("无权访问该项目 #" + projectId);
        }
    }

    /**
     * 当前用户可访问的项目 ID 集合。
     * 全局 ADMIN/MANAGER 返回 null（表示全部）；其他角色返回其成员项目 ID 集合。
     */
    public Set<Long> accessibleProjectIds() {
        if (isGlobalAdminOrManager()) return null;  // null = 全部
        String username = currentUsername();
        if (username == null) return Set.of();
        return memberRepository.findByUsername(username).stream()
                .map(ProjectMember::getProjectId)
                .collect(Collectors.toSet());
    }

    /**
     * 按项目访问权限过滤列表。全局 ADMIN/MANAGER 原样返回；
     * 其他角色只保留 projectId 在其成员项目集合中的记录。
     *
     * @param items       待过滤列表
     * @param projectIdOf 从元素提取 projectId 的函数
     */
    public <T> List<T> filterByAccessibleProjects(List<T> items, Function<T, Long> projectIdOf) {
        if (items == null || items.isEmpty()) return items == null ? List.of() : items;
        if (isGlobalAdminOrManager()) return items;
        Set<Long> allowed = accessibleProjectIds();
        if (allowed == null || allowed.isEmpty()) return List.of();
        return items.stream()
                .filter(i -> {
                    Long pid = projectIdOf.apply(i);
                    return pid != null && allowed.contains(pid);
                })
                .toList();
    }

    // ==================== 实体 → 项目 解析并断言 ====================

    public void assertCanAccessBuild(Long buildId) {
        if (buildId == null) return;
        Build b = buildRepository.findById(buildId).orElse(null);
        if (b == null) return;  // 不存在则交给业务层返回 404
        assertCanAccessProject(b.getProjectId());
    }

    public void assertCanAccessPipeline(Long pipelineId) {
        if (pipelineId == null) return;
        Pipeline p = pipelineRepository.findById(pipelineId).orElse(null);
        if (p == null) return;
        assertCanAccessProject(p.getProjectId());
    }

    public void assertCanAccessInstance(Long instanceId) {
        if (instanceId == null) return;
        ServiceInstance i = instanceRepository.findById(instanceId).orElse(null);
        if (i == null) return;
        assertCanAccessProject(i.getProjectId());
    }

    public void assertCanAccessEnvironment(Long environmentId) {
        if (environmentId == null) return;
        Environment e = environmentRepository.findById(environmentId).orElse(null);
        if (e == null) return;
        if (e.getProjectId() != null) {
            assertCanAccessProject(e.getProjectId());
        }
    }

    /** Artifact 无 projectId，通过 buildId 关联 */
    public void assertCanAccessArtifact(Long artifactId) {
        if (artifactId == null) return;
        Artifact a = artifactRepository.findById(artifactId).orElse(null);
        if (a == null || a.getBuildId() == null) return;
        assertCanAccessBuild(a.getBuildId());
    }

    public void assertCanAccessArtifactVersion(Long versionId) {
        if (versionId == null) return;
        ArtifactVersion v = artifactVersionRepository.findById(versionId).orElse(null);
        if (v == null) return;
        if (v.getProjectId() != null) {
            assertCanAccessProject(v.getProjectId());
        }
    }

    public void assertCanAccessTestReport(Long reportId) {
        if (reportId == null) return;
        TestReport r = testReportRepository.findById(reportId).orElse(null);
        if (r == null) return;
        assertCanAccessProject(r.getProjectId());
    }

    public void assertCanAccessSecurityReport(Long reportId) {
        if (reportId == null) return;
        SecurityReport r = securityReportRepository.findById(reportId).orElse(null);
        if (r == null) return;
        assertCanAccessProject(r.getProjectId());
    }

    public void assertCanAccessDeploymentRequest(Long requestId) {
        if (requestId == null) return;
        DeploymentRequest r = deploymentRequestRepository.findById(requestId).orElse(null);
        if (r == null) return;
        assertCanAccessProject(r.getProjectId());
    }
}
