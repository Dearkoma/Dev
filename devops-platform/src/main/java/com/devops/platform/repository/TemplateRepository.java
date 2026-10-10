package com.devops.platform.repository;

import com.devops.platform.entity.Template;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TemplateRepository extends JpaRepository<Template, Long> {
    List<Template> findByType(String type);
    List<Template> findByCategoryAndType(String category, String type);
    List<Template> findByTypeAndLanguageAndFramework(String type, String language, String framework);
    List<Template> findByIsBuiltinTrueAndEnabledTrue();

    @Query("SELECT t FROM Template t WHERE " +
           "LOWER(t.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(t.description) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(t.category) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<Template> searchByKeyword(@Param("keyword") String keyword);
}
