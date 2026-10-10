package com.devops.platform.repository;

import com.devops.platform.entity.BackupSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface BackupScheduleRepository extends JpaRepository<BackupSchedule, Long> {
    List<BackupSchedule> findByProjectIdAndEnabledTrue(Long projectId);
    List<BackupSchedule> findByEnabledTrue();
}
