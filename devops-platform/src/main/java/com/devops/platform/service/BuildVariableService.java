package com.devops.platform.service;

import com.devops.platform.entity.BuildVariable;
import com.devops.platform.repository.BuildVariableRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BuildVariableService {

    private final BuildVariableRepository variableRepository;
    private final SecretService secretService;

    /** 获取项目及流水线的所有变量（已解密） */
    public List<BuildVariable> getVariables(Long projectId, Long pipelineId) {
        return variableRepository.findByProjectIdOrPipelineIdOrderByPipelineIdAsc(projectId, pipelineId);
    }

    /** 解析所有变量为 Map（key→明文value），流水线级覆盖项目级 */
    public Map<String, String> resolveAll(Long projectId, Long pipelineId) {
        List<BuildVariable> vars = getVariables(projectId, pipelineId);
        Map<String, String> result = new LinkedHashMap<>();
        for (BuildVariable v : vars) {
            String val = v.getValue();
            if (Boolean.TRUE.equals(v.getSecret()) && val != null) {
                try { val = secretService.decrypt(val); } catch (Exception ignored) {}
            }
            result.put(v.getVarKey(), val);
        }
        return result;
    }

    /** 获取所有需要日志脱敏的敏感值 */
    public List<String> getSensitiveValues(Long projectId, Long pipelineId) {
        return getVariables(projectId, pipelineId).stream()
                .filter(v -> Boolean.TRUE.equals(v.getSecret()))
                .map(v -> {
                    try { return secretService.decrypt(v.getValue()); }
                    catch (Exception e) { return null; }
                })
                .filter(v -> v != null && !v.isEmpty())
                .collect(Collectors.toList());
    }

    public BuildVariable create(BuildVariable v) {
        if (Boolean.TRUE.equals(v.getSecret()) && v.getValue() != null) {
            v.setValue(secretService.encrypt(v.getValue()));
        }
        return variableRepository.save(v);
    }

    @Transactional
    public BuildVariable update(Long id, BuildVariable update) {
        BuildVariable v = variableRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("变量不存在"));
        v.setVarKey(update.getVarKey());
        v.setDescription(update.getDescription());
        v.setSecret(update.getSecret());
        if (update.getValue() != null) {
            v.setValue(Boolean.TRUE.equals(update.getSecret())
                    ? secretService.encrypt(update.getValue())
                    : update.getValue());
        }
        return variableRepository.save(v);
    }

    public void delete(Long id) {
        variableRepository.deleteById(id);
    }

    @Transactional
    public void deleteByProject(Long projectId) {
        variableRepository.deleteByProjectId(projectId);
    }

    @Transactional
    public void deleteByPipeline(Long pipelineId) {
        variableRepository.deleteByPipelineId(pipelineId);
    }
}
