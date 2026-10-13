package com.devops.platform.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Git 分支自动识别逻辑测试
 * <p>
 * 用本地裸仓库当「远端」(不依赖网络)，让 git 真实产出 ls-remote 输出，
 * 再喂给解析方法 —— 比手写字符串更接近真实行为。
 * <p>
 * 背景: 项目里 Git 分支默认写死 main，而很多仓库默认分支是 master，
 * 导致 `git clone -b main` 直接 128 失败。
 * <p>
 * 仓库夹具只建一次 (@BeforeAll)：Windows 上每次 git 调用开销都不小，
 * 7 个用例各自重建会让整个测试慢到 90 秒以上。
 */
class BuildServiceGitBranchTest {

    private static Path tmp;
    private static Path bare;   // 充当远端 (默认分支 master)

    @BeforeAll
    static void setUp() throws Exception {
        tmp = Files.createTempDirectory("gitbranch-test");
        bare = tmp.resolve("remote.git");
        // 默认分支强制为 master (与用户那个仓库一致)
        git(tmp, "git init -q -b master --bare remote.git");
        git(tmp, "git clone -q " + bare + " work");
        Path work = tmp.resolve("work");

        Files.writeString(work.resolve("a.txt"), "hi\n", StandardCharsets.UTF_8);
        git(work, "git -c user.email=t@t -c user.name=t add a.txt");
        git(work, "git -c user.email=t@t -c user.name=t commit -qm init");
        git(work, "git push -q origin master");
        // 再造一个非默认分支
        git(work, "git branch feature-x");
        git(work, "git push -q origin feature-x");
    }

    @AfterAll
    static void tearDown() {
        deleteRecursively(tmp == null ? null : tmp.toFile());
    }

    /** 真实 git 输出 → 默认分支应解析为 master */
    @Test
    void parsesDefaultBranchFromRealLsRemote() throws Exception {
        String out = git(tmp, "git ls-remote --symref " + bare + " HEAD");
        assertEquals("master", BuildService.parseRemoteDefaultBranch(out));
    }

    /** 真实 git 输出 → 分支清单应包含 master 与 feature-x */
    @Test
    void parsesBranchListFromRealLsRemote() throws Exception {
        String out = git(tmp, "git ls-remote --heads " + bare);
        List<String> branches = BuildService.parseRemoteBranchList(out);
        assertTrue(branches.contains("master"), "应包含 master: " + branches);
        assertTrue(branches.contains("feature-x"), "应包含 feature-x: " + branches);
        assertEquals(2, branches.size(), "不应混入非 refs/heads 的行: " + branches);
    }

    /**
     * 复现用户现象: 配置的分支不存在 → clone 失败；
     * 用探测到的远端默认分支重试 → 成功
     */
    @Test
    void cloneWithWrongBranchFails_thenSucceedsWithDetectedDefault() throws Exception {
        // 1. 按配置的 main 克隆 → 必然失败 (分支不存在)
        assertNotEquals(0, exec(tmp, "git clone -b main " + bare + " bad"),
                "main 分支不存在, 克隆应当失败");
        assertFalse(Files.exists(tmp.resolve("bad").resolve(".git")), "失败后不应留下可用仓库");

        // 2. 探测远端默认分支
        String probe = git(tmp, "git ls-remote --symref " + bare + " HEAD");
        String fallback = BuildService.parseRemoteDefaultBranch(probe);
        assertEquals("master", fallback);

        // 3. 用探测结果重试 → 成功
        assertEquals(0, exec(tmp, "git clone -q -b " + fallback + " " + bare + " good"),
                "用远端默认分支重试应当成功");
        assertTrue(Files.exists(tmp.resolve("good").resolve(".git")));

        // 4. 不带 -b 也能成功 (项目分支留空时的行为)
        assertEquals(0, exec(tmp, "git clone -q " + bare + " auto"),
                "不带 -b 时应由 git 使用远端默认分支");
    }

    // ---------- 解析边界 ----------

    /** runCommand 会给每行加两个空格前缀，解析必须容忍 */
    @Test
    void toleratesCommandOutputIndentPrefix() {
        String indented = "  ref: refs/heads/master\tHEAD\n  b5064c0\tHEAD\n";
        assertEquals("master", BuildService.parseRemoteDefaultBranch(indented));
    }

    /** CRLF 行尾 (Windows 抓取输出) 不能被截断 */
    @Test
    void toleratesCrlfLineEndings() {
        String crlf = "ref: refs/heads/develop\tHEAD\r\nabc123\tHEAD\r\n";
        assertEquals("develop", BuildService.parseRemoteDefaultBranch(crlf));

        String heads = "abc123\trefs/heads/dev\r\ndef456\trefs/heads/main\r\n";
        assertEquals(List.of("dev", "main"), BuildService.parseRemoteBranchList(heads));
    }

    /** 非 refs/heads 的行(如 tags)不得混入分支清单 */
    @Test
    void ignoresNonHeadRefs() {
        String out = "aaa\trefs/heads/main\nbbb\trefs/tags/v1.0\nccc\tHEAD\n";
        assertEquals(List.of("main"), BuildService.parseRemoteBranchList(out));
    }

    /** 输出为空/无 symref 行 → 返回 null，调用方据此走「不带 -b」的兜底 */
    @Test
    void returnsNullWhenNoSymrefLine() {
        assertNull(BuildService.parseRemoteDefaultBranch(null));
        assertNull(BuildService.parseRemoteDefaultBranch(""));
        assertNull(BuildService.parseRemoteDefaultBranch("abc123\tHEAD\n"));
        assertNull(BuildService.parseRemoteDefaultBranch("fatal: repository not found\n"));
        assertTrue(BuildService.parseRemoteBranchList(null).isEmpty());
        assertTrue(BuildService.parseRemoteBranchList("fatal: not a git repository").isEmpty());
    }

    // ---------- 工具 ----------

    /** 执行命令并返回合并后的 stdout+stderr */
    private static String git(Path dir, String command) throws Exception {
        Process p = new ProcessBuilder("cmd", "/c", command)
                .directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        assertEquals(0, code, "命令应成功: " + command + "\n输出: " + out);
        return out;
    }

    /** 执行命令只关心退出码 (不抛异常) */
    private static int exec(Path dir, String command) throws Exception {
        Process p = new ProcessBuilder("cmd", "/c", command)
                .directory(dir.toFile()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        return p.waitFor();
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursively(k);
        if (!f.delete()) f.deleteOnExit();   // git 对象是只读的，删不掉就留待 JVM 退出时处理
    }
}
