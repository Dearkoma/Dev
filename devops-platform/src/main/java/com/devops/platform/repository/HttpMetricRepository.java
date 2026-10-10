package com.devops.platform.repository;

import com.devops.platform.entity.HttpMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface HttpMetricRepository extends JpaRepository<HttpMetric, Long> {

    List<HttpMetric> findByRecordedAtAfterOrderByRecordedAtDesc(LocalDateTime since);

    @Query("SELECT m FROM HttpMetric m WHERE m.path = :path AND m.recordedAt > :since ORDER BY m.recordedAt DESC")
    List<HttpMetric> findByPathSince(@Param("path") String path, @Param("since") LocalDateTime since);

    /**
     * 清理老数据 - 默认保留 7 天,定时任务调度
     */
    @Modifying
    @Query("DELETE FROM HttpMetric m WHERE m.recordedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
