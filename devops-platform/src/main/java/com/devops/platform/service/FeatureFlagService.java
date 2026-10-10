package com.devops.platform.service;

import com.devops.platform.entity.FeatureFlag;
import com.devops.platform.repository.FeatureFlagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Feature Flag 特性开关服务 — 真实评估开关状态并在部署流程中强制执行
 *
 * 评估规则 (按 flagType):
 *  - BOOLEAN    : enabled ? true : false
 *  - PERCENTAGE : enabled && (rolloutPercent>=100 || 用户名哈希 % 100 < rolloutPercent)
 *  - TARGETED   : enabled && targetUsers 包含当前用户
 *
 * 部署门禁约定: key 为 "deploy-gate" 的开关若评估为 false，则阻断部署。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureFlagService {

    private final FeatureFlagRepository flagRepository;

    /**
     * 评估单个开关
     * @param flag 开关定义
     * @param username 当前用户名（用于 PERCENTAGE / TARGETED）
     */
    public boolean evaluate(FeatureFlag flag, String username) {
        if (flag == null) return true;
        if (!Boolean.TRUE.equals(flag.getEnabled())) return false;

        String type = flag.getFlagType() != null ? flag.getFlagType().toUpperCase() : "BOOLEAN";
        switch (type) {
            case "PERCENTAGE" -> {
                int pct = flag.getRolloutPercent() != null ? flag.getRolloutPercent() : 0;
                if (pct >= 100) return true;
                if (pct <= 0) return false;
                // 用户名哈希 → 稳定分流（同一用户始终命中同一桶）
                int bucket = Math.abs((username != null ? username : "anonymous").hashCode()) % 100;
                return bucket < pct;
            }
            case "TARGETED" -> {
                String targets = flag.getTargetUsers();
                if (targets == null || targets.isBlank() || username == null) return false;
                for (String u : targets.split(",")) {
                    if (u.trim().equals(username)) return true;
                }
                return false;
            }
            default -> {
                // BOOLEAN
                return true;
            }
        }
    }

    /**
     * 评估某项目+环境下的指定开关（不存在则返回 true 默认放行）
     */
    public boolean isEnabled(String key, Long projectId, String environment, String username) {
        return flagRepository.findByKey(key)
                .map(f -> evaluate(f, username))
                .orElse(true);
    }

    /**
     * 便捷重载：按 key 评估开关（供控制器直接调用）
     */
    public boolean evaluate(String key, Long projectId, String environment, String username) {
        return isEnabled(key, projectId, environment, username);
    }

    /**
     * 返回会阻断部署的开关列表（key="deploy-gate" 且评估为 false）
     */
    public List<FeatureFlag> getBlockingFlags(Long projectId, String environment, String username) {
        List<FeatureFlag> blocking = new ArrayList<>();
        List<FeatureFlag> flags = flagRepository.findByProjectIdAndEnvironment(projectId, environment);
        for (FeatureFlag flag : flags) {
            if ("deploy-gate".equals(flag.getKey()) && !evaluate(flag, username)) {
                blocking.add(flag);
            }
        }
        return blocking;
    }

    /**
     * 部署是否被 Feature Flag 门禁阻断（存在 deploy-gate 关闭开关）
     */
    public boolean isDeployBlocked(Long projectId, String environment, String username) {
        return !getBlockingFlags(projectId, environment, username).isEmpty();
    }

    /**
     * 评估某项目+环境下的全部开关，返回 key → 是否开启 的映射（供前端展示/调试）
     */
    public Map<String, Object> evaluateAll(Long projectId, String environment, String username) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<FeatureFlag> flags = flagRepository.findByProjectIdAndEnvironment(projectId, environment);
        for (FeatureFlag flag : flags) {
            result.put(flag.getKey(), Map.of(
                    "enabled", evaluate(flag, username),
                    "flagType", flag.getFlagType(),
                    "rolloutPercent", flag.getRolloutPercent() != null ? flag.getRolloutPercent() : 0
            ));
        }
        return result;
    }
}
