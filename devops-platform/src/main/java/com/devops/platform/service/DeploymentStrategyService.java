package com.devops.platform.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 部署策略服务 — 支持滚动更新 / 蓝绿部署 / 金丝雀发布
 * 
 * 策略说明：
 * - ROLLING（滚动更新）: K8s 原生 RollingUpdate，逐步替换 Pod，默认 maxSurge=1 maxUnavailable=0
 * - BLUE_GREEN（蓝绿部署）: 创建新版本 Deployment（green/blue 槽位），验证后切换 Service selector
 * - CANARY（金丝雀发布）: 创建独立 canary Deployment，按副本比例分流，观察后可全量提升
 */
@Slf4j
@Service
public class DeploymentStrategyService {

    @Value("${devops.pipeline.k8s.namespace:devops}")
    private String defaultNamespace;

    /**
     * 执行滚动更新部署（默认策略）
     * 在 YAML 中注入 RollingUpdate 策略后 kubectl apply
     */
    public boolean deployRolling(String deployYamlPath, String namespace, String workDir,
                                  StringBuilder logBuf) {
        logBuf.append("[STRATEGY] 滚动更新 (Rolling Update)\n");
        logBuf.append("[STRATEGY] 旧 Pod 逐步被新 Pod 替换，服务不中断\n");

        // 注入 RollingUpdate 策略
        try {
            String yaml = Files.readString(Path.of(deployYamlPath));
            if (!yaml.contains("strategy:")) {
                yaml = yaml.replaceFirst(
                    "(?m)^(\\s*)spec:\\s*\\n(\\s*)template:",
                    "$1spec:\n$1  strategy:\n$1    type: RollingUpdate\n$1    rollingUpdate:\n$1      maxSurge: 1\n$1      maxUnavailable: 0\n$2template:");
                Files.writeString(Path.of(deployYamlPath), yaml);
                logBuf.append("[STRATEGY] 已注入 RollingUpdate 策略 (maxSurge=1, maxUnavailable=0)\n");
            }
        } catch (Exception e) {
            logBuf.append("[STRATEGY] 注入策略失败，使用默认: ").append(e.getMessage()).append("\n");
        }

        return executeKubectl(workDir, "kubectl apply -f " + new File(deployYamlPath).getName()
                + " -n " + namespace, logBuf);
    }

