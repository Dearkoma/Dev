package com.devops.platform.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据库供应服务 — 为部署的 A 项目（下游项目）自动创建独立数据库。
 * <p>
 * 设计思路：
 * D 项目（本平台）部署 A 项目时不再强行共用 devops_platform 库，
 * 而是在 MySQL 中为每个 A 项目创建一个独立数据库，
 * 部署后的 A 项目通过 host.docker.internal 连接该库，
 * DataInitializer 会自动在库内建表并创建默认 admin 用户。
 */
@Slf4j
@Service
public class DatabaseProvisioningService {

    private final DataSource dataSource;

    public DatabaseProvisioningService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 数据库创建结果。
     */
    public static class CreateResult {
        public final boolean success;
        public final String dbName;         // 净化后的数据库名
        public final boolean alreadyExisted; // 物理上已存在
        public final String conflictProjectName; // 冲突项目名（null 表示无冲突）
        public final Long conflictProjectId;     // 冲突项目ID

        private CreateResult(boolean success, String dbName, boolean alreadyExisted,
                             String conflictProjectName, Long conflictProjectId) {
            this.success = success;
            this.dbName = dbName;
            this.alreadyExisted = alreadyExisted;
            this.conflictProjectName = conflictProjectName;
            this.conflictProjectId = conflictProjectId;
        }

        public static CreateResult created(String dbName) {
            return new CreateResult(true, dbName, false, null, null);
        }

        public static CreateResult existedNoConflict(String dbName) {
            return new CreateResult(true, dbName, true, null, null);
        }

        public static CreateResult conflict(String dbName, String conflictProject, Long conflictProjectId) {
            return new CreateResult(false, dbName, true, conflictProject, conflictProjectId);
        }
    }

    /**
     * 检查指定数据库在 MySQL 中是否已物理存在。
     */
    public boolean databaseExists(String dbName) {
        String safeName = sanitizeDbName(dbName);
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery(
                    "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = '" + safeName.replace("'", "''") + "'");
            return rs.next();
        } catch (Exception e) {
            log.warn("检查数据库是否存在时出错: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 为 A 项目创建独立数据库（带冲突检测）。
     * <p>
     * 冲突判定：
     * 1. 数据库物理已存在 + 有其他项目的构建记录使用此库名 → 冲突
     * 2. 数据库物理已存在 + 只有当前项目的构建记录 → 允许（同项目重建）
     * 3. 数据库物理不存在 → 正常创建
     *
     * @param dbName          用户输入或默认的数据库名
     * @param currentProjectId 当前正在构建的项目ID
     * @param buildRepository  构建记录仓库（用于检测跨项目占用）
     * @return CreateResult，success=false 为冲突
     */
    public CreateResult createDatabase(String dbName, Long currentProjectId,
                                        com.devops.platform.repository.BuildRepository buildRepository,
                                        boolean freshIsolated) {
        if (dbName == null || dbName.isBlank()) {
            log.warn("dbName 为空，跳过数据库创建");
            return new CreateResult(false, "", false, null, null);
        }

        String safeName = sanitizeDbName(dbName);

        // 0) 如果是"独立新部署"模式：库已存在就删除重建，确保新实例是 0 条数据
        if (freshIsolated && databaseExists(safeName)) {
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("DROP DATABASE `" + safeName + "`");
                log.info("独立部署模式：已删除旧库 '{}'，准备重建", safeName);
            } catch (Exception e) {
                log.error("删除旧库 '{}' 失败: {}", safeName, e.getMessage());
                return new CreateResult(false, safeName, true, null, null);
            }
        }

        // 1) 检查 MySQL 中是否已存在
        boolean exists = databaseExists(safeName);

        // 2) 检查跨项目冲突
        if (exists && currentProjectId != null && buildRepository != null) {
            List<com.devops.platform.entity.Build> conflictBuilds =
                    buildRepository.findByDbNameAndProjectIdNot(safeName, currentProjectId);
            if (!conflictBuilds.isEmpty()) {
                // 数据库已被其他项目占用
                Long otherProjectId = conflictBuilds.get(0).getProjectId();
                log.warn("数据库 '{}' 已被项目 #{} 占用，当前项目 #{} 无法使用", safeName, otherProjectId, currentProjectId);
                return CreateResult.conflict(safeName, "Project#" + otherProjectId, otherProjectId);
            }
        }

        // 3) 执行创建（直接复用 Spring DataSource，无需解析 JDBC URL）
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            if (!exists) {
                String sql = String.format(
                        "CREATE DATABASE `%s` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci",
                        safeName);
                stmt.executeUpdate(sql);
                log.info("数据库 '{}' 创建成功", safeName);
                return CreateResult.created(safeName);
            } else {
                log.info("数据库 '{}' 已存在（同项目重建，无冲突）", safeName);
                return CreateResult.existedNoConflict(safeName);
            }

        } catch (Exception e) {
            log.error("创建数据库 '{}' 失败: {}", safeName, e.getMessage());
            return new CreateResult(false, safeName, exists, null, null);
        }
    }

