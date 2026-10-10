package com.devops.platform.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 工具链服务 — 语言能力元数据 / 本机工具检测 / 缺失工具的下载与接入。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>检测优先级</b>：{@code tools/<工具名>/} 目录（平台自带，与 trivy 同一约定）→ 系统 PATH。
 *       这样"下载到 tools/ 即自动接入"，不需要改系统 PATH、不需要管理员权限。</li>
 *   <li><b>下载</b>：从官方源拉 zip 解压到 {@code tools/<工具名>/}，异步执行，前端轮询进度。</li>
 *   <li><b>元数据驱动</b>：语言 → 框架 / 默认构建命令 / 能力项 / 所需工具全部集中在 {@link #LANGUAGES}，
 *       新增语言只改这一处，前端自动跟随。</li>
 * </ul>
 */
@Slf4j
@Service
public class ToolchainService {

    /** 工具目录（相对项目根；与 trivy 的 ./tools/trivy 保持同一约定） */
    @Value("${devops.toolchain.dir:./tools}")
    private String toolchainDir;

    /** 单个工具下载超时（秒），默认 30 分钟 */
    @Value("${devops.toolchain.download-timeout-seconds:1800}")
    private int downloadTimeoutSeconds;

    // ===================== 元数据定义 =====================

    /**
     * 工具静态描述。
     *
     * @param id          工具标识，同时是 tools/ 下的目录名与可执行文件名（如 cmake → tools/cmake/cmake.exe）
     * @param label       展示名
     * @param probe       探测命令（PATH 检测用）；为空表示只查平台工具目录
     * @param versionArg  版本参数，如 "-v"、"version --client"
     * @param homeUrl     官网（手动安装时给出）
     * @param downloadUrl 可自动下载的 zip 地址；为空表示需手动安装
     * @param downloadNote 下载/安装补充说明
     * @param builtin     是否为平台自带
     */
    private record ToolDef(String id, String label, String probe, String versionArg,
                           String homeUrl, String downloadUrl, String downloadNote, boolean builtin) {}

    /**
     * 语言能力项。
     *
     * @param id        能力标识，与流水线阶段对应（BUILD / TEST / QUALITY / SAST / DOCKER / IMAGE_SCAN / K8S）
     * @param label     展示名
     * @param required  是否必选（不可取消勾选）
     * @param defaultOn 该语言的默认勾选状态
     * @param desc      说明
     * @param tools     该能力依赖的工具 id
     * @param requires  前置能力 id（未勾选前置能力时该能力不成立，如镜像扫描/部署都依赖镜像构建）
     */
    private record CapabilityDef(String id, String label, boolean required, boolean defaultOn,
                                 String desc, List<String> tools, List<String> requires) {}

    /**
     * 语言元数据。
     *
     * @param requiredTools 该语言编译所必需的工具（缺失会导致构建失败）
     * @param optionalTools 可选能力用到的工具
     */
    private record LanguageDef(String id, String label, List<String> frameworks, String defaultFramework,
                               String buildCommand, List<String> requiredTools, List<String> optionalTools,
                               List<CapabilityDef> capabilities) {}

    /** 工具静态表 */
    private static final Map<String, ToolDef> TOOLS = new LinkedHashMap<>();

    /** 语言元数据表（新增语言只改这里） */
    private static final List<LanguageDef> LANGUAGES;

    /** 下载任务（内存态） */
    private static final Map<String, DownloadTask> TASKS = new ConcurrentHashMap<>();

    /** 检测结果缓存：key = 工具 id */
    private static final Map<String, Map<String, Object>> DETECT_CACHE = new ConcurrentHashMap<>();

    static {
        // ---------- 语言基础工具链 ----------
        reg(new ToolDef("java", "JDK (java)", "java", "-version",
                "https://adoptium.net/temurin/releases/", null,
                "JDK 通常已随系统安装；如需安装请用 Adoptium 安装包", false));
        reg(new ToolDef("mvn", "Maven", "mvn", "-v",
                "https://maven.apache.org/download.cgi",
                "https://archive.apache.org/dist/maven/maven-3/3.9.6/binaries/apache-maven-3.9.6-bin.zip",
                "解压后平台自动接入（需系统已有 JAVA_HOME）", false));
        reg(new ToolDef("node", "Node.js", "node", "-v",
                "https://nodejs.org/",
                "https://nodejs.org/dist/v20.18.1/node-v20.18.1-win-x64.zip", null, false));
        reg(new ToolDef("npm", "npm", "npm", "-v",
                "https://nodejs.org/", null, "随 Node.js 一起安装", false));
        reg(new ToolDef("python", "Python", "python", "--version",
                "https://www.python.org/downloads/",
                "https://www.python.org/ftp/python/3.12.8/python-3.12.8-embed-amd64.zip",
                "Embeddable 版不含 pip，需要 pip 请用手动安装包", false));
        reg(new ToolDef("pip", "pip", "pip", "--version",
                "https://www.python.org/downloads/", null, "随 Python 一起安装", false));
        reg(new ToolDef("go", "Go", "go", "version",
                "https://go.dev/dl/",
                "https://go.dev/dl/go1.23.4.windows-amd64.zip", null, false));
        reg(new ToolDef("cargo", "Rust Cargo", "cargo", "--version",
                "https://rustup.rs/", null, "Rust 请通过 rustup 安装（含 cargo）", false));
        reg(new ToolDef("cmake", "CMake", "cmake", "--version",
                "https://cmake.org/download/",
                "https://github.com/Kitware/CMake/releases/download/v3.30.3/cmake-3.30.3-windows-x86_64.zip",
                null, false));
        reg(new ToolDef("gxx", "MinGW-w64 g++（C++ 编译器）", "g++", "--version",
                "https://winlibs.com/", null,
                "MinGW-w64 体积较大，建议从 winlibs.com 下载后解压；或安装 Visual Studio 的 C++ 工作负载", false));
        reg(new ToolDef("mingw32-make", "MinGW make", "mingw32-make", "--version",
                "https://winlibs.com/", null, "随 MinGW-w64 一起安装", false));

        // ---------- 通用/可选能力工具 ----------
        reg(new ToolDef("docker", "Docker", "docker", "--version",
                "https://www.docker.com/products/docker-desktop/", null,
                "请安装 Docker Desktop 或 Rancher Desktop", false));
        reg(new ToolDef("kubectl", "kubectl", "kubectl", "version --client",
                "https://kubernetes.io/docs/tasks/tools/", null, "请安装 kubectl 并配置 kubeconfig", false));
        reg(new ToolDef("trivy", "Trivy 漏洞扫描", "trivy", "--version",
                "https://github.com/aquasecurity/trivy/releases", null,
                "平台已自带 tools/trivy/trivy.exe", true));

        // ---------- 语言表 ----------
        List<LanguageDef> langs = new ArrayList<>();
        langs.add(new LanguageDef("Java", "Java",
                List.of("Spring Boot", "Spring Cloud", "Maven 通用", "Other"), "Spring Boot",
                "mvn clean package -DskipTests -B",
                List.of("java", "mvn"), List.of("trivy", "docker", "kubectl"),
                caps("TEST", "QUALITY", "SAST", "DOCKER", "IMAGE_SCAN", "K8S")));
        langs.add(new LanguageDef("Node.js", "Node.js",
                List.of("Express", "React", "Vue", "Next.js", "Other"), "Express",
                "npm run build",
                List.of("node", "npm"), List.of("trivy", "docker", "kubectl"),
                caps("TEST", "SAST", "DOCKER", "IMAGE_SCAN", "K8S")));
        langs.add(new LanguageDef("Python", "Python",
                List.of("Django", "Flask", "FastAPI", "Other"), "Django",
                "python -m compileall .",
                List.of("python", "pip"), List.of("trivy", "docker", "kubectl"),
                caps("TEST", "SAST", "DOCKER", "IMAGE_SCAN", "K8S")));
        langs.add(new LanguageDef("Go", "Go",
                List.of("Gin", "Echo", "Fiber", "Other"), "Gin",
                "go build -o app ./...",
                List.of("go"), List.of("trivy", "docker", "kubectl"),
                caps("TEST", "SAST", "DOCKER", "IMAGE_SCAN", "K8S")));
        langs.add(new LanguageDef("Rust", "Rust",
                List.of("Axum", "Actix", "Rocket", "Other"), "Axum",
                "cargo build --release",
                List.of("cargo"), List.of("trivy", "docker", "kubectl"),
                caps("TEST", "SAST", "DOCKER", "IMAGE_SCAN", "K8S")));
        langs.add(new LanguageDef("C++", "C/C++",
                List.of("CMake", "Makefile", "Other"), "CMake",
                "cmake -S . -B build -G \"MinGW Makefiles\" -DCMAKE_BUILD_TYPE=Release && cmake --build build",
                List.of("cmake", "gxx", "mingw32-make"), List.of("trivy", "docker", "kubectl"),
                caps("SAST")));
        langs.add(new LanguageDef("Other", "其他 / 自定义",
                List.of("Other"), "Other",
                "",
                List.of(), List.of("trivy", "docker", "kubectl"),
                caps("SAST")));
        LANGUAGES = List.copyOf(langs);
    }

    private static void reg(ToolDef def) {
        TOOLS.put(def.id(), def);
    }

    /** 生成某语言的能力清单，参数为默认勾选的能力 id */
    private static List<CapabilityDef> caps(String... defaultOn) {
        Set<String> on = Set.of(defaultOn);
        return List.of(
                new CapabilityDef("BUILD", "编译构建", true, true,
                        "拉取代码并编译出可执行产物", List.of(), List.of()),
                new CapabilityDef("TEST", "单元测试", false, on.contains("TEST"),
                        "执行测试用例并生成测试报告", List.of(), List.of()),
                new CapabilityDef("QUALITY", "质量门禁", false, on.contains("QUALITY"),
                        "校验测试通过率与代码覆盖率，不达标则中断", List.of(), List.of()),
                new CapabilityDef("SAST", "代码安全扫描", false, on.contains("SAST"),
                        "静态代码分析 + 依赖漏洞扫描（Trivy / Semgrep）", List.of("trivy"), List.of()),
                new CapabilityDef("DOCKER", "镜像构建", false, on.contains("DOCKER"),
                        "构建并推送容器镜像", List.of("docker"), List.of()),
                new CapabilityDef("IMAGE_SCAN", "镜像安全扫描", false, on.contains("IMAGE_SCAN"),
                        "扫描镜像层中的已知漏洞", List.of("trivy", "docker"), List.of("DOCKER")),
                new CapabilityDef("K8S", "K8s 部署", false, on.contains("K8S"),
                        "部署到 Kubernetes 集群", List.of("kubectl", "docker"), List.of("DOCKER"))
        );
    }

    // ===================== 对外接口 =====================

    /**
     * 归一化能力勾选：丢弃「前置能力未勾选」的能力。
     * 例如只要 BUILD+SAST 时，孤立存在的 IMAGE_SCAN / K8S 会被剔除——
     * 否则会生成「没有镜像却要扫镜像 / 部署」的流水线。
     * 入参为空时原样返回（历史项目未配置能力的旧语义：不限制）。
     */
    public static String normalizeCapabilities(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        Set<String> caps = new LinkedHashSet<>();
        for (String s : raw.split(",")) {
            String id = s.trim().toUpperCase();
            if (!id.isEmpty()) caps.add(id);
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String id : new ArrayList<>(caps)) {
                for (String need : requiresOf(id)) {
                    if (!caps.contains(need)) {
                        caps.remove(id);
                        changed = true;
                        break;
                    }
                }
            }
        }
        return String.join(",", caps);
    }

    /** 查某能力的前置能力（各语言定义一致，取首个匹配） */
    private static List<String> requiresOf(String capId) {
        for (LanguageDef l : LANGUAGES) {
            for (CapabilityDef c : l.capabilities()) {
                if (c.id().equals(capId)) return c.requires();
            }
        }
        return List.of();
    }

    /** 语言与工具元数据（前端渲染下拉与能力复选框用） */
    public Map<String, Object> getMeta() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("languages", LANGUAGES);
        out.put("tools", TOOLS.values());
        out.put("os", System.getProperty("os.name"));
        out.put("toolchainDir", resolveToolsRoot().toString());
        return out;
    }

    /** 查询某语言的工具链状态（null / 空 → 返回全部语言的汇总） */
    public Map<String, Object> getStatus(String language) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (language == null || language.isBlank()) {
            List<Map<String, Object>> all = new ArrayList<>();
            for (LanguageDef l : LANGUAGES) {
                all.add(statusOf(l));
            }
            out.put("languages", all);
            return out;
        }
        out.putAll(statusOf(findLanguage(language)));
        return out;
    }

    /** 重新检测（清缓存），下载安装后调用 */
    public void resetCache() {
        DETECT_CACHE.clear();
        log.info("工具链检测缓存已清空");
    }

    /** 启动异步下载安装，返回任务对象 */
    public DownloadTask startDownload(String toolId) {
        ToolDef def = TOOLS.get(toolId);
        if (def == null) {
            throw new IllegalArgumentException("未知工具: " + toolId);
        }
        if (def.downloadUrl() == null || def.downloadUrl().isBlank()) {
            throw new IllegalStateException(def.label() + " 不支持自动下载，请手动安装: " + def.homeUrl());
        }
        DownloadTask task = new DownloadTask();
        task.setId(UUID.randomUUID().toString());
        task.setToolId(toolId);
        task.setToolLabel(def.label());
        task.setStartedAt(now());
        task.setMessage("已排队，等待开始…");
        TASKS.put(task.getId(), task);

        CompletableFuture.runAsync(() -> doDownload(def, task));
        log.info("已启动工具下载任务: {} ({})", def.label(), task.getId());
        return task;
    }

    /** 查询下载任务 */
    public DownloadTask getTask(String taskId) {
        return TASKS.get(taskId);
    }

    // ===================== 状态检测 =====================

    private Map<String, Object> statusOf(LanguageDef lang) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("language", lang.id());
        out.put("label", lang.label());

        List<Map<String, Object>> tools = new ArrayList<>();
        boolean ready = true;
        for (String tid : allToolsOf(lang)) {
            ToolDef def = TOOLS.get(tid);
            if (def == null) continue;
            Map<String, Object> st = detect(def);
            boolean required = lang.requiredTools().contains(tid);
            st.put("required", required);
            tools.add(st);
            if (required && !Boolean.TRUE.equals(st.get("available"))) {
                ready = false;
            }
        }
        out.put("tools", tools);
        out.put("ready", ready);
        out.put("missingRequired", tools.stream()
                .filter(t -> Boolean.TRUE.equals(t.get("required")) && !Boolean.TRUE.equals(t.get("available")))
                .map(t -> t.get("label"))
                .collect(Collectors.toList()));
        return out;
    }

    private List<String> allToolsOf(LanguageDef lang) {
        List<String> all = new ArrayList<>(lang.requiredTools());
        for (String t : lang.optionalTools()) {
            if (!all.contains(t)) all.add(t);
        }
        return all;
    }

    /** 检测单个工具：平台工具目录 → 系统 PATH */
    private Map<String, Object> detect(ToolDef def) {
        Map<String, Object> cached = DETECT_CACHE.get(def.id());
        if (cached != null) return new LinkedHashMap<>(cached);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", def.id());
        out.put("label", def.label());
        out.put("builtin", def.builtin());
        out.put("homeUrl", def.homeUrl());
        out.put("downloadNote", def.downloadNote());
        boolean downloadable = def.downloadUrl() != null && !def.downloadUrl().isBlank();
        out.put("downloadable", downloadable);
        out.put("downloadUrl", def.downloadUrl());

        // 1) 平台工具目录（下载安装到这里的会被优先使用）
        File local = findInToolDir(def.id());
        if (local != null) {
            out.put("available", true);
            out.put("source", "平台工具目录");
            out.put("path", local.getAbsolutePath());
            out.put("version", probeVersion(local.getAbsolutePath(), def));
            DETECT_CACHE.put(def.id(), new LinkedHashMap<>(out));
            return out;
        }

        // 2) 系统 PATH
        String version = probeVersion(def.probe(), def);
        if (version != null) {
            out.put("available", true);
            out.put("source", "系统 PATH");
            out.put("path", def.probe());
            out.put("version", version);
        } else {
            out.put("available", false);
            out.put("source", null);
            out.put("path", null);
            out.put("version", null);
        }
        DETECT_CACHE.put(def.id(), new LinkedHashMap<>(out));
        return out;
    }

    /** 执行 `cmd versionArg` 取首行非空输出；失败/超时返回 null */
    private String probeVersion(String cmd, ToolDef def) {
        if (cmd == null || cmd.isBlank()) return null;
        try {
            List<String> argv = new ArrayList<>();
            argv.add(cmd);
            if (def.versionArg() != null && !def.versionArg().isBlank()) {
                for (String a : def.versionArg().trim().split("\\s+")) {
                    argv.add(a);
                }
            }
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String first = null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int guard = 0;
                while ((line = reader.readLine()) != null && guard++ < 10) {
                    if (!line.isBlank()) {
                        first = line.trim();
                        break;
                    }
                }
            }
            if (!proc.waitFor(8, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return null;
            }
            if (proc.exitValue() != 0) return null;
            return first;
        } catch (Exception e) {
            return null;
        }
    }

    /** 在 tools/<工具名>/ 下递归找可执行文件 */
    private File findInToolDir(String toolId) {
        for (File base : toolBaseDirs()) {
            File dir = new File(new File(base, relativeToolsDir()), toolId);
            if (!dir.isDirectory()) continue;
            File exe = searchExecutable(dir, toolId, 3);
            if (exe != null) return exe;
        }
        return null;
    }

    private File searchExecutable(File dir, String toolId, int depth) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (!f.isFile()) continue;
            String n = f.getName().toLowerCase();
            if (n.equals(toolId.toLowerCase() + ".exe")
                    || n.equals(toolId.toLowerCase() + ".cmd")
                    || n.equals(toolId.toLowerCase() + ".bat")
                    || n.equals(toolId.toLowerCase())) {
                return f;
            }
        }
        if (depth <= 0) return null;
        for (File f : files) {
            if (f.isDirectory()) {
                File found = searchExecutable(f, toolId, depth - 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    // ===================== 下载与接入 =====================

    private void doDownload(ToolDef def, DownloadTask task) {
        try {
            Path toolsRoot = resolveToolsRoot();
            Path tmpDir = toolsRoot.resolve("_download");
            Files.createDirectories(tmpDir);
            Path zipFile = tmpDir.resolve(def.id() + ".zip");

            task.setState("DOWNLOADING");
            task.setMessage("正在下载 " + def.label() + " …");

            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(def.downloadUrl()))
                    .timeout(Duration.ofSeconds(downloadTimeoutSeconds))
                    .header("User-Agent", "DevOpsPlatform/1.0")
                    .GET()
                    .build();

            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("下载源返回 HTTP " + response.statusCode());
            }
            long total = response.headers().firstValueAsLong("content-length").orElse(-1L);
            task.setTotalBytes(total);

            long read = 0;
            int lastPct = -1;
            try (InputStream in = response.body();
                 OutputStream out = Files.newOutputStream(zipFile)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    read += n;
                    task.setDownloadedBytes(read);
                    if (total > 0) {
                        int pct = (int) Math.min(99, read * 100 / total);
                        if (pct != lastPct) {
                            task.setProgress(pct);
                            task.setMessage("正在下载 " + def.label() + " … " + pct + "%");
                            lastPct = pct;
                        }
                    }
                }
            }

            task.setState("EXTRACTING");
            task.setProgress(100);
            task.setMessage("正在解压并接入平台工具目录 …");

            Path extractDir = tmpDir.resolve(def.id() + "-extract");
            deleteRecursively(extractDir);
            unzip(zipFile, extractDir);

            Path target = toolsRoot.resolve(def.id());
            deleteRecursively(target);
            Path root = findExtractRoot(extractDir);
            Files.createDirectories(target.getParent());
            try {
                Files.move(root, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailed) {
                copyDir(root, target);
            }

            deleteRecursively(extractDir);
            Files.deleteIfExists(zipFile);
            resetCache();

            task.setProgress(100);
            task.setState("DONE");
            task.setFinishedAt(now());
            task.setMessage("✅ " + def.label() + " 已安装到 tools/" + def.id()
                    + "/ ，平台已自动接入（无需配置系统 PATH）");
            log.info("工具下载完成: {} → {}", def.label(), target.toAbsolutePath());
        } catch (Exception e) {
            task.setState("FAILED");
            task.setFinishedAt(now());
            task.setError(e.getMessage());
            task.setMessage("❌ " + def.label() + " 安装失败: " + e.getMessage()
                    + "；可手动下载后解压到 tools/" + def.id() + "/ ：" + def.homeUrl());
            log.warn("工具下载失败: {} — {}", def.label(), e.getMessage());
        }
    }

    /** 解压后定位真正的根目录（含 bin/ 的那一层，或唯一顶层目录） */
    private Path findExtractRoot(Path extractDir) throws IOException {
        try (Stream<Path> walk = Files.walk(extractDir, 3)) {
            Optional<Path> withBin = walk
                    .filter(Files::isDirectory)
                    .filter(p -> Files.isDirectory(p.resolve("bin")))
                    .findFirst();
            if (withBin.isPresent()) return withBin.get();
        }
        Path cur = extractDir;
        for (int i = 0; i < 3; i++) {
            try (Stream<Path> children = Files.list(cur)) {
                List<Path> list = children.collect(Collectors.toList());
                if (list.size() == 1 && Files.isDirectory(list.get(0))) {
                    cur = list.get(0);
                } else {
                    break;
                }
            }
        }
        return cur;
    }

    private void unzip(Path zip, Path dest) throws IOException {
        Files.createDirectories(dest);
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path target = dest.resolve(entry.getName()).normalize();
                if (!target.startsWith(dest)) {
                    throw new IOException("非法的压缩包条目: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    private void copyDir(Path src, Path dst) throws IOException {
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : walk.collect(Collectors.toList())) {
                Path rel = src.relativize(p);
                Path target = dst.resolve(rel);
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.deleteIfExists(p);
            }
        }
    }

    // ===================== 路径工具 =====================

    /** tools 目录相对片段（去掉 ./ 前缀） */
    private String relativeToolsDir() {
        String dir = toolchainDir == null || toolchainDir.isBlank() ? "tools" : toolchainDir.trim();
        if (dir.startsWith("./")) dir = dir.substring(2);
        if (dir.startsWith(".\\")) dir = dir.substring(2);
        return dir;
    }

    /** 候选基目录：工作目录 + 其父目录（IDE 把工作目录设在子模块时也能找到） */
    private List<File> toolBaseDirs() {
        List<File> bases = new ArrayList<>();
        File userDir = new File(System.getProperty("user.dir"));
        bases.add(userDir);
        File parent = userDir.getParentFile();
        if (parent != null) bases.add(parent);
        return bases;
    }

    /** 解析 tools 根目录的绝对路径 */
    private Path resolveToolsRoot() {
        String rel = relativeToolsDir();
        Path p = Paths.get(rel);
        if (p.isAbsolute()) return p;
        Path userDir = Paths.get(System.getProperty("user.dir"));
        for (File base : toolBaseDirs()) {
            Path candidate = base.toPath().resolve(rel);
            if (p.isAbsolute() || Files.exists(candidate) || base.equals(new File(System.getProperty("user.dir")))) {
                return candidate;
            }
        }
        return userDir.resolve(rel);
    }

    private LanguageDef findLanguage(String language) {
        if (language != null) {
            String key = language.trim().toLowerCase();
            for (LanguageDef l : LANGUAGES) {
                if (l.id().toLowerCase().equals(key)) return l;
                // C/C++ / cpp / c++ 均归一到 C++
                if ((key.contains("c++") || key.equals("cpp") || key.equals("c")) && l.id().equals("C++")) {
                    return l;
                }
            }
        }
        return LANGUAGES.get(LANGUAGES.size() - 1); // Other
    }

    private static String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    // ===================== 下载任务模型 =====================

    /** 下载任务（内存态，供前端轮询进度） */
    @Data
    public static class DownloadTask {
        private String id;
        private String toolId;
        private String toolLabel;
        /** PENDING / DOWNLOADING / EXTRACTING / DONE / FAILED */
        private volatile String state = "PENDING";
        private volatile int progress = 0;
        private volatile long downloadedBytes = 0;
        private volatile long totalBytes = -1;
        private volatile String message = "";
        private volatile String error;
        private String startedAt;
        private volatile String finishedAt;
    }
}
