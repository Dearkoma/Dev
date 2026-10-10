package com.devops.platform.repository;

import com.devops.platform.entity.ProjectMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectMemberRepository extends JpaRepository<ProjectMember, Long> {

    List<ProjectMember> findByProjectIdOrderByCreatedAtAsc(Long projectId);

    Optional<ProjectMember> findByProjectIdAndUsername(Long projectId, String username);

    List<ProjectMember> findByUsername(String username);

    boolean existsByProjectIdAndUsernameAndRole(Long projectId, String username, String role);

    void deleteByProjectIdAndUsername(Long projectId, String username);
}