    /** 兼容旧调用（无冲突检测，建议迁移到 {@link #createDatabase(String, Long, com.devops.platform.repository.BuildRepository, boolean)}）。 */
    public CreateResult createDatabase(String dbName, Long currentProjectId,
                                        com.devops.platform.repository.BuildRepository buildRepository) {
        return createDatabase(dbName, currentProjectId, buildRepository, false);
    }

    /**
     * 兼容旧调用（无冲突检测，建议迁移到 {@link #createDatabase(String, Long, com.devops.platform.repository.BuildRepository)}）。
     */
    public boolean createDatabase(String dbName) {
        CreateResult r = createDatabase(dbName, null, null);
        return r.success;
    }

    /**
     * 生成默认数据库名（含构建编号，确保每次部署独立数据库）。
     */
    public static String defaultDbName(String projectCode, String buildNumber) {
        if (projectCode == null || projectCode.isBlank()) return "devops_app_" + buildNumber;
        // 清理特殊字符，拼接构建编号
        String code = sanitizeDbName(projectCode);
        // 构建编号 #1 → 1，去掉 # 号
        String num = buildNumber != null ? buildNumber.replace("#", "") : "0";
        return "devops_" + code + "_" + num;
    }

    /**
     * 兼容旧调用（不含构建编号，仅用于历史代码迁移）。
     */
    public static String defaultDbName(String projectCode) {
        if (projectCode == null || projectCode.isBlank()) return "devops_app";
        return "devops_" + sanitizeDbName(projectCode);
    }

    /**
     * 净化数据库名（移除非法字符，仅保留字母数字下划线和连字符）。
     * 与 createDatabase 内部净化逻辑一致，确保存储的名称与数据库实际名称匹配。
     */
    public static String sanitizeDbName(String raw) {
        if (raw == null || raw.isBlank()) return "devops_app";
        // 去首尾空白；替换连续非法字符为单个下划线
        String clean = raw.trim().replaceAll("[^a-zA-Z0-9_\\-]+", "_");
        // MySQL 数据库名不能以连字符开关
        clean = clean.replaceAll("^-+", "").replaceAll("^_+", "");
        if (clean.isEmpty()) clean = "app";
        return clean;
    }

