package com.devops.platform.entity;

import jakarta.persistence.*;
import lombok.Data;

/**
 * SAST 单个漏洞发现 — 一条代码级安全问题
 * 对应 Semgrep 的一条 finding 或内置模式扫描器的一条命中
 */
@Data
@Entity
@Table(name = "sast_findings")
public class SastFinding {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long reportId;

    @Column
    private Long buildId;

    @Column
    private Long projectId;

    @Column(length = 100)
    private String ruleId;          // 规则 ID，如 "java.lang.security.audit.crypto.use-of-md5"

    @Column(length = 500)
    private String title;           // 规则名称/标题

    @Column(length = 20)
    private String severity;        // CRITICAL / HIGH / MEDIUM / LOW

    @Column(length = 50)
    private String category;        // SECURITY / CORRECTNESS / INJECTION / CRYPTO / SECRET

    @Column(length = 500)
    private String filePath;        // 命中文件路径

    @Column
    private Integer lineNumber;     // 行号

    @Column(length = 20)
    private String language;        // java / javascript / python / go / yaml ...

    @Column(columnDefinition = "TEXT")
    private String codeSnippet;     // 命中代码片段

    @Column(columnDefinition = "TEXT")
    private String message;         // 漏洞说明

    @Column(length = 500)
    private String cweId;           // 关联 CWE，如 CWE-327

    @Column(length = 200)
    private String reference;       // 参考链接
}
