package com.devops.platform.controller;

import com.devops.platform.service.ToolchainService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 工具链能力接口 — 语言元数据 / 本机工具检测 / 缺失工具下载。
 *
 * <ul>
 *   <li>GET  /api/toolchain/meta              语言与工具元数据（前端渲染联动表单）</li>
 *   <li>GET  /api/toolchain/status?language=X 指定语言的工具链状态（缺省返回全部语言）</li>
 *   <li>POST /api/toolchain/reset             清空检测缓存后重新检测</li>
 *   <li>POST /api/toolchain/download          {tool:"cmake"} 启动异步下载安装</li>
 *   <li>GET  /api/toolchain/download/{taskId} 查询下载进度</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/toolchain")
@RequiredArgsConstructor
public class ToolchainController {

    private final ToolchainService toolchainService;

    /** 语言 / 能力 / 工具元数据 */
    @GetMapping("/meta")
    public ResponseEntity<?> meta() {
        return ResponseEntity.ok(toolchainService.getMeta());
    }

    /** 工具链检测状态 */
    @GetMapping("/status")
    public ResponseEntity<?> status(@RequestParam(required = false) String language) {
        return ResponseEntity.ok(toolchainService.getStatus(language));
    }

    /** 清缓存并重新检测 */
    @PostMapping("/reset")
    public ResponseEntity<?> reset() {
        toolchainService.resetCache();
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** 下载并接入指定工具 */
    @PostMapping("/download")
    public ResponseEntity<?> download(@RequestBody Map<String, String> body) {
        String toolId = body == null ? null : body.get("tool");
        if (toolId == null || toolId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少参数 tool"));
        }
        try {
            return ResponseEntity.ok(toolchainService.startDownload(toolId));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 查询下载任务进度 */
    @GetMapping("/download/{taskId}")
    public ResponseEntity<?> task(@PathVariable String taskId) {
        ToolchainService.DownloadTask task = toolchainService.getTask(taskId);
        return task == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(task);
    }
}
