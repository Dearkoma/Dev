package com.devops.platform.repository;

import com.devops.platform.entity.BuildVariable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BuildVariableRepository extends JpaRepository<BuildVariable, Long> {

    List<BuildVariable> findByProjectId(Long projectId);

    List<BuildVariable> findByPipelineId(Long pipelineId);

    /** 查询项目级 + 指定流水线级变量，按作用域排序 */
    List<BuildVariable> findByProjectIdOrPipelineIdOrderByPipelineIdAsc(Long projectId, Long pipelineId);

    void deleteByProjectId(Long projectId);

    void deleteByPipelineId(Long pipelineId);
}
