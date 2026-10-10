package com.devops.platform.controller;

import com.devops.platform.entity.ProjectMember;
import com.devops.platform.service.ProjectMemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}/members")
@RequiredArgsConstructor
public class ProjectMemberController {

    private final ProjectMemberService memberService;

    /** 列出项目成员 */
    @GetMapping
    public ResponseEntity<List<ProjectMember>> listMembers(@PathVariable Long projectId) {
        return ResponseEntity.ok(memberService.getMembers(projectId));
    }

    /** 添加成员 */
    @PostMapping
    public ResponseEntity<?> addMember(@PathVariable Long projectId,
                                       @RequestBody Map<String, String> body,
                                       Principal principal) {
        try {
            // 权限检查
            String username = principal != null ? principal.getName() : null;
            String globalRole = getGlobalRole(principal);
            if (!memberService.canManageMembers(projectId, username, globalRole)) {
                return ResponseEntity.status(403)
                        .body(Map.of("error", "无权管理项目成员（需项目 OWNER 或全局 ADMIN）"));
            }

            String memberUsername = body.get("username");
            String role = body.getOrDefault("role", "DEVELOPER");
            if (memberUsername == null || memberUsername.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "username 不能为空"));
            }

            ProjectMember member = memberService.addMember(projectId, memberUsername, role.toUpperCase());
            return ResponseEntity.ok(member);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 修改成员角色 */
    @PutMapping("/{memberId}")
    public ResponseEntity<?> updateMember(@PathVariable Long projectId,
                                          @PathVariable Long memberId,
                                          @RequestBody Map<String, String> body,
                                          Principal principal) {
        try {
            String username = principal != null ? principal.getName() : null;
            String globalRole = getGlobalRole(principal);
            if (!memberService.canManageMembers(projectId, username, globalRole)) {
                return ResponseEntity.status(403)
                        .body(Map.of("error", "无权管理项目成员"));
            }

            String newRole = body.get("role");
            if (newRole == null || newRole.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "role 不能为空"));
            }

            ProjectMember member = memberService.updateRole(memberId, newRole);
            return ResponseEntity.ok(member);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 移除成员 */
    @DeleteMapping("/{memberId}")
    public ResponseEntity<?> removeMember(@PathVariable Long projectId,
                                          @PathVariable Long memberId,
                                          Principal principal) {
        try {
            String username = principal != null ? principal.getName() : null;
            String globalRole = getGlobalRole(principal);
            if (!memberService.canManageMembers(projectId, username, globalRole)) {
                return ResponseEntity.status(403)
                        .body(Map.of("error", "无权管理项目成员"));
            }

            memberService.removeMember(projectId, memberId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 获取当前用户在该项目中的角色 */
    @GetMapping("/my-role")
    public ResponseEntity<Map<String, Object>> getMyRole(@PathVariable Long projectId,
                                                          Principal principal) {
        String username = principal != null ? principal.getName() : null;
        String globalRole = getGlobalRole(principal);
        String projectRole = memberService.getUserRole(projectId, username).orElse(null);

        return ResponseEntity.ok(Map.of(
                "username", username != null ? username : "",
                "globalRole", globalRole != null ? globalRole : "",
                "projectRole", projectRole,
                "canApprove", memberService.canApproveDeployment(projectId, username, globalRole),
                "canManage", memberService.canManageMembers(projectId, username, globalRole)
        ));
    }

    /** 从 Spring Security Context 获取全局角色 */
    private String getGlobalRole(Principal principal) {
        if (principal == null) return null;
        if (principal instanceof org.springframework.security.core.Authentication auth) {
            return auth.getAuthorities().stream()
                    .map(Object::toString)
                    .filter(a -> a.startsWith("ROLE_"))
                    .map(a -> a.substring(5))
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }
}
