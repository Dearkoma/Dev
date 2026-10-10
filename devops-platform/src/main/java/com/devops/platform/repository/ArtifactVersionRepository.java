package com.devops.platform.repository;

import com.devops.platform.entity.ArtifactVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ArtifactVersionRepository extends JpaRepository<ArtifactVersion, Long> {

    List<ArtifactVersion> findByArtifactIdOrderByCreatedAtDesc(Long artifactId);

    List<ArtifactVersion> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    List<ArtifactVersion> findByProjectIdAndStatusOrderByCreatedAtDesc(Long projectId, String status);

    Optional<ArtifactVersion> findByBuildIdAndArtifactId(Long buildId, Long artifactId);

    @Modifying
    @Query("UPDATE ArtifactVersion v SET v.deployCount = v.deployCount + 1, " +
            "v.lastDeployedAt = CURRENT_TIMESTAMP WHERE v.id = :id")
    int incrementDeployCount(@Param("id") Long id);

    /** 保留策略: 保留最近 N 个 ACTIVE, 其他的标 RETAINED */
    @Query("SELECT v FROM ArtifactVersion v WHERE v.artifactId = :artifactId AND v.status = 'ACTIVE' " +
            "ORDER BY v.createdAt DESC")
    List<ArtifactVersion> findActiveByArtifact(@Param("artifactId") Long artifactId);
}
