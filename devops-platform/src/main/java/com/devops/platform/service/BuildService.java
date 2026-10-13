package com.devops.platform.service;

import com.devops.platform.entity.*;
import com.devops.platform.repository.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 构建执行服务
 * 支持 WebSocket 实时日志、Webhook 触发、参数化构建、制品收集、通知推送
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuildService {

    private final BuildRepository buildRepository;
    private final ProjectRepository projectRepository;
    private final PipelineRepository pipelineRepository;
    private final ArtifactRepository artifactRepository;
    private final NotificationService notificationService;
    private final ServiceInstanceRepository instanceRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final DatabaseProvisioningService dbProvisioningService;
    private final DeploymentStrategyService deploymentStrategyService;
    private final SecurityScanService securityScanService;
    private final com.devops.platform.repository.SecurityReportRepository securityReportRepository;
    private final com.devops.platform.service.ArtifactVersionService artifactVersionService;
    private final com.devops.platform.service.WebhookDispatcher webhookDispatcher;
    private final BuildVariableService buildVariableService;
    private final SecretService secretService;
    private final TestReportService testReportService;
    private final SastScanService sastScanService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<Long, Process> runningProcesses = new ConcurrentHashMap<>();
    private final Map<Long, StringBuilder> logCache = new ConcurrentHashMap<>();

    // ===== 构建队列与并发控制 =====
    /** 项目级信号量：同一时间每个项目只允许 1 个构建在执行 */
    private final Map<Long, java.util.concurrent.Semaphore> projectLocks = new ConcurrentHashMap<>();
    /** 项目级等待队列：key=projectId, value=排队中的 buildId 列表 */
    private final Map<Long, java.util.Queue<Long>> projectQueues = new ConcurrentHashMap<>();
    /** 全局并行上限：最多同时执行 8 个构建（与线程池 maxPoolSize 一致） */
    private final java.util.concurrent.Semaphore globalSlots = new java.util.concurrent.Semaphore(8);

    @Value("${devops.pipeline.workspace-dir:./data/workspace}")
    private String workspaceDir;

    /* ---------- lazy-resolved absolute workspace root ---------- */
    private volatile Path workspaceRoot;

    /**
     * 当前构建的流水线上下文（@Async 线程内有效）
     * 用途：让同一项目的不同流水线使用独立工作目录，避免 git pull 冲突
     */
    private final ThreadLocal<com.devops.platform.entity.Pipeline> currentPipeline = new ThreadLocal<>();

    /**
     * 本次构建实际使用的绝对工作目录。
     * <p>
     * workspaceDirName() 依赖 currentPipeline 这个 ThreadLocal，不同调用点取到的
     * 流水线上下文可能不一致，导致「准备代码」与「收集制品」算出两个不同的目录
     * （例如 apartnment 与 apartnment-default），制品因此收集不到。
     * 这里在代码准备阶段把实际目录记下来，构建收尾统一读它，避免再算一次。
     */
    private final ThreadLocal<String> currentWorkspace = new ThreadLocal<>();

    /** 绝对路径的日志目录（避免 @Async 线程工作目录不一致导致的文件找不到） */
    private Path absoluteLogDir;

    /**
     * 工作目录名：项目编码 + 流水线名（净化后）
     * 同一项目下不同流水线互不影响，旧 commit 不会污染新流水线的 git pull
     * 回退：流水线名为空时（如 checkWorkspace API）回退到项目编码
     */
    private String workspaceDirName(Project project) {
        com.devops.platform.entity.Pipeline p = currentPipeline.get();
        if (p != null && p.getName() != null && !p.getName().isBlank()) {
            return project.getCode() + "-" + sanitizeFsName(p.getName());
        }
        return project.getCode();
    }

    /** 把流水线名转成合法文件/目录名（仅允许字母数字-_） */
    private static String sanitizeFsName(String raw) {
        if (raw == null) return "default";
        String clean = raw.trim().replaceAll("[^a-zA-Z0-9_-]+", "_");
        clean = clean.replaceAll("^[_-]+|[_-]+$", "");
        return clean.isEmpty() ? "default" : clean.toLowerCase();
    }

    /**
     * K8s 资源名（Deployment/Service/app 标签）：按流水线名隔离
     * 同项目不同流水线 → 独立 Deployment，互不冲突
     * 回退：无流水线上下文时用 projectCode（兼容 checkWorkspace 等场景）
     */
    private String k8sAppName(Project project) {
        return workspaceDirName(project);
    }

    @PostConstruct
    void initPaths() throws IOException {
        Path p = Paths.get(logDir).toAbsolutePath().normalize();
        Files.createDirectories(p);
        this.absoluteLogDir = p;
        log.info("日志目录: absoluteLogDir={}", absoluteLogDir);
    }

    @Value("${devops.pipeline.log-dir:./data/logs}")
    private String logDir;

    @Value("${devops.pipeline.simulation-mode:true}")
    private boolean simulationMode;

    @Value("${devops.pipeline.shell.command:cmd /c}")
    private String shellCommand;

    @Value("${devops.pipeline.docker.registry:}")
    private String dockerRegistry;

    @Value("${devops.pipeline.k8s.namespace:default}")
    private String k8sNamespace;

    @Value("${devops.pipeline.timeout-ms:600000}")
    private long timeoutMs;

    @Value("${devops.pipeline.docker.skip-if-unavailable:true}")
    private boolean dockerSkipIfUnavailable;

    @Value("${devops.pipeline.docker.command:docker}")
    private String dockerCommand;

    @Value("${devops.pipeline.k8s.skip-if-unavailable:true}")
    private boolean k8sSkipIfUnavailable;

    /** 缓存 Docker/K8s 可用性检测结果，避免重复检测 */
    private Boolean dockerAvailable = null;
    private Boolean kubectlAvailable = null;

    /** WebSocket 日志推送 */
    private void appendLog(Long buildId, String logText) {
        if (buildId != null) {
            try {
                Map<String, Object> payload = new HashMap<>();
                payload.put("buildId", buildId);
                payload.put("log", logText);
                payload.put("timestamp", System.currentTimeMillis());
                messagingTemplate.convertAndSend("/topic/builds/" + buildId + "/log", payload);
            } catch (Exception e) {
                // WebSocket 推送失败不阻塞构建
            }
        }
    }

    /**
     * 检查数据库名是否与其他项目冲突（构建弹窗实时预览用）。
     * @return Map with keys: conflict (boolean), conflictProject, dbExists
     */
    public Map<String, Object> checkDbConflict(String dbName, Long currentProjectId) {
        Map<String, Object> result = new LinkedHashMap<>();
        String safeName = DatabaseProvisioningService.sanitizeDbName(dbName);
        result.put("dbName", safeName);
        result.put("conflict", false);
        result.put("dbExists", false);

        // db_type 为 NULL 时默认 MySQL，仅显式 H2 才跳过
        boolean useH2 = true;
        if (currentProjectId != null) {
            Project project = projectRepository.findById(currentProjectId).orElse(null);
            useH2 = project == null || "H2".equalsIgnoreCase(project.getDbType());
        }
        if (useH2) {
            result.put("skipCheck", true);
            return result;
        }

        boolean exists = dbProvisioningService.databaseExists(safeName);
        result.put("dbExists", exists);

        if (exists && currentProjectId != null) {
            List<Build> conflictBuilds = buildRepository.findByDbNameAndProjectIdNot(safeName, currentProjectId);
            if (!conflictBuilds.isEmpty()) {
                result.put("conflict", true);
                result.put("conflictProject", "Project#" + conflictBuilds.get(0).getProjectId());
                result.put("conflictProjectId", conflictBuilds.get(0).getProjectId());
            } else {
                result.put("reusedByCurrent", true); // 同项目重建
            }
        }
        return result;
    }

    /** 触发构建（简便重载） */
    public Build triggerBuild(Long projectId, Long pipelineId, String triggeredBy) {
        return triggerBuild(projectId, pipelineId, triggeredBy, null, null, false, false, null);
    }

    /** 参数化触发构建（含独立跳过标志、数据库名称） */
    public Build triggerBuild(Long projectId, Long pipelineId, String triggeredBy,
                              String buildParams, String branch, boolean skipDocker, boolean skipK8s,
                              String dbName) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在: " + projectId));
        Pipeline pipeline = pipelineRepository.findById(pipelineId)
                .orElseThrow(() -> new RuntimeException("流水线不存在: " + pipelineId));

        // 构建编号原子性递增（synchronized 避免并发时编号重复）
        long count;
        synchronized (this) {
            count = buildRepository.count();
        }
        String buildNumber = "#" + (count + 1);

        // 数据库名：用户指定 > 默认规则 devops_<projectCode>_<buildNumber>
        // 每次构建生成独立数据库，确保新部署的实例是全新的（0条数据）
        // 当项目选 H2 时,useH2=true,数据库名也不会被使用,保留占位即可
        String effectiveDbName = (dbName != null && !dbName.isBlank())
                ? DatabaseProvisioningService.sanitizeDbName(dbName)
                : DatabaseProvisioningService.defaultDbName(project.getCode(), buildNumber);

        // db_type 为 NULL 时默认 MySQL（大多数 Spring Boot 项目用 MySQL）
        // 仅显式声明 H2 时才跳过数据库供应
        boolean useH2 = "H2".equalsIgnoreCase(project.getDbType());

        // 早期同步阶段也需要 currentPipeline 上下文(用于 findInitSql workspace 路径)
        currentPipeline.set(pipeline);
        try {
        if (!useH2) {
            // 每次构建都生成全新的独立数据库：freshIsolated=true 让已有同名库先删再建
            // 这样 O 项目每次部署都是 0 条数据，真正做到实例隔离
            DatabaseProvisioningService.CreateResult dbResult =
                    dbProvisioningService.createDatabase(effectiveDbName, projectId, buildRepository, true);
            if (!dbResult.success) {
                if (dbResult.conflictProjectId != null) {
                    throw new RuntimeException(String.format(
                            "数据库 '%s' 已被项目 %s 占用，请更换数据库名后重试",
                            dbResult.dbName, dbResult.conflictProjectName));
                }
                throw new RuntimeException("数据库 '" + effectiveDbName + "' 创建失败");
            }

            // 同步导入 O 项目的 init.sql（如果有）
            // 时机：库刚建好,所有表都空,O 项目还没启动 → 不会有连接冲突
            // 关键: 用 currentPipeline 感知的 workspace 目录(流水线隔离),
            //       避免多流水线时找错 workspace
            //
            // 【防误删D平台表】
            //   D 平台的 Spring dataSource 已绑定到 devops_platform 库
            //   但用户填的 dbName 可能等于 devops_platform
            //   这种情况下 createDatabase 的 DROP DATABASE 会删 D 平台自己的库
            //   executeInitSql 的 USE 也会切到 D 平台库执行 O 项目的 SQL
            //   必须拦截:
            //   1) dbName 撞 D 平台库 → 拒绝构建
            //   2) init.sql 里的 hardcodedDb 撞 D 平台库 → 拒绝执行
            //   3) 实际执行前再核一次 USE 切库成功
            String PLATFORM_DB = "devops_platform";
            if (effectiveDbName.equalsIgnoreCase(PLATFORM_DB)) {
                throw new RuntimeException(
                        "禁止把 dbName 设为 D 平台自身的库 " + PLATFORM_DB +
                        "（会与 D 平台表冲突）。请使用默认 dbName 或自定义其他名字");
            }
            try {
                Path workspace = Paths.get(workspaceDir).toAbsolutePath().normalize()
                        .resolve(workspaceDirName(project));
                Path sqlFile = dbProvisioningService.findInitSql(workspace);
                if (sqlFile != null) {
                    log.info("[triggerBuild] 发现 O 项目 SQL 脚本: {}", sqlFile);
                    Map<String, Object> importResult = dbProvisioningService.executeInitSql(sqlFile, effectiveDbName);
                    // 安全检查: 如果脚本里写死的库是 D 平台自身的库, 终止
                    Object hardcoded = importResult.get("hardcodedDb");
                    if (hardcoded != null && PLATFORM_DB.equalsIgnoreCase(hardcoded.toString())) {
                        throw new RuntimeException(
                                "init.sql 里的 hardcodedDb=" + hardcoded +
                                " 是 D 平台自身的库, 拒绝执行（避免 O 项目 SQL 写入 D 平台表）");
                    }
                    log.info("[triggerBuild] SQL 导入结果: success={} ok={} fail={} hardcodedDb={}",
                            importResult.get("success"), importResult.get("ok"),
                            importResult.get("fail"), hardcoded);
                } else {
                    log.info("[triggerBuild] O 项目未发现 init.sql,跳过");
                }
            } catch (Exception ex) {
                log.warn("[triggerBuild] SQL 导入异常(不阻塞构建): {}", ex.getMessage());
            }
        }
        } finally {
            // currentPipeline 在 executeBuildAsync 入口会重新 set,这里清掉即可
            currentPipeline.remove();
        }

        Build build = new Build();
        build.setProjectId(projectId);
        build.setPipelineId(pipelineId);
        build.setBuildNumber(buildNumber);
        build.setStatus(null);  // 稍后根据队列状态设置
        build.setTriggeredBy(triggeredBy != null ? triggeredBy : "admin");
        build.setStartTime(LocalDateTime.now());
        build.setBuildParams(buildParams);
        build.setBranch(branch != null ? branch : project.getGitBranch());
        build.setSkipDocker(skipDocker);
        build.setSkipK8s(skipK8s);
        build.setDbName(effectiveDbName);
        build = buildRepository.save(build);

        // ===== 构建队列与并发控制 =====
        // 检查该项目是否已有正在执行或排队的构建
        boolean hasRunning = buildRepository.existsByProjectIdAndStatusIn(
                projectId, List.of("RUNNING", "QUEUED"));

        if (hasRunning) {
            // 项目已有构建在执行/排队 → 加入队列
            build.setStatus("QUEUED");
            build.setResultMessage("排队中，前方有构建正在执行");
            buildRepository.save(build);
            log.info("[QUEUE] 构建 {} 已加入项目 {} 的等待队列", build.getBuildNumber(), projectId);

            // 加入项目级队列
            projectQueues.computeIfAbsent(projectId, k -> new java.util.concurrent.ConcurrentLinkedQueue<>())
                    .add(build.getId());
        } else {
            // 项目空闲 → 尝试获取全局槽位并立即执行
            build.setStatus("PENDING");
            buildRepository.save(build);
            executeBuildAsync(build.getId(), project, pipeline);
        }

        return build;
    }

    /** Webhook 触发构建 */
    public Build triggerBuildByWebhook(Long projectId, String branch, String committer, String commit) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在: " + projectId));

        // 查找匹配分支的活跃流水线
        List<Pipeline> pipelines = pipelineRepository.findByProjectIdAndStatus(projectId, "ACTIVE");
        Pipeline matched = null;

        for (Pipeline p : pipelines) {
            if (p.getBranchPattern() == null || p.getBranchPattern().isBlank()) {
                matched = p; // 无分支限制，匹配第一个
                break;
            }
            // 检查分支是否匹配
            if (branch != null && matchesBranch(branch, p.getBranchPattern())) {
                matched = p;
                break;
            }
        }

        if (matched == null && !pipelines.isEmpty()) {
            matched = pipelines.get(0); // fallback 到第一个活跃流水线
        }
        if (matched == null) {
            throw new RuntimeException("项目没有活跃的流水线");
        }

        String triggerType = "PUSH";
        Build build = triggerBuild(projectId, matched.getId(), committer, null, branch, false, false, null);
        build.setTriggerType(triggerType);
        build.setGitCommit(commit);
        buildRepository.save(build);

        return build;
    }

    private boolean matchesBranch(String branch, String pattern) {
        for (String p : pattern.split(",")) {
            p = p.trim();
            if (p.equals(branch)) return true;
            if (p.endsWith("/*")) {
                String prefix = p.substring(0, p.length() - 1);
                if (branch.startsWith(prefix)) return true;
            }
        }
        return false;
    }

    @Async
    public void executeBuildAsync(Long buildId, Project project, Pipeline pipeline) {
        currentPipeline.set(pipeline);

        // ===== 获取构建槽位（全局 + 项目级） =====
        boolean acquiredGlobal = false;
        boolean acquiredProject = false;
        java.util.concurrent.Semaphore projectLock = projectLocks.computeIfAbsent(
                project.getId(), k -> new java.util.concurrent.Semaphore(1));

        try {
            // 1) 尝试获取全局槽位（最多等待 30 分钟，排队期间可被取消）
            try {
                acquiredGlobal = globalSlots.tryAcquire(30, java.util.concurrent.TimeUnit.MINUTES);
                if (!acquiredGlobal) {
                    markBuildFailed(buildId, project, "全局构建槽位等待超时（30分钟），请稍后重试");
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                markBuildFailed(buildId, project, "排队被中断");
                return;
            }

            // 2) 尝试获取项目级锁（最多等待 30 分钟）
            try {
                acquiredProject = projectLock.tryAcquire(30, java.util.concurrent.TimeUnit.MINUTES);
                if (!acquiredProject) {
                    markBuildFailed(buildId, project, "项目构建锁等待超时（30分钟）");
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                markBuildFailed(buildId, project, "排队被中断");
                return;
            }

            // 检查构建是否在排队期间被取消
            Build preCheck = buildRepository.findById(buildId).orElse(null);
            if (preCheck != null && "CANCELLED".equals(preCheck.getStatus())) {
                return; // 已被取消，不执行
            }

            // 更新状态：QUEUED → RUNNING
            if (preCheck != null && "QUEUED".equals(preCheck.getStatus())) {
                preCheck.setStatus("RUNNING");
                preCheck.setStartTime(LocalDateTime.now());
                preCheck.setResultMessage(null);
                buildRepository.save(preCheck);
                log.info("[QUEUE] 构建 {} 从队列中释放，开始执行", preCheck.getBuildNumber());
            }

        Build build = buildRepository.findById(buildId).orElse(null);
        if (build == null) return;

        StringBuilder logBuf = new StringBuilder();
        logCache.put(buildId, logBuf);

        try {
            build.setStatus("RUNNING");
            buildRepository.save(build);
            String mode = simulationMode ? "【模拟模式】" : "【真实模式】";
            String triggerInfo = build.getTriggerType() != null && !"MANUAL".equals(build.getTriggerType())
                    ? " [" + build.getTriggerType() + "]" : "";
            logBuf.append("[INFO] ").append(LocalDateTime.now())
                    .append(" 开始构建 ").append(build.getBuildNumber())
                    .append(" ").append(mode).append(triggerInfo).append("\n");
            logBuf.append("[INFO] 项目: ").append(project.getName()).append("\n");
            logBuf.append("[INFO] 流水线: ").append(pipeline.getName()).append("\n");
            logBuf.append("[INFO] 分支: ")
                    .append(build.getBranch() != null && !build.getBranch().isBlank()
                            ? build.getBranch() : "(未指定, 跟随远端默认分支)").append("\n");
            if (build.getBuildParams() != null) {
                logBuf.append("[INFO] 构建参数: ").append(build.getBuildParams()).append("\n");
            }
            boolean skipDocker = build.getSkipDocker() != null && build.getSkipDocker();
            boolean skipK8s = build.getSkipK8s() != null && build.getSkipK8s();
            String deployDesc = "编译+测试";
            if (!skipDocker) deployDesc += " + Docker";
            if (!skipK8s) deployDesc += " + K8s";
            logBuf.append("[INFO] 部署选项: ").append(deployDesc).append("\n");
            logBuf.append("[INFO] 工作目录: ").append(getProjectWorkspace(project)).append("\n\n");
            appendLog(buildId, logBuf.toString());

            if (!simulationMode) {
                boolean prepared = prepareWorkspace(project, logBuf, buildId, build.getBranch());
                if (!prepared) {
                    throw new RuntimeException("代码准备失败");
                }
            }

            List<StageDef> stages = parsePipelineDefinition(pipeline.getDefinition(), project);
            build.setTotalSteps(stages.stream().mapToInt(s -> s.steps.size()).sum());
            buildRepository.save(build);

            long startTime = System.currentTimeMillis();
            int completedSteps = build.getCompletedSteps() != null ? build.getCompletedSteps() : 0;
            int globalStepIndex = 0;  // 全局步骤索引，用于断点续跑定位

            for (int si = 0; si < stages.size(); si++) {
                StageDef stage = stages.get(si);
                // 根据部署目标跳过不相关的阶段
                if (shouldSkipStage(stage, build)) {
                    logBuf.append("========== 阶段: ").append(stage.name)
                          .append(" 【已跳过】 ==========\n");
                    appendLog(buildId, logBuf.toString());
                    // 跳过的阶段也要推进 globalStepIndex
                    globalStepIndex += stage.steps.size();
                    continue;
                }

                // ===== 断点续跑：跳过已完成的阶段 =====
                if (Boolean.TRUE.equals(build.getResumeFromFailure())
                        && build.getFailedStageIndex() != null
                        && si < build.getFailedStageIndex()) {
                    logBuf.append("========== 阶段: ").append(stage.name)
                          .append(" 【已跳过(断点续跑)】 ==========\n");
                    appendLog(buildId, logBuf.toString());
                    globalStepIndex += stage.steps.size();
                    completedSteps += stage.steps.size();
                    continue;
                }

                logBuf.append("========== 阶段: ").append(stage.name).append(" ==========\n");
                appendLog(buildId, logBuf.toString());

                if (stage.parallel && stage.steps.size() > 1) {
                    // ===== 阶段内步骤并行执行 =====
                    logBuf.append("[PARALLEL] 本阶段 ").append(stage.steps.size())
                          .append(" 个步骤将并行执行\n");
                    appendLog(buildId, logBuf.toString());

                    List<java.util.concurrent.CompletableFuture<StepResult>> futures = new ArrayList<>();
                    int baseIndex = globalStepIndex;
                    for (int pi = 0; pi < stage.steps.size(); pi++) {
                        StepDef step = stage.steps.get(pi);
                        final int stepGlobalIdx = baseIndex + pi;
                        final int stepPi = pi;  // 捕获为 effectively-final 供 lambda 使用
                        // 断点续跑：跳过已完成的步骤
                        if (Boolean.TRUE.equals(build.getResumeFromFailure())
                                && build.getFailedStageIndex() != null
                                && si == build.getFailedStageIndex()
                                && build.getFailedStepIndex() != null
                                && stepPi < build.getFailedStepIndex()) {
                            completedSteps++;
                            continue;
                        }
                        final String stepLogPrefix = "[STEP-" + (stepPi + 1) + "/" + stage.steps.size() + "] ";
                        futures.add(asyncWithContext(() -> {
                            StringBuilder stepLog = new StringBuilder();
                            stepLog.append(stepLogPrefix).append("执行: ").append(step.name)
                                   .append(" (").append(step.type).append(")\n");
                            boolean ok = executeStepWithRetry(step, project, stepLog, buildId);
                            return new StepResult(stepPi, ok, stepLog.toString());
                        }));
                    }

                    // 等待所有并行步骤完成
                    java.util.concurrent.CompletableFuture.allOf(
                            futures.toArray(new java.util.concurrent.CompletableFuture[0])).join();

                    boolean anyFailed = false;
                    for (java.util.concurrent.CompletableFuture<StepResult> f : futures) {
                        StepResult sr = f.get();
                        logBuf.append(sr.log);
                        if (!sr.success) {
                            anyFailed = true;
                            // 记录第一个失败步骤的索引
                            build.setFailedStageIndex(si);
                            build.setFailedStepIndex(sr.stepIndex);
                        }
                        completedSteps++;
                    }
                    if (anyFailed) {
                        throw new RuntimeException("并行阶段执行失败: " + stage.name);
                    }
                    globalStepIndex = baseIndex + stage.steps.size();

                } else {
                    // ===== 阶段内步骤串行执行（原有逻辑 + 重试/超时/断点续跑） =====
                    for (int pi = 0; pi < stage.steps.size(); pi++) {
                        StepDef step = stage.steps.get(pi);

                        // 断点续跑：跳过失败阶段中已完成的步骤
                        if (Boolean.TRUE.equals(build.getResumeFromFailure())
                                && build.getFailedStageIndex() != null
                                && si == build.getFailedStageIndex()
                                && build.getFailedStepIndex() != null
                                && pi < build.getFailedStepIndex()) {
                            logBuf.append("[STEP] 跳过(已完成): ").append(step.name).append("\n");
                            appendLog(buildId, logBuf.toString());
                            completedSteps++;
                            globalStepIndex++;
                            continue;
                        }

                        // 条件判断：when 不满足则跳过
                        if (!evaluateWhen(step, build, project)) {
                            logBuf.append("[STEP] 跳过(条件不满足): ").append(step.name).append("\n");
                            if (step.when != null) {
                                logBuf.append("[WHEN] ").append(step.when).append("\n");
                            }
                            appendLog(buildId, logBuf.toString());
                            completedSteps++;
                            globalStepIndex++;
                            continue;
                        }

                        logBuf.append("[STEP] 执行: ").append(step.name)
                              .append(" (").append(step.type).append(")");
                        if (step.retryCount > 0) {
                            logBuf.append(" [重试≤").append(step.retryCount).append("次]");
                        }
                        if (step.timeoutSeconds > 0) {
                            logBuf.append(" [超时").append(step.timeoutSeconds).append("s]");
                        }
                        logBuf.append("\n");
                        appendLog(buildId, logBuf.toString());

                        boolean success = executeStepWithRetry(step, project, logBuf, buildId);
                        if (!success) {
                            // 记录失败位置，支持断点续跑
                            build.setFailedStageIndex(si);
                            build.setFailedStepIndex(pi);
                            throw new RuntimeException("步骤执行失败: " + step.name);
                        }

                        completedSteps++;
                        globalStepIndex++;
                        build.setCompletedSteps(completedSteps);
                        buildRepository.save(build);
                        logBuf.append("[SUCCESS] 步骤完成: ").append(step.name).append("\n\n");
                        appendLog(buildId, logBuf.toString());
                    }
                }
            }

            long endTime = System.currentTimeMillis();
            build.setStatus("SUCCESS");
            build.setEndTime(LocalDateTime.now());
            build.setDurationMs(endTime - startTime);
            build.setCompletedSteps(completedSteps);
            build.setResultMessage("构建成功");
            logBuf.append("\n[INFO] 构建完成！耗时: ").append(endTime - startTime).append("ms\n");

            // 构建结束后, 汇总本次的安全扫描报告
            try {
                var reports = securityReportRepository.findByBuildIdOrderByScannedAtDesc(buildId);
                if (!reports.isEmpty()) {
                    logBuf.append("\n========== 安全扫描汇总 ==========\n");
                    for (var r : reports) {
                        logBuf.append(String.format(
                                "  [%s] %s → %s (CRITICAL=%d, HIGH=%d, MEDIUM=%d, LOW=%d, 合计=%d, 阈值=critical≤%d/high≤%d)\n",
                                r.getScanType(), r.getTarget(),
                                r.getStatus(), r.getCriticalCount(), r.getHighCount(),
                                r.getMediumCount(), r.getLowCount(), r.getTotalCount(),
                                r.getThresholdCritical(), r.getThresholdHigh()));
                    }
                    logBuf.append("[HINT] 查看详情: GET /api/security/reports/build/").append(buildId).append("\n");
                }
            } catch (Exception ex) {
                log.warn("汇总安全报告失败: {}", ex.getMessage());
            }

            appendLog(buildId, logBuf.toString());

            project.setStatus("DEPLOYED");
            projectRepository.save(project);

            // 收集制品
            collectArtifacts(build, project, logBuf);

            // 记录服务实例（如果部署了）
            recordServiceInstance(build, project, logBuf);

            // 保存构建缓存
            try {
                saveBuildCache(Paths.get(getProjectWorkspace(project)), project, logBuf);
            } catch (Exception ex) { log.warn("保存缓存失败: {}", ex.getMessage()); }

            // 发送成功通知
            notificationService.buildSuccess(build.getBuildNumber(), build.getId(), project.getName());

            // 触发 build.success 事件 (Webhook 派发)
            try {
                webhookDispatcher.dispatch(com.devops.platform.entity.WebhookSubscription.EVENT_BUILD_SUCCESS, java.util.Map.of(
                        "title", "构建成功 " + build.getBuildNumber(),
                        "project", project.getName(),
                        "projectId", String.valueOf(project.getId()),
                        "buildId", String.valueOf(build.getId()),
                        "duration", (endTime - startTime) + "ms"
                ), project.getId());
            } catch (Exception ignored) {}

        } catch (Exception e) {
            logBuf.append("\n[ERROR] 构建失败: ").append(e.getMessage()).append("\n");
            appendLog(buildId, logBuf.toString());
            build.setStatus("FAILED");
            build.setEndTime(LocalDateTime.now());

            build.setResultMessage("构建失败: " + e.getMessage());
            project.setStatus("FAILED");
            projectRepository.save(project);

            // 发送失败通知
            notificationService.buildFailed(build.getBuildNumber(), build.getId(),
                    project.getName(), e.getMessage());

            // 触发 build.failed 事件 (Webhook 派发)
            try {
                webhookDispatcher.dispatch(com.devops.platform.entity.WebhookSubscription.EVENT_BUILD_FAILED, java.util.Map.of(
                        "title", "构建失败 " + build.getBuildNumber(),
                        "project", project.getName(),
                        "projectId", String.valueOf(project.getId()),
                        "buildId", String.valueOf(build.getId()),
                        "error", e.getMessage() == null ? "" : e.getMessage()
                ), project.getId());
            } catch (Exception ignored) {}
        } finally {
            buildRepository.save(build);
            appendLog(buildId, logBuf.toString());
            Map<String, Object> endPayload = new HashMap<>();
            endPayload.put("buildId", buildId);
            endPayload.put("status", build.getStatus());
            endPayload.put("log", logBuf.toString());
            endPayload.put("timestamp", System.currentTimeMillis());
            endPayload.put("finished", true);
            messagingTemplate.convertAndSend("/topic/builds/" + buildId + "/log", endPayload);

            saveLogFile(buildId, logBuf.toString());
            logCache.remove(buildId);
            runningProcesses.remove(buildId);
        }
        } finally {
            currentPipeline.remove();
            currentWorkspace.remove();

            // ===== 释放锁并处理队列 =====
            if (acquiredProject) {
                projectLock.release();
                // 检查该项目是否还有排队的构建
                dequeueNext(project.getId());
            }
            if (acquiredGlobal) {
                globalSlots.release();
            }
        }
    }

    /** 收集构建制品 */
    /**
     * 把当前的「流水线 / 工作目录」上下文带进异步任务。
     * <p>
     * 步骤执行被包在 CompletableFuture 里做并行与超时控制，实际跑在
     * ForkJoinPool 线程上。ThreadLocal 不会自动传递，异步线程里
     * workspaceDirName() 因取不到流水线而算出另一个目录，于是出现
     * 「准备代码在 apartnment-default、Maven 却构建在 apartnment」的
     * 分裂，制品收集因此扫不到文件。这里显式把上下文复制过去。
     */
    private <T> java.util.concurrent.CompletableFuture<T> asyncWithContext(
            java.util.function.Supplier<T> task) {
        final com.devops.platform.entity.Pipeline pipeline = currentPipeline.get();
        final String workspace = currentWorkspace.get();
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            if (pipeline != null) currentPipeline.set(pipeline);
            if (workspace != null) currentWorkspace.set(workspace);
            try {
                return task.get();
            } finally {
                currentPipeline.remove();
                currentWorkspace.remove();
            }
        });
    }

    private void collectArtifacts(Build build, Project project, StringBuilder logBuf) {
        if (simulationMode) {
            logBuf.append("[INFO] 模拟模式，跳过制品收集\n");
            return;
        }
        try {
            // 用与构建步骤相同的目录解析：monorepo 时真正的构建根目录在子目录
            // （如 workspace/backend），制品在它的 target 下而不是工作区根目录。
            String workspace = findProjectRoot(project).toString();
            String lang = project.getLanguage() != null ? project.getLanguage().toLowerCase() : "java";

            // 制品搜索: (目录, 后缀列表)
            List<Path[]> searchPaths = new ArrayList<>();
            if (lang.contains("node")) {
                searchPaths.add(new Path[]{Paths.get(workspace, "dist"), Paths.get(workspace, "build")});
            } else if (lang.contains("python")) {
                searchPaths.add(new Path[]{Paths.get(workspace, "dist")});
            } else if (lang.equals("go") || lang.equals("golang")) {
                searchPaths.add(new Path[]{Paths.get(workspace)}); // go binary at root
            } else if (lang.equals("rust")) {
                searchPaths.add(new Path[]{Paths.get(workspace, "target/release")});
            } else if (lang.contains("c++") || lang.contains("cpp") || lang.equals("c")) {
                searchPaths.add(new Path[]{Paths.get(workspace, "build")});
            } else {
                // Java / default
                searchPaths.add(new Path[]{Paths.get(workspace, "target")});
            }

            logBuf.append("========== 制品收集 ==========\n");
            logBuf.append("[INFO] 扫描目录: ").append(workspace).append("（语言: ").append(lang).append("）\n");

            int found = 0;
            for (Path[] dirs : searchPaths) {
                for (Path dir : dirs) {
                    if (!Files.exists(dir)) {
                        logBuf.append("[INFO] 目录不存在，跳过: ").append(dir).append("\n");
                        continue;
                    }
                    try (var files = Files.list(dir)) {
                        var hits = files.filter(p -> isArtifactFile(p, lang)).toList();
                        if (hits.isEmpty()) {
                            logBuf.append("[INFO] 目录内未匹配到制品: ").append(dir).append("\n");
                        }
                        for (Path f : hits) {
                            try {
                                recordSingleArtifact(build, f, logBuf);
                                found++;
                            } catch (IOException e) {
                                logBuf.append("[WARN] 制品收集失败: ").append(e.getMessage()).append("\n");
                            }
                        }
                    }
                }
            }
            if (found == 0) {
                logBuf.append("[WARN] 未收集到任何制品，请检查构建是否产出预期文件\n");
            } else {
                logBuf.append("[INFO] 本次共登记制品 ").append(found).append(" 件\n");
            }
        } catch (IOException e) {
            logBuf.append("[WARN] 制品扫描失败: ").append(e.getMessage()).append("\n");
        }
    }

    private boolean isArtifactFile(Path p, String lang) {
        String name = p.getFileName().toString().toLowerCase();
        if (lang.contains("node")) return name.endsWith(".js") || name.endsWith(".mjs");
        if (lang.contains("python")) return name.endsWith(".whl") || name.endsWith(".tar.gz");
        if (lang.equals("go") || lang.equals("golang")) {
            // Go binaries often have no extension; skip dirs and source
            return !Files.isDirectory(p) && !name.endsWith(".go") && !name.equals("go.mod") && !name.equals("go.sum");
        }
        if (lang.equals("rust")) return name.endsWith(".exe") || (!Files.isDirectory(p) && !name.contains("."));
        if (lang.contains("c++") || lang.contains("cpp") || lang.equals("c"))
            return name.endsWith(".exe") || name.endsWith(".so") || name.endsWith(".dll") || name.endsWith(".a") || (!Files.isDirectory(p) && !name.contains("."));
        // Java / default
        return name.endsWith(".jar") || name.endsWith(".war");
    }

    private void recordSingleArtifact(Build build, Path file, StringBuilder logBuf) throws IOException {
        String version = "latest";
        if (build.getBuildParams() != null) {
            try {
                Map<String, Object> params = objectMapper.readValue(
                        build.getBuildParams(), new TypeReference<>() {});
                if (params.containsKey("version")) {
                    version = params.get("version").toString();
                }
            } catch (Exception ignored) {}
        }
        Artifact artifact = new Artifact();
        artifact.setBuildId(build.getId());
        artifact.setFileName(file.getFileName().toString());
        artifact.setFilePath(file.toAbsolutePath().toString());
        String name = file.getFileName().toString().toLowerCase();
        String type = "FILE";
        if (name.endsWith(".jar")) type = "JAR";
        else if (name.endsWith(".war")) type = "WAR";
        else if (name.endsWith(".whl")) type = "WHEEL";
        else if (name.endsWith(".js") || name.endsWith(".mjs")) type = "JS";
        artifact.setFileType(type);
        artifact.setFileSize(Files.size(file));
        artifact.setVersion(version);
        artifact.setDownloadUrl("/api/artifacts/" + build.getId());
        Artifact saved = artifactRepository.save(artifact);
        try {
            ArtifactVersion ver = artifactVersionService.recordVersion(
                    saved, version, file.toAbsolutePath().toString());
            logBuf.append("[ARTIFACT] 收集制品: ")
                    .append(file.getFileName()).append(" (").append(Files.size(file) / 1024)
                    .append(" KB, SHA256=")
                    .append(ver.getSha256() == null ? "n/a" : ver.getSha256().substring(0, 12))
                    .append("...)\n");
        } catch (Exception e) {
            logBuf.append("[WARN] ArtifactVersion 落库失败: ").append(e.getMessage()).append("\n");
        }
    }

    /** 根据跳过标志记录服务实例 */
    private void recordServiceInstance(Build build, Project project, StringBuilder logBuf) {
        boolean skipDocker = build.getSkipDocker() != null && build.getSkipDocker();
        boolean skipK8s = build.getSkipK8s() != null && build.getSkipK8s();
        if (skipDocker && skipK8s) return; // 全部跳过，不记录实例

        logBuf.append("\n========== 记录服务实例 ==========\n");

        String imageNameStr = dockerRegistry.isBlank() ? project.getCode() : dockerRegistry + "/" + project.getCode();
        String instDbName = build.getDbName();

        try {
            // Docker 实例（只要构建了 Docker 镜像就记录，无论是否跳过 K8s）
            if (!skipDocker) {
                ServiceInstance dockerInst = new ServiceInstance();
                dockerInst.setProjectId(project.getId());
                dockerInst.setProjectName(project.getName());
                dockerInst.setDeployType("DOCKER");
                dockerInst.setInstanceName(project.getCode() + "-docker-" + build.getBuildNumber().replace("#", ""));
                dockerInst.setImageName(imageNameStr);
                dockerInst.setImageTag("latest");
                dockerInst.setHost("localhost");
                dockerInst.setPort(8080);
                dockerInst.setContainerId("");
                dockerInst.setStatus("RUNNING");
                dockerInst.setHealthStatus("HEALTHY");
                dockerInst.setLastHeartbeat(LocalDateTime.now());
                dockerInst.setDbName(instDbName);
                dockerInst.setAdminUsername("admin");
                dockerInst.setAdminPassword("admin123");
                instanceRepository.save(dockerInst);
                logBuf.append("[INSTANCE] Docker 实例: ").append(dockerInst.getInstanceName()).append("\n");
                logBuf.append("[INSTANCE] 镜像: ").append(dockerInst.getImageName()).append(":").append(dockerInst.getImageTag()).append("\n");
                logBuf.append("[INSTANCE] 数据库: ").append(instDbName).append("\n");
            }

            // K8s 实例
            if (!skipK8s) {
                String appTag = k8sAppName(project);
                ServiceInstance k8sInst = new ServiceInstance();
                k8sInst.setProjectId(project.getId());
                k8sInst.setProjectName(project.getName());
                k8sInst.setDeployType("K8S");
                k8sInst.setInstanceName(appTag + "-k8s-" + build.getBuildNumber().replace("#", ""));
                k8sInst.setK8sNamespace(k8sNamespace);
                k8sInst.setK8sPodName(appTag);
                k8sInst.setImageName(imageNameStr);
                k8sInst.setImageTag("latest");
                k8sInst.setStatus("RUNNING");
                k8sInst.setHealthStatus("HEALTHY");
                k8sInst.setLastHeartbeat(LocalDateTime.now());
                k8sInst.setDbName(instDbName);
                k8sInst.setAdminUsername("admin");
                k8sInst.setAdminPassword("admin123");
                instanceRepository.save(k8sInst);
                logBuf.append("[INSTANCE] K8s 实例: ").append(k8sInst.getInstanceName()).append("\n");
                logBuf.append("[INSTANCE] 命名空间: ").append(k8sNamespace).append("\n");
                logBuf.append("[INSTANCE] Pod: ").append(k8sInst.getK8sPodName()).append("\n");
                logBuf.append("[INSTANCE] 数据库: ").append(instDbName).append("\n");
                logBuf.append("[INSTANCE] 管理员: admin / ").append(k8sInst.getAdminPassword()).append("\n");
            }
        } catch (Exception e) {
            logBuf.append("[WARN] 记录实例失败: ").append(e.getMessage()).append("\n");
        }
    }

    /** 检测 Docker 是否可用（结果缓存） */
    private boolean isDockerAvailable() {
        if (dockerAvailable != null) return dockerAvailable;
        try {
            ProcessBuilder pb = new ProcessBuilder(dockerCommand, "info");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            boolean finished = proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            if (finished) {
                dockerAvailable = proc.exitValue() == 0;
            } else {
                proc.destroyForcibly();
                dockerAvailable = false;
            }
        } catch (Exception e) {
            dockerAvailable = false;
        }
        return dockerAvailable;
    }

    /**
     * 检查本地镜像是否存在
     * 用于跳过重复构建,避免 BuildKit gRPC 502 等瞬时错误
     */
    private boolean isLocalImageExists(String imageName) {
        try {
            ProcessBuilder pb = new ProcessBuilder(dockerCommand, "inspect", imageName);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            boolean finished = proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (finished) {
                return proc.exitValue() == 0;
            } else {
                proc.destroyForcibly();
                return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** 检测 kubectl 是否可用（结果缓存） */
    private boolean isKubectlAvailable() {
        if (kubectlAvailable != null) return kubectlAvailable;
        try {
            ProcessBuilder pb = new ProcessBuilder("kubectl", "version", "--client");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            boolean finished = proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            kubectlAvailable = finished && proc.exitValue() == 0;
        } catch (Exception e) {
            kubectlAvailable = false;
        }
        return kubectlAvailable;
    }

    /** 重置工具可用性缓存（重新连接后调用） */
    public void resetToolAvailability() {
        dockerAvailable = null;
        kubectlAvailable = null;
    }

    /** 根据跳过标志判断是否跳过某个阶段 */
    private boolean shouldSkipStage(StageDef stage, Build build) {
        boolean skipDocker = build.getSkipDocker() != null && build.getSkipDocker();
        boolean skipK8s = build.getSkipK8s() != null && build.getSkipK8s();

        boolean hasDockerStep = stage.steps.stream().anyMatch(s ->
                "DOCKER_BUILD".equalsIgnoreCase(s.type) || "DOCKER_PUSH".equalsIgnoreCase(s.type));
        boolean hasK8sStep = stage.steps.stream().anyMatch(s ->
                "K8S_DEPLOY".equalsIgnoreCase(s.type));

        if (skipDocker && hasDockerStep) return true;
        if (skipK8s && hasK8sStep) return true;
        return false;
    }

    /** 评估 when 条件是否满足 */
    private boolean evaluateWhen(StepDef step, Build build, Project project) {
        if (step.when == null || step.when.isEmpty()) return true;  // 无条件 = 总是执行
        Map<String, String> vars = buildVariableService.resolveAll(project.getId(), build.getPipelineId());
        for (Map.Entry<String, String> cond : step.when.entrySet()) {
            String key = cond.getKey();
            String expected = cond.getValue();
            String actual = null;
            if ("branch".equals(key)) {
                actual = build.getBranch();
            } else if (key.startsWith("param.")) {
                actual = vars.get(key.substring(6));
            } else if (key.startsWith("var.")) {
                actual = vars.get(key.substring(4));
            } else {
                actual = vars.get(key);
            }
            // null 或 不等于期望值 → 不满足
            if (actual == null || !actual.equals(expected)) return false;
        }
        return true;
    }

    private boolean executeStep(StepDef step, Project project, StringBuilder logBuf, Long buildId) {
        try {
            if (simulationMode) return executeStepSimulated(step, project, logBuf);
            switch (step.type.toUpperCase()) {
                case "SHELL": return executeShellReal(step, project, logBuf, buildId);
                case "DOCKER_BUILD": return executeDockerBuildReal(step, project, logBuf, buildId);
                case "DOCKER_PUSH": return executeDockerPushReal(step, project, logBuf, buildId);
                case "K8S_DEPLOY": return executeK8sDeployReal(step, project, logBuf, buildId);
                case "TEST": return executeTestReal(step, project, logBuf, buildId);
                case "NPM_INSTALL": return executeNpmInstallReal(step, project, logBuf, buildId);
                case "NPM_BUILD": return executeNpmBuildReal(step, project, logBuf, buildId);
                case "MVN_PACKAGE": return executeMvnPackageReal(step, project, logBuf, buildId);
                case "SECURITY_SCAN_FS": return executeSecurityScanFsReal(step, project, logBuf, buildId);
                case "SECURITY_SCAN_DEP": return executeSecurityScanDepReal(step, project, logBuf, buildId);
                case "SECURITY_SCAN_IMAGE": return executeSecurityScanImageReal(step, project, logBuf, buildId);
                case "SAST_SCAN": return executeSastScanReal(step, project, logBuf, buildId);
                case "PIP_INSTALL": return executePipInstallReal(step, project, logBuf, buildId);
                case "PYTHON_BUILD": return executePythonBuildReal(step, project, logBuf, buildId);
                case "GO_BUILD": return executeGoBuildReal(step, project, logBuf, buildId);
                case "CMAKE_BUILD": return executeCmakeBuildReal(step, project, logBuf, buildId);
                case "CARGO_BUILD": return executeCargoBuildReal(step, project, logBuf, buildId);
                case "APPROVAL": return executeApprovalReal(step, project, logBuf, buildId);
                case "QUALITY_GATE": return executeQualityGateReal(step, project, logBuf, buildId);
                default:
                    logBuf.append("[WARN] 未知步骤类型: ").append(step.type).append("\n");
                    return executeShellReal(step, project, logBuf, buildId);
            }
        } catch (Exception e) {
            logBuf.append("[ERROR] 步骤异常: ").append(e.getMessage()).append("\n");
            return false;
        }
    }

    // ===================== 模拟执行 =====================
    private boolean executeStepSimulated(StepDef step, Project project, StringBuilder logBuf) throws Exception {
        switch (step.type.toUpperCase()) {
            case "SHELL":
                logBuf.append("$ ").append(step.command).append("\n");
                simulateDelay(800, 2000);
                logBuf.append("> 模拟执行完成\n");
                return true;
            case "DOCKER_BUILD":
                logBuf.append("[DOCKER] [模拟] 构建镜像: ").append(project.getCode()).append(":latest\n");
                simulateDelay(1000, 3000);
                logBuf.append("[DOCKER] [模拟] 镜像构建完成\n");
                return true;
            case "DOCKER_PUSH":
                logBuf.append("[DOCKER] [模拟] 推送镜像...\n");
                simulateDelay(500, 1500);
                logBuf.append("[DOCKER] [模拟] 镜像推送完成\n");
                return true;
            case "K8S_DEPLOY":
                logBuf.append("[K8S] [模拟] 部署到集群 (ns: ").append(k8sNamespace).append(")...\n");
                simulateDelay(1000, 2000);
                logBuf.append("[K8S] [模拟] 部署完成\n");
                return true;
            case "TEST":
                logBuf.append("[TEST] [模拟] 执行测试...\n");
                simulateDelay(500, 1500);
                logBuf.append("[TEST] [模拟] 测试完成: 42 passed, 0 failed\n");
                return true;
            case "NPM_INSTALL":
                logBuf.append("[NPM] [模拟] npm install...\n");
                simulateDelay(800, 2000);
                logBuf.append("[NPM] [模拟] 依赖安装完成\n");
                return true;
            case "NPM_BUILD":
                logBuf.append("[NPM] [模拟] npm run build...\n");
                simulateDelay(800, 2000);
                logBuf.append("[NPM] [模拟] 构建完成\n");
                return true;
            case "MVN_PACKAGE":
                logBuf.append("[MVN] [模拟] mvn clean package -DskipTests -B\n");
                simulateDelay(1500, 4000);
                logBuf.append("[MVN] [模拟] BUILD SUCCESS\n");
                return true;
            case "PIP_INSTALL":
                logBuf.append("[PIP] [模拟] pip install -r requirements.txt\n");
                simulateDelay(800, 2000);
                logBuf.append("[PIP] [模拟] 依赖安装完成\n");
                return true;
            case "PYTHON_BUILD":
                logBuf.append("[PYTHON] [模拟] python -m compileall .\n");
                simulateDelay(500, 1500);
                logBuf.append("[PYTHON] [模拟] 语法检查通过\n");
                return true;
            case "GO_BUILD":
                logBuf.append("[GO] [模拟] go build -o app ./...\n");
                simulateDelay(1000, 3000);
                logBuf.append("[GO] [模拟] 编译完成\n");
                return true;
            case "CMAKE_BUILD":
                logBuf.append("[CMAKE] [模拟] cmake --build build\n");
                simulateDelay(1500, 4000);
                logBuf.append("[CMAKE] [模拟] 构建完成\n");
                return true;
            case "CARGO_BUILD":
                logBuf.append("[CARGO] [模拟] cargo build --release\n");
                simulateDelay(2000, 5000);
                logBuf.append("[CARGO] [模拟] 编译完成\n");
                return true;
            case "SECURITY_SCAN_FS":
                logBuf.append("[SEC] [模拟] Trivy FS 扫描...\n");
                simulateDelay(1000, 3000);
                logBuf.append("[SEC] [模拟] 扫描完成: CRITICAL=0, HIGH=0, MEDIUM=2, LOW=5 → PASSED\n");
                return true;
            case "SECURITY_SCAN_DEP":
                logBuf.append("[SEC] [模拟] Trivy 依赖扫描...\n");
                simulateDelay(1000, 3000);
                logBuf.append("[SEC] [模拟] 扫描完成: CRITICAL=0, HIGH=1, MEDIUM=3, LOW=8 → PASSED\n");
                return true;
            case "SECURITY_SCAN_IMAGE":
                logBuf.append("[SEC] [模拟] Trivy 镜像扫描...\n");
                simulateDelay(1000, 3000);
                logBuf.append("[SEC] [模拟] 扫描完成: CRITICAL=0, HIGH=0, MEDIUM=1, LOW=2 → PASSED\n");
                return true;
            case "QUALITY_GATE":
                logBuf.append("[GATE] [模拟] 解析测试报告...\n");
                simulateDelay(300, 800);
                logBuf.append("[GATE] [模拟] 测试: 245/250 通过 (98.0%), 覆盖率: 72.3% → PASSED\n");
                return true;
            case "APPROVAL":
                logBuf.append("[APPROVAL] [模拟] 审批步骤: ").append(step.name).append("\n");
                logBuf.append("[APPROVAL] [模拟] 自动通过 (模拟模式)\n");
                return true;
            default:
                logBuf.append("$ ").append(step.command).append("\n");
                simulateDelay(500, 1500);
                logBuf.append("> 模拟完成\n");
                return true;
        }
    }

    // ===================== 真实执行 =====================
    private boolean executeShellReal(StepDef step, Project project, StringBuilder logBuf, Long buildId) throws Exception {
        String command = step.command;
        if (command == null || command.isBlank()) return true;
        String workDir = findProjectRoot(project).toString();
        logBuf.append("[INFO] 工作目录: ").append(workDir).append("\n");
        logBuf.append("$ ").append(command).append("\n");
        appendLog(buildId, logBuf.toString());
        int exitCode = runCommand(workDir, command, logBuf, buildId);
        if (exitCode != 0) {
            logBuf.append("[ERROR] 退出码: ").append(exitCode).append("\n");
            return false;
        }
        return true;
    }

    private boolean executeDockerBuildReal(StepDef step, Project project, StringBuilder logBuf, Long buildId) throws Exception {
        if (!isDockerAvailable()) {
            if (dockerSkipIfUnavailable) {
                logBuf.append("[DOCKER] Docker 不可用，跳过镜像构建\n");
                logBuf.append("[HINT] 启动 Docker Desktop 或 Rancher Desktop 后重试\n");
                return true;
            }
            logBuf.append("[ERROR] Docker 不可用，无法构建镜像\n");
            return false;
        }

        // Docker build context 应使用 workspace 根目录，以包含前端等子项目
        Path projectRoot = findProjectRoot(project);
        Path workspacePath = resolveWorkspaceRoot().resolve(workspaceDirName(project));
        Path buildContext = workspacePath;

        // 确定 Dockerfile 路径：优先级
        //   1) 项目配置的 dockerfilePath 字段（可指定子目录）
        //   2) step.command（流水线里手填的）
        //   3) 默认 "Dockerfile"
        String dockerfile = (project.getDockerfilePath() != null && !project.getDockerfilePath().isBlank())
                ? project.getDockerfilePath()
                : (step.command.isBlank() ? "Dockerfile" : step.command);
        Path dockerfilePath = projectRoot.resolve(dockerfile);
        if (!Files.exists(dockerfilePath)) {
            // 检查 workspace 根目录是否有 Dockerfile
            Path workspaceDockerfile = workspacePath.resolve(dockerfile);
            if (Files.exists(workspaceDockerfile)) {
                dockerfilePath = workspaceDockerfile;
            } else if (project.getDockerfileContent() != null && !project.getDockerfileContent().isBlank()) {
                // D 平台托管 Dockerfile：项目配置的 dockerfileContent 字段非空 → 写入项目根目录
                try {
                    Files.createDirectories(dockerfilePath.getParent() != null ? dockerfilePath.getParent() : projectRoot);
                    Files.writeString(dockerfilePath, project.getDockerfileContent());
                    logBuf.append("[DOCKER] 使用项目配置的 Dockerfile 写入: ").append(dockerfilePath).append("\n");
                } catch (IOException e) {
                    logBuf.append("[ERROR] 写入托管 Dockerfile 失败: ").append(e.getMessage()).append("\n");
                    return false;
                }
            }
        }

        // 计算 Dockerfile 相对于 build context 的路径
        String dockerfileRelPath = buildContext.relativize(dockerfilePath).toString();

        String imageName = dockerRegistry.isBlank() ? project.getCode() + ":latest"
                : dockerRegistry + "/" + project.getCode() + ":latest";
        String command = dockerCommand + " build -t " + imageName + " -f " + dockerfileRelPath + " .";

        // 检查镜像是否已存在（避免 BuildKit gRPC 502 等瞬时错误强制重试）
        // 单节点开发环境：镜像已存在且 Pod 在跑 → 跳过重建
        if (isLocalImageExists(imageName)) {
            logBuf.append("[DOCKER] 镜像已存在,跳过构建: ").append(imageName).append("\n");
            logBuf.append("[HINT] 如需重新构建,请先 docker rmi ").append(imageName).append(" 或在 D 平台项目设置中开启「强制重建」\n");
            appendLog(buildId, logBuf.toString());
        } else {
            logBuf.append("[DOCKER] 构建镜像: ").append(imageName).append("\n");
            logBuf.append("[INFO] 工作目录: ").append(buildContext).append("\n");
            logBuf.append("[INFO] Dockerfile: ").append(dockerfilePath).append("\n");
            appendLog(buildId, logBuf.toString());
            int exitCode = runCommand(buildContext.toString(), command, logBuf, buildId);
            if (exitCode != 0) { logBuf.append("[ERROR] Docker 构建失败\n"); return false; }
            logBuf.append("[DOCKER] 镜像构建完成\n");
        }

        // 检查是否有前端 Dockerfile，如果有则构建前端 nginx 镜像（前后端分离部署）
        Path frontendDockerfile = projectRoot.resolve("Dockerfile.frontend");
        if (!Files.exists(frontendDockerfile)) {
            frontendDockerfile = workspacePath.resolve("Dockerfile.frontend");
        }
        // 前端 Dockerfile 不存在但 frontend/ 目录存在 → 自动生成默认的
        if (!Files.exists(frontendDockerfile)) {
            Path frontendDir = workspacePath.resolve("frontend");
            if (Files.isDirectory(frontendDir)) {
                // 在 frontend/ 下找 package.json（可能嵌套一层，如 frontend/apartment-front/）
                Path frontendProjectDir = frontendDir;
                if (!Files.exists(frontendDir.resolve("package.json"))) {
                    try (var stream = Files.list(frontendDir)) {
                        for (Path child : stream.toList()) {
                            if (Files.isDirectory(child) && Files.exists(child.resolve("package.json"))) {
                                frontendProjectDir = child;
                                break;
                            }
                        }
                    } catch (IOException ignored) {}
                }
                if (Files.exists(frontendProjectDir.resolve("package.json"))) {
                    String relFrontendPath = workspacePath.relativize(frontendProjectDir).toString().replace("\\", "/");
                    String generated = generateDefaultFrontendDockerfile(relFrontendPath);
                    frontendDockerfile = workspacePath.resolve("Dockerfile.frontend");
                    try {
                        Files.writeString(frontendDockerfile, generated);
                        logBuf.append("[DOCKER] 自动生成 Dockerfile.frontend (前端: ").append(relFrontendPath).append(")\n");
                    } catch (IOException e) {
                        logBuf.append("[WARN] 生成 Dockerfile.frontend 失败: ").append(e.getMessage()).append("\n");
                    }
                }
            }
        }
        if (Files.exists(frontendDockerfile)) {
            String frontendDockerfileRel = buildContext.relativize(frontendDockerfile).toString().replace("\\", "/");
            // 前端镜像名跟后端一样用 projectCode(共用,流水线名不参与,避免多流水线重复构建)
            String codeForImage = project.getCode() != null ? project.getCode() : "devops-app";
            String frontendImageName = dockerRegistry.isBlank() ? codeForImage + "-frontend:latest"
                    : dockerRegistry + "/" + codeForImage + "-frontend:latest";
            // 镜像已存在则跳过（与后端镜像一致）
            if (isLocalImageExists(frontendImageName)) {
                logBuf.append("[DOCKER] 前端镜像已存在,跳过构建: ").append(frontendImageName).append("\n");
            } else {
                String frontendCmd = dockerCommand + " build -t " + frontendImageName
                        + " -f " + frontendDockerfileRel + " .";
                logBuf.append("[DOCKER] 构建前端镜像: ").append(frontendImageName).append("\n");
                appendLog(buildId, logBuf.toString());
                int frontendExit = runCommand(buildContext.toString(), frontendCmd, logBuf, buildId);
                if (frontendExit != 0) {
                    logBuf.append("[ERROR] 前端镜像构建失败\n");
                    return false;
                }
                logBuf.append("[DOCKER] 前端镜像构建完成\n");
            }
        }

        return true;
    }

    /**
     * 自动生成默认的前端 Dockerfile（多阶段构建：node build → nginx 运行）
     * @param frontendRelPath 前端项目相对于 workspace 根的路径（如 "frontend/apartment-front"）
     */
    private String generateDefaultFrontendDockerfile(String frontendRelPath) {
        return "# 自动生成的前端 Dockerfile (D 平台)\n" +
                "FROM node:22-alpine AS builder\n" +
                "WORKDIR /build\n" +
                "COPY " + frontendRelPath + "/package*.json ./\n" +
                "RUN npm config set registry https://registry.npmmirror.com && npm install\n" +
                "COPY " + frontendRelPath + "/ .\n" +
                "RUN npm run build\n" +
                "\n" +
                "FROM nginx:alpine\n" +
                "RUN printf 'server {\\n" +
                "  listen 80;\\n" +
                "  root /usr/share/nginx/html;\\n" +
                "  location / {\\n" +
                "    try_files $uri $uri/ /index.html;\\n" +
                "  }\\n" +
                "  location /api/ {\\n" +
                "    proxy_pass http://127.0.0.1:8080;\\n" +
                "    proxy_set_header Host $host;\\n" +
                "    proxy_set_header X-Real-IP $remote_addr;\\n" +
                "  }\\n" +
                "}' > /etc/nginx/conf.d/default.conf\n" +
                "COPY --from=builder /build/dist /usr/share/nginx/html\n" +
                "EXPOSE 80\n";
    }

    private boolean executeDockerPushReal(StepDef step, Project project, StringBuilder logBuf, Long buildId) throws Exception {
        if (!isDockerAvailable()) {
            logBuf.append("[DOCKER] Docker 不可用，跳过镜像推送\n");
            return true;
        }
        // No registry configured → skip push (image is available locally, no remote registry to push to)
        if (dockerRegistry.isBlank()) {
            logBuf.append("[DOCKER] 未配置镜像仓库地址，跳过推送（本地镜像可直接使用）\n");
            logBuf.append("[HINT] 如需推送远程仓库，请在 application.yml 配置 devops.pipeline.docker.registry\n");
            return true;
        }
        Path workDir = findProjectRoot(project);
        String imageName = dockerRegistry + "/" + project.getCode() + ":latest";
        String tagCmd = dockerCommand + " tag " + project.getCode() + ":latest " + imageName;
        logBuf.append("$ ").append(tagCmd).append("\n");
        if (runCommand(workDir.toString(), tagCmd, logBuf, buildId) != 0) {
            logBuf.append("[ERROR] Docker tag 失败\n"); return false;
        }
        String command = dockerCommand + " push " + imageName;
        logBuf.append("[DOCKER] 推送: ").append(imageName).append("\n");
        appendLog(buildId, logBuf.toString());
        int exitCode = runCommand(workDir.toString(), command, logBuf, buildId);
        if (exitCode != 0) { logBuf.append("[ERROR] 推送失败\n"); return false; }
        logBuf.append("[DOCKER] 推送完成\n");
        return true;
    }

    private boolean executeK8sDeployReal(StepDef step, Project project, StringBuilder logBuf, Long buildId) throws Exception {
        if (!isKubectlAvailable()) {
            if (k8sSkipIfUnavailable) {
                logBuf.append("[K8S] kubectl 不可用，跳过部署\n");
                logBuf.append("[HINT] 确保 kubectl 已安装且集群可访问\n");
                return true;
            }
            logBuf.append("[ERROR] kubectl 不可用，无法部署\n");
            return false;
        }
        Path workDir = findProjectRoot(project);
        String deployFile = step.command.isBlank() ? "deployment.yaml" : step.command;
        Path deployPath = workDir.resolve(deployFile);

        // === 渲染到独立文件,永不修改原始 deployment.yaml ===
        // 原因: deployment.yaml 可能不被 git 追踪(D 平台生成的),git reset 不会恢复
        //       原地修改导致 name 替换累积: apartnment → apartnment-new → apartnment-new-new → ...
        //       但 rollout status 查的是固定的 k8sAppName,导致 NotFound
        Path renderedPath = workDir.resolve("deployment-rendered.yaml");

        // 如果 deployment.yaml 不存在，自动生成默认模板（写到 renderedPath,不污染原始路径）
        if (!Files.exists(deployPath)) {
            logBuf.append("[K8S] deployment.yaml 不存在，自动生成默认模板\n");
            Build build = buildRepository.findById(buildId).orElse(null);
            String dbName = build != null ? build.getDbName() : null;
            String generated = generateDefaultK8sDeployment(project, dbName);
            try {
                Files.writeString(renderedPath, generated);
                logBuf.append("[K8S] 已生成: ").append(renderedPath.toString()).append("\n");
            } catch (IOException e) {
                logBuf.append("[ERROR] 无法写入 deployment-rendered.yaml: ").append(e.getMessage()).append("\n");
                return false;
            }
        } else {
            // deployment.yaml 已存在：读取原始内容,修改后写到 renderedPath
            logBuf.append("[K8S] 使用已有: ").append(deployPath.toString()).append("\n");
            logBuf.append("[K8S] 注入本次构建的独立数据库配置...\n");

            try {
                String raw = Files.readString(deployPath);

                // === 多流水线隔离：把 YAML 里的 projectCode 替换为 k8sAppName ===
                // 从原始文件读取,不会累积(原始文件不被修改)
                String oldName = project.getCode() != null ? project.getCode() : "";
                String newName = k8sAppName(project);
                if (!oldName.isBlank() && !oldName.equals(newName)) {
                    String replaced = raw
                            .replaceAll("(?m)^(\\s*)name:\\s*" + java.util.regex.Pattern.quote(oldName) + "\\b",
                                    "$1name: " + newName)
                            .replaceAll("(?m)^(\\s*)app:\\s*" + java.util.regex.Pattern.quote(oldName) + "\\b",
                                    "$1app: " + newName);
                    if (!replaced.equals(raw)) {
                        raw = replaced;
                        logBuf.append("[K8S] 流水线隔离: Deployment/Service/app 标签 ")
                                .append(oldName).append(" → ").append(newName).append("\n");
                    }
                }

            // 端口转发方案：保持 replicas 随机 (1-3)，不强制 1
            // hostNetwork: true 不再注入，Pod 用标准容器网络，Service 暴露 ClusterIP，外部走 kubectl port-forward
            int randomReplicas = 1 + new java.util.Random().nextInt(3);
            logBuf.append("[K8S] Pod 副本数: ").append(randomReplicas).append("（hostNetwork 已移除，标准 K8s 容器网络）\n");
                // 替换已有 replicas
                raw = raw.replaceAll(
                        "(?m)^(\\s*)replicas:\\s*\\d+\\s*$",
                        "$1replicas: " + randomReplicas);
                // 移除可能的 hostNetwork: true (旧兼容)
                raw = raw.replaceAll(
                        "(?m)^[ \\t]+hostNetwork:\\s*true\\s*$", "");
                // 修正 host.docker.internal: K3s 容器内可解析(默认 192.168.127.254 → 宿主机)
                // 如用户配置 K8S_DB_HOST 则用之
                String k8sDbHost = System.getenv().getOrDefault("K8S_DB_HOST", "host.docker.internal");
                if (raw.contains("host.docker.internal") && !k8sDbHost.equals("host.docker.internal")) {
                    raw = raw.replaceAll("host\\.docker\\.internal", k8sDbHost);
                    logBuf.append("[K8S] 已将 host.docker.internal 替换为 ").append(k8sDbHost).append("\n");
                }
                Files.writeString(renderedPath, raw);
                logBuf.append("[K8S] 已渲染: ").append(renderedPath.toString())
                        .append(" (replicas=").append(randomReplicas).append(")\n");
            } catch (IOException ex) {
                logBuf.append("[WARN] 更新 replicas 失败: ").append(ex.getMessage()).append("\n");
            }
            Build build = buildRepository.findById(buildId).orElse(null);
            String dbName = build != null ? build.getDbName() : null;
            if (dbName != null && !dbName.isBlank() && !"H2".equalsIgnoreCase(project.getDbType())) {
                try {
                    String original = Files.readString(renderedPath);
                    // dbHost 解析优先级：
                    // 1) 环境变量 K8S_DB_HOST (一次性测试)
                    // 2) project.dbHost 非空 → 用项目的
                    // 3) 默认 host.docker.internal (K3s 内置 DNS → 192.168.127.254 → 宿主机 MySQL)
                    // 4) 用户部署的镜像本身已带 hostNetwork: true → 也能用 127.0.0.1
                    String envK8sDbHost = System.getenv("K8S_DB_HOST");
                    String dbHost;
                    if (envK8sDbHost != null && !envK8sDbHost.isBlank()) {
                        dbHost = envK8sDbHost;
                    } else if (project.getDbHost() != null && !project.getDbHost().isBlank()) {
                        dbHost = project.getDbHost();
                    } else {
                        // 默认 host.docker.internal（K3s 容器内可解析为宿主机 IP）
                        // 这是 D 平台+K3s+Rancher Desktop 唯一能稳定访问宿主机 MySQL 的方式
                        dbHost = "host.docker.internal";
                    }
                    int dbPort = project.getDbPort() != null ? project.getDbPort() : 3306;
                    String dbUser = project.getDbUsername() != null && !project.getDbUsername().isBlank()
                            ? project.getDbUsername() : "root";
                    String dbPass = project.getDbPassword() != null ? project.getDbPassword() : "";
                    String jdbcUrl = "jdbc:mysql://" + dbHost + ":" + dbPort + "/" + dbName
                            + "?useUnicode=true&characterEncoding=utf-8&useSSL=false"
                            + "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
                            + "&createDatabaseIfNotExist=true";

                    String modified = original;

                    // 注入或替换 env 段：兼容三种情况
                    // A) 没有 env 段 → 在 container 块内追加 env
                    // B) 有 env 段但没 SPRING_DATASOURCE_URL → 在 env 末尾追加
                    // C) 有 SPRING_DATASOURCE_URL → 替换它的值

                    if (modified.contains("SPRING_DATASOURCE_URL")) {
                        // 情况 C: 替换现有值
                        modified = modified.replaceAll(
                                "SPRING_DATASOURCE_URL\\s*:\\s*\"[^\"]*\"",
                                "SPRING_DATASOURCE_URL: \"" + jdbcUrl + "\"");
                        modified = modified.replaceAll(
                                "SPRING_DATASOURCE_URL\\s*:\\s*'[^']*'",
                                "SPRING_DATASOURCE_URL: \"" + jdbcUrl + "\"");
                        modified = modified.replaceAll(
                                "SPRING_DATASOURCE_URL\\s*:\\s*[^\\s\"']+",
                                "SPRING_DATASOURCE_URL: \"" + jdbcUrl + "\"");
                        modified = modified.replaceAll(
                                "SPRING_DATASOURCE_URL[\\s\\S]{0,30}?value\\s*:\\s*\"[^\"]*\"",
                                "SPRING_DATASOURCE_URL\n              value: \"" + jdbcUrl + "\"");
                        modified = modified.replaceAll(
                                "SPRING_DATASOURCE_URL[\\s\\S]{0,30}?value\\s*:\\s*'[^']*'",
                                "SPRING_DATASOURCE_URL\n              value: \"" + jdbcUrl + "\"");
                        modified = modified.replaceAll(
                                "SPRING_DATASOURCE_URL[\\s\\S]{0,30}?value\\s*:\\s*[^\\s\"']+",
                                "SPRING_DATASOURCE_URL\n              value: \"" + jdbcUrl + "\"");
                    } else if (modified.matches("(?s).*\\n\\s*env:\\s*\\n.*")) {
                        // 情况 B: 有 env 段但没 URL → 在 env 段末尾追加
                        // 找到 env: 的位置，向后追加新条目到 env 块结束
                        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                                "(env:\\s*\\n)([\\s\\S]*?)(?=\\n\\s*-|\\n[a-zA-Z])");
                        java.util.regex.Matcher m = p.matcher(modified);
                        if (m.find()) {
                            String envBlock = m.group(2);
                            String indent = envBlock.isEmpty() ? "            " : "            ";
                            String newEnv = "            - name: SPRING_DATASOURCE_URL\n"
                                    + "              value: \"" + jdbcUrl + "\"\n"
                                    + "            - name: SPRING_DATASOURCE_USERNAME\n"
                                    + "              value: \"" + dbUser + "\"\n"
                                    + "            - name: SPRING_DATASOURCE_PASSWORD\n"
                                    + "              value: \"" + dbPass + "\"\n";
                            modified = modified.replace(m.group(0), m.group(1) + envBlock + newEnv);
                        }
                    } else {
                        // 情况 A: 没有 env 段 → 在 ports 段后插入 env 段
                        // 找到第一个 container 块（以 "- name:" 开始）的结束位置之前插入
                        // 简化: 在 "ports:" 段后插入 env
                        String envInjection = "\n          env:\n"
                                + "            - name: SPRING_DATASOURCE_URL\n"
                                + "              value: \"" + jdbcUrl + "\"\n"
                                + "            - name: SPRING_DATASOURCE_USERNAME\n"
                                + "              value: \"" + dbUser + "\"\n"
                                + "            - name: SPRING_DATASOURCE_PASSWORD\n"
                                + "              value: \"" + dbPass + "\"\n";
                        // 找到 "containerPort: 8080" 行后插入
                        modified = modified.replaceFirst(
                                "(- containerPort:[^\\n]+\\n)",
                                "$1" + envInjection);
                    }

                    // 用户名密码处理（同样的逻辑）
                    if (modified.contains("SPRING_DATASOURCE_USERNAME")) {
                        modified = modified.replaceAll(
                                "SPRING_DATASOURCE_USERNAME\\s*:\\s*\"[^\"]*\"",
                                "SPRING_DATASOURCE_USERNAME: \"" + dbUser + "\"");
                        modified = modified.replaceAll(
                                "SPRING_DATASOURCE_USERNAME\\s*:\\s*'[^']*'",
                                "SPRING_DATASOURCE_USERNAME: \"" + dbUser + "\"");
                        modified = modified.replaceAll(
                                "SPRING_DATASOURCE_USERNAME\\s*:\\s*[^\\n]+",
                                "SPRING_DATASOURCE_USERNAME: \"" + dbUser + "\"");
                        modified = modified.replaceAll(
                                "SPRING_DATASOURCE_USERNAME[\\s\\S]{0,30}?value\\s*:\\s*\"[^\"]*\"",
                                "SPRING_DATASOURCE_USERNAME\n              value: \"" + dbUser + "\"");
                        modified = modified.replaceAll(
                                "SPRING_DATASOURCE_USERNAME[\\s\\S]{0,30}?value\\s*:\\s*[^\\n]+",
                                "SPRING_DATASOURCE_USERNAME\n              value: \"" + dbUser + "\"");
                    }
                    if (modified.contains("SPRING_DATASOURCE_PASSWORD")) {
                        modified = modified.replaceAll(
                                "SPRING_DATASOURCE_PASSWORD\\s*:\\s*\"[^\"]*\"",
                                "SPRING_DATASOURCE_PASSWORD: \"" + dbPass + "\"");
                        modified = modified.replaceAll(
                                "SPRING_DATASOURCE_PASSWORD\\s*:\\s*'[^']*'",
                                "SPRING_DATASOURCE_PASSWORD: \"" + dbPass + "\"");
                        modified = modified.replaceAll(
                                "SPRING_DATASOURCE_PASSWORD\\s*:\\s*[^\\n]+",
                                "SPRING_DATASOURCE_PASSWORD: \"" + dbPass + "\"");
                        modified = modified.replaceAll(
                                "SPRING_DATASOURCE_PASSWORD[\\s\\S]{0,30}?value\\s*:\\s*\"[^\"]*\"",
                                "SPRING_DATASOURCE_PASSWORD\n              value: \"" + dbPass + "\"");
                        modified = modified.replaceAll(
                                "SPRING_DATASOURCE_PASSWORD[\\s\\S]{0,30}?value\\s*:\\s*[^\\n]+",
                                "SPRING_DATASOURCE_PASSWORD\n              value: \"" + dbPass + "\"");
                    }

                    // 5) 注入 env 段（如果还没有）— 用与 generateDefault 一致的 dbHost/端口
                    if (!modified.contains("SPRING_DATASOURCE_URL")) {
                        // K8s 容器需要访问宿主机 MySQL，host.docker.internal 在 K8s 中不解析
                        String k8sDbHost = System.getenv().getOrDefault("K8S_DB_HOST", "127.0.0.1");
                        String envInjection =
                                "            - name: SPRING_DATASOURCE_URL\n" +
                                "              value: \"jdbc:mysql://" + k8sDbHost + ":" + dbPort + "/" + dbName
                                + "?useUnicode=true&characterEncoding=utf-8&useSSL=false"
                                + "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
                                + "&createDatabaseIfNotExist=true\"\n" +
                                "            - name: SPRING_DATASOURCE_USERNAME\n" +
                                "              value: \"" + dbUser + "\"\n" +
                                "            - name: SPRING_DATASOURCE_PASSWORD\n" +
                                "              value: \"" + dbPass + "\"\n";
                        // 找到 "containerPort: 8080" 行后插入
                        modified = modified.replaceFirst(
                                "(- containerPort:[^\\n]+\\n)",
                                "$1" + envInjection);
                    } else {
                        // 已存在 SPRING_DATASOURCE_URL 时，把 host.docker.internal 保留（K3s 可解析）
                        // K8S_DB_HOST 环境变量可覆盖
                        String k8sDbHost = System.getenv().getOrDefault("K8S_DB_HOST", "host.docker.internal");
                        if (modified.contains("host.docker.internal") && !k8sDbHost.equals("host.docker.internal")) {
                            modified = modified.replaceAll("host\\.docker\\.internal", k8sDbHost);
                        }
                    }

                    // 6) 不再自动添加 hostNetwork: true
                    // 改用 K8s 标准服务暴露 (ClusterIP + kubectl port-forward 隧道)
                    // Pod 用默认容器网络,通过 host.docker.internal 访问宿主机 MySQL

                    if (!modified.equals(original)) {
                        Files.writeString(renderedPath, modified);
                        logBuf.append("[K8S] 已注入/替换 SPRING_DATASOURCE_URL 指向: ").append(jdbcUrl).append("\n");
                    } else {
                        logBuf.append("[K8S] 警告: 未修改 deployment-rendered.yaml，O 项目可能使用自己的 application.yml\n");
                    }
                } catch (IOException e) {
                    logBuf.append("[WARN] 修改 deployment-rendered.yaml 失败: ").append(e.getMessage()).append("\n");
                }
            }
        }

        // ===== 部署策略选择 =====
        // 从构建参数中读取策略（默认 ROLLING）
        Build currentBuild = buildRepository.findById(buildId).orElse(null);
        String deployStrategy = "ROLLING";
        if (currentBuild != null && currentBuild.getDeployStrategy() != null) {
            deployStrategy = currentBuild.getDeployStrategy();
        }
        logBuf.append("[K8S] 部署策略: ").append(DeploymentStrategyService.getStrategyLabel(deployStrategy)).append("\n");
        appendLog(buildId, logBuf.toString());
        logBuf.setLength(0); // 清空已推送的内容

        boolean deploySuccess;
        String deployPathStr = renderedPath.toString();

        switch (deployStrategy) {
            case "BLUE_GREEN":
                deploySuccess = deploymentStrategyService.deployBlueGreen(
                        deployPathStr, k8sNamespace, workDir.toString(),
                        k8sAppName(project), logBuf);
                break;
            case "CANARY":
                deploySuccess = deploymentStrategyService.deployCanary(
                        deployPathStr, k8sNamespace, workDir.toString(),
                        k8sAppName(project), 1, logBuf); // 默认 1 副本（约25%流量）
                break;
            default:
                deploySuccess = deploymentStrategyService.deployRolling(
                        deployPathStr, k8sNamespace, workDir.toString(), logBuf);
                break;
        }

        appendLog(buildId, logBuf.toString());
        logBuf.setLength(0);

        if (!deploySuccess) {
            logBuf.append("[ERROR] 部署失败（策略: ").append(deployStrategy).append("）\n");
            return false;
        }

        // 滚动更新和蓝绿部署需要等待 rollout，金丝雀已在策略内等待
        if ("ROLLING".equals(deployStrategy) || "BLUE_GREEN".equals(deployStrategy)) {
            String rolloutDeployment = "BLUE_GREEN".equals(deployStrategy)
                    ? k8sAppName(project) + "-" + "green"  // 蓝绿部署后新版本在 green 槽位
                    : k8sAppName(project);
            String rolloutCmd = "kubectl rollout status deployment/" + rolloutDeployment
                    + " -n " + k8sNamespace + " --timeout=60s";
            logBuf.append("[K8S] $ ").append(rolloutCmd).append("\n");
            appendLog(buildId, logBuf.toString());
            int rc = runCommand(workDir.toString(), rolloutCmd, logBuf, buildId);
            if (rc != 0) {
                logBuf.append("[WARN] rollout 在 60 秒内未完成，Pod 可能仍在拉取镜像或启动中\n");
            }
        }
        logBuf.append("[K8S] 部署完成（策略: ").append(DeploymentStrategyService.getStrategyLabel(deployStrategy)).append("）\n");

        // ==================== 检测宿主机端口冲突 ====================
        // hostNetwork 已移除：Pod 不再直接监听宿主机端口
        // kubectl port-forward 走 30000-32767 端口池，由 D 平台负责避免冲突
        // 这里保留提示：避免 O 项目和 D 平台本身抢宿主机 8080
        String[] hostPorts = {"8080", "80", "3000", "3306"};
        StringBuilder portConflicts = new StringBuilder();
        for (String port : hostPorts) {
            try {
                ProcessBuilder pb = new ProcessBuilder("netstat", "-ano");
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(proc.getInputStream()))) {
                    String l;
                    while ((l = r.readLine()) != null) {
                        if (l.contains(":" + port) && l.contains("LISTENING")) {
                            String[] parts = l.trim().split("\\s+");
                            String pid = parts[parts.length - 1];
                            // 排除 K8s 自身进程
                            try {
                                ProcessBuilder namePb = new ProcessBuilder("tasklist", "/FI", "PID eq " + pid, "/NH");
                                namePb.redirectErrorStream(true);
                                Process nameProc = namePb.start();
                                try (java.io.BufferedReader nr = new java.io.BufferedReader(
                                        new java.io.InputStreamReader(nameProc.getInputStream()))) {
                                    String nameLine = nr.readLine();
                                    if (nameLine != null && (nameLine.contains("kube") || nameLine.contains("docker"))) {
                                        continue;
                                    }
                                }
                            } catch (Exception ignored) {}
                            portConflicts.append("  - 宿主机端口 ").append(port)
                                    .append(" 被 PID ").append(pid).append(" 占用\n");
                            break;
                        }
                    }
                }
                proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        }
        if (portConflicts.length() > 0) {
            logBuf.append("[K8S] ⚠️ 检测到宿主机端口占用 (仅作提示,hostNetwork 已移除,不阻塞部署):\n");
            logBuf.append(portConflicts);
            logBuf.append("[HINT] hostNetwork: true 已不再注入。Pod 通过 ClusterIP + kubectl port-forward 暴露\n");
            logBuf.append("[HINT] D 平台一键转发时会从 30000-32767 自动选可用端口\n");
        }

        // ==================== 部署后真实状态检查 ====================
        // 关键: 不能用 kubectl apply 成功 + rollout 警告就认为部署成功
        // 必须真实检查 Pod 是否就绪 (Running 且 Ready)
        // 之前的问题: Pod Pending/CrashLoopBackOff 时仍然标记"成功"
        logBuf.append("[K8S] 检查 Pod 真实状态...\n");
        appendLog(buildId, logBuf.toString());
        int deploymentNameLen = k8sAppName(project).length();
        String deploymentName = k8sAppName(project);
        // 用 -o name 列出 Pod（避免 jsonpath 数组越界）
        String podsOutput;
        try {
            int rc1 = runCommand(workDir.toString(),
                    "kubectl get pods -n " + k8sNamespace
                            + " -l app=" + deploymentName
                            + " -o name", logBuf, buildId);
            // 复用刚才的 rollout 输出无法重用 runCommand 的返回字符串
            // 直接用 ProcessBuilder 单独跑一次只读 Pod 列表
            podsOutput = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"),
                            "k8s-pods-list-" + System.currentTimeMillis() + ".tmp")));
        } catch (Exception ex) {
            podsOutput = "";
        }
        // 改用 ProcessBuilder 直接拿输出
        StringBuilder podsBuf = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder("kubectl", "get", "pods", "-n", k8sNamespace,
                    "-l", "app=" + deploymentName, "-o", "name");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(proc.getInputStream()))) {
                String l;
                while ((l = r.readLine()) != null) podsBuf.append(l).append("\n");
            }
            proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ex) {
            logBuf.append("[WARN] 列出 Pod 失败: ").append(ex.getMessage()).append("\n");
        }
        podsOutput = podsBuf.toString();
        int totalPods = 0;
        int runningPods = 0;
        if (podsOutput != null && !podsOutput.trim().isEmpty()) {
            for (String line : podsOutput.split("\\R")) {
                String name = line.trim();
                if (name.isEmpty()) continue;
                totalPods++;
                String podName = name.startsWith("pod/") ? name.substring(4) : name;
                String phase = "";
                try {
                    ProcessBuilder pb = new ProcessBuilder("kubectl", "get", "pod", podName,
                            "-n", k8sNamespace, "-o", "jsonpath={.status.phase}");
                    pb.redirectErrorStream(true);
                    Process proc = pb.start();
                    try (java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(proc.getInputStream()))) {
                        String l;
                        while ((l = r.readLine()) != null) phase = l;
                    }
                    proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                    phase = phase.trim();
                } catch (Exception ex) {
                    phase = "Unknown";
                }
                if ("Running".equals(phase)) runningPods++;
                else {
                    logBuf.append("[K8S] Pod ").append(podName).append(" 状态=").append(phase).append("（非 Running）\n");
                }
            }
        }
        if (totalPods == 0) {
            logBuf.append("[ERROR] 部署后未发现任何 Pod（应用可能未正确启动）\n");
            appendLog(buildId, logBuf.toString());
            return false;
        }
        if (runningPods == 0) {
            logBuf.append("[ERROR] 部署后所有 Pod 都不在 Running 状态（共 ").append(totalPods).append(" 个）\n");
            logBuf.append("[HINT] 查看 Pod 状态: kubectl get pods -n ").append(k8sNamespace).append(" -l app=").append(deploymentName).append("\n");
            logBuf.append("[HINT] 查看 Pod 日志: kubectl logs -n ").append(k8sNamespace).append(" <pod-name>\n");
            appendLog(buildId, logBuf.toString());
            return false;
        }
        if (runningPods < totalPods) {
            logBuf.append("[ERROR] 部署后只有 ").append(runningPods).append("/").append(totalPods).append(" Pod 处于 Running 状态\n");
            appendLog(buildId, logBuf.toString());
            return false;
        }
        logBuf.append("[K8S] 所有 Pod (").append(runningPods).append("/").append(totalPods).append(") 状态正常\n");
        appendLog(buildId, logBuf.toString());
        return true;
    }

    /**
     * 自动生成默认的 K8s Deployment YAML
     * @param dbName 目标数据库名（null 时回退到 devops_platform）
     */
    private String generateDefaultK8sDeployment(Project project, String dbName) {
        String appName = k8sAppName(project);
        String imageName = (project.getCode() != null ? project.getCode() : "devops-app") + ":latest";
        int containerPort = getDefaultPort(project);

        // ==================== 随机 Pod 副本数 (1-3) ====================
        // K8s 会自动调度这些副本到不同 Node（如果有的话）
        // 单节点开发环境也会自动调度到当前集群
        int replicas = 1 + new java.util.Random().nextInt(3); // 1-3 之间随机

        // 检查 workspace 是否有 frontend/ 目录（前后端分离部署）
        boolean hasFrontend = false;
        try {
            Path workspacePath = resolveWorkspaceRoot().resolve(workspaceDirName(project));
            hasFrontend = Files.exists(workspacePath.resolve("frontend"));
        } catch (IOException ignored) {}

        // 数据库名：使用构建时指定的独立库（已含构建编号），兜底也用带编号的
        String targetDb = (dbName != null && !dbName.isBlank()) ? dbName
                : DatabaseProvisioningService.defaultDbName(project.getCode(), "0");

        // 数据库连接：H2 模式零依赖；MySQL 模式注入连接信息（db_type 为 NULL 时默认 MySQL）
        // K8s 容器内访问宿主机 MySQL 唯一稳定方案：用 host.docker.internal (K3s 内置 DNS → 192.168.127.254)
        // 不要 hostNetwork: true（Pod 内 127.0.0.1 = Pod 自己的 loopback；多副本还会抢宿主端口）
        // 自动改写: 用户填了 127.0.0.1 / localhost / 192.168.127.2 → 强制改成 host.docker.internal
        //   (用户填本机 IP 是最自然的选择,K3s 容器内 127.0.0.1 = Pod loopback,连不上)
        boolean useH2 = "H2".equalsIgnoreCase(project.getDbType());
        String dbHost;
        if (useH2) {
            dbHost = project.getDbHost() != null && !project.getDbHost().isBlank()
                    ? rewriteLoopbackHost(project.getDbHost()) : "host.docker.internal";
        } else {
            String envK8sDbHost = System.getenv("K8S_DB_HOST");
            if (envK8sDbHost != null && !envK8sDbHost.isBlank()) {
                dbHost = envK8sDbHost;
            } else if (project.getDbHost() != null && !project.getDbHost().isBlank()) {
                dbHost = rewriteLoopbackHost(project.getDbHost());
            } else {
                // K3s (Rancher Desktop) 唯一稳定的宿主机 MySQL 访问方式
                dbHost = "host.docker.internal";
            }
        }
        // 不再开启 hostNetwork：避免端口冲突 + 端口转发方案可正常工作
        String envSection = "";
        if (!useH2) {
            int dbPort = project.getDbPort() != null ? project.getDbPort() : 3306;
            String dbUser = project.getDbUsername() != null && !project.getDbUsername().isBlank()
                    ? project.getDbUsername() : "root";
            String dbPass = project.getDbPassword() != null ? project.getDbPassword() : "";
            String jdbcUrl = "jdbc:mysql://" + dbHost + ":" + dbPort + "/" + targetDb
                    + "?useUnicode=true&characterEncoding=utf-8&useSSL=false"
                    + "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&createDatabaseIfNotExist=true";
            envSection =
                "          env:\n" +
                // Spring Boot 风格 (Java)
                "            - name: SPRING_DATASOURCE_URL\n" +
                "              value: \"" + jdbcUrl + "\"\n" +
                "            - name: SPRING_DATASOURCE_USERNAME\n" +
                "              value: \"" + dbUser + "\"\n" +
                "            - name: SPRING_DATASOURCE_PASSWORD\n" +
                "              value: \"" + dbPass + "\"\n" +
                // 通用风格 (Python/Node.js/Go/C++/Rust)
                "            - name: DATABASE_URL\n" +
                "              value: \"" + jdbcUrl + "\"\n" +
                "            - name: DB_HOST\n" +
                "              value: \"" + dbHost + "\"\n" +
                "            - name: DB_PORT\n" +
                "              value: \"" + dbPort + "\"\n" +
                "            - name: DB_NAME\n" +
                "              value: \"" + targetDb + "\"\n" +
                "            - name: DB_USER\n" +
                "              value: \"" + dbUser + "\"\n" +
                "            - name: DB_PASSWORD\n" +
                "              value: \"" + dbPass + "\"\n";
        }

        // 前端 nginx 容器（如果有 frontend 目录）
        // 镜像名用 projectCode(共用,与构建一致),不用 k8sAppName(否则流水线名参与,本地无镜像 → ImagePullBackOff)
        String frontendContainer = "";
        if (hasFrontend) {
            String codeForImage = project.getCode() != null ? project.getCode() : "devops-app";
            frontendContainer =
                "        - name: " + codeForImage + "-frontend\n" +
                "          image: " + codeForImage + "-frontend:latest\n" +
                "          imagePullPolicy: IfNotPresent\n" +
                "          ports:\n" +
                "            - containerPort: 80\n" +
                "          resources:\n" +
                "            requests:\n" +
                "              memory: \"64Mi\"\n" +
                "              cpu: \"50m\"\n" +
                "            limits:\n" +
                "              memory: \"128Mi\"\n" +
                "              cpu: \"100m\"\n";
        }

        // Service targetPort: 有前端时指向 nginx(80)，否则指向后端容器端口
        int serviceTargetPort = hasFrontend ? 80 : containerPort;

        return "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: " + appName + "\n" +
                "  namespace: " + k8sNamespace + "\n" +
                "  labels:\n" +
                "    replicas-auto: \"" + replicas + "\"\n" +
                "spec:\n" +
                "  replicas: " + replicas + "\n" +
                "  selector:\n" +
                "    matchLabels:\n" +
                "      app: " + appName + "\n" +
                "  template:\n" +
                "    metadata:\n" +
                "      labels:\n" +
                "        app: " + appName + "\n" +
                "    spec:\n" +
                "      containers:\n" +
                "        - name: " + appName + "\n" +
                "          image: " + imageName + "\n" +
                "          imagePullPolicy: IfNotPresent\n" +
                "          ports:\n" +
                "            - containerPort: " + containerPort + "\n" +
                envSection +
                "          resources:\n" +
                "            requests:\n" +
                "              memory: \"256Mi\"\n" +
                "              cpu: \"250m\"\n" +
                "            limits:\n" +
                "              memory: \"512Mi\"\n" +
                "              cpu: \"500m\"\n" +
                (hasFrontend ? frontendContainer : "") +
                "---\n" +
                "apiVersion: v1\n" +
                "kind: Service\n" +
                "metadata:\n" +
                "  name: " + appName + "-svc\n" +
                "  namespace: " + k8sNamespace + "\n" +
                "spec:\n" +
                "  selector:\n" +
                "    app: " + appName + "\n" +
                "  ports:\n" +
                "    - port: 8080\n" +
                "      targetPort: " + serviceTargetPort + "\n" +
                "  type: ClusterIP\n";
    }

    private boolean executeTestReal(StepDef step, Project project, StringBuilder logBuf, Long buildId) throws Exception {
        Path workDir = findProjectRoot(project);
        String lang = project.getLanguage() != null ? project.getLanguage().toLowerCase() : "java";
        String cmd;
        if (lang.contains("node")) {
            cmd = "npm test -- --json --outputFile=test-results.json 2>&1 || true";
        } else if (lang.contains("python")) {
            Path pytest = workDir.resolve("pytest.ini");
            Path tox = workDir.resolve("tox.ini");
            if (Files.exists(pytest) || Files.exists(tox) || Files.exists(workDir.resolve(".pytest_cache"))) {
                cmd = "python -m pytest --junitxml=test-results.xml --tb=short 2>&1 || true";
            } else {
                cmd = "python -m unittest discover 2>&1 || true";
            }
        } else if (lang.equals("go") || lang.equals("golang")) {
            cmd = "go test ./... -json > test-output.json 2>&1 || true";
        } else if (lang.equals("rust")) {
            cmd = "cargo test 2>&1 || true";
        } else if (lang.contains("c++") || lang.contains("cpp") || lang.equals("c")) {
            Path buildDir = workDir.resolve("build");
            if (Files.exists(buildDir)) {
                cmd = "cd build && ctest --output-on-failure 2>&1 || true";
            } else {
                cmd = "ctest --output-on-failure 2>&1 || true";
            }
        } else {
            // Java / Kotlin / Scala — run with JaCoCo if available
            Path gradleBuild = workDir.resolve("build.gradle");
            Path gradleKts = workDir.resolve("build.gradle.kts");
            if (Files.exists(gradleBuild) || Files.exists(gradleKts)) {
                cmd = "gradle test jacocoTestReport 2>&1 || true";
            } else {
                cmd = "mvn test -B jacoco:report -DfailIfNoTests=false 2>&1 || true";
            }
        }
        logBuf.append("[TEST] ").append(cmd).append("\n");
        logBuf.append("[INFO] 工作目录: ").append(workDir).append("\n");
        appendLog(buildId, logBuf.toString());

        long startTime = System.currentTimeMillis();
        int exitCode = runCommand(workDir.toString(), cmd, logBuf, buildId);
        long duration = System.currentTimeMillis() - startTime;

        // 解析测试报告（测试失败不阻塞 — 由质量门判断）
        logBuf.append("[TEST] 测试命令退出码: ").append(exitCode).append("\n");
        logBuf.append("[TEST] 解析测试报告...\n");
        appendLog(buildId, logBuf.toString());

        Long pipelineId = getCurrentPipelineId(buildId);
        testReportService.parseAndSave(workDir, lang, buildId, project.getId(), pipelineId, duration, logBuf);

        // 返回 true 让流程继续（质量门步骤会检查报告），测试失败本身不阻塞
        return true;
    }

    private Long getCurrentPipelineId(Long buildId) {
        return buildRepository.findById(buildId)
                .map(Build::getPipelineId)
                .orElse(null);
    }

    private boolean executeNpmInstallReal(StepDef step, Project project, StringBuilder logBuf, Long buildId) throws Exception {
        Path projectRoot = findProjectRoot(project);
        Path packageJson = projectRoot.resolve("package.json");
        if (!Files.exists(packageJson)) {
            logBuf.append("[ERROR] 找不到 package.json: ").append(projectRoot.toAbsolutePath()).append("\n");
            return false;
        }
        String cmd = "npm install";
        logBuf.append("$ ").append(cmd).append("\n");
        logBuf.append("[INFO] 工作目录: ").append(projectRoot).append("\n");
        appendLog(buildId, logBuf.toString());
        return runCommand(projectRoot.toString(), cmd, logBuf, buildId) == 0;
    }

    private boolean executeNpmBuildReal(StepDef step, Project project, StringBuilder logBuf, Long buildId) throws Exception {
        Path projectRoot = findProjectRoot(project);
        Path packageJson = projectRoot.resolve("package.json");
        if (!Files.exists(packageJson)) {
            logBuf.append("[ERROR] 找不到 package.json: ").append(projectRoot.toAbsolutePath()).append("\n");
            return false;
        }
        String cmd = "npm run build";
        logBuf.append("$ ").append(cmd).append("\n");
        logBuf.append("[INFO] 工作目录: ").append(projectRoot).append("\n");
        appendLog(buildId, logBuf.toString());
        return runCommand(projectRoot.toString(), cmd, logBuf, buildId) == 0;
    }

    // ===================== Python 构建 =====================

    private boolean executePipInstallReal(StepDef step, Project project, StringBuilder logBuf, Long buildId) throws Exception {
        Path projectRoot = findProjectRoot(project);
        // 优先级: pyproject.toml → requirements.txt → setup.py → setup.cfg
        Path requirementsFile = projectRoot.resolve("requirements.txt");
        if (!Files.exists(requirementsFile)) {
            Path pyproject = projectRoot.resolve("pyproject.toml");
            if (Files.exists(pyproject)) {
                logBuf.append("[INFO] 使用 pyproject.toml 安装依赖\n");
                String cmd = "pip install -e .";
                logBuf.append("$ ").append(cmd).append("\n");
                appendLog(buildId, logBuf.toString());
                return runCommand(projectRoot.toString(), cmd, logBuf, buildId) == 0;
            }
            Path setupPy = projectRoot.resolve("setup.py");
            Path setupCfg = projectRoot.resolve("setup.cfg");
            if (Files.exists(setupPy) || Files.exists(setupCfg)) {
                logBuf.append("[INFO] 使用 setup.py/setup.cfg 安装依赖\n");
                String cmd = "pip install -e .";
                logBuf.append("$ ").append(cmd).append("\n");
                appendLog(buildId, logBuf.toString());
                return runCommand(projectRoot.toString(), cmd, logBuf, buildId) == 0;
            }
            logBuf.append("[WARN] 未找到 requirements.txt / pyproject.toml / setup.py\n");
            logBuf.append("[HINT] 确保依赖文件在项目根目录，然后重试\n");
            listDirectory(projectRoot, logBuf, 0, 2);
            return false;
        }
        String cmd = "pip install -r requirements.txt";
        logBuf.append("$ ").append(cmd).append("\n");
        logBuf.append("[INFO] 工作目录: ").append(projectRoot).append("\n");
        appendLog(buildId, logBuf.toString());
        return runCommand(projectRoot.toString(), cmd, logBuf, buildId) == 0;
    }

    private boolean executePythonBuildReal(StepDef step, Project project, StringBuilder logBuf, Long buildId) throws Exception {
        Path projectRoot = findProjectRoot(project);
        String command = step.command != null && !step.command.isBlank()
                ? step.command : "python -m compileall .";
        logBuf.append("$ ").append(command).append("\n");
        logBuf.append("[INFO] 工作目录: ").append(projectRoot).append("\n");
        appendLog(buildId, logBuf.toString());
        return runCommand(projectRoot.toString(), command, logBuf, buildId) == 0;
    }

    // ===================== Go 构建 =====================

    private boolean executeGoBuildReal(StepDef step, Project project, StringBuilder logBuf, Long buildId) throws Exception {
        Path projectRoot = findProjectRoot(project);
        Path goMod = projectRoot.resolve("go.mod");
        if (!Files.exists(goMod)) {
            logBuf.append("[ERROR] 找不到 go.mod\n");
            logBuf.append("[ERROR] 查找路径: ").append(projectRoot.toAbsolutePath()).append("\n");
            listDirectory(projectRoot, logBuf, 0, 2);
            return false;
        }
        String command = step.command != null && !step.command.isBlank()
                ? step.command : "go build -o app ./...";
        logBuf.append("$ ").append(command).append("\n");
        logBuf.append("[INFO] 工作目录: ").append(projectRoot).append("\n");
        appendLog(buildId, logBuf.toString());
        return runCommand(projectRoot.toString(), command, logBuf, buildId) == 0;
    }

    // ===================== C/C++ (CMake) 构建 =====================

    private boolean executeCmakeBuildReal(StepDef step, Project project, StringBuilder logBuf, Long buildId) throws Exception {
        Path projectRoot = findProjectRoot(project);
        Path cmakeLists = projectRoot.resolve("CMakeLists.txt");
        if (!Files.exists(cmakeLists)) {
            logBuf.append("[ERROR] 找不到 CMakeLists.txt\n");
            logBuf.append("[ERROR] 查找路径: ").append(projectRoot.toAbsolutePath()).append("\n");
            listDirectory(projectRoot, logBuf, 0, 2);
            return false;
        }
        Path buildDir = projectRoot.resolve("build");
        Files.createDirectories(buildDir);
        String cmd = "cmake -S . -B build && cmake --build build --config Release";
        logBuf.append("$ ").append(cmd).append("\n");
        logBuf.append("[INFO] 工作目录: ").append(projectRoot).append("\n");
        appendLog(buildId, logBuf.toString());
        int exitCode = runCommand(projectRoot.toString(), cmd, logBuf, buildId);
        return exitCode == 0;
    }

    // ===================== Rust 构建 =====================

    private boolean executeCargoBuildReal(StepDef step, Project project, StringBuilder logBuf, Long buildId) throws Exception {
        Path projectRoot = findProjectRoot(project);
        Path cargoToml = projectRoot.resolve("Cargo.toml");
        if (!Files.exists(cargoToml)) {
            logBuf.append("[ERROR] 找不到 Cargo.toml\n");
            logBuf.append("[ERROR] 查找路径: ").append(projectRoot.toAbsolutePath()).append("\n");
            listDirectory(projectRoot, logBuf, 0, 2);
            return false;
        }
        String command = step.command != null && !step.command.isBlank()
                ? step.command : "cargo build --release";
        logBuf.append("$ ").append(command).append("\n");
        logBuf.append("[INFO] 工作目录: ").append(projectRoot).append("\n");
        appendLog(buildId, logBuf.toString());
        return runCommand(projectRoot.toString(), command, logBuf, buildId) == 0;
    }

    private boolean executeMvnPackageReal(StepDef step, Project project, StringBuilder logBuf, Long buildId) throws Exception {
        Path projectRoot = findProjectRoot(project);
        Path pomXml = projectRoot.resolve("pom.xml");

        if (!Files.exists(pomXml)) {
            logBuf.append("[ERROR] 找不到 pom.xml\n");
            logBuf.append("[ERROR] 查找路径: ").append(projectRoot.toAbsolutePath()).append("\n");
            logBuf.append("[HINT] 确认 pom.xml 在仓库中且未被 .gitignore 排除\n");
            logBuf.append("[HINT] 如果 pom.xml 在子目录中，可以在项目的构建命令里指定路径\n");
            // 列出目录内容帮助诊断
            logBuf.append("--- 目录内容 ---\n");
            listDirectory(projectRoot, logBuf, 0, 2);
            logBuf.append("----------------\n");
            return false;
        }

        String cmd = "mvn clean package -DskipTests -B";
        String workDir = projectRoot.toString();
        logBuf.append("$ ").append(cmd).append("\n");
        logBuf.append("[INFO] 工作目录: ").append(workDir).append("\n");
        appendLog(buildId, logBuf.toString());
        return runCommand(workDir, cmd, logBuf, buildId) == 0;
    }

    // ===================== 安全扫描 (DevSecOps) =====================

    /** 解析 step.command 里的 key=value 配置,例如 "thresholdHigh=5 thresholdCritical=0" */
    private java.util.Map<String, Integer> parseThresholdParams(String command) {
        java.util.Map<String, Integer> m = new java.util.HashMap<>();
        if (command == null || command.isBlank()) return m;
        for (String kv : command.split("\\s+")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            try { m.put(kv.substring(0, eq), Integer.parseInt(kv.substring(eq + 1))); }
            catch (NumberFormatException ignored) {}
        }
        return m;
    }

    /**
     * 安全扫描未通过时派发 security.failed 事件 (Webhook)
     * 覆盖 文件系统 / 依赖 / 镜像 / SAST 四类扫描
     */
    private void notifySecurityFailed(Project project, Long buildId, String scanKind, String summary) {
        try {
            String brief = summary == null ? "" : summary;
            if (brief.length() > 500) brief = brief.substring(0, 500) + " ...";
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("title", "安全扫描未通过 · " + scanKind);
            payload.put("project", project.getName() == null ? "" : project.getName());
            payload.put("projectId", String.valueOf(project.getId()));
            payload.put("buildId", String.valueOf(buildId));
            payload.put("scan", scanKind);
            payload.put("summary", brief);
            webhookDispatcher.dispatch(
                    com.devops.platform.entity.WebhookSubscription.EVENT_SECURITY_FAILED,
                    payload, project.getId());
        } catch (Exception e) {
            log.warn("[SEC] security.failed 事件派发失败: {}", e.getMessage());
        }
    }

    private boolean executeSecurityScanFsReal(StepDef step, Project project, StringBuilder logBuf, Long buildId) throws Exception {
        java.util.Map<String, Integer> p = parseThresholdParams(step.command);
        int th = p.getOrDefault("thresholdHigh", 5);
        int tc = p.getOrDefault("thresholdCritical", 0);

        Path projectRoot = findProjectRoot(project);
        logBuf.append("[SEC] FS 扫描: ").append(projectRoot).append("\n");
        logBuf.append("[SEC] 阈值: critical≤").append(tc).append(", high≤").append(th).append("\n");
        appendLog(buildId, logBuf.toString());

        if (!securityScanService.isTrivyAvailable()) {
            logBuf.append("[WARN] Trivy 未安装, 跳过扫描 (返回 ERRORED)\n");
            logBuf.append("[HINT] 安装 Trivy: https://aquasecurity.github.io/trivy/latest/getting-started/installation/\n");
            appendLog(buildId, logBuf.toString());
            return true;  // 不阻断, 让用户感知但不强制失败
        }

        SecurityReport report = securityScanService.scanFilesystem(buildId, project.getId(), projectRoot, th, tc);
        logBuf.append("[SEC] ").append(report.getSummary()).append("\n");
        appendLog(buildId, logBuf.toString());
        boolean passed = !SecurityScanService.STATUS_FAILED.equals(report.getStatus());
        if (!passed) notifySecurityFailed(project, buildId, "文件系统扫描", report.getSummary());
        return passed;
    }

    /** SAST_SCAN 步骤: 静态代码安全分析 (Semgrep / 内置规则) */
    private boolean executeSastScanReal(StepDef step, Project project, StringBuilder logBuf, Long buildId) throws Exception {
        java.util.Map<String, Integer> p = parseThresholdParams(step.command);
        int th = p.getOrDefault("thresholdHigh", 0);
        int tc = p.getOrDefault("thresholdCritical", 0);

        Path projectRoot = findProjectRoot(project);
        logBuf.append("[SAST] 静态代码扫描: ").append(projectRoot).append("\n");
        logBuf.append("[SAST] 阈值: critical≤").append(tc).append(", high≤").append(th).append("\n");
        logBuf.append("[SAST] 引擎: ").append(sastScanService.isSemgrepAvailable() ? "Semgrep" : "内置模式扫描器(降级)").append("\n");
        appendLog(buildId, logBuf.toString());

        SastScanReport report = sastScanService.scanSource(buildId, project.getId(), projectRoot, th, tc);
        logBuf.append("[SAST] ").append(report.getSummary()).append("\n");
        appendLog(buildId, logBuf.toString());
        boolean passed = !SastScanService.STATUS_FAILED.equals(report.getStatus());
        if (!passed) notifySecurityFailed(project, buildId, "静态代码扫描", report.getSummary());
        return passed;
    }

    private boolean executeSecurityScanDepReal(StepDef step, Project project, StringBuilder logBuf, Long buildId) throws Exception {
        java.util.Map<String, Integer> p = parseThresholdParams(step.command);
        int th = p.getOrDefault("thresholdHigh", 10);
        int tc = p.getOrDefault("thresholdCritical", 0);

        Path projectRoot = findProjectRoot(project);
        logBuf.append("[SEC] 依赖扫描: ").append(projectRoot).append("\n");
        logBuf.append("[SEC] 阈值: critical≤").append(tc).append(", high≤").append(th).append("\n");
        appendLog(buildId, logBuf.toString());

        if (!securityScanService.isTrivyAvailable()) {
            logBuf.append("[WARN] Trivy 未安装, 跳过扫描 (返回 ERRORED)\n");
            appendLog(buildId, logBuf.toString());
            return true;
        }

        SecurityReport report = securityScanService.scanDependency(buildId, project.getId(), projectRoot, th, tc);
        logBuf.append("[SEC] ").append(report.getSummary()).append("\n");
        appendLog(buildId, logBuf.toString());
        boolean passed = !SecurityScanService.STATUS_FAILED.equals(report.getStatus());
        if (!passed) notifySecurityFailed(project, buildId, "依赖扫描", report.getSummary());
        return passed;
    }

    private boolean executeSecurityScanImageReal(StepDef step, Project project, StringBuilder logBuf, Long buildId) throws Exception {
        java.util.Map<String, Integer> p = parseThresholdParams(step.command);
        int th = p.getOrDefault("thresholdHigh", 5);
        int tc = p.getOrDefault("thresholdCritical", 0);

        // image name 由流水线上下文决定: projectCode:latest
        String imageName = project.getCode() + ":latest";
        logBuf.append("[SEC] 镜像扫描: ").append(imageName).append("\n");
        logBuf.append("[SEC] 阈值: critical≤").append(tc).append(", high≤").append(th).append("\n");
        appendLog(buildId, logBuf.toString());

        if (!securityScanService.isTrivyAvailable()) {
            logBuf.append("[WARN] Trivy 未安装, 跳过扫描 (返回 ERRORED)\n");
            appendLog(buildId, logBuf.toString());
            return true;
        }

        SecurityReport report = securityScanService.scanImage(buildId, project.getId(), imageName, th, tc);
        logBuf.append("[SEC] ").append(report.getSummary()).append("\n");
        appendLog(buildId, logBuf.toString());
        boolean passed = !SecurityScanService.STATUS_FAILED.equals(report.getStatus());
        if (!passed) notifySecurityFailed(project, buildId, "镜像扫描", report.getSummary());
        return passed;
    }

    /** QUALITY_GATE 步骤：检查已保存的测试报告并执行质量门禁 */
    private boolean executeQualityGateReal(StepDef step, Project project, StringBuilder logBuf, Long buildId) {
        java.util.Map<String, Integer> p = parseThresholdParams(step.command);
        int minPassRate = p.getOrDefault("passRate", 90);
        int minCoverage = p.getOrDefault("coverage", 60);

        logBuf.append("[GATE] ========== 质量门禁 ==========\n");
        logBuf.append("[GATE] 最低通过率: ").append(minPassRate).append("%\n");
        logBuf.append("[GATE] 最低覆盖率: ").append(minCoverage).append("%\n");

        // 从已保存的 TestReport 中获取结果
        List<TestReport> reports = testReportService.getByBuildId(buildId);
        if (reports.isEmpty()) {
            logBuf.append("[GATE] 未找到测试报告 — 门禁跳过\n");
            logBuf.append("[GATE] 提示: 确保流水线包含 TEST 步骤\n");
            return true; // 无报告时放行
        }

        boolean allPassed = true;
        for (TestReport report : reports) {
            logBuf.append("[GATE] --- 报告 #").append(report.getId())
                    .append(" (").append(report.getTestFramework()).append(") ---\n");

            // 检查通过率
            if (report.getTotalTests() != null && report.getTotalTests() > 0) {
                double passRate = 100.0 * report.getPassed() / report.getTotalTests();
                logBuf.append(String.format("[GATE] 通过率: %.1f%% (%d/%d)\n",
                        passRate, report.getPassed(), report.getTotalTests()));
                if (passRate < minPassRate) {
                    logBuf.append("[GATE] ❌ 通过率不达标 (min ").append(minPassRate).append("%)\n");
                    allPassed = false;
                }
            }

            // 检查覆盖率
            if (minCoverage > 0 && report.getCoveragePercent() != null && report.getCoveragePercent() >= 0) {
                logBuf.append(String.format("[GATE] 覆盖率: %.1f%%\n", report.getCoveragePercent()));
                if (report.getCoveragePercent() < minCoverage) {
                    logBuf.append("[GATE] ❌ 覆盖率不达标 (min ").append(minCoverage).append("%)\n");
                    allPassed = false;
                }
            } else if (minCoverage > 0) {
                logBuf.append("[GATE] ⚠ 未采集覆盖率数据\n");
            }

            // 检查失败数
            if (report.getFailed() != null && report.getFailed() > 0) {
                logBuf.append("[GATE] ❌ 存在 ").append(report.getFailed()).append(" 个失败测试\n");
                allPassed = false;
            }
        }

        if (allPassed) {
            logBuf.append("[GATE] ✅ 质量门禁通过\n");
            return true;
        } else {
            logBuf.append("[GATE] ❌ 质量门禁未通过\n");
            return false;
        }
    }

    /** 废弃: 旧版 XML 解析已迁移至 TestReportService */
    @Deprecated
    private int extractXmlInt(String xml, String attrName) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                attrName + "\\s*=\\s*\"(\\d+)\"");
        java.util.regex.Matcher m = p.matcher(xml);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** APPROVAL 步骤：暂停构建，等待人工审批 */
    private boolean executeApprovalReal(StepDef step, Project project, StringBuilder logBuf, Long buildId) {
        Build build = buildRepository.findById(buildId).orElse(null);
        if (build == null) return false;

        String approverHint = step.approver != null && !step.approver.isBlank()
                ? step.approver : "任意管理员";
        logBuf.append("[APPROVAL] ⏸ 阶段审批门: ").append(step.name).append("\n");
        logBuf.append("[APPROVAL] 审批人: ").append(approverHint).append("\n");
        logBuf.append("[APPROVAL] 等待人工审批... 通过: POST /api/builds/").append(buildId)
              .append("/approve-step  拒绝: POST /api/builds/").append(buildId).append("/reject-step\n");
        appendLog(buildId, logBuf.toString());

        // 将构建状态改为等待审批
        build.setStatus("WAITING_APPROVAL");
        build.setResultMessage("等待审批: " + step.name + " (" + approverHint + ")");
        build.setFailedStageIndex(null);  // 复用此字段存储当前审批步骤名
        buildRepository.save(build);

        // 发送通知
        try {
            notificationService.deployApprovalRequest(buildId, project.getName(), step.name);
        } catch (Exception ignored) {}

        // 暂停：等待审批通过或拒绝（通过 approveStep/rejectStep 方法恢复）
        // 这里不直接返回 false 或 true，而是让外部轮询状态
        // 实际：返回 false 会让调用方认为失败，但我们在 executor 中做特殊处理

        // 把 current build 信息写到 log 并等待
        long waitStart = System.currentTimeMillis();
        long maxWait = 3600_000; // 最多等 1 小时
        while (System.currentTimeMillis() - waitStart < maxWait) {
            try {
                Thread.sleep(2000); // 每 2 秒检查
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            Build latest = buildRepository.findById(buildId).orElse(null);
            if (latest == null) return false;
            if ("CANCELLED".equals(latest.getStatus())) return false;
            if ("RUNNING".equals(latest.getStatus())) {
                // 审批通过，状态已由 approveStep 改回 RUNNING
                logBuf.append("[APPROVAL] ✅ 审批通过: ").append(step.name).append("\n");
                appendLog(buildId, logBuf.toString());
                return true;
            }
            if ("FAILED".equals(latest.getStatus()) && latest.getResultMessage() != null
                    && latest.getResultMessage().startsWith("审批拒绝")) {
                logBuf.append("[APPROVAL] ❌ 审批拒绝: ").append(step.name).append("\n");
                return false;
            }
        }
        // 超时
        logBuf.append("[APPROVAL] ⏰ 审批超时 (1小时)\n");
        return false;
    }

    /** 审批通过当前等待中的步骤 */
    public void approveStep(Long buildId, String approvedBy) {
        Build build = buildRepository.findById(buildId)
                .orElseThrow(() -> new RuntimeException("构建记录不存在"));
        if (!"WAITING_APPROVAL".equals(build.getStatus())) {
            throw new RuntimeException("当前构建不在等待审批状态: " + build.getStatus());
        }
        build.setStatus("RUNNING");
        build.setResultMessage("审批通过: " + approvedBy);
        build.setTriggeredBy(approvedBy);  // 记录审批人
        buildRepository.save(build);
        log.info("[APPROVAL] 构建 {} 审批通过，审批人: {}", build.getBuildNumber(), approvedBy);
    }

    /** 审批拒绝当前等待中的步骤 */
    public void rejectStep(Long buildId, String rejectedBy, String reason) {
        Build build = buildRepository.findById(buildId)
                .orElseThrow(() -> new RuntimeException("构建记录不存在"));
        if (!"WAITING_APPROVAL".equals(build.getStatus())) {
            throw new RuntimeException("当前构建不在等待审批状态: " + build.getStatus());
        }
        build.setStatus("FAILED");
        build.setEndTime(LocalDateTime.now());
        build.setResultMessage("审批拒绝: " + rejectedBy + (reason != null ? " (" + reason + ")" : ""));
        build.setTriggeredBy(rejectedBy);
        buildRepository.save(build);
        log.info("[APPROVAL] 构建 {} 审批拒绝，审批人: {}", build.getBuildNumber(), rejectedBy);
    }

    // ===================== Git 操作 =====================

    /** 确保工作区根目录已创建并返回绝对路径 */
    private Path resolveWorkspaceRoot() throws IOException {
        if (workspaceRoot != null) return workspaceRoot;
        synchronized (this) {
            if (workspaceRoot != null) return workspaceRoot;
            Path p = Paths.get(workspaceDir).toAbsolutePath().normalize();
            Files.createDirectories(p);
            workspaceRoot = p;
            return workspaceRoot;
        }
    }

    private boolean prepareWorkspace(Project project, StringBuilder logBuf, Long buildId, String branch) throws Exception {
        Path root = resolveWorkspaceRoot();
        String projectCode = project.getCode();
        if (projectCode == null || projectCode.isBlank()) {
            logBuf.append("[ERROR] 项目编码 (code) 为空，无法创建工作目录\n");
            return false;
        }
        // 工作目录按流水线名隔离：同项目不同流水线互不污染 git 历史
        String dirName = workspaceDirName(project);
        Path workspacePath = root.resolve(dirName);
        String workspaceAbs = workspacePath.toString();
        // 记下实际目录，构建收尾（制品收集/服务实例/缓存）统一读它
        currentWorkspace.set(workspaceAbs);
        String gitUrl = project.getGitUrl();
        // 分支来源: 触发参数 > 项目配置; 两者都为空 = null, 表示跟随远端默认分支 (main / master 自动识别)
        String effectiveBranch = (branch != null && !branch.isBlank()) ? branch.trim()
                : (project.getGitBranch() != null && !project.getGitBranch().isBlank()) ? project.getGitBranch().trim()
                : null;

        logBuf.append("========== 代码准备 ==========\n");
        logBuf.append("[INFO] 工作目录: ").append(workspaceAbs).append("\n");
        com.devops.platform.entity.Pipeline cur = currentPipeline.get();
        if (cur != null && !dirName.equals(projectCode)) {
            logBuf.append("[INFO] 流水线隔离: ").append(cur.getName())
                    .append(" → 独立目录 ").append(dirName).append("\n");
        }

        if (gitUrl == null || gitUrl.isBlank()) {
            logBuf.append("[WARN] 未配置 Git 地址，跳过代码拉取\n");
            if (!Files.exists(workspacePath)) Files.createDirectories(workspacePath);
            return true;
        }

        // --- 已有 Git 仓库 → Pull ---
        if (Files.exists(workspacePath) && Files.exists(workspacePath.resolve(".git"))) {
            // 未指定分支 → 优先用本地当前分支; 读不到则探测远端默认分支
            String pullBranch = effectiveBranch;
            if (pullBranch == null) pullBranch = readLocalBranch(workspaceAbs);
            if (pullBranch == null) pullBranch = probeRemote(gitUrl, root.toString()).defaultBranch();
            if (pullBranch == null) {
                logBuf.append("[ERROR] 未配置 Git 分支, 且无法自动识别本地/远端分支\n");
                logBuf.append("[HINT] 请在「项目管理 → 编辑」中填写 Git 分支\n");
                return false;
            }
            logBuf.append("[GIT] 仓库已存在，拉取最新代码 (分支: ").append(pullBranch).append(")\n");
            // 强制与远端对齐：避免本地遗留 commit 与远端 force-push 冲突
            String pullCmd = "git fetch origin && git checkout " + pullBranch
                    + " && git reset --hard origin/" + pullBranch;
            logBuf.append("$ ").append(pullCmd).append("\n");
            appendLog(buildId, logBuf.toString());
            int exitCode = runGitCommandWithRetry(workspaceAbs, pullCmd, logBuf, buildId, 3);
            if (exitCode != 0) {
                logBuf.append("[ERROR] Git 同步失败 (退出码: ").append(String.valueOf(exitCode)).append(")\n");
                logBuf.append("[HINT] 如果是认证失败，请检查 Git 地址是否包含 Token 或密钥\n");
                return false;
            }
            recordWorkspaceCommit(buildId, workspaceAbs, logBuf);
            logBuf.append("[GIT] 代码已对齐远端 ").append(pullBranch).append("\n\n");
            return verifyWorkspaceContents(project, workspacePath, logBuf);
        }

        // --- 目录存在但非 Git 仓库 → 清理后重新克隆 ---
        if (Files.exists(workspacePath)) {
            logBuf.append("[WARN] 目录已存在但不是 Git 仓库，清理后重新克隆\n");
            deleteRecursively(workspacePath);
        }

        // --- 全新克隆 ---
        logBuf.append("[GIT] 克隆: ").append(gitUrl)
                .append(" (分支: ").append(effectiveBranch != null ? effectiveBranch : "远端默认分支").append(")\n");
        Files.createDirectories(root);
        String cloneCmd = cloneCommand(gitUrl, dirName, effectiveBranch);
        logBuf.append("$ ").append(cloneCmd).append("\n");
        appendLog(buildId, logBuf.toString());
        int exitCode = runGitCommandWithRetry(root.toString(), cloneCmd, logBuf, buildId, 3);
        if (exitCode != 0) {
            logBuf.append("[ERROR] Git clone 失败 (退出码: ").append(String.valueOf(exitCode)).append(")\n");
            appendLog(buildId, logBuf.toString());

            // --- 诊断: 区分「分支不存在」与「地址/权限问题」 ---
            RemoteProbe probe = probeRemote(gitUrl, root.toString());
            String fallback = null;
            if (probe.reachable()) {
                if (!probe.branches().isEmpty()) {
                    logBuf.append("[GIT] 远端分支: ").append(String.join(", ", probe.branches())).append("\n");
                }
                logBuf.append("[GIT] 远端默认分支: ")
                        .append(probe.defaultBranch() != null ? probe.defaultBranch() : "未知").append("\n");
                if (effectiveBranch != null && !probe.branches().contains(effectiveBranch)) {
                    logBuf.append("[WARN] 配置的分支 \"").append(effectiveBranch).append("\" 在远端不存在\n");
                    fallback = probe.defaultBranch() != null ? probe.defaultBranch()
                            : (probe.branches().isEmpty() ? null : probe.branches().get(0));
                }
            } else {
                logBuf.append("[GIT] 无法连接远端仓库, 疑似地址错误或无访问权限\n");
            }

            // 分支不存在 → 自动改用远端默认分支重试一次
            if (fallback != null && !fallback.equals(effectiveBranch)) {
                logBuf.append("[HINT] 自动改用远端默认分支 \"").append(fallback).append("\" 重新克隆\n");
                appendLog(buildId, logBuf.toString());
                try {
                    if (Files.exists(workspacePath)) deleteRecursively(workspacePath);  // 清理半成品目录
                } catch (Exception ex) {
                    log.warn("[GIT] 清理失败目录异常: {}", ex.getMessage());
                }
                int retryExit = runGitCommandWithRetry(root.toString(),
                        cloneCommand(gitUrl, dirName, fallback), logBuf, buildId, 1);
                if (retryExit == 0) {
                    logBuf.append("[GIT] 已用分支 \"").append(fallback).append("\" 克隆成功\n");
                    logBuf.append("[HINT] 建议把项目的 Git 分支改为 \"").append(fallback)
                            .append("\" (或留空跟随远端默认分支), 免得每次构建都要重试\n");
                    appendLog(buildId, logBuf.toString());
                } else {
                    appendCloneHints(logBuf, gitUrl, fallback);
                    return false;
                }
            } else {
                appendCloneHints(logBuf, gitUrl, effectiveBranch);
                return false;
            }
        }

        // 验证克隆结果
        if (!Files.exists(workspacePath)) {
            logBuf.append("[ERROR] Git clone 未报错但目标目录不存在: ").append(workspaceAbs).append("\n");
            return false;
        }
        recordWorkspaceCommit(buildId, workspaceAbs, logBuf);
        logBuf.append("[GIT] 克隆完成\n\n");

        // === 恢复构建缓存（Maven .m2 / npm node_modules / Go GOMODCACHE） ===
        restoreBuildCache(workspacePath, project, logBuf);

        // === 同步改写 O 项目 application.yml 的 MySQL 账号密码(用户填的 → 替换 O 项目自带的) ===
        // 原因: D 平台在项目里配了 dbType=MYSQL + dbUsername + dbPassword
        //       O 项目(如 apartment)自己的 application.yml 里写死了 root/z3053958492.
        //       如果不改写,Docker 镜像构建时把 yml 打进 jar,K8s 部署时
        //       SPRING_DATASOURCE_USERNAME 注入到 env 覆盖了 datasource.url 的 host
        //       但 username/password 仍来自 yml 里的旧值 → 驱动连接失败
        // 时机: git clone 之后,Docker build 之前
        if (!"H2".equalsIgnoreCase(project.getDbType())
                && project.getDbUsername() != null && !project.getDbUsername().isBlank()) {
            try {
                rewriteApplicationYmlCreds(workspacePath, project);
            } catch (Exception ex) {
                logBuf.append("[WARN] 改写 application.yml 账号失败: ").append(ex.getMessage()).append("\n");
            }
        }

        return verifyWorkspaceContents(project, workspacePath, logBuf);
    }

    // ===================== Git 分支自动识别 =====================

    /** 组装 clone 命令; branch 为 null 时不带 -b, 由 git 使用远端默认分支 */
    private static String cloneCommand(String gitUrl, String dirName, String branch) {
        return branch != null
                ? "git clone -b " + branch + " " + gitUrl + " " + dirName
                : "git clone " + gitUrl + " " + dirName;
    }

    /**
     * 远端仓库探测结果
     *
     * @param reachable     远端是否可达 (地址正确且有权限)
     * @param defaultBranch 远端 HEAD 指向的默认分支, 探测不到为 null
     * @param branches      远端分支清单
     */
    private record RemoteProbe(boolean reachable, String defaultBranch, List<String> branches) {}

    /**
     * 探测远端仓库: 是否可达 + 默认分支 + 分支清单
     * <p>
     * 用只读的 git ls-remote, 不改动任何本地文件。探测失败(地址错/无权限)时 reachable=false,
     * 调用方据此区分「分支不存在」(可自动换分支重试) 与「地址或权限问题」(换分支也没用)。
     */
    private RemoteProbe probeRemote(String gitUrl, String workDir) {
        String defaultBranch = null;
        List<String> branches = new ArrayList<>();
        boolean reachable = false;
        try {
            // HEAD 的 symref 就是远端默认分支: "ref: refs/heads/master<TAB>HEAD"
            StringBuilder sb = new StringBuilder();
            if (runCommand(workDir, "git ls-remote --symref " + gitUrl + " HEAD", sb, null) == 0) {
                reachable = true;
                defaultBranch = parseRemoteDefaultBranch(sb.toString());
            }
            // 全部远端分支
            StringBuilder sb2 = new StringBuilder();
            if (runCommand(workDir, "git ls-remote --heads " + gitUrl, sb2, null) == 0) {
                reachable = true;
                branches = parseRemoteBranchList(sb2.toString());
            }
        } catch (Exception e) {
            log.debug("[GIT] 远端探测失败: {}", e.getMessage());
        }
        return new RemoteProbe(reachable, defaultBranch, branches);
    }

    /**
     * 解析 git ls-remote 输出中「ref: refs/heads/xxx」那一行的分支名
     * 输入行形如: {@code ref: refs/heads/master<TAB>HEAD}
     * 注意 runCommand 会给每行加两个空格前缀, 这里统一 trim 后再判断
     */
    static String parseRemoteDefaultBranch(String output) {
        if (output == null) return null;
        for (String raw : output.split("\\R")) {
            String line = raw.trim();
            if (!line.startsWith("ref: refs/heads/")) continue;
            String rest = line.substring("ref: refs/heads/".length()).trim();
            String name = rest.split("\\s+")[0].trim();
            if (!name.isEmpty()) return name;
        }
        return null;
    }

    /** 解析 git ls-remote --heads 输出中的分支名 (行形如: {@code <sha><TAB>refs/heads/xxx}) */
    static List<String> parseRemoteBranchList(String output) {
        List<String> out = new ArrayList<>();
        if (output == null) return out;
        for (String raw : output.split("\\R")) {
            String line = raw.trim();
            int i = line.indexOf("refs/heads/");
            if (i < 0) continue;
            String name = line.substring(i + "refs/heads/".length()).trim();
            if (!name.isEmpty() && !out.contains(name)) out.add(name);
        }
        return out;
    }

    /**
     * 记录本次构建实际构建的 commit，写入 {@code builds.git_commit}。
     * <p>
     * 用于「项目在 GitHub 上有更新」的比对：把已部署构建的 commit 与远端最新 commit 对比，
     * 就知道要不要提示用户更新。原来只有 webhook 触发的构建才写这个字段，手动构建是空的，
     * 导致更新检查只能靠提交时间猜。
     * <p>
     * 读不到不影响构建 —— 只是这条构建不参与 commit 比对（会退化为按时间判断）。
     */
    private void recordWorkspaceCommit(Long buildId, String workDir, StringBuilder logBuf) {
        if (buildId == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            if (runCommand(workDir, "git rev-parse HEAD", sb, null) != 0) return;
            String sha = null;
            for (String raw : sb.toString().split("\\R")) {
                String s = raw.trim();
                if (s.matches("[0-9a-fA-F]{40}")) { sha = s; break; }
            }
            if (sha == null) return;
            final String finalSha = sha;
            buildRepository.findById(buildId).ifPresent(b -> {
                b.setGitCommit(finalSha);
                buildRepository.save(b);
            });
            logBuf.append("[GIT] 本次构建 commit: ").append(sha, 0, 8).append("\n");
        } catch (Exception e) {
            log.warn("[GIT] 记录 commit 失败 (buildId={}): {}", buildId, e.getMessage());
        }
    }

    /** 读取本地仓库当前分支; detached HEAD 或读取失败时返回 null */
    private String readLocalBranch(String workDir) {
        try {
            StringBuilder sb = new StringBuilder();
            if (runCommand(workDir, "git rev-parse --abbrev-ref HEAD", sb, null) != 0) return null;
            String last = null;
            for (String raw : sb.toString().split("\\R")) {
                String s = raw.trim();
                if (!s.isEmpty()) last = s;
            }
            // detached HEAD 时该命令输出 "HEAD", 不可当分支名用
            if (last == null || "HEAD".equals(last) || last.contains(" ")) return null;
            return last;
        } catch (Exception e) {
            return null;
        }
    }

    /** clone 彻底失败时的排查提示 */
    private void appendCloneHints(StringBuilder logBuf, String gitUrl, String branch) {
        logBuf.append("[HINT] 请确认:\n");
        logBuf.append("  1. Git 地址正确: ").append(gitUrl).append("\n");
        logBuf.append("  2. 分支存在: ").append(branch != null ? branch : "(跟随远端默认分支)").append("\n");
        logBuf.append("  3. 有访问权限 (公开仓库 或 个人 Token)\n");
    }

    /**
     * 恢复构建缓存（依赖目录持久化，避免每次重新下载）
     * 缓存目录: ./data/cache/{projectCode}/
     *   - m2/          → ~/.m2 (Maven)
     *   - npm-cache/   → ~/.npm (npm)
     *   - gomodcache/  → GOMODCACHE (Go)
     *   - pip-cache/   → ~/.cache/pip (Python)
     */
    private void restoreBuildCache(Path workspacePath, Project project, StringBuilder logBuf) {
        try {
            Path cacheRoot = resolveWorkspaceRoot().getParent().resolve("cache").resolve(project.getCode());
            String lang = project.getLanguage() != null ? project.getLanguage().toLowerCase() : "java";

            if (lang.contains("node")) {
                Path npmCache = cacheRoot.resolve("npm-cache");
                if (Files.exists(npmCache)) {
                    Path target = workspacePath.resolve("node_modules");
                    if (!Files.exists(target) || isEmptyDir(target)) {
                        copyDir(npmCache, target);
                        logBuf.append("[CACHE] 恢复 npm 缓存: node_modules\n");
                    }
                }
            } else if (lang.contains("python")) {
                Path pipCache = cacheRoot.resolve("pip-cache");
                if (Files.exists(pipCache)) {
                    logBuf.append("[CACHE] pip 缓存可用 (").append(dirSize(pipCache) / 1024 / 1024)
                          .append(" MB)\n");
                }
            } else if (lang.equals("go") || lang.equals("golang")) {
                Path goCache = cacheRoot.resolve("gomodcache");
                if (Files.exists(goCache)) {
                    logBuf.append("[CACHE] Go 模块缓存可用 (").append(dirSize(goCache) / 1024 / 1024)
                          .append(" MB)\n");
                }
            } else {
                // Java: restore .m2
                Path m2Cache = cacheRoot.resolve("m2");
                if (Files.exists(m2Cache)) {
                    Path target = workspacePath.resolve(".m2-cache");
                    copyDir(m2Cache, target);
                    logBuf.append("[CACHE] 恢复 Maven 缓存: .m2 (").append(dirSize(m2Cache) / 1024 / 1024)
                          .append(" MB)\n");
                }
            }
        } catch (Exception e) {
            logBuf.append("[CACHE] 恢复缓存失败: ").append(e.getMessage()).append("\n");
        }
    }

    /**
     * 保存构建缓存（构建成功后调用，保留已下载的依赖）
     */
    private void saveBuildCache(Path workspacePath, Project project, StringBuilder logBuf) {
        try {
            Path cacheRoot = resolveWorkspaceRoot().getParent().resolve("cache").resolve(project.getCode());
            Files.createDirectories(cacheRoot);
            String lang = project.getLanguage() != null ? project.getLanguage().toLowerCase() : "java";

            if (lang.contains("node")) {
                Path nodeModules = workspacePath.resolve("node_modules");
                if (Files.exists(nodeModules) && !isEmptyDir(nodeModules)) {
                    copyDir(nodeModules, cacheRoot.resolve("npm-cache"));
                    logBuf.append("[CACHE] 保存 npm 缓存\n");
                }
            } else {
                // Maven: save local repository
                Path m2User = Path.of(System.getProperty("user.home"), ".m2", "repository");
                if (Files.exists(m2User)) {
                    copyDir(m2User, cacheRoot.resolve("m2"));
                    logBuf.append("[CACHE] 保存 Maven 缓存 (").append(dirSize(m2User) / 1024 / 1024)
                          .append(" MB)\n");
                }
            }
        } catch (Exception e) {
            logBuf.append("[CACHE] 保存缓存失败: ").append(e.getMessage()).append("\n");
        }
    }

    private boolean isEmptyDir(Path dir) {
        try {
            if (!Files.isDirectory(dir)) return true;
            try (var s = Files.list(dir)) { return s.findAny().isEmpty(); }
        } catch (IOException e) { return true; }
    }

    private void copyDir(Path src, Path dst) {
        try {
            if (Files.exists(dst)) deleteRecursively(dst);
            Files.walk(src).forEach(s -> {
                try {
                    Path d = dst.resolve(src.relativize(s));
                    if (Files.isDirectory(s)) Files.createDirectories(d);
                    else Files.copy(s, d, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    private long dirSize(Path dir) {
        try {
            return Files.walk(dir).filter(Files::isRegularFile)
                    .mapToLong(f -> { try { return Files.size(f); } catch (IOException e) { return 0; } }).sum();
        } catch (IOException e) { return 0; }
    }

    /**
     * 同步改写 O 项目里 application.yml/application.properties 的 MySQL 账号密码
     * <p>
     * 用户在 D 平台项目里配了 dbType=MYSQL + dbUsername + dbPassword
     * 之前只通过 env 注入 SPRING_DATASOURCE_URL/USERNAME/PASSWORD,但 O 项目 jar 里的
     * application.yml 有自己的 username/password 字段,Docker 镜像时把 yml 打进去后
     * Spring Boot 仍会读取 yml,env 覆盖的优先级够高才生效
     * 实际: 容器启动时看到 application.yml 里的 username 与注入的 SPRING_DATASOURCE_USERNAME 不一致
     *       会以 yml 优先(env 是兜底),导致连接被 MySQL 拒绝
     * <p>
     * 这里主动改写 workspace 里所有 application.yml/properties,让两边一致
     * 工作流:
     *  1) Spring Boot 项目找 application.yml 或 application-{profile}.yml
     *  2) 解析 spring.datasource.username / password
     *  3) 替换为用户配置的 dbUsername / dbPassword
     *  4) host:port 也用 project 配置的(用户填的优先,空则用 host.docker.internal)
     *
     * @param workspacePath O 项目工作目录
     * @param project D 平台项目(含 dbType/dbUsername/dbPassword)
     */
    private void rewriteApplicationYmlCreds(Path workspacePath, Project project) {
        String targetUser = project.getDbUsername();
        String targetPass = project.getDbPassword() != null ? project.getDbPassword() : "";
        String targetHost = (project.getDbHost() != null && !project.getDbHost().isBlank())
                ? project.getDbHost() : "host.docker.internal";
        int targetPort = project.getDbPort() != null ? project.getDbPort() : 3306;

        // 候选文件: application.yml, application.yaml, application.properties
        // 多模块项目里找 src/main/resources/,target/classes/ 也认
        List<Path> candidates = new ArrayList<>();
        Path resDir = workspacePath.resolve("src/main/resources");
        if (!Files.exists(resDir)) {
            // 嵌套结构如 backend/src/main/resources
            try (var s = Files.list(workspacePath)) {
                for (Path child : s.toList()) {
                    if (Files.isDirectory(child) && Files.exists(child.resolve("src/main/resources"))) {
                        resDir = child.resolve("src/main/resources");
                        break;
                    }
                }
            } catch (IOException ignored) {}
        }
        if (Files.exists(resDir)) {
            for (String name : new String[]{"application.yml", "application.yaml", "application.properties"}) {
                Path p = resDir.resolve(name);
                if (Files.exists(p)) candidates.add(p);
            }
        }
        if (candidates.isEmpty()) {
            log.info("[O项目] 未找到 application.yml/properties,跳过账号改写");
            return;
        }
        for (Path yml : candidates) {
            try {
                String orig = Files.readString(yml, java.nio.charset.StandardCharsets.UTF_8);
                if (!orig.isEmpty() && orig.charAt(0) == '\uFEFF') {
                    orig = orig.substring(1);
                }
                String modified = orig;
                if (yml.getFileName().toString().endsWith(".properties")) {
                    // application.properties: spring.datasource.username/password
                    modified = modified.replaceAll(
                            "(?m)^(\\s*spring\\.datasource\\.username\\s*=\\s*).*$",
                            "$1" + java.util.regex.Matcher.quoteReplacement(targetUser));
                    modified = modified.replaceAll(
                            "(?m)^(\\s*spring\\.datasource\\.password\\s*=\\s*).*$",
                            "$1" + java.util.regex.Matcher.quoteReplacement(targetPass));
                } else {
                    // application.yml/yaml: spring.datasource.username/password
                    // 处理带引号 / 不带引号
                    modified = modified.replaceAll(
                            "(?m)^(\\s*username:\\s*)['\"]?[^'\"\\s#]+['\"]?(\\s*(?:#.*)?)$",
                            "$1" + "'" + java.util.regex.Matcher.quoteReplacement(targetUser) + "'$2");
                    modified = modified.replaceAll(
                            "(?m)^(\\s*password:\\s*)['\"]?[^'\"\\s#]*['\"]?(\\s*(?:#.*)?)$",
                            "$1" + "'" + java.util.regex.Matcher.quoteReplacement(targetPass) + "'$2");
                    // 也把 host/port 一起改(YAML 里的 url 经常是 localhost:3306)
                    modified = modified.replaceAll(
                            "(?im)^(\\s*url:\\s*['\"]?jdbc:mysql://)[^:/'\"]+(:\\d+/\\w+.*)$",
                            "$1" + java.util.regex.Matcher.quoteReplacement(targetHost) +
                            ":" + targetPort + "/$2".replace("$2", "$2"));
                    // 简单 url 替换(只换 host 部分,不动 path/params)
                    modified = modified.replaceAll(
                            "(?m)^(\\s*url:\\s*['\"]?jdbc:mysql://)([^:/'\"]+)(:\\d+)?(/\\w+\\S*?['\"]?\\s*(?:#.*)?)$",
                            "$1" + java.util.regex.Matcher.quoteReplacement(rewriteLoopbackHost(targetHost)) + "$3$4");
                }
                if (!modified.equals(orig)) {
                    Files.writeString(yml, modified, java.nio.charset.StandardCharsets.UTF_8);
                    log.info("[O项目] 改写账号: {} → user='{}' (host={}:{})",
                            yml.getFileName(), targetUser, targetHost, targetPort);
                }
            } catch (Exception ex) {
                log.warn("[O项目] 改写 {} 失败: {}", yml, ex.getMessage());
            }
        }
    }

    /**
     * 验证工作目录内容 —— 列出文件结构，确认构建文件存在
     */
    private boolean verifyWorkspaceContents(Project project, Path workspacePath, StringBuilder logBuf) {
        try {
            logBuf.append("--- 工作目录内容 ---\n");
            listDirectory(workspacePath, logBuf, 0, 3);

            // 检测项目类型, 验证关键构建文件
            Path pomXml = findFile(workspacePath, "pom.xml");
            Path packageJson = findFile(workspacePath, "package.json");
            Path buildGradle = findFile(workspacePath, "build.gradle");
            Path requirementsTxt = findFile(workspacePath, "requirements.txt");
            Path pyprojectToml = findFile(workspacePath, "pyproject.toml");
            Path setupPy = findFile(workspacePath, "setup.py");
            Path goMod = findFile(workspacePath, "go.mod");
            Path cmakeLists = findFile(workspacePath, "CMakeLists.txt");
            Path cargoToml = findFile(workspacePath, "Cargo.toml");

            String language = project.getLanguage() != null ? project.getLanguage().toLowerCase() : "java";
            boolean hasBuildFile = false;

            if (language.contains("node")) {
                if (packageJson != null) {
                    logBuf.append("[OK] 找到 package.json: ").append(workspacePath.relativize(packageJson)).append("\n");
                    hasBuildFile = true;
                } else {
                    logBuf.append("[WARN] 未找到 package.json\n");
                }
            } else if (language.contains("python")) {
                if (requirementsTxt != null) {
                    logBuf.append("[OK] 找到 requirements.txt: ").append(workspacePath.relativize(requirementsTxt)).append("\n");
                    hasBuildFile = true;
                }
                if (pyprojectToml != null) {
                    logBuf.append("[OK] 找到 pyproject.toml: ").append(workspacePath.relativize(pyprojectToml)).append("\n");
                    hasBuildFile = true;
                }
                if (setupPy != null) {
                    logBuf.append("[OK] 找到 setup.py: ").append(workspacePath.relativize(setupPy)).append("\n");
                    hasBuildFile = true;
                }
                if (!hasBuildFile) {
                    logBuf.append("[WARN] 未找到 requirements.txt / pyproject.toml / setup.py\n");
                }
            } else if (language.equals("go") || language.equals("golang")) {
                if (goMod != null) {
                    logBuf.append("[OK] 找到 go.mod: ").append(workspacePath.relativize(goMod)).append("\n");
                    hasBuildFile = true;
                } else {
                    logBuf.append("[WARN] 未找到 go.mod\n");
                }
            } else if (language.contains("c++") || language.contains("cpp") || language.equals("c")) {
                if (cmakeLists != null) {
                    logBuf.append("[OK] 找到 CMakeLists.txt: ").append(workspacePath.relativize(cmakeLists)).append("\n");
                    hasBuildFile = true;
                } else {
                    logBuf.append("[WARN] 未找到 CMakeLists.txt\n");
                    logBuf.append("[HINT] C++ 项目需要 CMakeLists.txt 文件\n");
                }
            } else if (language.equals("rust")) {
                if (cargoToml != null) {
                    logBuf.append("[OK] 找到 Cargo.toml: ").append(workspacePath.relativize(cargoToml)).append("\n");
                    hasBuildFile = true;
                } else {
                    logBuf.append("[WARN] 未找到 Cargo.toml\n");
                }
            } else {
                // Java / Kotlin / Scala / Other
                if (pomXml != null) {
                    logBuf.append("[OK] 找到 pom.xml: ").append(workspacePath.relativize(pomXml)).append("\n");
                    hasBuildFile = true;
                }
                if (buildGradle != null) {
                    logBuf.append("[OK] 找到 build.gradle: ").append(workspacePath.relativize(buildGradle)).append("\n");
                    hasBuildFile = true;
                }
                if (!hasBuildFile) {
                    logBuf.append("[WARN] 未找到 Maven pom.xml 或 Gradle build.gradle\n");
                    logBuf.append("[HINT] 请确认项目中有 pom.xml 文件，且已上传到 Git 仓库\n");
                }
            }

            if (!hasBuildFile) {
                logBuf.append("[HINT] 构建文件不在根目录？可以在流水线定义中指定工作子目录\n");
            }

            logBuf.append("--------------------\n\n");
            return true;  // 目录已就绪，构建文件缺失由具体步骤报错
        } catch (Exception e) {
            logBuf.append("[WARN] 验证工作目录内容失败: ").append(e.getMessage()).append("\n");
            return true;  // 不影响构建继续
        }
    }

    /** 递归列出目录（限制深度和条目数） */
    private void listDirectory(Path dir, StringBuilder buf, int depth, int maxDepth) {
        if (depth > maxDepth) return;
        try {
            java.util.List<Path> entries = new java.util.ArrayList<>();
            try (var stream = Files.list(dir)) { entries = stream.sorted().toList(); } catch (IOException ignored) {}
            int shown = 0;
            for (Path entry : entries) {
                if (shown >= 30) { buf.append("  ... 还有 ").append(entries.size() - 30).append(" 项\n"); break; }
                String indent = "  ".repeat(depth + 1);
                if (Files.isDirectory(entry)) {
                    buf.append(indent).append(entry.getFileName()).append("/\n");
                    if (depth < maxDepth - 1) listDirectory(entry, buf, depth + 1, maxDepth);
                } else {
                    long size = 0;
                    try { size = Files.size(entry); } catch (IOException ignored) {}
                    buf.append(indent).append(entry.getFileName());
                    buf.append(" (").append(size / 1024).append(" KB)\n");
                }
                shown++;
            }
        } catch (Exception ignored) {}
    }

    /** 在目录及一级子目录中查找文件 */
    private Path findFile(Path dir, String fileName) {
        Path direct = dir.resolve(fileName);
        if (Files.exists(direct)) return direct;
        // 搜索一级子目录
        try (var stream = Files.list(dir)) {
            for (Path child : stream.toList()) {
                if (Files.isDirectory(child)) {
                    Path nested = child.resolve(fileName);
                    if (Files.exists(nested)) return nested;
                }
            }
        } catch (IOException ignored) {}
        return null;
    }

    /** 在 workspace 中定位项目根目录（包含 pom.xml/package.json 等构建文件） */
    /** 项目根目录标记文件（优先级从高到低） */
    private static final String[] ROOT_MARKERS = {
        "pom.xml", "package.json", "build.gradle", "build.gradle.kts",
        "requirements.txt", "pyproject.toml", "setup.py", "setup.cfg",
        "go.mod", "CMakeLists.txt", "Cargo.toml", "Makefile"
    };

    private Path findProjectRoot(Project project) throws IOException {
        Path workspacePath = resolveWorkspaceRoot().resolve(workspaceDirName(project));
        if (!Files.exists(workspacePath)) return workspacePath;

        // 先检查 workspace 本身是否有任何已知构建文件
        for (String marker : ROOT_MARKERS) {
            if (Files.exists(workspacePath.resolve(marker))) {
                return workspacePath;
            }
        }

        // 找一级子目录中带构建标记文件的（支持 monorepo）
        try (var stream = Files.list(workspacePath)) {
            for (Path child : stream.toList()) {
                if (!Files.isDirectory(child)) continue;
                for (String marker : ROOT_MARKERS) {
                    if (Files.exists(child.resolve(marker))) {
                        return child;
                    }
                }
            }
        }

        return workspacePath;
    }

    private void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                for (Path child : stream.toList()) deleteRecursively(child);
            }
        }
        Files.deleteIfExists(path);
    }

    /**
     * 根据项目语言返回默认容器端口
     */
    private int getDefaultPort(Project project) {
        String lang = project.getLanguage() != null ? project.getLanguage().toLowerCase() : "java";
        if (lang.contains("node")) return 3000;
        if (lang.contains("python")) return 8000;
        if (lang.equals("go") || lang.equals("golang")) return 8080;
        if (lang.contains("c++") || lang.contains("cpp") || lang.equals("c")) return 8080;
        if (lang.equals("rust")) return 8080;
        return 8080; // Java / default
    }

    // ===================== 命令执行 =====================
    /**
     * 带重试的 Git 命令执行 —— 网络错误自动重试，认证/分支错误不重试
     */
    private int runGitCommandWithRetry(String workDir, String command, StringBuilder logBuf,
                                        Long buildId, int maxRetries) throws Exception {
        int lastExitCode = -1;
        StringBuilder retryLog = new StringBuilder();

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            StringBuilder attemptBuf = new StringBuilder();
            int exitCode = runCommand(workDir, command, attemptBuf, buildId);
            String output = attemptBuf.toString();
            logBuf.append(output);
            lastExitCode = exitCode;

            if (exitCode == 0) {
                return 0;
            }

            // 判断是否为网络类错误（可重试）
            boolean isNetworkError = output.contains("Connection was reset")
                    || output.contains("Could not connect to server")
                    || output.contains("RPC failed")
                    || output.contains("Failed to connect")
                    || output.contains("Connection timed out")
                    || output.contains("Could not resolve host")
                    || output.contains("Recv failure");

            // 判断是否为不可重试的错误（认证、分支不存在等）
            boolean isFatalError = output.contains("Authentication failed")
                    || output.contains("could not read Username")
                    || output.contains("could not read Password")
                    || output.contains("Repository not found")
                    || output.contains("couldn't find remote ref")
                    || output.contains("fatal: ambiguous argument");

            if (isFatalError || !isNetworkError) {
                // 不可重试的错误，直接返回
                return exitCode;
            }

            if (attempt < maxRetries) {
                long waitSeconds = (long) Math.pow(2, attempt + 1); // 4s, 8s, 16s
                retryLog.append("[RETRY] Git 网络错误，").append(waitSeconds)
                        .append("s 后重试 (第 ").append(attempt).append("/").append(maxRetries).append(" 次)\n");
                logBuf.append(retryLog.toString());
                appendLog(buildId, retryLog.toString());
                retryLog.setLength(0);
                Thread.sleep(waitSeconds * 1000);
            }
        }
        return lastExitCode;
    }

    private int runCommand(String workDir, String command, StringBuilder logBuf, Long buildId) throws Exception {
        Path workPath = Paths.get(workDir);
        if (!Files.exists(workPath)) Files.createDirectories(workPath);

        ProcessBuilder pb;
        if (shellCommand.contains("bash")) pb = new ProcessBuilder("bash", "-c", command);
        else if (shellCommand.contains("powershell")) pb = new ProcessBuilder("powershell", "-Command", command);
        else pb = new ProcessBuilder("cmd", "/c", command);

        pb.directory(new File(workDir));
        pb.redirectErrorStream(true);

        // 强制子进程使用 UTF-8 编码，避免 Windows GBK 与 Maven UTF-8 冲突导致乱码
        pb.environment().put("JAVA_TOOL_OPTIONS", "-Dfile.encoding=UTF-8");
        pb.environment().put("PYTHONIOENCODING", "UTF-8");

        Process proc = pb.start();
        if (buildId != null) runningProcesses.put(buildId, proc);

        // Windows 下 cmd.exe 默认 GBK，但 Maven/javac 输出 UTF-8；设置 JAVA_TOOL_OPTIONS 后统一 UTF-8
        // docker / kubectl 输出多为 ASCII，UTF-8 读取兼容
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logBuf.append("  ").append(line).append("\n");
                if (buildId != null) appendLog(buildId, logBuf.toString());
            }
        }

        boolean finished = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!finished) {
            proc.destroyForcibly();
            logBuf.append("[ERROR] 命令超时 (").append(timeoutMs / 1000).append("s)\n");
            return -1;
        }
        if (buildId != null) runningProcesses.remove(buildId);
        return proc.exitValue();
    }

    // ===================== 辅助方法 =====================
    private String getProjectWorkspace(Project project) {
        // 构建过程中优先用「代码准备阶段记下的实际目录」，避免因流水线上下文
        // 不一致而算出另一个目录（制品收集不到就是这么来的）
        String recorded = currentWorkspace.get();
        if (recorded != null) {
            return recorded;
        }
        try {
            Path root = resolveWorkspaceRoot();
            return root.resolve(workspaceDirName(project)).toString();
        } catch (IOException e) {
            return workspaceDir + "/" + workspaceDirName(project);  // fallback
        }
    }

    /**
     * 检测并改写用户填的"本机 IP"为 K3s 容器内可用的 DNS
     * <p>
     * 用户填的本机 IP 在 K8s 容器内:
     *  - 127.0.0.1 / localhost → 指向 Pod 自己的 loopback,连不上宿主机 MySQL
     *  - 192.168.127.2 → WSL2 节点 IP,K3s 容器网络隔离,连不上
     *  - 192.168.x.x (宿主机真实 IP) → 取决于网络模式,不稳定
     * <p>
     * 统一改写成 host.docker.internal → K3s 内置 DNS → 宿主机 IP (192.168.127.254)
     * 唯一稳定的宿主机访问方式
     * <p>
     * 不动用户填的"真实远端 IP"(看起来是公网/内网 IP,不是 loopback)
     */
    private static String rewriteLoopbackHost(String host) {
        if (host == null) return "host.docker.internal";
        String h = host.trim();
        if (h.isEmpty()) return "host.docker.internal";
        // 改成小写比较
        String lower = h.toLowerCase();
        if (lower.equals("127.0.0.1")
                || lower.equals("localhost")
                || lower.equals("0.0.0.0")
                || lower.equals("::1")
                || lower.equals("127.0.1.1")
                // WSL2 K3s 节点常见 IP
                || lower.equals("192.168.127.1")
                || lower.equals("192.168.127.2")
                || lower.startsWith("192.168.127.")
                // 一些 Linux 网桥默认 IP
                || lower.equals("172.17.0.1")
                || lower.startsWith("172.17.0.")
                || lower.equals("10.0.2.2")) {
            return "host.docker.internal";
        }
        return h;
    }

    private void simulateDelay(int minMs, int maxMs) throws InterruptedException {
        Thread.sleep(minMs + new Random().nextInt(maxMs - minMs));
    }

    private List<StageDef> parsePipelineDefinition(String definition, Project project) {
        try {
            if (definition == null || definition.trim().isEmpty()) return getDefaultStages(project);
            JsonNode root = objectMapper.readTree(definition);
            List<StageDef> stages = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode stageNode : root) {
                    StageDef stage = new StageDef();
                    stage.name = stageNode.get("name").asText();
                    // 解析阶段内并行标志
                    if (stageNode.has("parallel")) {
                        stage.parallel = stageNode.get("parallel").asBoolean();
                    }
                    stage.steps = new ArrayList<>();
                    JsonNode stepsNode = stageNode.get("steps");
                    if (stepsNode != null && stepsNode.isArray()) {
                        for (JsonNode stepNode : stepsNode) {
                            StepDef step = new StepDef();
                            step.name = stepNode.get("name").asText();
                            step.type = stepNode.get("type").asText();
                            step.command = stepNode.has("command") ? stepNode.get("command").asText() : "";
                            // 解析重试次数
                            if (stepNode.has("retry")) {
                                step.retryCount = stepNode.get("retry").asInt();
                            }
                            // 解析步骤超时（秒）
                            if (stepNode.has("timeout")) {
                                step.timeoutSeconds = stepNode.get("timeout").asInt();
                            }
                            // 解析条件表达式
                            if (stepNode.has("when") && stepNode.get("when").isObject()) {
                                step.when = new LinkedHashMap<>();
                                stepNode.get("when").fields().forEachRemaining(
                                    e -> step.when.put(e.getKey(), e.getValue().asText()));
                            }
                            // 解析审批人（APPROVAL 步骤专用）
                            if (stepNode.has("approver")) {
                                step.approver = stepNode.get("approver").asText();
                            }
                            stage.steps.add(step);
                        }
                    }
                    stages.add(stage);
                }
            }
            return stages;
        } catch (Exception e) {
            log.warn("解析流水线定义失败", e);
            return getDefaultStages(project);
        }
    }

    /**
     * 根据项目语言生成合适的默认流水线阶段
     * 包含: 构建 → 测试 → 安全扫描(FS+依赖) → Docker → 安全扫描(镜像) → K8s部署
     */
    private List<StageDef> getDefaultStages(Project project) {
        List<StageDef> stages = new ArrayList<>();
        String lang = project.getLanguage() != null ? project.getLanguage().toLowerCase() : "java";

        // ---- 阶段 1: 编译构建 (按语言选择) ----
        if (lang.contains("node")) {
            stages.add(new StageDef("编译构建", List.of(
                    new StepDef("安装依赖", "NPM_INSTALL", "npm install"),
                    new StepDef("编译项目", "NPM_BUILD", "npm run build")
            )));
        } else if (lang.contains("python")) {
            stages.add(new StageDef("编译构建", List.of(
                    new StepDef("安装依赖", "PIP_INSTALL", "pip install -r requirements.txt"),
                    new StepDef("语法检查", "PYTHON_BUILD", "python -m compileall .")
            )));
        } else if (lang.equals("go") || lang.equals("golang")) {
            stages.add(new StageDef("编译构建", List.of(
                    new StepDef("Go 编译", "GO_BUILD", "go build -o app ./...")
            )));
        } else if (lang.contains("c++") || lang.contains("cpp") || lang.equals("c")) {
            stages.add(new StageDef("编译构建", List.of(
                    new StepDef("CMake 构建", "CMAKE_BUILD", "cmake -S . -B build -G \"MinGW Makefiles\" -DCMAKE_BUILD_TYPE=Release && cmake --build build")
            )));
        } else if (lang.equals("rust")) {
            stages.add(new StageDef("编译构建", List.of(
                    new StepDef("Cargo 构建", "CARGO_BUILD", "cargo build --release")
            )));
        } else {
            // Java / Kotlin / Scala / Other → 用 Maven 或通用 SHELL
            stages.add(new StageDef("编译构建", List.of(
                    new StepDef("Maven 编译", "MVN_PACKAGE", "mvn clean package -DskipTests -B")
            )));
        }

        // ---- 阶段 2: 单元测试 ----
        stages.add(new StageDef("单元测试", List.of(new StepDef("执行测试", "TEST", ""))));

        // ---- 阶段 3: 安全扫描 (代码 + 依赖 + SAST) ----
        stages.add(new StageDef("安全扫描(代码)", List.of(
                new StepDef("SAST 静态分析", "SAST_SCAN", "thresholdHigh=0 thresholdCritical=0"),
                new StepDef("Trivy FS 扫描", "SECURITY_SCAN_FS", "thresholdHigh=5 thresholdCritical=0"),
                new StepDef("Trivy 依赖扫描", "SECURITY_SCAN_DEP", "thresholdHigh=10 thresholdCritical=0")
        )));

        // ---- 阶段 4: Docker 构建 ----
        stages.add(new StageDef("Docker 构建", List.of(
                new StepDef("构建镜像", "DOCKER_BUILD", "Dockerfile"),
                new StepDef("推送镜像", "DOCKER_PUSH", "")
        )));

        // ---- 阶段 5: 镜像安全扫描 ----
        stages.add(new StageDef("安全扫描(镜像)", List.of(
                new StepDef("Trivy 镜像扫描", "SECURITY_SCAN_IMAGE", "thresholdHigh=5 thresholdCritical=0")
        )));

        // ---- 阶段 6: K8s 部署 ----
        stages.add(new StageDef("K8s 部署", List.of(new StepDef("部署到集群", "K8S_DEPLOY", "deployment.yaml"))));

        return stages;
    }

    private void saveLogFile(Long buildId, String logContent) {
        // 1) 写入文件系统（使用绝对路径，避免 @Async 线程的工作目录问题）
        try {
            Path logFile = absoluteLogDir.resolve("build-" + buildId + ".log");
            Files.writeString(logFile, logContent);
        } catch (IOException e) {
            log.error("保存日志文件失败 buildId={}", buildId, e);
        }

        // 2) 同时持久化到数据库，确保日志不会因为文件系统问题而丢失
        try {
            buildRepository.findById(buildId).ifPresent(build -> {
                build.setLogContent(logContent);
                buildRepository.save(build);
            });
        } catch (Exception e) {
            log.error("持久化日志到数据库失败 buildId={}", buildId, e);
        }
    }

    public String getBuildLog(Long buildId) {
        // 1) 运行中的构建 → 直接从内存缓存取（实时日志）
        StringBuilder cached = logCache.get(buildId);
        if (cached != null) return cached.toString();

        // 2) 已完成的构建 → 先尝试从文件系统读取
        try {
            Path logFile = absoluteLogDir.resolve("build-" + buildId + ".log");
            if (Files.exists(logFile)) {
                return Files.readString(logFile);
            }
        } catch (IOException e) { log.warn("读取日志文件失败 buildId={}", buildId, e); }

        // 3) 文件不存在 → 从数据库兜底
        Build build = buildRepository.findById(buildId).orElse(null);
        if (build != null && build.getLogContent() != null && !build.getLogContent().isBlank()) {
            return build.getLogContent();
        }

        return "日志不存在";
    }

    public List<Build> getBuilds(Long projectId, String status) {
        if (projectId != null && status != null)
            return buildRepository.findByProjectIdAndStatus(projectId, status);
        else if (projectId != null)
            return buildRepository.findByProjectIdOrderByStartTimeDesc(projectId);
        else if (status != null)
            return buildRepository.findByStatus(status);
        else
            return buildRepository.findAll();
    }

    public Build getBuild(Long buildId) {
        return buildRepository.findById(buildId).orElse(null);
    }

    public void cancelBuild(Long buildId) {
        Build build = buildRepository.findById(buildId).orElse(null);
        if (build == null) return;
        if ("RUNNING".equals(build.getStatus()) || "QUEUED".equals(build.getStatus())) {
            build.setStatus("CANCELLED");
            build.setEndTime(LocalDateTime.now());
            buildRepository.save(build);
            Process proc = runningProcesses.remove(buildId);
            if (proc != null) {
                proc.destroy();
                log.info("已取消构建 #{}", buildId);
            }
            // 从队列中移除（如果是 QUEUED 状态）
            if ("QUEUED".equals(build.getStatus())) {
                Long projectId = build.getProjectId();
                java.util.Queue<Long> queue = projectQueues.get(projectId);
                if (queue != null) {
                    queue.remove(buildId);
                    log.info("已从队列中移除构建 #{}", buildId);
                }
            }
        }
    }

    /**
     * 从失败步骤断点续跑
     * 复用原有构建记录的已完成步骤，从失败位置重新执行
     */
    public Build retryBuild(Long buildId) {
        Build failedBuild = buildRepository.findById(buildId)
                .orElseThrow(() -> new RuntimeException("构建记录不存在: " + buildId));

        if (!"FAILED".equals(failedBuild.getStatus())) {
            throw new RuntimeException("仅允许对失败的构建进行断点续跑，当前状态: " + failedBuild.getStatus());
        }
        if (failedBuild.getFailedStepIndex() == null) {
            throw new RuntimeException("该构建未记录失败步骤索引，无法断点续跑，请重新触发完整构建");
        }

        Project project = projectRepository.findById(failedBuild.getProjectId())
                .orElseThrow(() -> new RuntimeException("项目不存在"));
        Pipeline pipeline = pipelineRepository.findById(failedBuild.getPipelineId())
                .orElseThrow(() -> new RuntimeException("流水线不存在"));

        // 检查项目是否已有运行中/排队的构建
        boolean hasRunning = buildRepository.existsByProjectIdAndStatusIn(
                failedBuild.getProjectId(), List.of("RUNNING", "QUEUED"));
        if (hasRunning) {
            throw new RuntimeException("该项目已有构建在执行或排队中，请等待完成后再重试");
        }

        // 创建新的构建记录（复用原配置，标记为断点续跑）
        synchronized (this) {
            long count = buildRepository.count();
            String buildNumber = "#" + (count + 1);

            Build retryBuild = new Build();
            retryBuild.setProjectId(failedBuild.getProjectId());
            retryBuild.setPipelineId(failedBuild.getPipelineId());
            retryBuild.setBuildNumber(buildNumber);
            retryBuild.setStatus("PENDING");
            retryBuild.setTriggeredBy(failedBuild.getTriggeredBy());
            retryBuild.setStartTime(LocalDateTime.now());
            retryBuild.setBuildParams(failedBuild.getBuildParams());
            retryBuild.setBranch(failedBuild.getBranch());
            retryBuild.setSkipDocker(failedBuild.getSkipDocker());
            retryBuild.setSkipK8s(failedBuild.getSkipK8s());
            retryBuild.setDbName(failedBuild.getDbName());
            retryBuild.setDeployStrategy(failedBuild.getDeployStrategy());
            retryBuild.setResumeFromFailure(true);
            retryBuild.setCompletedSteps(failedBuild.getCompletedSteps() != null ? failedBuild.getCompletedSteps() : 0);
            retryBuild.setFailedStageIndex(failedBuild.getFailedStageIndex());
            retryBuild.setFailedStepIndex(failedBuild.getFailedStepIndex());
            retryBuild.setResultMessage("从构建 " + failedBuild.getBuildNumber() + " 的失败步骤断点续跑");
            retryBuild = buildRepository.save(retryBuild);

            executeBuildAsync(retryBuild.getId(), project, pipeline);
            return retryBuild;
        }
    }

    /** 删除构建记录（含日志缓存清理、运行中进程终止） */
    public Build deleteBuild(Long buildId) {
        Build build = buildRepository.findById(buildId)
                .orElseThrow(() -> new RuntimeException("构建记录不存在: " + buildId));

        // 如果正在运行则先终止
        if ("RUNNING".equals(build.getStatus())) {
            Process proc = runningProcesses.remove(buildId);
            if (proc != null) proc.destroy();
        }

        // 清理日志缓存
        logCache.remove(buildId);

        // 删除日志文件
        try {
            Files.deleteIfExists(absoluteLogDir.resolve("build-" + buildId + ".log"));
        } catch (IOException ignored) {}

        buildRepository.delete(build);
        log.info("已删除构建记录 #{}", buildId);
        return build;
    }

    /** 检查项目工作目录状态（供前端诊断用） */
    public Map<String, Object> checkWorkspace(Long projectId) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Project project = projectRepository.findById(projectId).orElse(null);
            if (project == null) {
                result.put("error", "项目不存在");
                return result;
            }
            result.put("projectId", projectId);
            result.put("projectName", project.getName());
            result.put("projectCode", project.getCode());
            result.put("gitUrl", project.getGitUrl());
            result.put("gitBranch", project.getGitBranch());

            Path workspacePath = resolveWorkspaceRoot().resolve(project.getCode());
            result.put("workspacePath", workspacePath.toString());
            result.put("workspaceExists", Files.exists(workspacePath));
            result.put("isGitRepo", Files.exists(workspacePath.resolve(".git")));

            if (Files.exists(workspacePath)) {
                // 列出一级文件
                List<String> rootFiles = new java.util.ArrayList<>();
                try (var stream = Files.list(workspacePath)) {
                    for (Path p : stream.sorted().toList()) {
                        String name = p.getFileName().toString();
                        if (Files.isDirectory(p)) name += "/";
                        rootFiles.add(name);
                    }
                }
                result.put("rootFiles", rootFiles);

                // 检查关键构建文件
                Path pomXml = findFile(workspacePath, "pom.xml");
                result.put("hasPomXml", pomXml != null);
                if (pomXml != null) result.put("pomXmlPath", workspacePath.relativize(pomXml).toString());

                Path packageJson = findFile(workspacePath, "package.json");
                result.put("hasPackageJson", packageJson != null);
                if (packageJson != null) result.put("packageJsonPath", workspacePath.relativize(packageJson).toString());

                Path buildGradle = findFile(workspacePath, "build.gradle");
                result.put("hasBuildGradle", buildGradle != null);

                // Maven wrapper
                result.put("hasMvnw", Files.exists(workspacePath.resolve("mvnw"))
                        || Files.exists(workspacePath.resolve("mvnw.cmd")));
            }

            result.put("ok", true);
        } catch (Exception e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    private static class StageDef {
        String name;
        List<StepDef> steps;
        boolean parallel = false;  // 阶段内步骤是否并行执行
        StageDef() {}
        StageDef(String name, List<StepDef> steps) { this.name = name; this.steps = steps; }
    }

    private static class StepDef {
        String name;
        String type;
        String command;
        int retryCount = 0;       // 失败重试次数（0=不重试）
        int timeoutSeconds = 0;      // 步骤超时秒数（0=使用全局超时）
        Map<String, String> when;  // 条件表达式，如 {"branch":"main", "env":"prod"}
        String approver;            // APPROVAL 步骤：审批人（为空则任意管理员均可审批）
        StepDef() {}
        StepDef(String name, String type, String command) {
            this.name = name; this.type = type; this.command = command;
        }
    }

    /** 并行步骤执行结果 */
    private static class StepResult {
        int stepIndex;
        boolean success;
        String log;
        StepResult(int stepIndex, boolean success, String log) {
            this.stepIndex = stepIndex; this.success = success; this.log = log;
        }
    }

    // ===== 构建队列辅助方法 =====

    /**
     * 从项目队列中取出下一个构建并执行
     */
    private void dequeueNext(Long projectId) {
        java.util.Queue<Long> queue = projectQueues.get(projectId);
        if (queue == null || queue.isEmpty()) {
            projectQueues.remove(projectId);
            projectLocks.remove(projectId);
            return;
        }

        Long nextBuildId = queue.poll();
        if (nextBuildId == null) {
            projectQueues.remove(projectId);
            projectLocks.remove(projectId);
            return;
        }

        Build nextBuild = buildRepository.findById(nextBuildId).orElse(null);
        if (nextBuild == null || !"QUEUED".equals(nextBuild.getStatus())) {
            dequeueNext(projectId);
            return;
        }

        Project project = projectRepository.findById(projectId).orElse(null);
        Pipeline pipeline = pipelineRepository.findById(nextBuild.getPipelineId()).orElse(null);
        if (project == null || pipeline == null) {
            markBuildFailed(nextBuildId, null, "项目或流水线已被删除，无法从队列中启动");
            dequeueNext(projectId);
            return;
        }

        log.info("[QUEUE] 构建 {} 从队列中出队，开始执行", nextBuild.getBuildNumber());
        executeBuildAsync(nextBuildId, project, pipeline);
    }

    /**
     * 快速标记构建为失败（不抛异常，用于排队超时等场景）
     */
    private void markBuildFailed(Long buildId, Project project, String reason) {
        try {
            Build build = buildRepository.findById(buildId).orElse(null);
            if (build == null) return;
            build.setStatus("FAILED");
            build.setEndTime(LocalDateTime.now());
            build.setResultMessage(reason);
            if (project != null) {
                project.setStatus("FAILED");
                projectRepository.save(project);
            }
            buildRepository.save(build);
            log.warn("[QUEUE] 构建 {} 标记失败: {}", build.getBuildNumber(), reason);
        } catch (Exception e) {
            log.error("标记构建失败时出错: {}", e.getMessage());
        }
    }

    /**
     * 带重试的步骤执行包装
     * 支持：重试次数、指数退避、每步独立超时
     */
    private boolean executeStepWithRetry(StepDef step, Project project, StringBuilder logBuf, Long buildId) {
        int maxAttempts = step.retryCount + 1;  // retryCount=2 表示最多执行 3 次
        long stepTimeoutMs = step.timeoutSeconds > 0
                ? step.timeoutSeconds * 1000L
                : timeoutMs;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (attempt > 1) {
                long waitSeconds = (long) Math.pow(2, attempt);
                logBuf.append("[RETRY] 第 ").append(attempt).append("/").append(maxAttempts)
                      .append(" 次重试，等待 ").append(waitSeconds).append("s...\n");
                appendLog(buildId, logBuf.toString());
                try {
                    Thread.sleep(waitSeconds * 1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logBuf.append("[ERROR] 重试等待被中断\n");
                    return false;
                }
            }

            try {
                boolean ok;
                if (step.timeoutSeconds > 0) {
                    final long timeout = step.timeoutSeconds * 1000L;
                    java.util.concurrent.CompletableFuture<Boolean> future =
                            asyncWithContext(() -> {
                                try {
                                    return executeStep(step, project, logBuf, buildId);
                                } catch (Exception e) {
                                    logBuf.append("[ERROR] 步骤异常: ").append(e.getMessage()).append("\n");
                                    return false;
                                }
                            });
                    try {
                        ok = future.get(timeout, java.util.concurrent.TimeUnit.MILLISECONDS);
                    } catch (java.util.concurrent.TimeoutException e) {
                        logBuf.append("[TIMEOUT] 步骤超时 (")
                              .append(step.timeoutSeconds).append("s)，强制终止\n");
                        future.cancel(true);
                        ok = false;
                    }
                } else {
                    ok = executeStep(step, project, logBuf, buildId);
                }

                if (ok) return true;

                if (attempt < maxAttempts) {
                    logBuf.append("[RETRY] 步骤失败，准备重试...\n");
                    appendLog(buildId, logBuf.toString());
                }
            } catch (java.util.concurrent.CancellationException e) {
                logBuf.append("[ERROR] 步骤执行被取消\n");
                return false;
            } catch (Exception e) {
                logBuf.append("[ERROR] 步骤执行异常: ").append(e.getMessage()).append("\n");
                if (attempt >= maxAttempts) return false;
            }
        }
        logBuf.append("[ERROR] 步骤经 ").append(maxAttempts).append(" 次尝试后仍失败\n");
        return false;
    }
}
