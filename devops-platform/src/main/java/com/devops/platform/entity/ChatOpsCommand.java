package com.devops.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * ChatOps 交互式指令 — IM 机器人命令注册
 * 支持用户在飞书/钉钉/企微中通过 @机器人 触发部署/查询/回滚
 */
@Data
@Entity
@Table(name = "chatops_commands")
public class ChatOpsCommand {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String command;            // 命令名: deploy / rollback / status / logs / help

    @Column(length = 200)
    private String description;

    @Column(length = 500)
    private String usage_;             // 用法示例 (usage 是 SQL 保留字)

    @Column(nullable = false, length = 30)
    private String permission = "DEVELOPER";  // 所需最低角色

    @Column
    private Boolean enabled = true;

    @Column(length = 100)
    private String handlerClass;       // 处理器类名（反射调用）

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
