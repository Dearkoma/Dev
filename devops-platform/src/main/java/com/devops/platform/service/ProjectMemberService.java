package com.devops.platform.service;

import com.devops.platform.entity.ProjectMember;
import com.devops.platform.repository.ProjectMemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectMemberService {

    private final ProjectMemberRepository memberRepository;

    /** 列出项目所有成员 */
    public List<ProjectMember> getMembers(Long projectId) {
        return memberRepository.findByProjectIdOrderByCreatedAtAsc(projectId);
    }

    /** 添加成员 */
    public ProjectMember addMember(Long projectId, String username, String role) {
        if (memberRepository.findByProjectIdAndUsername(projectId, username).isPresent()) {
            throw new RuntimeException("该用户已是项目成员");
        }
        if (!isValidRole(role)) {
            throw new RuntimeException("无效角色: " + role + " (允许: OWNER, MAINTAINER, DEVELOPER, VIEWER)");
        }
        ProjectMember member = new ProjectMember();
        member.setProjectId(projectId);
        member.setUsername(username);
        member.setRole(role.toUpperCase());
        log.info("项目 #{} 添加成员: username={}, role={}", projectId, username, role);
        return memberRepository.save(member);
    }

    /** 更新成员角色 */
    public ProjectMember updateRole(Long memberId, String newRole) {
        ProjectMember member = memberRepository.findById(memberId)
                .orElseThrow(() -> new RuntimeException("成员不存在"));
        if (!isValidRole(newRole)) {
            throw new RuntimeException("无效角色: " + newRole);
        }
        member.setRole(newRole.toUpperCase());
        return memberRepository.save(member);
    }

    /** 移除成员 */
    @Transactional
    public void removeMember(Long projectId, Long memberId) {
        ProjectMember member = memberRepository.findById(memberId)
                .orElseThrow(() -> new RuntimeException("成员不存在"));
        if (!member.getProjectId().equals(projectId)) {
            throw new RuntimeException("成员不属于该项目");
        }
        memberRepository.delete(member);
        log.info("项目 #{} 移除成员: username={}", projectId, member.getUsername());
    }

    /** 获取用户在某项目中的角色 */
    public Optional<String> getUserRole(Long projectId, String username) {
        return memberRepository.findByProjectIdAndUsername(projectId, username)
                .map(ProjectMember::getRole);
    }

    /** 检查用户是否可以审批该项目部署 */
    public boolean canApproveDeployment(Long projectId, String username, String globalRole) {
        // 全局 ADMIN 或 MANAGER 始终可审批
        if ("ADMIN".equals(globalRole) || "MANAGER".equals(globalRole)) {
            return true;
        }
        // 项目 OWNER 或 MAINTAINER 可审批
        return getUserRole(projectId, username)
                .map(r -> "OWNER".equals(r) || "MAINTAINER".equals(r))
                .orElse(false);
    }

    /** 检查用户是否可以管理项目成员 */
    public boolean canManageMembers(Long projectId, String username, String globalRole) {
        if ("ADMIN".equals(globalRole)) {
            return true;
        }
        return getUserRole(projectId, username)
                .map("OWNER"::equals)
                .orElse(false);
    }

    private boolean isValidRole(String role) {
        if (role == null) return false;
        return switch (role.toUpperCase()) {
            case "OWNER", "MAINTAINER", "DEVELOPER", "VIEWER" -> true;
            default -> false;
        };
    }
}
