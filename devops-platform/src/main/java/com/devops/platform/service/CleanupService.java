package com.devops.platform.service;

import com.devops.platform.entity.Build;
import com.devops.platform.repository.BuildRepository;
import com.devops.platform.repository.ArtifactRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 定时清理服务
 * - 构建日志自动清理（默认 30 天）
 * - 制品自动清理（默认 90 天）
 * - 旧构建记录清理（默认 90 天）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CleanupService {

    private final BuildRepository buildRepository;
    private final ArtifactRepository artifactRepository;

    @Value("${devops.cleanup.log-retention-days:30}")
    private int logRetentionDays;

    @Value("${devops.cleanup.artifact-retention-days:90}")
    private int artifactRetentionDays;

    @Value("${devops.cleanup.build-retention-days:90}")
    private int buildRetentionDays;

    @Value("${devops.pipeline.log-dir:./data/logs}")
    private String logDir;

    /** 每天凌晨 3 点执行清理 */
    @Scheduled(cron = "0 3 * * * *")
    public void scheduledCleanup() {
        log.info("[CLEANUP] 开始定时清理...");
        cleanupOldBuildLogs();
        cleanupOldBuilds();
        cleanupOldArtifacts();
        log.info("[CLEANUP] 定时清理完成");
    }

    /** 清理过期的构建日志文件 */
    public int cleanupOldBuildLogs() {
        int deleted = 0;
        try {
            Path logPath = Paths.get(logDir).toAbsolutePath().normalize();
            if (!Files.exists(logPath)) return 0;

            LocalDateTime cutoff = LocalDateTime.now().minusDays(logRetentionDays);
            try (var stream = Files.list(logPath)) {
                for (Path file : stream.toList()) {
                    String name = file.getFileName().toString();
                    if (!name.startsWith("build-") || !name.endsWith(".log")) continue;
                    try {
                        long buildId = Long.parseLong(name.replace("build-", "").replace(".log", ""));
                        Build build = buildRepository.findById(buildId).orElse(null);
                        if (build != null && build.getStartTime() != null
                                && build.getStartTime().isAfter(cutoff)) {
                            continue; // 还未过期
                        }
                    } catch (NumberFormatException ignored) {}
                    Files.deleteIfExists(file);
                    deleted++;
                }
            }
            log.info("[CLEANUP] 清理了 {} 个过期日志文件 (保留 {} 天)", deleted, logRetentionDays);
        } catch (IOException e) {
            log.warn("[CLEANUP] 日志清理失败: {}", e.getMessage());
        }
        return deleted;
    }

    /** 清理过期的构建记录及日志 */
    public int cleanupOldBuilds() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(buildRetentionDays);
        List<Build> oldBuilds = buildRepository.findByStartTimeBefore(cutoff);
        int deleted = 0;
        for (Build b : oldBuilds) {
            // 删除日志文件
            try {
                Path logFile = Paths.get(logDir).toAbsolutePath().normalize()
                        .resolve("build-" + b.getId() + ".log");
                Files.deleteIfExists(logFile);
            } catch (IOException ignored) {}
            buildRepository.delete(b);
            deleted++;
        }
        log.info("[CLEANUP] 清理了 {} 条过期构建记录 (保留 {} 天)", deleted, buildRetentionDays);
        return deleted;
    }

    /** 清理过期制品 */
    public int cleanupOldArtifacts() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(artifactRetentionDays);
        var oldArtifacts = artifactRepository.findAll().stream()
                .filter(a -> a.getCreatedAt() != null && a.getCreatedAt().isBefore(cutoff))
                .toList();
        for (var a : oldArtifacts) {
            // 删除物理文件
            try {
                if (a.getFilePath() != null) {
                    Files.deleteIfExists(Path.of(a.getFilePath()));
                }
            } catch (IOException ignored) {}
            artifactRepository.delete(a);
        }
        log.info("[CLEANUP] 清理了 {} 个过期制品 (保留 {} 天)", oldArtifacts.size(), artifactRetentionDays);
        return oldArtifacts.size();
    }
}