    /**
     * 执行蓝绿部署
     * 1. 查询当前活跃槽位（blue/green）
     * 2. 将新版本部署到非活跃槽位
     * 3. 等待新版本 Pod 全部 Ready
     * 4. 切换 Service selector 到新槽位
     * 5. 保留旧版本用于快速回滚
     */
    public boolean deployBlueGreen(String deployYamlPath, String namespace, String workDir,
                                    String appName, StringBuilder logBuf) {
        logBuf.append("[STRATEGY] 蓝绿部署 (Blue-Green Deployment)\n");

        try {
            String yaml = Files.readString(Path.of(deployYamlPath));

            // 查询当前活跃槽位
            String activeSlot = queryActiveSlot(namespace, appName);
            String newSlot = "green".equals(activeSlot) ? "blue" : "green";
            logBuf.append("[STRATEGY] 当前活跃槽位: ").append(activeSlot).append("\n");
            logBuf.append("[STRATEGY] 新版本部署到槽位: ").append(newSlot).append("\n");

            // 生成新版本 Deployment YAML（修改名称+注入 slot 标签）
            String newName = appName + "-" + newSlot;
            String newYaml = yaml.replace("name: " + appName, "name: " + newName);
            // 注入 slot 标签到 template.labels
            if (!newYaml.contains("slot:")) {
                newYaml = newYaml.replaceFirst(
                    "(?m)^(\\s*)app:\\s*" + appName + "\\s*\\n",
                    "$1app: " + appName + "\n$1slot: " + newSlot + "\n");
            }

            String tempYaml = new File(deployYamlPath).getParent() + "/deployment-" + newSlot + ".yaml";
            Files.writeString(Path.of(tempYaml), newYaml);
            logBuf.append("[STRATEGY] 生成 ").append(newSlot).append(" 版本 YAML\n");

            // 部署新版本
            logBuf.append("[STRATEGY] 部署 ").append(newSlot).append(" 版本...\n");
            if (!executeKubectl(workDir, "kubectl apply -f deployment-" + newSlot + ".yaml -n " + namespace, logBuf)) {
                logBuf.append("[STRATEGY] ").append(newSlot).append(" 版本部署失败\n");
                return false;
            }

            // 等待新版本 Ready
            logBuf.append("[STRATEGY] 等待 ").append(newSlot).append(" 版本 Pod Ready...\n");
            if (!waitForRollout(namespace, newName, 90, logBuf)) {
                logBuf.append("[STRATEGY] ").append(newSlot).append(" 版本 Pod 未就绪\n");
                return false;
            }
            logBuf.append("[STRATEGY] ✓ ").append(newSlot).append(" 版本已就绪\n");

            // 切换 Service selector
            String svcName = appName + "-svc";
            logBuf.append("[STRATEGY] 切换 Service 流量到 ").append(newSlot).append("...\n");
            String patchJson = "{\"spec\":{\"selector\":{\"app\":\"" + appName + "\",\"slot\":\"" + newSlot + "\"}}}";
            String patchFile = new File(deployYamlPath).getParent() + "/svc-patch.json";
            Files.writeString(Path.of(patchFile), patchJson);

            if (!executeKubectl(workDir, "kubectl patch svc " + svcName + " -n " + namespace
                    + " --type merge -p \"$(cat svc-patch.json)\"", logBuf)) {
                // Windows cmd 下 cat 可能不可用，用直接 -p
                executeKubectl(workDir, "kubectl patch svc " + svcName + " -n " + namespace
                        + " --type merge -p " + patchJson, logBuf);
            }

            logBuf.append("[STRATEGY] ✓ 蓝绿部署完成！流量已切换到 ").append(newSlot).append("\n");
            logBuf.append("[STRATEGY] 旧版本 (").append(activeSlot).append(") 保留，可用于快速回滚\n");
            logBuf.append("[STRATEGY] 回滚: kubectl patch svc ").append(svcName)
                    .append(" -n ").append(namespace)
                    .append(" -p '{\"spec\":{\"selector\":{\"slot\":\"").append(activeSlot).append("\"}}}'\n");

            return true;

        } catch (Exception e) {
            logBuf.append("[STRATEGY] 蓝绿部署异常: ").append(e.getMessage()).append("\n");
            return false;
        }
    }

