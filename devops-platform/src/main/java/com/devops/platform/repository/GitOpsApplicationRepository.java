package com.devops.platform.repository;

import com.devops.platform.entity.GitOpsApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface GitOpsApplicationRepository extends JpaRepository<GitOpsApplication, Long> {
    List<GitOpsApplication> findByProjectId(Long projectId);
    List<GitOpsApplication> findByTargetCluster(String targetCluster);
}
