package com.devops.platform.repository;

import com.devops.platform.entity.CostRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.List;

public interface CostRecordRepository extends JpaRepository<CostRecord, Long> {
    List<CostRecord> findByProjectIdAndBillingDateBetween(Long projectId, LocalDate from, LocalDate to);
    List<CostRecord> findByProjectId(Long projectId);
    @Query("SELECT SUM(c.costAmount) FROM CostRecord c WHERE c.projectId = :projectId")
    Double totalCostByProject(@Param("projectId") Long projectId);
}