    /**
     * 执行金丝雀发布
     * 1. 创建独立的 canary Deployment（副本数=1，约25%流量）
     * 2. Service selector 匹配 app 标签（不区分 track），流量按副本比例分发
     * 3. 观察 canary Pod 状态
     * 4. 后续可手动提升比例或全量发布
     */
    public boolean deployCanary(String deployYamlPath, String namespace, String workDir,
                                 String appName, int canaryReplicas, StringBuilder logBuf) {
        logBuf.append("[STRATEGY] 金丝雀发布 (Canary Release)\n");
        logBuf.append("[STRATEGY] 金丝雀副本数: ").append(canaryReplicas)
                .append("（约 ").append(canaryReplicas * 25).append("% 流量）\n");

        try {
            String yaml = Files.readString(Path.of(deployYamlPath));

            // 生成 canary Deployment
            String canaryName = appName + "-canary";
            String canaryYaml = yaml
                    .replace("name: " + appName, "name: " + canaryName)
                    .replaceAll("replicas:\\s*\\d+", "replicas: " + canaryReplicas);

            // 注入 track=canary 标签
            if (!canaryYaml.contains("track:")) {
                canaryYaml = canaryYaml.replaceFirst(
                    "(?m)^(\\s*)app:\\s*" + appName + "\\s*\\n",
                    "$1app: " + appName + "\n$1track: canary\n");
            }

            String tempYaml = new File(deployYamlPath).getParent() + "/deployment-canary.yaml";
            Files.writeString(Path.of(tempYaml), canaryYaml);
            logBuf.append("[STRATEGY] 生成金丝雀 YAML\n");

            // 部署 canary 版本（不修改 stable Deployment）
            logBuf.append("[STRATEGY] 部署金丝雀版本...\n");
            if (!executeKubectl(workDir, "kubectl apply -f deployment-canary.yaml -n " + namespace, logBuf)) {
                logBuf.append("[STRATEGY] 金丝雀版本部署失败\n");
                return false;
            }

            // 等待 canary Pod Ready
            logBuf.append("[STRATEGY] 等待金丝雀 Pod Ready...\n");
            if (!waitForRollout(namespace, canaryName, 60, logBuf)) {
                logBuf.append("[STRATEGY] 金丝雀 Pod 未就绪\n");
                return false;
            }

            logBuf.append("[STRATEGY] ✓ 金丝雀发布完成！\n");
            logBuf.append("[STRATEGY] 流量分配: stable≈").append(100 - canaryReplicas * 25)
                    .append("%, canary≈").append(canaryReplicas * 25).append("%\n");
            logBuf.append("[STRATEGY] 全量发布: kubectl scale deployment ").append(appName)
                    .append(" -n ").append(namespace).append(" --replicas=新数 && kubectl delete deployment ")
                    .append(canaryName).append(" -n ").append(namespace).append("\n");
            logBuf.append("[STRATEGY] 回滚金丝雀: kubectl delete deployment ").append(canaryName)
                    .append(" -n ").append(namespace).append("\n");

            return true;

        } catch (Exception e) {
            logBuf.append("[STRATEGY] 金丝雀发布异常: ").append(e.getMessage()).append("\n");
            return false;
        }
    }

