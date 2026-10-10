package com.devops.platform.service;

import com.devops.platform.entity.Artifact;
import com.devops.platform.entity.ArtifactVersion;
import com.devops.platform.entity.Build;
import com.devops.platform.repository.ArtifactRepository;
import com.devops.platform.repository.ArtifactVersionRepository;
import com.devops.platform.repository.BuildRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;

/**
 * 制品版本管理服务
 *  - 制品落库时自动计算 SHA256 + 元数据
 *  - 保留策略: 同一个 artifact 最多保留 N 个 ACTIVE, 老的标 RETAINED
 *  - 部署时 incrementDeployCount (热度追踪)
 *  - 校验和验证: 重新计算 SHA256 跟记录的对比
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArtifactVersionService {

    private final ArtifactVersionRepository versionRepository;
    private final ArtifactRepository artifactRepository;
    private final BuildRepository buildRepository;

    @Value("${devops.artifact.retention-active:5}")
    private int defaultRetentionActive;

    /**
     * 制品落库 - 自动算 SHA256 + 写 version 记录
     * @param artifact 主记录
     * @param version 版本号 (e.g. "1.2.3" 或 "#42")
     * @param storagePath 制品文件路径
     * @return 新建的 ArtifactVersion
     */
    @Transactional
    public ArtifactVersion recordVersion(Artifact artifact, String version, String storagePath) {
        if (artifact == null) throw new IllegalArgumentException("artifact 不能为空");
        String ver = (version == null || version.isBlank()) ? defaultVersion() : version;
        Path p = Paths.get(storagePath);
        Long size = null;
        String sha256 = null;
        if (Files.exists(p)) {
            try {
                size = Files.size(p);
                sha256 = computeSha256(p);
            } catch (IOException e) {
                log.warn("计算 SHA256 失败: {}", e.getMessage());
            }
        }

        // 通过 buildId 找 projectId
        Long projectId = null;
        Build build = buildRepository.findById(artifact.getBuildId()).orElse(null);
        if (build != null) projectId = build.getProjectId();

        ArtifactVersion v = new ArtifactVersion();
        v.setProjectId(projectId);
        v.setBuildId(artifact.getBuildId());
        v.setArtifactId(artifact.getId());
        v.setVersion(ver);
        v.setSha256(sha256);
        v.setSize(size);
        v.setStoragePath(storagePath);
        v.setStatus(ArtifactVersion.STATUS_ACTIVE);
        v = versionRepository.save(v);

        applyRetentionPolicy(artifact.getId(), defaultRetentionActive);
        return v;
    }

    @Transactional
    public ArtifactVersion recordDockerImageVersion(Artifact artifact, String imageName, String version) {
        if (artifact == null) throw new IllegalArgumentException("artifact 不能为空");
        String ver = (version == null || version.isBlank()) ? defaultVersion() : version;
        Long projectId = null;
        Build build = buildRepository.findById(artifact.getBuildId()).orElse(null);
        if (build != null) projectId = build.getProjectId();

        ArtifactVersion v = new ArtifactVersion();
        v.setProjectId(projectId);
        v.setBuildId(artifact.getBuildId());
        v.setArtifactId(artifact.getId());
        v.setVersion(ver);
        v.setStoragePath("docker://" + imageName);
        v.setStatus(ArtifactVersion.STATUS_ACTIVE);
        v = versionRepository.save(v);
        applyRetentionPolicy(artifact.getId(), defaultRetentionActive);
        return v;
    }

    /**
     * 部署一次 - incrementDeployCount
     */
    @Transactional
    public void onDeployed(Long versionId) {
        versionRepository.incrementDeployCount(versionId);
    }

    /**
     * 校验和验证 - 重新计算 SHA256 对比
     */
    public boolean verifyIntegrity(Long versionId) {
        Optional<ArtifactVersion> v = versionRepository.findById(versionId);
        if (v.isEmpty() || v.get().getSha256() == null) return false;
        String stored = v.get().getSha256();
        Path p = Paths.get(v.get().getStoragePath());
        if (!Files.exists(p)) return false;
        try {
            return stored.equals(computeSha256(p));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 保留策略 - 同一个 artifact 最多 N 个 ACTIVE, 其余标 RETAINED
     */
    @Transactional
    public int applyRetentionPolicy(Long artifactId, int keepActive) {
        List<ArtifactVersion> all = versionRepository.findActiveByArtifact(artifactId);
        if (all.size() <= keepActive) return 0;
        int updated = 0;
        for (int i = keepActive; i < all.size(); i++) {
            ArtifactVersion v = all.get(i);
            v.setStatus(ArtifactVersion.STATUS_RETAINED);
            versionRepository.save(v);
            updated++;
        }
        return updated;
    }

    /**
     * 删版本 - 仅改状态, 不真删文件
     */
    @Transactional
    public boolean deleteVersion(Long versionId) {
        return versionRepository.findById(versionId)
                .map(v -> {
                    v.setStatus(ArtifactVersion.STATUS_DELETED);
                    versionRepository.save(v);
                    return true;
                })
                .orElse(false);
    }

    public List<ArtifactVersion> versionsByProject(Long projectId) {
        return versionRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    public List<ArtifactVersion> versionsByArtifact(Long artifactId) {
        return versionRepository.findByArtifactIdOrderByCreatedAtDesc(artifactId);
    }

    public java.util.Optional<ArtifactVersion> findById(Long versionId) {
        return versionRepository.findById(versionId);
    }

    private String defaultVersion() {
        return "v" + System.currentTimeMillis() / 1000;
    }

    /** 流式 SHA256 - 大文件不爆内存 */
    private String computeSha256(Path p) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(p)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            byte[] hash = md.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 算法不可用", e);
        }
    }
}
