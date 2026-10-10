package com.devops.platform.service;
import com.devops.platform.entity.Pipeline;
import com.devops.platform.repository.PipelineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;
@Service
@RequiredArgsConstructor
public class PipelineService {
    private final PipelineRepository pipelineRepository;
    private final com.devops.platform.repository.PipelineVersionRepository versionRepository;
    public List<Pipeline> getPipelinesByProject(Long projectId) {
        return pipelineRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }
    public List<Pipeline> getAllPipelines() {
        return pipelineRepository.findAll();
    }
    public Pipeline getPipeline(Long id) { return pipelineRepository.findById(id).orElse(null); }
    public Pipeline createPipeline(Pipeline pipeline) {
        if (pipeline.getName() == null || pipeline.getName().isBlank()) {
            throw new IllegalArgumentException("流水线名称不能为空");
        }
        if (pipeline.getProjectId() == null) {
            throw new IllegalArgumentException("请选择所属项目");
        }
        // 确保 cronEnabled 有默认值
        if (pipeline.getCronEnabled() == null) {
            pipeline.setCronEnabled(false);
        }
        return pipelineRepository.save(pipeline);
    }

    public Pipeline updatePipeline(Long id, Pipeline pipeline) {
        Pipeline existing = getPipeline(id);
        if (existing == null) return null;

        // 保存旧版本快照（如果定义有变化）
        if (pipeline.getDefinition() != null && !pipeline.getDefinition().equals(existing.getDefinition())) {
            var version = new com.devops.platform.entity.PipelineVersion();
            version.setPipelineId(id);
            version.setVersionNumber((int) (versionRepository.countByPipelineId(id) + 1));
            version.setDefinition(existing.getDefinition());
            version.setChangedBy("admin"); // TODO: 从 JWT 获取用户名
            versionRepository.save(version);
        }

        existing.setName(pipeline.getName());
        existing.setDescription(pipeline.getDescription());
        existing.setDefinition(pipeline.getDefinition());
        existing.setStatus(pipeline.getStatus());
        existing.setBranchPattern(pipeline.getBranchPattern());
        existing.setCronExpression(pipeline.getCronExpression());
        if (pipeline.getCronEnabled() != null) {
            existing.setCronEnabled(pipeline.getCronEnabled());
        }
        return pipelineRepository.save(existing);
    }
    public void deletePipeline(Long id) { pipelineRepository.deleteById(id); }
    public List<Pipeline> findByProjectIdAndStatus(Long projectId, String status) {
        return pipelineRepository.findByProjectIdAndStatus(projectId, status);
    }
    public List<com.devops.platform.entity.PipelineVersion> getVersionHistory(Long pipelineId) {
        return versionRepository.findByPipelineIdOrderByVersionNumberDesc(pipelineId);
    }
}
