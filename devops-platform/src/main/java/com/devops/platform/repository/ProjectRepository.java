package com.devops.platform.repository;
import com.devops.platform.entity.Project;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
@Repository
public interface ProjectRepository extends JpaRepository<Project, Long> {
    List<Project> findByNameContainingIgnoreCase(String name);
    List<Project> findByStatus(String status);
    Page<Project> findAll(Pageable pageable);

    /** 项目编码唯一约束 (code 列 unique=true) 的前置校验，避免 save 时裸抛 500 */
    boolean existsByCode(String code);

    /** 编辑场景：排除自身后判断编码是否被其他项目占用 */
    boolean existsByCodeAndIdNot(String code, Long id);
}
