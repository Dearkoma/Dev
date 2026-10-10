package com.devops.platform.repository;

import com.devops.platform.entity.PipelineVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PipelineVersionRepository extends JpaRepository<PipelineVersion, Long> {

    List<PipelineVersion> findByPipelineIdOrderByVersionNumberDesc(Long pipelineId);

    long countByPipelineId(Long pipelineId);
}
