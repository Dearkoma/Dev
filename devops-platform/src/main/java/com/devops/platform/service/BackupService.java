package com.devops.platform.service;

import com.devops.platform.entity.BackupSchedule;
import com.devops.platform.repository.BackupScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

/**
 * 数据库备份服务 — 真实执行 mysqldump 导出 + gzip 压缩 + 落盘
 *
 * 备份对象: D 平台为下游 App 创建的隔离 MySQL 库（DatabaseProvisioningService 创建）
 * 恢复方式: gunzip + mysql < backup.sql.gz
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackupService {

    private final BackupScheduleRepository backupRepository;

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @Value("${spring.datasource.username}")
    private String dbUsername;

    @Value("${spring.datasource.password}")
    private String dbPassword;

    @Value("${devops.backup.dir:./data/backups}")
    private String backupDir;

    @Value("${devops.backup.mysqldump-path:mysqldump}")
    private String mysqldumpPath;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * 执行一次真实备份
     */
    public BackupSchedule runBackup(Long scheduleId) {
        BackupSchedule schedule = backupRepository.findById(scheduleId).orElse(null);
        if (schedule == null) throw new RuntimeException("备份调度不存在: " + scheduleId);
        return runBackup(schedule);
    }

    public BackupSchedule runBackup(BackupSchedule schedule) {
        String dbName = schedule.getSourceDbName();
        if (dbName == null || dbName.isBlank()) {
            schedule.setStatus("FAILED");
            log.warn("[BACKUP] 备份失败: sourceDbName 为空, schedule={}", schedule.getId());
            return backupRepository.save(schedule);
        }

        schedule.setStatus("RUNNING");
        backupRepository.save(schedule);

        try {
            // 1. 解析 mysqldump 可执行路径
            String mysqldump = resolveMysqldump();
            // 2. 解析 MySQL 连接信息
            DbConn conn = parseDatasourceUrl();

            // 3. 创建备份目录
            Path dir = Paths.get(backupDir).toAbsolutePath().normalize();
            Files.createDirectories(dir);

            // 4. 生成文件名: <db>-<timestamp>.sql.gz
            String fileName = dbName + "-" + LocalDateTime.now().format(TS) + ".sql.gz";
            Path outFile = dir.resolve(fileName);

            // 5. 执行 mysqldump → gzip → 落盘
            log.info("[BACKUP] 开始备份: db={}, out={}", dbName, outFile);
            boolean ok = dumpAndGzip(mysqldump, conn, dbName, outFile);

            // 6. 更新调度状态
            if (ok && Files.exists(outFile)) {
                schedule.setStatus("SUCCESS");
                schedule.setLastBackupAt(LocalDateTime.now());
                schedule.setLastBackupFile(outFile.toString());
                schedule.setLastBackupSizeBytes(Files.size(outFile));
                log.info("[BACKUP] 备份成功: {} ({} bytes)", outFile, schedule.getLastBackupSizeBytes());
            } else {
                schedule.setStatus("FAILED");
                log.warn("[BACKUP] 备份失败: db={}", dbName);
            }

            // 7. 清理过期备份
            cleanupExpired(schedule);

            return backupRepository.save(schedule);

        } catch (Exception e) {
            log.error("[BACKUP] 备份异常: db={}, error={}", dbName, e.getMessage(), e);
            schedule.setStatus("FAILED");
            return backupRepository.save(schedule);
        }
    }

    /**
     * 执行 mysqldump 并通过 gzip 压缩写入文件
     */
    private boolean dumpAndGzip(String mysqldump, DbConn conn, String dbName, Path outFile) throws Exception {
        // 构建 mysqldump 命令
        List<String> cmd = List.of(
                mysqldump,
                "-h", conn.host,
                "-P", String.valueOf(conn.port),
                "-u", conn.user,
                "-p" + conn.password,
                "--single-transaction",
                "--routines",
                "--triggers",
                "--skip-lock-tables",
                dbName
        );

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process proc = pb.start();

        try (InputStream in = proc.getInputStream();
             OutputStream fileOut = Files.newOutputStream(outFile);
             GZIPOutputStream gzip = new GZIPOutputStream(fileOut)) {
            in.transferTo(gzip);
        }

        boolean finished = proc.waitFor(10, TimeUnit.MINUTES);
        if (!finished) {
            proc.destroyForcibly();
            Files.deleteIfExists(outFile);
            log.error("[BACKUP] mysqldump 超时 (10min)");
            return false;
        }
        int exit = proc.exitValue();
        if (exit != 0) {
            // 删除不完整的备份文件
            Files.deleteIfExists(outFile);
            log.error("[BACKUP] mysqldump 退出码 {}", exit);
            return false;
        }
        return true;
    }

    /**
     * 按保留天数清理过期备份文件
     */
    private void cleanupExpired(BackupSchedule schedule) {
        if (schedule.getRetentionDays() == null || schedule.getRetentionDays() <= 0) return;
        try {
            Path dir = Paths.get(backupDir).toAbsolutePath().normalize();
            if (!Files.isDirectory(dir)) return;
            long cutoff = System.currentTimeMillis() - schedule.getRetentionDays() * 24L * 3600 * 1000;
            try (var stream = Files.list(dir)) {
                for (Path f : stream.toList()) {
                    // 只清理同源数据库的备份文件
                    if (!f.getFileName().toString().startsWith(schedule.getSourceDbName() + "-")) continue;
                    if (Files.getLastModifiedTime(f).toMillis() < cutoff) {
                        Files.deleteIfExists(f);
                        log.info("[BACKUP] 清理过期备份: {}", f.getFileName());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[BACKUP] 清理过期备份失败: {}", e.getMessage());
        }
    }

    /**
     * 解析 mysqldump 可执行路径（跨平台）
     */
    private String resolveMysqldump() {
        if (mysqldumpPath == null || mysqldumpPath.isBlank()) return "mysqldump";
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        File f = new File(mysqldumpPath);
        if (isWindows && !f.getName().toLowerCase().endsWith(".exe")) {
            File exe = new File(mysqldumpPath + ".exe");
            if (exe.exists()) return exe.getAbsolutePath();
        }
        if (f.exists()) return f.getAbsolutePath();
        return mysqldumpPath;
    }

    /**
     * 从 spring.datasource.url 解析 host/port
     */
    private DbConn parseDatasourceUrl() {
        DbConn conn = new DbConn();
        conn.user = dbUsername != null ? dbUsername : "root";
        conn.password = dbPassword != null ? dbPassword : "";
        String url = datasourceUrl != null ? datasourceUrl : "jdbc:mysql://localhost:3306/devops_platform";
        // jdbc:mysql://host:port/db?params
        String body = url;
        if (body.contains("://")) body = body.substring(body.indexOf("://") + 3);
        if (body.contains("?")) body = body.substring(0, body.indexOf("?"));
        String hostPort = body.contains("/") ? body.substring(0, body.indexOf("/")) : body;
        if (hostPort.contains(":")) {
            conn.host = hostPort.substring(0, hostPort.lastIndexOf(":"));
            try {
                conn.port = Integer.parseInt(hostPort.substring(hostPort.lastIndexOf(":") + 1));
            } catch (NumberFormatException e) {
                conn.port = 3306;
            }
        } else {
            conn.host = hostPort;
            conn.port = 3306;
        }
        return conn;
    }

    private static class DbConn {
        String host = "localhost";
        int port = 3306;
        String user = "root";
        String password = "";
    }

    /**
     * 定时自动备份：每分钟检查到期的 cron 调度（可扩展为真正按 cron 触发）
     */
    @Scheduled(fixedRate = 60000)
    public void scheduledBackupCheck() {
        // 目前按需触发（run 端点），此处预留定时调度钩子
    }
}
