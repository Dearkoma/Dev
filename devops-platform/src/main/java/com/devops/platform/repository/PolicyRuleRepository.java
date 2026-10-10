package com.devops.platform.repository;

import com.devops.platform.entity.PolicyRule;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PolicyRuleRepository extends JpaRepository<PolicyRule, Long> {
    List<PolicyRule> findByEnabledTrue();
    List<PolicyRule> findByProjectIdOrProjectIdIsNull(Long projectId);
    List<PolicyRule> findByCategoryAndEnabledTrue(String category);
}
