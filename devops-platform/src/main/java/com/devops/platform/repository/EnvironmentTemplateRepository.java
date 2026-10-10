package com.devops.platform.repository;

import com.devops.platform.entity.EnvironmentTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EnvironmentTemplateRepository extends JpaRepository<EnvironmentTemplate, Long> {

    List<EnvironmentTemplate> findByBuiltinTrueOrderById();

    List<EnvironmentTemplate> findByTemplateType(String templateType);

    Optional<EnvironmentTemplate> findFirstByTemplateType(String templateType);
}