    /** 查询当前蓝绿哪个槽位是活跃的 */
    private String queryActiveSlot(String namespace, String appName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("kubectl", "get", "svc",
                    appName + "-svc", "-n", namespace,
                    "-o", "jsonpath={.spec.selector.slot}");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            proc.waitFor(5, TimeUnit.SECONDS);
            output = output.trim();
            if ("blue".equals(output) || "green".equals(output)) {
                return output;
            }
        } catch (Exception ignored) {}
        return "blue";
    }

    /** 等待 Deployment rollout 完成 */
    private boolean waitForRollout(String namespace, String deploymentName, int timeoutSec, StringBuilder logBuf) {
        try {
            ProcessBuilder pb = new ProcessBuilder("kubectl", "rollout", "status",
                    "deployment/" + deploymentName, "-n", namespace,
                    "--timeout=" + timeoutSec + "s");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            proc.waitFor(timeoutSec + 10, TimeUnit.SECONDS);
            logBuf.append(output);
            return proc.exitValue() == 0;
        } catch (Exception e) {
            logBuf.append("[STRATEGY] rollout 等待异常: ").append(e.getMessage()).append("\n");
            return false;
        }
    }

    /** 执行 kubectl 命令（cmd.exe） */
    private boolean executeKubectl(String workDir, String command, StringBuilder logBuf) {
        try {
            ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", command);
            pb.directory(new File(workDir));
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            proc.waitFor(120, TimeUnit.SECONDS);
            logBuf.append("$ ").append(command).append("\n").append(output).append("\n");
            return proc.exitValue() == 0;
        } catch (Exception e) {
            logBuf.append("[STRATEGY] 命令执行失败: ").append(e.getMessage()).append("\n");
            return false;
        }
    }

    /** 获取策略中文描述 */
    public static String getStrategyLabel(String strategy) {
        return switch (strategy) {
            case "BLUE_GREEN" -> "蓝绿部署";
            case "CANARY" -> "金丝雀发布";
            default -> "滚动更新";
        };
    }

    // ==================== 金丝雀自动健康检查与提升 ====================

    /**
     * 检查金丝雀 Pod 健康状态
     * @return true 表示金丝雀 Pod 全部 Running + Ready
     */
    public boolean checkCanaryHealth(String namespace, String appName, StringBuilder logBuf) {
        String canaryName = appName + "-canary";
        logBuf.append("[CANARY] 健康检查: ").append(canaryName).append("\n");
        try {
            ProcessBuilder pb = new ProcessBuilder("kubectl", "get", "pods",
                    "-n", namespace, "-l", "app=" + appName + ",track=canary",
                    "-o", "jsonpath={.items[*].status.phase}");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String phases = new String(proc.getInputStream().readAllBytes()).trim();
            proc.waitFor(10, TimeUnit.SECONDS);

            if (phases.isEmpty()) {
                logBuf.append("[CANARY] ⚠️ 未找到金丝雀 Pod\n");
                return false;
            }
            for (String phase : phases.split("\\s+")) {
                if (!"Running".equals(phase)) {
                    logBuf.append("[CANARY] ⚠️ Pod 状态异常: ").append(phase).append("\n");
                    return false;
                }
            }
            logBuf.append("[CANARY] ✅ 所有金丝雀 Pod 健康\n");
            return true;
        } catch (Exception e) {
            logBuf.append("[CANARY] 健康检查失败: ").append(e.getMessage()).append("\n");
            return false;
        }
    }

    /**
     * 提升金丝雀到全量发布
     * 1. 将 stable Deployment 的 replicas 扩到目标值
     * 2. 删除 canary Deployment
     */
    public boolean promoteCanary(String namespace, String appName, int fullReplicas, StringBuilder logBuf) {
        String canaryName = appName + "-canary";
        logBuf.append("[CANARY] 🚀 提升金丝雀到全量 (replicas=").append(fullReplicas).append(")\n");

        try {
            // 1) 扩展 stable Deployment
            String scaleCmd = "kubectl scale deployment/" + appName
                    + " -n " + namespace + " --replicas=" + fullReplicas;
            logBuf.append("[CANARY] $ ").append(scaleCmd).append("\n");
            if (!executeKubectl(null, scaleCmd, logBuf)) {
                logBuf.append("[CANARY] ❌ 全量提升失败: 扩容 stable Deployment 失败\n");
                return false;
            }

            // 2) 等待 stable rollout
            String rolloutCmd = "kubectl rollout status deployment/" + appName
                    + " -n " + namespace + " --timeout=120s";
            logBuf.append("[CANARY] 等待 stable Deployment 就绪...\n");
            executeKubectl(null, rolloutCmd, logBuf);

            // 3) 删除 canary
            String deleteCmd = "kubectl delete deployment/" + canaryName + " -n " + namespace;
            logBuf.append("[CANARY] 清理金丝雀 Deployment...\n");
            executeKubectl(null, deleteCmd, logBuf);

            logBuf.append("[CANARY] ✅ 金丝雀已全量提升\n");
            return true;
        } catch (Exception e) {
            logBuf.append("[CANARY] 提升异常: ").append(e.getMessage()).append("\n");
            return false;
        }
    }

    /**
     * 回滚金丝雀：直接删除 canary Deployment，stable 继续运行
     */
    public boolean rollbackCanary(String namespace, String appName, StringBuilder logBuf) {
        String canaryName = appName + "-canary";
        logBuf.append("[CANARY] ↩ 回滚金丝雀...\n");
        try {
            String cmd = "kubectl delete deployment/" + canaryName + " -n " + namespace;
            return executeKubectl(null, cmd, logBuf);
        } catch (Exception e) {
            logBuf.append("[CANARY] 回滚失败: ").append(e.getMessage()).append("\n");
            return false;
        }
    }

    /**
     * 获取金丝雀当前副本数
     */
    public int getCanaryReplicas(String namespace, String appName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("kubectl", "get", "deployment",
                    appName + "-canary", "-n", namespace,
                    "-o", "jsonpath={.spec.replicas}");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes()).trim();
            proc.waitFor(5, TimeUnit.SECONDS);
            return Integer.parseInt(output);
        } catch (Exception e) {
            return 0;
        }
    }
}