    /**
     * 在指定数据库里执行 SQL 脚本。
     * 自动剥离 CREATE DATABASE / USE 指令（脚本自带的目标库 ≠ 我们刚建的独立库），
     * 再按 ; 切分逐条执行（兼容 MyBatis/普通 SQL）。
     *
     * <p>关键: 用独立连接执行,SQL 全部带库名前缀,不污染 D 平台 dataSource 池
     *
     * @param sqlFilePath  SQL 文件绝对路径（来自 O 项目 src/main/resources/sql/init.sql）
     * @param targetDbName 要导入数据的目标库名（即刚建好的独立库）
     */
    public Map<String, Object> executeInitSql(Path sqlFilePath, String targetDbName) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("file", sqlFilePath.toString());
        result.put("targetDb", targetDbName);
        if (!Files.exists(sqlFilePath)) {
            result.put("success", false);
            result.put("message", "SQL 文件不存在: " + sqlFilePath);
            return result;
        }
        String content;
        try {
            content = Files.readString(sqlFilePath, StandardCharsets.UTF_8);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "读取 SQL 失败: " + e.getMessage());
            return result;
        }
        // 剥除 BOM
        if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
            content = content.substring(1);
        }
        // 按行剥除注释 / 空行（保留行内 -- 注释的"语句前注释"会跟着语句一起被丢弃）
        StringBuilder buf = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new StringReader(content))) {
            String line;
            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                if (trimmed.startsWith("--")) continue;
                // 行内注释: 去掉 -- 之后的内容
                int cIdx = trimmed.indexOf("--");
                if (cIdx >= 0) trimmed = trimmed.substring(0, cIdx).trim();
                if (!trimmed.isEmpty()) buf.append(trimmed).append("\n");
            }
        } catch (Exception e) {
            // should not happen
        }
        String cleaned = buf.toString();

        // 检测脚本里写死的数据库名(脚本里 CREATE DATABASE xxx / USE xxx)
        String hardcodedDb = null;
        java.util.regex.Matcher m1 = java.util.regex.Pattern
                .compile("(?i)CREATE\\s+DATABASE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?[`]?([\\w-]+)[`]?")
                .matcher(cleaned);
        if (m1.find()) hardcodedDb = m1.group(1);
        java.util.regex.Matcher m2 = java.util.regex.Pattern
                .compile("(?i)\\bUSE\\s+[`]?([\\w-]+)[`]?")
                .matcher(cleaned);
        if (m2.find()) hardcodedDb = m2.group(1);

        // 去掉 CREATE DATABASE / USE 语句（脚本可能写死 apartment_db，但我们要导入独立库）
        cleaned = cleaned.replaceAll("(?i)CREATE\\s+DATABASE[^;]*;", "");
        cleaned = cleaned.replaceAll("(?i)USE\\s+[`\\w-]+\\s*;", "");

        result.put("hardcodedDb", hardcodedDb);

        // 切分语句（用 ; 分隔；忽略最后空段）
        String[] stmts = cleaned.split(";\\s*\\n|;\\s*$");
        List<String> statements = new ArrayList<>();
        for (String s : stmts) {
            String t = s.trim();
            if (!t.isEmpty()) statements.add(t);
        }
        result.put("totalStatements", statements.size());

        long start = System.currentTimeMillis();
        int ok = 0, fail = 0;
        String firstError = null;
        // === 关键修复: 独立连接,不走 Hikari 池 ===
        // 之前用 dataSource.getConnection() 拿池化连接,再执行 USE <dbName>
        // 会污染连接的状态,D 平台 JPA 后续查询会用到这个切过的库
        // → 报 "Table 'devops_platform.projects' doesn't exist" 这类错
        // 现在: 自己创建独立连接,所有 SQL 都带库名前缀,关闭即彻底释放
        String host = System.getProperty("devops.platform.db.host");
        String port = System.getProperty("devops.platform.db.port");
        if (host == null || host.isBlank()) {
            // 兜底从环境变量取
            host = System.getenv().getOrDefault("DB_HOST", "localhost");
            port = System.getenv().getOrDefault("DB_PORT", "3306");
        }
        // 实际从 dataSource 的连接 URL 里提(更准)
        try (java.sql.Connection meta = dataSource.getConnection()) {
            java.sql.DatabaseMetaData md = meta.getMetaData();
            String url = md.getURL();
            // jdbc:mysql://host:port/db?params
            String hostPort = url.replaceFirst("jdbc:mysql://([^/?]+).*", "$1");
            host = hostPort.split(":")[0];
            port = hostPort.contains(":") ? hostPort.split(":")[1] : "3306";
        } catch (Exception ignored) {}
        String user = System.getenv().getOrDefault("DB_USER", "root");
        String pass = System.getenv().getOrDefault("DB_PASS", "Dearkoma.962464");
        String jdbcUrl = "jdbc:mysql://" + host + ":" + port
                + "/?useUnicode=true&characterEncoding=utf-8&useSSL=false"
                + "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(jdbcUrl, user, pass);
             java.sql.Statement stmt = conn.createStatement()) {
            for (String s : statements) {
                try {
                    // 每条 SQL 前加库名前缀,避免 USE 污染
                    String scoped = prefixDb(s, targetDbName);
                    stmt.executeUpdate(scoped);
                    ok++;
                } catch (Exception ex) {
                    fail++;
                    if (firstError == null) firstError = ex.getMessage();
                    log.warn("init.sql 单条失败(继续): {}", ex.getMessage());
                }
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "连接/执行失败: " + e.getMessage());
            result.put("costMs", System.currentTimeMillis() - start);
            return result;
        }
        long cost = System.currentTimeMillis() - start;
        result.put("success", fail == 0);
        result.put("ok", ok);
        result.put("fail", fail);
        result.put("costMs", cost);
        if (fail == 0) {
            String note = "";
            if (hardcodedDb != null && !hardcodedDb.equalsIgnoreCase(targetDbName)) {
                note = " (脚本里写死的库 " + hardcodedDb + " 已被前缀重定向到 " + targetDbName + ")";
            }
            result.put("message", "✅ 已导入 " + ok + " 条 SQL 到 " + targetDbName + note);
        } else {
            result.put("message", "⚠️ " + ok + " 成功 / " + fail + " 失败，首错: " + firstError);
        }
        return result;
    }

    /**
     * 给 SQL 加上库名前缀,把所有无前缀的表名限定到目标库。
     * <p>
     * 实现方式: 状态机式 SQL 解析器,而非脆弱的 12 条正则。
     * <ul>
     *   <li>先用 {@link #tokenize(String)} 剥除字符串字面量/反引号/注释,只保留纯 token 流</li>
     *   <li>识别每条语句的首关键字(CREATE/INSERT/ALTER/DROP/UPDATE/REPLACE/DELETE/RENAME/TRUNCATE)</li>
     *   <li>只在"语句起始 + 紧跟标识符"的位置加前缀,跳过:
     *     <ul>
     *       <li>字符串/反引号内的任何标识符(不影响用户数据)</li>
     *       <li>ON UPDATE / ON DELETE 这种子句里的标识符</li>
     *       <li>列定义里的类型/默认值/COMMENT(都是纯 token,不是表名)</li>
     *       <li>已经是 db.tbl 形式的全限定引用</li>
     *     </ul>
     *   </li>
     * </ul>
     * 这样 init.sql 里 "ON UPDATE CURRENT_TIMESTAMP" / "DEFAULT CURRENT_TIMESTAMP" /
     * "COMMENT '更新于 abc 时刻'" / "ENUM('Y','N')" 都不会被误伤。
     */
    private static String prefixDb(String sql, String db) {
        if (sql == null || sql.isBlank()) return sql;
        String s = sql.trim();

        // 已经是 `db`.`tbl` 形式(首 token 是 db.tbl),跳过
        // 通过 tokenize 检查
        List<String> toks = tokenize(s);
        if (toks.isEmpty()) return s;
        String first = toks.get(0).toUpperCase();
        if (!isDdlDmlFirstKeyword(first)) return s;
        // 如果第二个 token 是 db.tbl 形式(已限定),跳过
        if (toks.size() >= 2 && toks.get(1).contains(".")) return s;

        // 根据首关键字分派
        switch (first) {
            case "CREATE":
                return handleCreate(s, toks, db);
            case "INSERT":
            case "REPLACE":
                return handleInsertOrReplace(s, toks, db, first);
            case "ALTER":
                return handleAlter(s, toks, db);
            case "DROP":
                return handleDrop(s, toks, db);
            case "UPDATE":
                return handleUpdate(s, toks, db);
            case "DELETE":
                return handleDelete(s, toks, db);
            case "TRUNCATE":
                return handleTruncate(s, toks, db);
            case "RENAME":
                return handleRename(s, toks, db);
            default:
                return s;
        }
    }

    /** 判断是否是 DDL/DML 语句的首关键字 */
    private static boolean isDdlDmlFirstKeyword(String kw) {
        switch (kw) {
            case "CREATE": case "INSERT": case "REPLACE":
            case "ALTER": case "DROP": case "UPDATE":
            case "DELETE": case "TRUNCATE": case "RENAME":
                return true;
            default:
                return false;
        }
    }

    /**
     * 把 SQL 拆成 token 流。规则:
     * <ul>
     *   <li>空白字符/换行/分号: 跳过</li>
     *   <li>单引号 '...' : 整段作为单个 STRING token</li>
     *   <li>双引号 "..." : 同上(很少用,兼容)</li>
     *   <li>反引号 `...` : 同上(标识符引用)</li>
     *   <li>-- 行注释: 忽略该行</li>
     *   <li>/* ... * / 块注释: 跳过</li>
     *   <li>其他: 累计成 IDENT token,直到遇到空白/分号/引号/注释</li>
     * </ul>
     * 返回的 token 保留原始大小写,但首 token 调用方通常会 toUpperCase。
     */
    private static List<String> tokenize(String sql) {
        List<String> tokens = new ArrayList<>();
        int i = 0, n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            // 空白
            if (Character.isWhitespace(c)) { i++; continue; }
            // 行注释
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') i++;
                continue;
            }
            // 块注释
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) i++;
                i = Math.min(n, i + 2);
                continue;
            }
            // 字符串字面量
            if (c == '\'' || c == '"' || c == '`') {
                char quote = c;
                int start = i;
                i++;
                while (i < n && sql.charAt(i) != quote) {
                    if (sql.charAt(i) == '\\' && i + 1 < n) i += 2;
                    else i++;
                }
                i = Math.min(n, i + 1); // skip closing quote
                tokens.add(sql.substring(start, i));
                continue;
            }
            // 分号(语句结束) - 跳过
            if (c == ';') { i++; continue; }
            // 标识符/关键字
            int start = i;
            while (i < n) {
                char cc = sql.charAt(i);
                if (Character.isWhitespace(cc) || cc == ';' || cc == '\'' || cc == '"' || cc == '`'
                        || (cc == '-' && i + 1 < n && sql.charAt(i + 1) == '-')
                        || (cc == '/' && i + 1 < n && sql.charAt(i + 1) == '*')) break;
                i++;
            }
            if (i > start) tokens.add(sql.substring(start, i));
        }
        return tokens;
    }

    /** 找到第 n 个 token 的位置(从 0 开始),返回在原 sql 里的字符索引。找不到返回 -1。 */
    private static int indexOfToken(String sql, List<String> tokens, int n) {
        if (n >= tokens.size()) return -1;
        String target = tokens.get(n);
        // 简单做法: 从头扫 sql 找第一个非空白/非注释/非字符串字面量位置,其 token 等于 target
        int i = 0, cur = 0, nLen = sql.length();
        while (i < nLen) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c) || c == ';') { i++; continue; }
            if (c == '-' && i + 1 < nLen && sql.charAt(i + 1) == '-') {
                while (i < nLen && sql.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && i + 1 < nLen && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < nLen && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) i++;
                i = Math.min(nLen, i + 2);
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                char quote = c;
                i++;
                while (i < nLen && sql.charAt(i) != quote) {
                    if (sql.charAt(i) == '\\' && i + 1 < nLen) i += 2;
                    else i++;
                }
                i = Math.min(nLen, i + 1);
                continue;
            }
            int start = i;
            while (i < nLen) {
                char cc = sql.charAt(i);
                if (Character.isWhitespace(cc) || cc == ';' || cc == '\'' || cc == '"' || cc == '`'
                        || (cc == '-' && i + 1 < nLen && sql.charAt(i + 1) == '-')
                        || (cc == '/' && i + 1 < nLen && sql.charAt(i + 1) == '*')) break;
                i++;
            }
            if (cur == n) return start;
            cur++;
        }
        return -1;
    }

    /** 在 sql 里第 n 个 token 之前插入 prefix (例如 `db`.`),返回新 sql。 */
    private static String insertBeforeToken(String sql, List<String> tokens, int n, String prefix) {
        int idx = indexOfToken(sql, tokens, n);
        if (idx < 0) return sql;
        return sql.substring(0, idx) + prefix + sql.substring(idx);
    }

    /** 在 sql 里把第 n 个 token 替换为 newToken。 */
    private static String replaceToken(String sql, List<String> tokens, int n, String newToken) {
        int idx = indexOfToken(sql, tokens, n);
        if (idx < 0) return sql;
        int end = idx;
        while (end < sql.length()) {
            char c = sql.charAt(end);
            if (Character.isWhitespace(c) || c == ';' || c == '\'' || c == '"' || c == '`') break;
            end++;
        }
        return sql.substring(0, idx) + newToken + sql.substring(end);
    }

    // === 各语句类型的处理器 ===
    // 设计原则: 跳过字符串/反引号 token(它们是字面量/限定标识符,不是表名)
    //          跳过点 . 前的 token(它是 db 限定)
    //          在"语句起始 + 紧跟标识符"的位置加 db 前缀

    /** 跳过字符串/反引号/点前的部分,找下一个真正的"无前缀标识符 token"在 tokens 里的索引 */
    private static int nextTableTokenIndex(List<String> tokens, int from, Set<String> stopKeywords) {
        for (int i = from; i < tokens.size(); i++) {
            String t = tokens.get(i);
            // 字符串/反引号 token: 跳过
            if (t.startsWith("'") || t.startsWith("\"") || t.startsWith("`")) continue;
            // 已经是 db.tbl 形式: 跳过
            if (t.contains(".")) continue;
            // 关键字: 命中 stopKeywords 就返回 -1(说明表名没在期望位置)
            String upper = t.toUpperCase();
            if (stopKeywords.contains(upper)) return -1;
            // 否则这就是表名
            return i;
        }
        return -1;
    }

    private static final Set<String> SET_KW = Set.of("SET", "WHERE", "ORDER", "GROUP", "HAVING", "LIMIT", "VALUES");
    private static final Set<String> INSERT_KW = Set.of("VALUES", "SELECT", "SET");
    private static final Set<String> CREATE_TABLE_KW = Set.of("(", "IF");  // CREATE TABLE [IF NOT EXISTS] tbl ( ...
    private static final Set<String> DROP_OBJ = Set.of("TABLE", "INDEX", "VIEW", "TRIGGER", "DATABASE", "SCHEMA");

    private static String handleCreate(String sql, List<String> toks, String db) {
        if (toks.size() < 2) return sql;
        String second = toks.get(1).toUpperCase();
        // CREATE TABLE [IF NOT EXISTS] tbl
        if (second.equals("TABLE")) {
            // 找表名 token 的索引
            int idx = 2;
            if (idx < toks.size() && toks.get(idx).toUpperCase().equals("IF")) idx += 2; // IF NOT EXISTS
            if (idx >= toks.size()) return sql;
            String tblTok = toks.get(idx);
            if (tblTok.contains(".") || tblTok.startsWith("`")) return sql;
            // 在 idx 之前插入 db.
            String prefix = "`" + db + "`.";
            return insertBeforeToken(sql, toks, idx, prefix);
        }
        // CREATE [UNIQUE|FULLTEXT] INDEX idx ON tbl
        if (second.equals("UNIQUE") || second.equals("FULLTEXT")) {
            // 找 ON
            int onIdx = -1;
            for (int i = 2; i < toks.size(); i++) {
                if (toks.get(i).equalsIgnoreCase("ON")) { onIdx = i; break; }
            }
            if (onIdx < 0 || onIdx + 1 >= toks.size()) return sql;
            String tblTok = toks.get(onIdx + 1);
            if (tblTok.contains(".") || tblTok.startsWith("`")) return sql;
            return insertBeforeToken(sql, toks, onIdx + 1, "`" + db + "`.");
        }
        if (second.equals("INDEX")) {
            int onIdx = -1;
            for (int i = 2; i < toks.size(); i++) {
                if (toks.get(i).equalsIgnoreCase("ON")) { onIdx = i; break; }
            }
            if (onIdx < 0 || onIdx + 1 >= toks.size()) return sql;
            String tblTok = toks.get(onIdx + 1);
            if (tblTok.contains(".") || tblTok.startsWith("`")) return sql;
            return insertBeforeToken(sql, toks, onIdx + 1, "`" + db + "`.");
        }
        // CREATE TRIGGER / VIEW
        if (second.equals("TRIGGER") || second.equals("VIEW")) {
            if (toks.size() < 3) return sql;
            String nameTok = toks.get(2);
            if (nameTok.contains(".") || nameTok.startsWith("`")) return sql;
            return insertBeforeToken(sql, toks, 2, "`" + db + "`.");
        }
        return sql;
    }

    private static String handleInsertOrReplace(String sql, List<String> toks, String db, String kw) {
        // INSERT [INTO] tbl [ ... ]
        // REPLACE [INTO] tbl [ ... ]
        int idx = 1;
        if (idx < toks.size() && toks.get(idx).equalsIgnoreCase("INTO")) idx++;
        if (idx >= toks.size()) return sql;
        String tblTok = toks.get(idx);
        if (tblTok.contains(".") || tblTok.startsWith("`") || tblTok.startsWith("'")) return sql;
        return insertBeforeToken(sql, toks, idx, "`" + db + "`.");
    }

    private static String handleAlter(String sql, List<String> toks, String db) {
        // ALTER TABLE tbl ...
        if (toks.size() < 3) return sql;
        if (!toks.get(1).equalsIgnoreCase("TABLE")) return sql;
        String tblTok = toks.get(2);
        if (tblTok.contains(".") || tblTok.startsWith("`")) return sql;
        return insertBeforeToken(sql, toks, 2, "`" + db + "`.");
    }

    private static String handleDrop(String sql, List<String> toks, String db) {
        // DROP [TABLE|INDEX|VIEW|TRIGGER] [IF EXISTS] name
        int idx = 1;
        if (idx < toks.size() && DROP_OBJ.contains(toks.get(idx).toUpperCase())) idx++;
        if (idx < toks.size() && toks.get(idx).equalsIgnoreCase("IF")) idx += 2;
        if (idx >= toks.size()) return sql;
        String nameTok = toks.get(idx);
        if (nameTok.contains(".") || nameTok.startsWith("`")) return sql;
        // DROP DATABASE/SCHEMA 跳过(那是切库,我们剥了 CREATE DATABASE,这里也忽略)
        return insertBeforeToken(sql, toks, idx, "`" + db + "`.");
    }

    private static String handleUpdate(String sql, List<String> toks, String db) {
        // UPDATE tbl SET ... — 表名就是 toks[1]
        if (toks.size() < 2) return sql;
        String tblTok = toks.get(1);
        if (tblTok.contains(".") || tblTok.startsWith("`")) return sql;
        return insertBeforeToken(sql, toks, 1, "`" + db + "`.");
    }

    private static String handleDelete(String sql, List<String> toks, String db) {
        // DELETE FROM tbl WHERE ...
        if (toks.size() < 3) return sql;
        if (!toks.get(1).equalsIgnoreCase("FROM")) return sql;
        String tblTok = toks.get(2);
        if (tblTok.contains(".") || tblTok.startsWith("`")) return sql;
        return insertBeforeToken(sql, toks, 2, "`" + db + "`.");
    }

    private static String handleTruncate(String sql, List<String> toks, String db) {
        // TRUNCATE [TABLE] tbl
        int idx = 1;
        if (idx < toks.size() && toks.get(idx).equalsIgnoreCase("TABLE")) idx++;
        if (idx >= toks.size()) return sql;
        String tblTok = toks.get(idx);
        if (tblTok.contains(".") || tblTok.startsWith("`")) return sql;
        return insertBeforeToken(sql, toks, idx, "`" + db + "`.");
    }

    private static String handleRename(String sql, List<String> toks, String db) {
        // RENAME TABLE tbl TO newtbl
        if (toks.size() < 2) return sql;
        if (!toks.get(1).equalsIgnoreCase("TABLE")) return sql;
        if (toks.size() < 3) return sql;
        String tblTok = toks.get(2);
        if (tblTok.contains(".") || tblTok.startsWith("`")) return sql;
        return insertBeforeToken(sql, toks, 2, "`" + db + "`.");
    }


    /**
     * 在 O 项目 workspace 里找 SQL 初始化脚本。
     * 搜索位置（按优先级）：
     * 1) workspace/src/main/resources/sql/init.sql
     * 2) workspace/sql/init.sql
     * 3) workspace/<子项目>/src/main/resources/sql/init.sql
     * 4) workspace/db/init.sql
     *
     * @return 第一个找到的 SQL 文件路径；没找到返回 null
     */
    public Path findInitSql(Path workspacePath) {
        String[] candidates = {
                "src/main/resources/sql/init.sql",
                "sql/init.sql",
                "db/init.sql",
                "database/init.sql",
                "src/main/resources/db/init.sql"
        };
        if (!Files.exists(workspacePath)) return null;
        // 直接命中
        for (String rel : candidates) {
            Path p = workspacePath.resolve(rel);
            if (Files.exists(p)) return p;
        }
        // 一级子目录里找（如 backend/）
        try (var stream = Files.list(workspacePath)) {
            for (Path child : stream.toList()) {
                if (!Files.isDirectory(child)) continue;
                for (String rel : candidates) {
                    Path p = child.resolve(rel);
                    if (Files.exists(p)) return p;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
