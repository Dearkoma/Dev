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
    private final ProjectAccessService accessService;
    public List<Pipeline> getPipelinesByProject(Long projectId) {
        return pipelineRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }
    public List<Pipeline> getAllPipelines() {
        return pipelineRepository.findAll();
    }
    public Pipeline getPipeline(Long id) { return pipelineRepository.findById(id).orElse(null); }
    /**
     * 定时表达式校验：非法表达式在写入时就拒绝，而不是等到调度时才跳过。
     * 原本只在 SchedulerService 触发时判断，用户填错了得不到即时反馈。
     */
    private void validateCron(String cronExpression) {
        if (cronExpression != null && !cronExpression.isBlank()
                && !SchedulerService.isValidCron(cronExpression)) {
            throw new IllegalArgumentException(
                    "定时触发表达式不合法，请使用标准 6 段 cron 表达式（分 时 日 月 周 年）");
        }
    }

    public Pipeline createPipeline(Pipeline pipeline) {
        if (pipeline.getName() == null || pipeline.getName().isBlank()) {
            throw new IllegalArgumentException("流水线名称不能为空");
        }
        if (pipeline.getProjectId() == null) {
            throw new IllegalArgumentException("请选择所属项目");
        }
        validateCron(pipeline.getCronExpression());
        // 确保 cronEnabled 有默认值
        if (pipeline.getCronEnabled() == null) {
            pipeline.setCronEnabled(false);
        }
        return pipelineRepository.save(pipeline);
    }

    public Pipeline updatePipeline(Long id, Pipeline pipeline) {
        Pipeline existing = getPipeline(id);
        if (existing == null) return null;

        // 校验放在改动落库之前，非法表达式不会被写入
        validateCron(pipeline.getCronExpression());

        // 保存旧版本快照（如果定义有变化）
        if (pipeline.getDefinition() != null && !pipeline.getDefinition().equals(existing.getDefinition())) {
            var version = new com.devops.platform.entity.PipelineVersion();
            version.setPipelineId(id);
            version.setVersionNumber((int) (versionRepository.countByPipelineId(id) + 1));
            version.setDefinition(existing.getDefinition());
            // 变更人取当前登录用户；无 SecurityContext（防御：将来若被定时任务调用）兜底 system
            String username = accessService.currentUsername();
            version.setChangedBy(username != null ? username : "system");
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

    /**
     * 回滚流水线到指定历史版本。
     * 实现上复用 updatePipeline：把旧 definition 写回，updatePipeline 会把「回滚前的当前定义」
     * 再存一个版本快照 —— 回滚操作自身也留下审计记录，不会丢历史。
     */
    public Pipeline rollbackToVersion(Long pipelineId, Long versionId) {
        Pipeline pipeline = getPipeline(pipelineId);
        if (pipeline == null) return null;
        var version = versionRepository.findById(versionId).orElse(null);
        if (version == null || !pipelineId.equals(version.getPipelineId())) {
            throw new IllegalArgumentException("版本不存在或不属于该流水线");
        }
        pipeline.setDefinition(version.getDefinition());
        return updatePipeline(pipelineId, pipeline);
    }
}
